/*
 * Copyright (c) 2021 Leonard Sch�ngel
 * 
 * For licensing information see the included license (LICENSE.txt)
 */
package racecontrol.utility;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Manages a Discord leaderboard message using a webhook.
 */
public final class DiscordLeaderboardManager {

    private static final Logger LOG = Logger.getLogger(DiscordLeaderboardManager.class.getName());

    // Store file in working directory
    private static final String STORE_FILENAME = "DiscordLeaderboard.store";

    // Throttle to 1 update/sec
    private static final long MIN_UPDATE_INTERVAL_MS = 1000L;
    private static final AtomicLong lastUpdateEpochMs = new AtomicLong(0L);

    // In-memory state
    private static volatile String persistedWebhookUrl = null;
    private static volatile String persistedMessageId = null;
    private static volatile SessionMode lastSessionMode = null;
    private static volatile String lastContentHashHex = null;

    private DiscordLeaderboardManager() {
    }

    /**
     * Row value for the leaderboard.
     */
    public static class LeaderboardEntry {
        public final int position;
        public final String driverName;
        public final Duration lastLap;   // nullable
        public final Duration bestLap;   // nullable
        public final Duration gap;       // nullable, vs P1 or car ahead depending on mode
        public final Duration sector1;   // nullable
        public final Duration sector2;   // nullable
        public final Duration sector3;   // nullable
        public final int laps;
        public final int pits;
        public final boolean retired;

        public LeaderboardEntry(int position,
                String driverName,
                Duration lastLap,
                Duration bestLap,
                Duration gap,
                Duration sector1,
                Duration sector2,
                Duration sector3,
                int laps,
                int pits,
                boolean retired) {
            this.position = position;
            this.driverName = driverName == null ? "" : driverName;
            this.lastLap = lastLap;
            this.bestLap = bestLap;
            this.gap = gap;
            this.sector1 = sector1;
            this.sector2 = sector2;
            this.sector3 = sector3;
            this.laps = laps;
            this.pits = pits;
            this.retired = retired;
        }
    }

    public enum SessionMode {
        PRACTICE,
        QUALIFYING,
        RACE
    }

    /**
     * Update the Discord leaderboard message in place. If no message exists yet, it will be posted once
     * and its message ID stored. Subsequent calls will edit the same message.
     *
     * @param mode    Session mode (practice/qualifying/race)
     * @param trackName Track name for the header
     * @param entries Ordered rows for the table
     */
    public static synchronized void updateLeaderboard(SessionMode mode, String trackName, List<LeaderboardEntry> entries) {
        String webhookUrl = System.getProperty("discord.webhook.url", "").trim();
        if (webhookUrl.isEmpty()) {
            LOG.fine("Discord webhook not configured; skipping leaderboard update");
            return;
        }

        // Respect throttle
        long now = System.currentTimeMillis();
        long last = lastUpdateEpochMs.get();
        if (now - last < MIN_UPDATE_INTERVAL_MS) {
            return;
        }
        // Session change → clear message id so a new message with a new header is posted
        if (lastSessionMode != null && mode != lastSessionMode) {
            persistedMessageId = null;
        }
        lastSessionMode = mode;

        // Build message
        String content = buildMessage(mode, trackName, entries);

        // Suppress duplicate content
        String contentHash = sha256Hex(content);
        if (Objects.equals(contentHash, lastContentHashHex)) {
            return;
        }

        // Load persisted state (message id and last webhook used)
        loadStore();
        boolean webhookChanged = !Objects.equals(webhookUrl, persistedWebhookUrl);

        try {
            if (persistedMessageId != null && !webhookChanged) {
                // Try edit-in-place
                EditResult result = tryEdit(webhookUrl, persistedMessageId, content);
                if (result == EditResult.SUCCESS) {
                    lastContentHashHex = contentHash;
                    lastUpdateEpochMs.set(now);
                    return;
                } else if (result == EditResult.RETRY_LATER) {
                    // Do not post a new message on transient errors to avoid spam
                    return;
                }
                // NOT_FOUND → fall through to re-post
            }

            // Post a new message and persist its ID
            String messageId = postMessage(webhookUrl, content);
            if (messageId != null && !messageId.isEmpty()) {
                persistedWebhookUrl = webhookUrl;
                persistedMessageId = messageId;
                saveStore();
                lastContentHashHex = contentHash;
                lastUpdateEpochMs.set(now);
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Discord leaderboard update failed", e);
        }
    }


public static String buildMessage(SessionMode mode, String trackName, List<LeaderboardEntry> entries) {
    StringBuilder sb = new StringBuilder();
    sb.append(buildHeaderTitle(mode, trackName)).append("\n\n");
    sb.append("```\n");

    switch (mode) {
        case PRACTICE: {
            // Use only sequential specifiers \u2014 mixing %1$Ns with %-Ns causes arg[1] to be consumed twice
            sb.append(String.format("%-3s %-18s %-9s %-9s %-10s %-7s %-7s %-7s\n",
                    "P", "Driver", "Last", "Best", "Gap", "S1", "S2", "S3"));
            for (LeaderboardEntry e : entries) {
                String gapStr = e.position == 1 ? "\u2014" : formatGap(e.gap);
                sb.append(String.format("%3d %-18s %-9s %-9s %-10s %-7s %-7s %-7s\n",
                        e.position,
                        monoTruncate(e.driverName, 18),
                        formatLap(e.lastLap),
                        formatLap(e.bestLap),
                        padWidth(gapStr, 10),
                        formatSector(e.sector1),
                        formatSector(e.sector2),
                        formatSector(e.sector3)));
            }
            break;
        }
        case QUALIFYING: {
            Duration bestLapTime = entries.stream()
                    .filter(e -> e.bestLap != null)
                    .map(e -> e.bestLap)
                    .min(Duration::compareTo)
                    .orElse(Duration.ZERO);

            sb.append(String.format("%-3s %-18s %-9s %-9s %-10s\n",
                    "P", "Driver", "Last", "Best", "Gap"));
            for (LeaderboardEntry e : entries) {
                String gapStr;
                if (e.position == 1 || e.bestLap == null) {
                    gapStr = "\u2014";
                } else if (bestLapTime.isZero()) {
                    gapStr = "+?";
                } else {
                    gapStr = formatGap(e.bestLap.minus(bestLapTime));
                }
                sb.append(String.format("%3d %-18s %-9s %-9s %-10s\n",
                        e.position,
                        monoTruncate(e.driverName, 18),
                        formatLap(e.lastLap),
                        formatLap(e.bestLap),
                        gapStr));
            }
            break;
        }
        case RACE: {
            sb.append("P   Driver             Last      Best      Int        Laps Pits\n");
            for (LeaderboardEntry e : entries) {
                String intStr = e.position == 1 ? "\u2014" : formatGap(e.gap);
                sb.append(String.format("%3d %-18s %-9s %-9s %-10s %4s %4s\n",
                        e.position,
                        monoTruncate(e.driverName, 18),
                        formatLap(e.lastLap),
                        formatLap(e.bestLap),
                        padWidth(intStr, 10),
                        rightAlign(e.laps, 4),
                        rightAlign(e.pits, 4)));
            }
            break;
        }
        default:
            break;
    }

    sb.append("```");
    return sb.toString();
}


    private static String buildHeaderTitle(SessionMode mode, String trackName) {
        String t = (trackName == null || trackName.isBlank()) ? "Unknown" : trackName;
        // Use \u2014 (em dash) \u2014 literal "\u2014" compiles to mojibake under windows-1252 encoding
        switch (mode) {
            case PRACTICE:   return "Free Practice \u2014 " + t;
            case QUALIFYING: return "Qualifying \u2014 " + t;
            case RACE:       return "Race \u2014 " + t;
            default:         return t;
        }
    }

    private static String monoTruncate(String s, int max) {
        if (s == null) return "";
        int count = s.codePointCount(0, s.length());
        if (count <= max) return padWidth(s, max);
        int end = s.offsetByCodePoints(0, max - 1);
        return padWidth(s.substring(0, end) + "\u2026", max);
    }

    private static String rightAlign(int value, int width) {
        return String.format("%" + width + "d", value);
    }

    private static String padWidth(String s, int width) {
        if (s == null) s = "";
        if (s.length() >= width) return s;
        return String.format("%1$-" + width + "s", s);
    }

    private static String formatLap(Duration d) {
        if (d == null || d.isNegative() || d.isZero()) return "--";
        long ms = d.toMillis();
        int millis = (int) (ms % 1000);
        long totalSeconds = ms / 1000;
        int s = (int) (totalSeconds % 60);
        int m = (int) ((totalSeconds / 60) % 60);
        return String.format("%02d:%02d.%03d", m, s, millis);
    }

    private static String formatSector(Duration d) {
        if (d == null || d.isNegative() || d.isZero()) return "--";
        long ms = d.toMillis();
        int millis = (int) (ms % 1000);
        int s = (int) (ms / 1000);
        return String.format("%d.%03d", s, millis);
    }

    private static String formatGap(Duration d) {
        if (d == null) return "--";
        long ms = d.toMillis();
        String sign = ms < 0 ? "-" : "+";
        ms = Math.abs(ms);
        int millis = (int) (ms % 1000);
        long totalSeconds = ms / 1000;
        int s = (int) (totalSeconds % 60);
        int m = (int) ((totalSeconds / 60) % 60);
        if (m >= 1) {
            return String.format("%s%d:%02d.%03d", sign, m, s, millis);
        }
        return String.format("%s%d.%03d", sign, s, millis);
    }

    private static String sha256Hex(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(text.hashCode());
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(0, Math.max(0, max - 1)) + "\u2026"; // ellipsis
    }

    private static String padDash(String s, int width) {
        String v = (s == null || s.isEmpty()) ? "--" : s;
        if (v.length() >= width) return v;
        return String.format("%1$-" + width + "s", v);
    }

    private enum EditResult { SUCCESS, NOT_FOUND, RETRY_LATER }

    private static EditResult tryEdit(String webhookUrl, String messageId, String content) {
        try {
            // Prefer Java 11 HttpClient with real PATCH
            EditResult clientResult = tryEditWithHttpClient(webhookUrl, messageId, content);
            if (clientResult != null) {
                return clientResult;
            }
            URL url = new URL(webhookUrl + "/messages/" + messageId);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            // Attempt PATCH
            boolean methodSet = setMethod(conn, "PATCH");
            if (!methodSet) {
                // Fallback: override with POST
                conn.setRequestMethod("POST");
                conn.setRequestProperty("X-HTTP-Method-Override", "PATCH");
            }
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            writeJsonPayload(conn, content);
            int code = conn.getResponseCode();
            if (code >= 200 && code < 300) {
                return EditResult.SUCCESS;
            }
            if (code == 404) {
                return EditResult.NOT_FOUND;
            }
            // Other statuses (e.g., 400/429/5xx) → retry later, don't spam new posts
            LOG.info("Discord edit failed with status " + code + "; suppressing re-post to avoid spam");
            return EditResult.RETRY_LATER;
        } catch (Exception e) {
            LOG.log(Level.INFO, "Discord edit failed (exception); suppressing re-post", e);
            return EditResult.RETRY_LATER;
        }
    }

    // Uses Java 11+ HttpClient when available to perform a true PATCH
    private static EditResult tryEditWithHttpClient(String webhookUrl, String messageId, String content) {
        try {
            // Defer class loading to avoid issues on older JREs
            Class<?> httpClientClass = Class.forName("java.net.http.HttpClient");
            Class<?> httpRequestClass = Class.forName("java.net.http.HttpRequest");
            Class<?> bodyPublishersClass = Class.forName("java.net.http.HttpRequest$BodyPublishers");
            Class<?> httpResponseClass = Class.forName("java.net.http.HttpResponse");

            Object httpClient = httpClientClass.getMethod("newHttpClient").invoke(null);

            String uri = webhookUrl + "/messages/" + messageId;
            Object bodyPublisher = bodyPublishersClass.getMethod("ofString", CharSequence.class)
                    .invoke(null, jsonContent(content));

            Object builder = httpRequestClass.getMethod("newBuilder", java.net.URI.class)
                    .invoke(null, java.net.URI.create(uri));
            builder = builder.getClass().getMethod("header", String.class, String.class)
                    .invoke(builder, "Content-Type", "application/json; charset=utf-8");
            builder = builder.getClass().getMethod("method", String.class, Class.forName("java.net.http.HttpRequest$BodyPublisher"))
                    .invoke(builder, "PATCH", bodyPublisher);
            Object request = builder.getClass().getMethod("build").invoke(builder);

            Object response = httpClientClass.getMethod("send", httpRequestClass, Class.forName("java.net.http.HttpResponse$BodyHandler"))
                    .invoke(httpClient, request, Class.forName("java.net.http.HttpResponse$BodyHandlers")
                            .getMethod("ofString").invoke(null));

            int statusCode = (int) httpResponseClass.getMethod("statusCode").invoke(response);
            if (statusCode >= 200 && statusCode < 300) {
                return EditResult.SUCCESS;
            }
            if (statusCode == 404) {
                return EditResult.NOT_FOUND;
            }
            LOG.info("Discord edit failed with status " + statusCode + " (HttpClient); suppressing re-post to avoid spam");
            return EditResult.RETRY_LATER;
        } catch (ClassNotFoundException cnfe) {
            return null; // HttpClient not available
        } catch (Throwable t) {
            LOG.log(Level.FINE, "HttpClient PATCH failed; will try HttpURLConnection fallback", t);
            return null;
        }
    }

    private static String jsonContent(String content) {
        return "{\"content\":\"" + escapeJson(content) + "\"}";
    }

    private static String postMessage(String webhookUrl, String content) throws Exception {
        URL url = new URL(webhookUrl + "?wait=true");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        writeJsonPayload(conn, content);

        int code = conn.getResponseCode();
        if (code < 200 || code >= 300) {
            String err = readStreamQuiet(conn.getErrorStream());
            LOG.warning("Discord post failed with status " + code + ": " + err);
            return null;
        }
        String body = readStreamQuiet(conn.getInputStream());
        // Extract "id":"<messageId>" from JSON (simple parse to avoid extra deps)
        String marker = "\"id\":";
        int i = body.indexOf(marker);
        if (i >= 0) {
            int start = i + marker.length();
            // value can be a string or numeric; Discord returns ID as string in quotes
            int q1 = body.indexOf('"', start);
            int q2 = q1 >= 0 ? body.indexOf('"', q1 + 1) : -1;
            if (q1 >= 0 && q2 > q1) {
                return body.substring(q1 + 1, q2);
            }
        }
        LOG.warning("Discord post succeeded but message id was not found in response");
        return null;
    }

    private static void writeJsonPayload(HttpURLConnection conn, String content) throws Exception {
        String json = "{\"content\":\"" + escapeJson(content) + "\"}";
        byte[] payload = json.getBytes(StandardCharsets.UTF_8);
        conn.setFixedLengthStreamingMode(payload.length);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(payload);
        }
    }

    private static String escapeJson(String text) {
        if (text == null) return "";
        return text
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "")
                .replace("\n", "\\n");
    }

    private static String readStreamQuiet(InputStream is) {
        if (is == null) return "";
        try (is) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    private static boolean setMethod(HttpURLConnection conn, String method) throws Exception {
        try {
            conn.setRequestMethod(method); // Works if JVM supports PATCH
            return true;
        } catch (java.net.ProtocolException ex) {
            try {
                // Fallback: force set via reflection
                var m = HttpURLConnection.class.getDeclaredMethod("setRequestMethod", String.class);
                m.setAccessible(true);
                m.invoke(conn, method);
                return true;
            } catch (Throwable t) {
                return false;
            }
        }
    }

    private static void loadStore() {
        if (persistedWebhookUrl != null || persistedMessageId != null) {
            return;
        }
        File file = new File(System.getProperty("user.dir"), STORE_FILENAME);
        if (!file.exists()) {
            return;
        }
        try (BufferedReader br = new BufferedReader(new FileReader(file, StandardCharsets.UTF_8))) {
            String url = br.readLine();
            String id = br.readLine();
            persistedWebhookUrl = (url != null && !url.isBlank()) ? url.trim() : null;
            persistedMessageId = (id != null && !id.isBlank()) ? id.trim() : null;
        } catch (Exception e) {
            LOG.log(Level.FINE, "Failed to load Discord leaderboard store", e);
        }
    }

    private static void saveStore() {
        File file = new File(System.getProperty("user.dir"), STORE_FILENAME);
        try (BufferedWriter bw = new BufferedWriter(new FileWriter(file, StandardCharsets.UTF_8, false))) {
            bw.write(persistedWebhookUrl == null ? "" : persistedWebhookUrl);
            bw.newLine();
            bw.write(persistedMessageId == null ? "" : persistedMessageId);
        } catch (Exception e) {
            LOG.log(Level.FINE, "Failed to save Discord leaderboard store", e);
        }
    }
}


