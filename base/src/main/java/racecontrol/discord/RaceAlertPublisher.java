/*
 * Copyright (c) 2021 Leonard Sch?ngel
 *
 * For licensing information see the included license (LICENSE.txt)
 */
package racecontrol.discord;

import racecontrol.client.AccBroadcastingClient;
import racecontrol.client.model.Car;
import racecontrol.client.model.Model;
import racecontrol.client.protocol.enums.SessionType;
import racecontrol.utility.TimeUtils;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Polls every 2 seconds during a race and fires feed alerts for:
 * - Lead changes
 * - Close battles (gap < 1 s, 30 s cooldown per pair)
 * - Pit stops (when a car's pitlaneCount increases)
 * - Race halfway point (one-shot)
 *
 * Resets all state automatically when the session changes.
 */
public final class RaceAlertPublisher {

    private static final Logger LOG = Logger.getLogger(RaceAlertPublisher.class.getName());
    private static final AtomicBoolean STARTED = new AtomicBoolean(false);

    private static final ScheduledExecutorService SCHEDULER =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "discord-race-alerts");
                t.setDaemon(true);
                return t;
            });

    /**
     * Battle detection fires on CHANGE, not on mere proximity:
     *   CLOSING IN   - a car is hunting down the one ahead (gap shrinking)
     *   SIDE BY SIDE - the chase has reached wheel-to-wheel range
     *   OVERTAKE     - a clean on-track position swap
     * All three are field-wide. The old proximity-only "battle" alert was
     * retired: being close is a state, not an event, so it spammed the feed.
     */
    /** Closing: gap must be inside this window (ms) to be worth narrating. */
    private static final long CLOSING_GAP_WINDOW_MS = 2_000;
    /** Closing: gap must have shrunk at least this much (ms) across the window. */
    private static final long CLOSING_DELTA_MS = 300;
    /** Closing: number of 2s ticks the history window spans (4 samples ~ 6s). */
    private static final int CLOSING_WINDOW_TICKS = 3;
    /** Closing: under this gap (ms) the pair is committed - side-by-side takes over. */
    private static final long CLOSING_FLOOR_MS = 400;
    /** Side-by-side: gap under this (ms) counts as wheel-to-wheel. */
    private static final long SIDE_BY_SIDE_GAP_MS = 300;

    /** Per-pair cooldowns (ms). */
    private static final long CLOSING_COOLDOWN_MS      = 90 * 1_000L;
    private static final long SIDE_BY_SIDE_COOLDOWN_MS = 60 * 1_000L;
    private static final long OVERTAKE_COOLDOWN_MS     = 30 * 1_000L;

    /** Per-tick alert caps - prevents rate-limit bursts. */
    private static final int MAX_CLOSING_ALERTS_PER_TICK      = 1;
    private static final int MAX_SIDE_BY_SIDE_ALERTS_PER_TICK = 1;
    private static final int MAX_OVERTAKE_ALERTS_PER_TICK     = 2;
    /** Max pit-stop alerts per 2-second tick. */
    private static final int MAX_PIT_ALERTS_PER_TICK = 2;

    // All state below is only touched from the single SCHEDULER thread.
    private static int     currentLeaderId = -1;
    private static boolean halfwayFired    = false;
    private static final Map<Integer, Integer> pitCounts    = new HashMap<>();
    /** carId -> position on the previous tick (for overtake detection). */
    private static final Map<Integer, Integer> lastPosition = new HashMap<>();
    /** carId -> rolling history of {aheadCarId, gapMs} samples (for closing rate). */
    private static final Map<Integer, Deque<int[]>> gapHistory = new HashMap<>();
    /** Per-pair last-fire timestamps, one map per alert type. */
    private static final Map<String, Long> closingLastFire    = new HashMap<>();
    private static final Map<String, Long> sideBySideLastFire = new HashMap<>();
    private static final Map<String, Long> overtakeLastFire   = new HashMap<>();
    /** carId -> number of on-track overtakes this session (for post-race recap). */
    private static final Map<Integer, Integer> overtakeCounts = new ConcurrentHashMap<>();
    private static String  lastSessionKey  = "";

    private RaceAlertPublisher() {}

    public static void start() {
        if (!STARTED.compareAndSet(false, true)) return;
        SCHEDULER.scheduleAtFixedRate(RaceAlertPublisher::tick, 2, 2, TimeUnit.SECONDS);
        LOG.info("RaceAlertPublisher started (2 s interval).");
    }

    // ?? Main tick ?????????????????????????????????????????????????????????????

    private static void tick() {
        try {
            DiscordService discord = DiscordService.get();
            if (discord == null) return;

            Model model = AccBroadcastingClient.getClient().getModel();
            if (!model.gameConnected) return;

            // Detect session change - reset all state
            var sid = model.currentSessionId;
            String key = sid.getType() + "_" + sid.getIndex();
            if (!key.equals(lastSessionKey)) {
                lastSessionKey  = key;
                currentLeaderId = -1;
                halfwayFired    = false;
                pitCounts.clear();
                lastPosition.clear();
                gapHistory.clear();
                closingLastFire.clear();
                sideBySideLastFire.clear();
                overtakeLastFire.clear();
                overtakeCounts.clear();
            }

            if (sid.getType() != SessionType.RACE) return;

            int sessionTimeMs = model.session.raw.getSessionTime();
            int sessionEndMs  = model.session.raw.getSessionEndTime();
            List<Car> cars    = LiveBoardPublisher.dedupedCars(model.getCars());

            // Don't fire any alerts until the race leader has completed at least
            // 1 lap. This suppresses the grid-spacing false positives that fire
            // during the session transition and formation/standing start.
            boolean raceUnderway = cars.stream()
                    .filter(c -> c.realtimePosition == 1)
                    .findFirst()
                    .map(c -> c.lapCount >= 1)
                    .orElse(false);

            if (raceUnderway) checkLeadChange(discord, cars, sessionTimeMs);
            if (raceUnderway) checkOvertakes(discord, cars);
            if (raceUnderway) checkClosing(discord, cars);
            if (raceUnderway) checkSideBySide(discord, cars);
            checkPitStops(discord, cars, sessionTimeMs);
            checkHalfway(discord, sessionTimeMs, sessionEndMs);

        } catch (Throwable t) {
            LOG.log(Level.FINE, "RaceAlertPublisher tick failed", t);
        }
    }

    // ?? Lead change ???????????????????????????????????????????????????????????

    private static void checkLeadChange(DiscordService discord, List<Car> cars, int sessionTimeMs) {
        Car leader = cars.stream()
                .filter(c -> c.realtimePosition == 1)
                .findFirst().orElse(null);
        if (leader == null) return;

        if (currentLeaderId == -1) {
            // First observation - initialise silently
            currentLeaderId = leader.id;
            return;
        }
        if (leader.id == currentLeaderId) return;

        int  oldId   = currentLeaderId;
        currentLeaderId = leader.id;
        Car  old     = cars.stream().filter(c -> c.id == oldId).findFirst().orElse(null);
        String oldName = old != null ? old.getDriver().fullName() : "Unknown";
        String elapsed = TimeUtils.asDurationShort(sessionTimeMs);

        discord.postFeed("**LEAD CHANGE** - **" + leader.getDriver().fullName()
                + "** takes the lead from " + oldName + " (" + elapsed + " elapsed)");
    }

    // ?? Battles ???????????????????????????????????????????????????????????????

    private static void checkOvertakes(DiscordService discord, List<Car> cars) {
        long now = System.currentTimeMillis();
        int fired = 0;
        for (Car car : cars) {
            if (fired >= MAX_OVERTAKE_ALERTS_PER_TICK) break;

            Integer prevPos = lastPosition.get(car.id);
            if (prevPos == null) continue;
            int currPos = car.realtimePosition;
            if (currPos != prevPos - 1) continue;          // gained exactly one place
            if (car.isInPit() || justPitted(car)) continue;

            // The car it passed now sits where this car used to be.
            Car passee = null;
            for (Car c : cars) {
                if (c.realtimePosition == prevPos) { passee = c; break; }
            }
            if (passee == null) continue;
            Integer passeePrev = lastPosition.get(passee.id);
            if (passeePrev == null || passeePrev != currPos) continue;  // must be a clean swap
            if (passee.isInPit() || justPitted(passee)) continue;

            String key = pairKey(car.id, passee.id);
            Long last = overtakeLastFire.get(key);
            if (last != null && (now - last) < OVERTAKE_COOLDOWN_MS) continue;

            overtakeLastFire.put(key, now);
            overtakeCounts.merge(car.id, 1, Integer::sum);
            discord.postFeed("**OVERTAKE** - **" + car.getDriver().fullName()
                    + "** passes **" + passee.getDriver().fullName() + "** for P" + currPos);
            fired++;
        }

        // Snapshot positions for next tick.
        lastPosition.clear();
        for (Car c : cars) lastPosition.put(c.id, c.realtimePosition);
    }

    // -- Closing rate (field-wide) ----------------------------------------------

    private static void checkClosing(DiscordService discord, List<Car> cars) {
        long now = System.currentTimeMillis();

        // Key by car id so lookup is disconnect-proof (no assumption of contiguous positions).
        Map<Integer, Car> idMap = new HashMap<>();
        for (Car c : cars) idMap.put(c.id, c);

        int fired = 0;
        // Tightest gaps first so the best story fires before the per-tick cap.
        List<Car> ordered = cars.stream()
                .filter(c -> c.realtimePosition > 1)
                .sorted(Comparator.comparingInt(c -> c.gapPositionAhead))
                .collect(Collectors.toList());

        for (Car car : ordered) {
            // carPositionAhead is the id stored by GapExtension (0 = leader/no car ahead).
            int aheadId = car.carPositionAhead;
            Car ahead   = aheadId > 0 ? idMap.get(aheadId) : null;
            int gap     = car.gapPositionAhead;

            // Record the sample first so side-by-side and the next tick can use it.
            Deque<int[]> hist = gapHistory.computeIfAbsent(car.id, k -> new ArrayDeque<>());
            hist.addLast(new int[]{aheadId, gap});
            while (hist.size() > CLOSING_WINDOW_TICKS + 1) hist.removeFirst();

            if (fired >= MAX_CLOSING_ALERTS_PER_TICK) continue;   // keep recording, stop alerting
            if (ahead == null) continue;
            if (car.isInPit() || ahead.isInPit()) continue;
            if (gap <= 0 || gap == Integer.MAX_VALUE) continue;
            if (gap >= CLOSING_GAP_WINDOW_MS) continue;           // not close enough yet
            if (gap < CLOSING_FLOOR_MS) continue;                 // committed - side-by-side takes over
            if (hist.size() < CLOSING_WINDOW_TICKS + 1) continue; // need a full window

            // The same car must have been ahead for the whole window.
            boolean sameAhead = true;
            for (int[] s : hist) if (s[0] != aheadId) { sameAhead = false; break; }
            if (!sameAhead) continue;

            int oldestGap = hist.peekFirst()[1];
            if (oldestGap - gap < CLOSING_DELTA_MS) continue;     // not closing fast enough

            String key = pairKey(car.id, aheadId);
            Long last = closingLastFire.get(key);
            if (last != null && (now - last) < CLOSING_COOLDOWN_MS) continue;

            closingLastFire.put(key, now);
            discord.postFeed(String.format(Locale.US,
                    "**CLOSING IN** - **%s** is hunting down **%s** - %.1fs and dropping (P%d)",
                    car.getDriver().fullName(), ahead.getDriver().fullName(),
                    gap / 1000.0, car.realtimePosition));
            fired++;
        }

        // Forget cars that have left the session.
        gapHistory.keySet().retainAll(
                cars.stream().map(c -> c.id).collect(Collectors.toSet()));
    }

    // -- Side by side (wheel to wheel) ------------------------------------------

    private static void checkSideBySide(DiscordService discord, List<Car> cars) {
        long now = System.currentTimeMillis();

        Map<Integer, Car> idMap = new HashMap<>();
        for (Car c : cars) idMap.put(c.id, c);

        int fired = 0;
        List<Car> ordered = cars.stream()
                .filter(c -> c.realtimePosition > 1)
                .filter(c -> c.gapPositionAhead > 0
                          && c.gapPositionAhead != Integer.MAX_VALUE
                          && c.gapPositionAhead < SIDE_BY_SIDE_GAP_MS)
                .sorted(Comparator.comparingInt(c -> c.gapPositionAhead))
                .collect(Collectors.toList());

        for (Car car : ordered) {
            if (fired >= MAX_SIDE_BY_SIDE_ALERTS_PER_TICK) break;

            Car ahead = car.carPositionAhead > 0 ? idMap.get(car.carPositionAhead) : null;
            if (ahead == null || car.isInPit() || ahead.isInPit()) continue;

            // Require they were already together last tick (filters single-frame blips).
            Deque<int[]> hist = gapHistory.get(car.id);
            int[] prev = previousSample(hist);
            if (prev == null || prev[0] != ahead.id || prev[1] >= SIDE_BY_SIDE_GAP_MS * 2) continue;

            String key = pairKey(car.id, ahead.id);
            Long last = sideBySideLastFire.get(key);
            if (last != null && (now - last) < SIDE_BY_SIDE_COOLDOWN_MS) continue;

            sideBySideLastFire.put(key, now);
            discord.postFeed(String.format(Locale.US,
                    "**SIDE BY SIDE** - **%s** and **%s** are wheel-to-wheel for P%d - %.3fs",
                    ahead.getDriver().fullName(), car.getDriver().fullName(),
                    car.realtimePosition, car.gapPositionAhead / 1000.0));
            fired++;
        }
    }

    // -- Battle helpers ---------------------------------------------------------

    /** True if the car's pit count has risen since the last tick (pit-out shuffle). */
    private static boolean justPitted(Car car) {
        int known = pitCounts.getOrDefault(car.id, car.pitlaneCount);
        return car.pitlaneCount > known;
    }

    /**
     * Snapshot of on-track overtake counts for the current session.
     * Thread-safe copy; safe to call from any thread (e.g. during session change).
     * Returns carId -> number of overtakes made.
     */
    public static Map<Integer, Integer> getOvertakeCounts() {
        return Collections.unmodifiableMap(new HashMap<>(overtakeCounts));
    }

    /** Stable order-independent key for a pair of car ids. */
    private static String pairKey(int a, int b) {
        return Math.min(a, b) + "_" + Math.max(a, b);
    }

    /** The sample before the most recent one, or null if history is too short. */
    private static int[] previousSample(Deque<int[]> hist) {
        if (hist == null || hist.size() < 2) return null;
        Iterator<int[]> it = hist.descendingIterator();
        it.next();          // skip current (last) sample
        return it.next();   // previous sample
    }

    // ?? Pit stops ?????????????????????????????????????????????????????????????

    private static void checkPitStops(DiscordService discord, List<Car> cars, int sessionTimeMs) {
        int fired = 0;
        for (Car car : cars) {
            int prev = pitCounts.getOrDefault(car.id, Integer.MIN_VALUE);
            int curr = car.pitlaneCount;
            if (prev == Integer.MIN_VALUE) {
                // First time we see this car - record baseline without alerting
                pitCounts.put(car.id, curr);
                continue;
            }
            if (curr > prev) {
                pitCounts.put(car.id, curr);
                if (fired < MAX_PIT_ALERTS_PER_TICK) {
                    String elapsed = TimeUtils.asDurationShort(sessionTimeMs);
                    discord.postFeed("**" + car.getDriver().fullName().toUpperCase()
                            + "** pits (stop " + curr + " | " + elapsed + " elapsed)");
                    fired++;
                }
            }
        }
    }

    // ?? Halfway ???????????????????????????????????????????????????????????????

    private static void checkHalfway(DiscordService discord, int sessionTimeMs, int sessionEndMs) {
        if (halfwayFired || sessionEndMs <= 0 || sessionTimeMs <= 0) return;
        if ((long) sessionTimeMs * 2 >= sessionEndMs) {
            halfwayFired = true;
            String elapsed   = TimeUtils.asDurationShort(sessionTimeMs);
            String remaining = TimeUtils.asDurationShort(Math.max(0, sessionEndMs - sessionTimeMs));
            discord.postFeed("**Halfway** - " + elapsed + " elapsed | " + remaining + " remaining");
        }
    }
}
