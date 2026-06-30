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

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
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

    /** Gap threshold for a "battle" alert in ms. */
    private static final long BATTLE_GAP_MS = 1_000;
    /** Minimum time between alerts for the same pair (ms). */
    private static final long BATTLE_COOLDOWN_MS = 30_000;
    /** Max battle alerts per 2-second tick — prevents rate-limit bursts. */
    private static final int MAX_BATTLE_ALERTS_PER_TICK = 2;
    /** Max pit-stop alerts per 2-second tick. */
    private static final int MAX_PIT_ALERTS_PER_TICK = 2;

    // All state below is only touched from the single SCHEDULER thread.
    private static int     currentLeaderId = -1;
    private static boolean halfwayFired    = false;
    private static final Map<Integer, Integer> pitCounts      = new HashMap<>();
    private static final Map<String,  Long>    battleLastFire = new HashMap<>();
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
                battleLastFire.clear();
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

            checkLeadChange(discord, cars, sessionTimeMs);
            if (raceUnderway) checkBattles(discord, cars);
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

    private static void checkBattles(DiscordService discord, List<Car> cars) {
        long now = System.currentTimeMillis();

        // Sort by closest gap first so the tightest battles fire before the cap is hit
        List<Car> candidates = cars.stream()
                .filter(c -> c.realtimePosition > 1 && !c.isInPit())
                .filter(c -> c.gapPositionAhead > 0
                          && c.gapPositionAhead != Integer.MAX_VALUE
                          && c.gapPositionAhead < BATTLE_GAP_MS)
                .sorted(Comparator.comparingInt(c -> c.gapPositionAhead))
                .collect(Collectors.toList());

        int fired = 0;
        for (Car car : candidates) {
            if (fired >= MAX_BATTLE_ALERTS_PER_TICK) break;

            Car ahead = cars.stream()
                    .filter(c -> c.realtimePosition == car.realtimePosition - 1)
                    .findFirst().orElse(null);
            if (ahead == null || ahead.isInPit()) continue;

            String pairKey = Math.min(car.id, ahead.id) + "_" + Math.max(car.id, ahead.id);
            Long lastFire  = battleLastFire.get(pairKey);
            if (lastFire != null && (now - lastFire) < BATTLE_COOLDOWN_MS) continue;

            battleLastFire.put(pairKey, now);
            discord.postFeed(String.format("**BATTLE** - %s (P%d) vs %s (P%d) - %.3fs",
                    car.getDriver().fullName(),   car.realtimePosition,
                    ahead.getDriver().fullName(), ahead.realtimePosition,
                    car.gapPositionAhead / 1000.0));
            fired++;
        }
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
