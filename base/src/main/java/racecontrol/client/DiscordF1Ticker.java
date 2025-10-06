/*
 * Copyright (c) 2021 Leonard Sch�ngel
 * 
 * For licensing information see the included license (LICENSE.txt)
 */
package racecontrol.client;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import racecontrol.client.model.Car;
import racecontrol.client.model.Model;
import racecontrol.client.protocol.enums.SessionType;
import racecontrol.utility.DiscordLeaderboardManager;
import racecontrol.utility.DiscordLeaderboardManager.LeaderboardEntry;
import racecontrol.utility.DiscordLeaderboardManager.SessionMode;

public final class DiscordF1Ticker {

    private static final Logger LOG = Logger.getLogger(DiscordF1Ticker.class.getName());
    private static final ScheduledExecutorService SCHEDULER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "DiscordF1Ticker");
        t.setDaemon(true);
        return t;
    });

    private static volatile boolean started = false;
    private static int infoTickCounter = 0;

    private DiscordF1Ticker() {
    }

    public static synchronized void start() {
        if (started) return;
        started = true;
        String token = System.getProperty("discord.bot.token", "").trim();
        String channelId = System.getProperty("discord.channel.id", "").trim();
        if (token.isEmpty() || channelId.isEmpty()) {
            LOG.info("Discord F1 ticker enabled but bot is not configured (set -Ddiscord.bot.token and -Ddiscord.channel.id). The ticker will be idle.");
        } else {
            LOG.info("Starting Discord F1 ticker (interval=2s) → channel=" + channelId);
        }
        SCHEDULER.scheduleAtFixedRate(DiscordF1Ticker::tick, 0, 2, TimeUnit.SECONDS);
    }

    private static void tick() {
        Model model = AccBroadcastingClient.getClient().getModel();
        SessionType sessionType = model.currentSessionId.getType();
        // Only publish for the three primary session types
        if (sessionType != SessionType.PRACTICE
                && sessionType != SessionType.QUALIFYING
                && sessionType != SessionType.RACE) {
            return;
        }
        SessionMode mode = mapMode(sessionType);
        String track = model.trackInfo != null ? model.trackInfo.getTrackName() : "Unknown";
        List<Car> cars = new ArrayList<>(model.getCars());
        cars.sort(Comparator.comparingInt(c -> c.realtimePosition == 0 ? Integer.MAX_VALUE : c.realtimePosition));

        // Throttled info log roughly every 20 seconds
        if ((infoTickCounter++ % 10) == 0) {
            LOG.info("Discord ticker tick: mode=" + mode + ", track=" + track + ", cars=" + cars.size());
        }

        List<LeaderboardEntry> rows = new ArrayList<>();
        for (Car car : cars) {
            if (car.realtimePosition <= 0) continue;
            String driver = deriveDriverName(car);

            Duration last = toDurationOrNull(car.lastLap.getLapTimeMS());
            Duration best = toDurationOrNull(car.bestLap.getLapTimeMS());

            Duration gap;
            if (mode == SessionMode.RACE) {
                gap = car.realtimePosition > 1 ? toDurationOrNull(car.gapPositionAhead) : null; // leader shows em dash
            } else {
                // practice/qualifying: gap to session best (leader shows em dash)
                // Only show if driver has a valid best lap
                boolean hasValidBest = car.bestLap.getLapTimeMS() > 0 && car.bestLap.getLapTimeMS() != Integer.MAX_VALUE;
                gap = (car.realtimePosition > 1 && hasValidBest)
                        ? toDurationOrNull(car.deltaToSessionBest)
                        : null;
            }

            Duration s1 = splitToDuration(car, 0);
            Duration s2 = splitToDuration(car, 1);
            Duration s3 = splitToDuration(car, 2);

            int laps = car.lapCount;
            int pits = car.pitlaneCount;
            boolean retired = false; // not tracked in current model

            rows.add(new LeaderboardEntry(
                    car.realtimePosition,
                    driver,
                    last,
                    best,
                    gap,
                    s1,
                    s2,
                    s3,
                    laps,
                    pits,
                    retired));
        }

        // Prefer bot-based updates if configured; else fallback to webhook manager
        if (!System.getProperty("discord.bot.token", "").trim().isEmpty()
                && !System.getProperty("discord.channel.id", "").trim().isEmpty()) {
            racecontrol.utility.DiscordBotLeaderboardManager.updateLeaderboard(mode, track, rows);
        } else {
            racecontrol.utility.DiscordLeaderboardManager.updateLeaderboard(mode, track, rows);
        }
    }

    private static SessionMode mapMode(SessionType t) {
        if (t == null) return SessionMode.PRACTICE;
        switch (t) {
            case PRACTICE:
            case HOTLAP:
            case SUPERPOLE:
                return SessionMode.PRACTICE;
            case QUALIFYING:
                return SessionMode.QUALIFYING;
            case RACE:
                return SessionMode.RACE;
            default:
                return SessionMode.PRACTICE;
        }
    }

    private static String deriveDriverName(Car car) {
        var d = car.getDriver();
        if (d.shortName != null && !d.shortName.isEmpty()) return d.shortName;
        return d.truncatedName();
    }

    private static Duration toDurationOrNull(int ms) {
        if (ms <= 0 || ms == Integer.MAX_VALUE) return null;
        return Duration.ofMillis(ms);
    }

    private static Duration splitToDuration(Car car, int idx) {
        var splits = car.lastLap.getSplits();
        if (splits == null || splits.size() <= idx) return null;
        int ms = splits.get(idx);
        return toDurationOrNull(ms);
    }
}


