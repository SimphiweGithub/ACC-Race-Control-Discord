/*
 * Copyright (c) 2021 Leonard Sch�ngel
 * 
 * For licensing information see the included license (LICENSE.txt)
 */
package racecontrol.client;

import racecontrol.client.model.Car;
import racecontrol.client.model.Model;
import racecontrol.client.protocol.enums.SessionType;
import racecontrol.utility.DiscordWebhookNotifier;
import racecontrol.utility.TimeUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Periodically publishes a compact F1-style leaderboard to Discord via webhook.
 */
public final class DiscordLeaderboardPublisher {

    private static final Logger LOG = Logger.getLogger(DiscordLeaderboardPublisher.class.getName());
    private static final ScheduledExecutorService EXEC = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "discord-leaderboard");
        t.setDaemon(true);
        return t;
    });

    private static Optional<DiscordWebhookNotifier> notifier = Optional.empty();
    private static volatile boolean started = false;

    private DiscordLeaderboardPublisher() {}

    public static void startIfConfigured() {
        if (started) return;
        notifier = DiscordWebhookNotifier.fromEnvOrProperty();
        if (notifier.isEmpty()) {
            LOG.info("Discord leaderboard publisher not started (no webhook configured)");
            return;
        }
        started = true;
        LOG.info("Starting Discord leaderboard publisher (10s interval)");
        EXEC.scheduleAtFixedRate(DiscordLeaderboardPublisher::tick, 10, 10, TimeUnit.SECONDS);
    }

    private static void tick() {
        try {
            if (notifier.isEmpty()) return;
            Model model = AccBroadcastingClient.getClient().getModel();
            if (!model.gameConnected) return;
            if (model.getCars().isEmpty()) return;

            // Compose table of top 10 by current position
            List<Car> cars = new ArrayList<>(model.getCars());
            cars.sort(Comparator.comparingInt(c -> c.position <= 0 ? Integer.MAX_VALUE : c.position));

            String headerSession = model.currentSessionId.getType() == null ? "SESSION" : model.currentSessionId.getType().name();
            String track = model.trackInfo == null ? "Unknown" : model.trackInfo.getTrackName();
            String title = "Car Status — " + headerSession + " @ " + track;

            StringBuilder sb = new StringBuilder();
            sb.append("```").append("\n");
            sb.append(title).append("\n\n");
            sb.append(String.format("%-3s %-20s %-8s %-8s\n", "P", "Driver", "Last", "Best"));

            int count = 0;
            for (Car car : cars) {
                if (car.position <= 0) continue;
                String driver = car.getDriver().fullName();
                String last = formatLap(car.lastLap.getLapTimeMS());
                String best = formatLap(car.bestLap.getLapTimeMS());
                sb.append(String.format("%-3d %-20.20s %-8s %-8s\n", car.position, driver, last, best));
                if (++count >= 10) break;
            }
            if (count == 0) return;
            sb.append("```");

            notifier.get().send(sb.toString());
        } catch (Throwable t) {
            LOG.log(Level.FINE, "Discord leaderboard tick failed", t);
        }
    }

    private static String formatLap(int ms) {
        if (ms <= 0 || ms == Integer.MAX_VALUE) return "--";
        return TimeUtils.asLapTime(ms);
    }
}


