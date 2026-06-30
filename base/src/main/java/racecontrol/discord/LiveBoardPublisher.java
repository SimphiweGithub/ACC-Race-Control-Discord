package racecontrol.discord;

import racecontrol.client.AccBroadcastingClient;
import racecontrol.client.model.Car;
import racecontrol.client.model.Model;
import racecontrol.client.protocol.enums.SessionType;
import racecontrol.utility.DiscordLeaderboardManager;
import racecontrol.utility.DiscordLeaderboardManager.LeaderboardEntry;
import racecontrol.utility.DiscordLeaderboardManager.SessionMode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Updates the pinned live board message every 5 seconds.
 * Replaces DiscordF1Ticker + DiscordBotLeaderboardManager.
 */
public final class LiveBoardPublisher {

    private static final Logger LOG = Logger.getLogger(LiveBoardPublisher.class.getName());
    private static final ScheduledExecutorService SCHEDULER =
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "discord-live-board");
            t.setDaemon(true);
            return t;
        });

    private static volatile String lastContentHash = null;
    private static volatile boolean started = false;

    private LiveBoardPublisher() {}

    public static synchronized void start() {
        if (started) return;
        started = true;
        SCHEDULER.scheduleAtFixedRate(LiveBoardPublisher::tick, 5, 5, TimeUnit.SECONDS);
        LOG.info("LiveBoardPublisher started (5s interval).");
    }

    private static void tick() {
        try {
            DiscordService discord = DiscordService.get();
            if (discord == null) return;

            Model model = AccBroadcastingClient.getClient().getModel();
            if (!model.gameConnected || model.getCars().isEmpty()) return;

            SessionType sessionType = model.currentSessionId.getType();
            if (sessionType != SessionType.PRACTICE
                    && sessionType != SessionType.QUALIFYING
                    && sessionType != SessionType.RACE) return;

            SessionMode mode = mapMode(sessionType);
            String track = model.trackInfo != null ? model.trackInfo.getTrackName() : "Unknown";

            List<Car> cars = dedupedCars(model.getCars());

            List<LeaderboardEntry> rows = buildRows(cars, mode);
            String content = DiscordLeaderboardManager.buildMessage(mode, track, rows);

            // Skip update if nothing changed
            int hash = content.hashCode();
            String hashStr = Integer.toHexString(hash);
            if (hashStr.equals(lastContentHash)) return;
            lastContentHash = hashStr;

            // Truncate if over 2000 chars
            if (content.length() > 1990) {
                content = content.substring(0, 1987) + "...";
            }

            discord.updateLiveBoard(content);
        } catch (Throwable t) {
            LOG.log(Level.FINE, "LiveBoardPublisher tick failed", t);
        }
    }

    private static List<LeaderboardEntry> buildRows(List<Car> cars, SessionMode mode) {
        List<LeaderboardEntry> rows = new ArrayList<>();
        for (Car car : cars) {
            if (car.realtimePosition <= 0) continue;
            Duration last = ms(car.lastLap.getLapTimeMS());
            Duration best = ms(car.bestLap.getLapTimeMS());
            Duration gap  = mode == SessionMode.RACE && car.realtimePosition > 1
                ? ms(car.gapPositionAhead)
                : (car.realtimePosition > 1 ? ms(car.deltaToSessionBest) : null);

            List<Integer> splits = car.lastLap.getSplits();
            Duration s1 = splitMs(splits, 0);
            Duration s2 = splitMs(splits, 1);
            Duration s3 = splitMs(splits, 2);

            rows.add(new LeaderboardEntry(
                car.realtimePosition,
                car.getDriver().fullName(),
                last, best, gap, s1, s2, s3,
                car.lapCount, car.pitlaneCount, false));
        }
        return rows;
    }

    private static Duration ms(int ms) {
        if (ms <= 0 || ms == Integer.MAX_VALUE) return null;
        return Duration.ofMillis(ms);
    }

    private static Duration splitMs(List<Integer> splits, int idx) {
        if (splits == null || splits.size() <= idx) return null;
        return ms(splits.get(idx));
    }

    /**
     * Deduplicate the car list by car ID and sort by position.
     * The ACC model can accumulate stale entries between sessions, producing the
     * same car twice (or two cars with the same realtimePosition). Keep the entry
     * with the better (lower) position for each car ID.
     */
    static List<Car> dedupedCars(java.util.Collection<Car> raw) {
        Map<Integer, Car> byId = new LinkedHashMap<>();
        for (Car c : raw) {
            byId.merge(c.id, c, (a, b) -> {
                boolean aValid = a.realtimePosition > 0;
                boolean bValid = b.realtimePosition > 0;
                if (aValid && bValid) return a.realtimePosition <= b.realtimePosition ? a : b;
                return aValid ? a : b;
            });
        }
        List<Car> deduped = new ArrayList<>(byId.values());
        deduped.sort(Comparator.comparingInt(c -> c.realtimePosition <= 0 ? Integer.MAX_VALUE : c.realtimePosition));
        return deduped;
    }

    private static SessionMode mapMode(SessionType t) {
        if (t == null) return SessionMode.PRACTICE;
        return switch (t) {
            case QUALIFYING -> SessionMode.QUALIFYING;
            case RACE       -> SessionMode.RACE;
            default         -> SessionMode.PRACTICE;
        };
    }
}
