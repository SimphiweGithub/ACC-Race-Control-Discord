/*
 * Copyright (c) 2021 Leonard Sch�ngel
 * 
 * For licensing information see the included license (LICENSE.txt)
 */
package racecontrol.client;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import racecontrol.client.model.Car;
import racecontrol.client.model.Model;
import racecontrol.client.protocol.LapInfo;
import racecontrol.client.protocol.enums.SessionType;
import racecontrol.utility.TimeUtils;

/**
 * Periodically publishes rotating Discord messages: positions/intervals and sector times.
 */
public class DiscordRotatingPublisher {

    private static final Logger LOG = Logger.getLogger(DiscordRotatingPublisher.class.getName());

    private static final ScheduledExecutorService SCHEDULER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "DiscordRotatingPublisher");
        t.setDaemon(true);
        return t;
    });

    private static volatile boolean started = false;
    private static volatile int stateIndex = 0; // 0=positions, 1=sectors

    private DiscordRotatingPublisher() {
    }

    public static synchronized void startIfConfigured() {
        if (started) {
            return;
        }
        String webhookUrl = System.getProperty("discord.webhook.url", "").trim();
        if (webhookUrl.isEmpty()) {
            LOG.info("Discord rotating publisher not started: webhook not configured.");
            return;
        }
        started = true;
        LOG.info("Starting Discord rotating publisher.");

        // Initial delay 0, then every 10 seconds
        SCHEDULER.scheduleAtFixedRate(() -> {
            try {
                publishState(webhookUrl);
                stateIndex = (stateIndex + 1) % 2;
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Failed to publish to Discord", e);
            }
        }, 0, 10, TimeUnit.SECONDS);
    }

    private static void publishState(String webhookUrl) {
        Model model = AccBroadcastingClient.getClient().getModel();

        String title;
        String body;
        if (stateIndex == 0) {
            title = buildHeader(model, "Car Status — Positions/Intervals");
            body = buildPositionsAndIntervals(model);
        } else {
            title = buildHeader(model, "Sector Times (S1/S2/S3)");
            body = buildSectors(model);
        }

        String content = title + "\n\n" + body;
        postDiscordMessage(webhookUrl, content);
    }

    private static String buildHeader(Model model, String pageTitle) {
        SessionType type = model.currentSessionId.getType();
        String sessionName = type.name();
        String track = model.trackInfo != null ? model.trackInfo.getTrackName() : "Unknown track";
        return pageTitle + " @ " + track + " (" + sessionName + ")";
    }

    private static String buildPositionsAndIntervals(Model model) {
        List<Car> cars = new ArrayList<>(model.getCars());
        cars.sort(Comparator.comparingInt(c -> c.realtimePosition == 0 ? Integer.MAX_VALUE : c.realtimePosition));

        StringBuilder sb = new StringBuilder();
        sb.append(codeRow("P", "Driver", "Last", "Best", "Int"));
        int pos = 1;
        for (Car car : cars) {
            if (car.realtimePosition <= 0) {
                continue;
            }
            String driver = safeName(getDriverDisplayName(car));
            String last = lapTimeOrDash(car.lastLap);
            String best = lapTimeOrDash(car.bestLap);
            String interval;
            if (model.currentSessionId.getType() == SessionType.RACE) {
                interval = car.realtimePosition > 1 ? TimeUtils.asGap(car.gapPositionAhead) : "--";
            } else {
                interval = (car.bestLap.getLapTimeMS() != Integer.MAX_VALUE && car.deltaToSessionBest != 0)
                        ? TimeUtils.asDelta(car.deltaToSessionBest) : "--";
            }

            sb.append(codeRow(String.valueOf(pos), driver, last, best, interval));
            pos++;
        }
        return sb.toString();
    }

    private static String buildSectors(Model model) {
        List<Car> cars = new ArrayList<>(model.getCars());
        cars.sort(Comparator.comparingInt(c -> c.realtimePosition == 0 ? Integer.MAX_VALUE : c.realtimePosition));

        StringBuilder sb = new StringBuilder();
        sb.append(codeRow("P", "Driver", "S1", "S2", "S3"));
        int pos = 1;
        for (Car car : cars) {
            if (car.realtimePosition <= 0) {
                continue;
            }
            String driver = safeName(getDriverDisplayName(car));
            List<Integer> splits = car.lastLap.getSplits();
            String s1 = splitOrDash(splits, 0);
            String s2 = splitOrDash(splits, 1);
            String s3 = splitOrDash(splits, 2);

            sb.append(codeRow(String.valueOf(pos), driver, s1, s2, s3));
            pos++;
        }
        return sb.toString();
    }

    private static String lapTimeOrDash(LapInfo lap) {
        int v = lap.getLapTimeMS();
        if (v <= 0 || v == Integer.MAX_VALUE) {
            return "--";
        }
        return TimeUtils.asLapTime(v);
    }

    private static String splitOrDash(List<Integer> splits, int idx) {
        if (splits == null || splits.size() <= idx) {
            return "--";
        }
        int v = splits.get(idx);
        if (v <= 0 || v == Integer.MAX_VALUE) {
            return "--";
        }
        return TimeUtils.asLapTime(v);
    }

    private static String safeName(String name) {
        if (name == null) {
            return "Unknown";
        }
        // Discord uses UTF-8; ensure problematic characters are preserved
        return name;
    }

    private static String getDriverDisplayName(Car car) {
        var d = car.getDriver();
        if (d.shortName != null && !d.shortName.isEmpty()) {
            return d.shortName;
        }
        return d.truncatedName();
    }

    private static String codeRow(String c1, String c2, String c3, String c4, String c5) {
        // pad columns for readability inside a code block
        return String.format("``%1$-3s  %2$-18s %3$-8s %4$-8s %5$-8s``\n",
                c1, c2, c3, c4, c5);
    }

    private static void postDiscordMessage(String webhookUrl, String content) {
        try {
            URL url = new URL(webhookUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");

            // Escape JSON newlines and quotes
            String json = "{\"content\":\"" + escapeJson(content) + "\"}";
            byte[] payload = json.getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(payload.length);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(payload);
            }
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                LOG.warning("Discord webhook responded with status code " + code);
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Error posting Discord message", e);
        }
    }

    private static String escapeJson(String text) {
        return text
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "")
                .replace("\n", "\\n");
    }
}


