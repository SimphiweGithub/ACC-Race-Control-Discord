/*
 * Copyright (c) 2021 Leonard Schüngel
 * 
 * For licensing information see the included license (LICENSE.txt)
 */
package racecontrol.utility;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Uses Discord bot REST API to send and edit a single message.
 * Fixed version that prevents message spam.
 */
public final class DiscordBotLeaderboardManager {

    private static final Logger LOG = Logger.getLogger(DiscordBotLeaderboardManager.class.getName());
    private static final String STORE_FILENAME = "DiscordLeaderboard.store";
    private static final long MIN_UPDATE_INTERVAL_MS = 2000L; // Increased to 5 seconds
    private static final int MAX_MESSAGE_LENGTH = 2000; // Reduced to be more conservative
    private static final AtomicLong lastUpdateEpochMs = new AtomicLong(0L);
    private static final int MAX_DRIVERS_DISPLAYED = 36;

    private static volatile String persistedChannelId = null;
    private static volatile String[] persistedMessageIds = null;
    private static volatile DiscordLeaderboardManager.SessionMode lastSessionMode = null;
    private static volatile String lastContentHashHex = null;

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private DiscordBotLeaderboardManager() {}

    public static synchronized void updateLeaderboard(
            DiscordLeaderboardManager.SessionMode mode,
            String trackName,
            java.util.List<DiscordLeaderboardManager.LeaderboardEntry> entries) {

        String token = System.getProperty("discord.bot.token", "").trim();
        String channelId = System.getProperty("discord.channel.id", "").trim();
        if (token.isEmpty() || channelId.isEmpty()) {
            LOG.fine("Discord bot not configured; skipping leaderboard update");
            return;
        }

        // Enhanced throttling though i think this may be useless 
        long now = System.currentTimeMillis();
        if (now - lastUpdateEpochMs.get() < MIN_UPDATE_INTERVAL_MS) {
            LOG.fine("Update throttled - too soon since last update");
            return;
        }

        loadStore();
        boolean channelChanged = !Objects.equals(channelId, persistedChannelId);
        
        // Clear message IDs on session change or channel change
        if ((lastSessionMode != null && mode != lastSessionMode) || channelChanged) {
            LOG.info("Session or channel changed - deleting old messages");
            deleteAllMessages(token, channelId);
            persistedMessageIds = null;
        }
        lastSessionMode = mode;

        // Build content and check for changes
        String content = buildCompleteMessage(mode, trackName, entries);
        String contentHash = sha256Hex(content);
        
        if (Objects.equals(contentHash, lastContentHashHex)) {
            LOG.fine("Content unchanged - skipping update");
            return;
        }

        try {
            // Check if content fits in a single message
            if (content.length() <= MAX_MESSAGE_LENGTH) {
                // Single message approach
                String messageId = (persistedMessageIds != null && persistedMessageIds.length > 0) 
                        ? persistedMessageIds[0] 
                        : null;

                boolean success = false;
                if (messageId != null && !channelChanged) {
                    // Try to edit existing message
                    success = tryEdit(token, channelId, messageId, content);
                    if (!success) {
                        LOG.info("Edit failed - deleting old message and creating new one");
                        deleteMessage(token, channelId, messageId);
                        messageId = null;
                    }
                }

                // If no existing message or edit failed, create new message
                if (messageId == null) {
                    messageId = postMessage(token, channelId, content);
                    success = (messageId != null);
                }

                if (success) {
                    // Clean up any extra messages
                    if (persistedMessageIds != null && persistedMessageIds.length > 1) {
                        for (int i = 1; i < persistedMessageIds.length; i++) {
                            if (persistedMessageIds[i] != null) {
                                deleteMessage(token, channelId, persistedMessageIds[i]);
                            }
                        }
                    }
                    
                    persistedChannelId = channelId;
                    persistedMessageIds = new String[]{messageId};
                    lastContentHashHex = contentHash;
                    lastUpdateEpochMs.set(now);
                    saveStore();
                    LOG.fine("Leaderboard updated successfully (single message)");
                }
            } else {
                // Multi-message approach (only if absolutely necessary) so close to removing this shit cant get it right
                LOG.info("Content too large for single message - using multi-message approach");
                handleLargeContent(token, channelId, content, contentHash, now, channelChanged);
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Discord bot leaderboard update failed", e);
        }
    }

    private static void handleLargeContent(String token, String channelId, String content, 
            String contentHash, long now, boolean channelChanged) throws Exception {
        
        String header = extractHeader(content);
        String bodyContent = extractBody(content);
        List<String> messageChunks = splitIntoChunks(header, bodyContent);
        String[] newMessageIds = new String[messageChunks.size()];
        boolean allSucceeded = true;

        LOG.info("Splitting content into " + messageChunks.size() + " messages");

        for (int i = 0; i < messageChunks.size(); i++) {
            String chunk = messageChunks.get(i);
            String messageId = (persistedMessageIds != null && i < persistedMessageIds.length) 
                    ? persistedMessageIds[i] 
                    : null;

            if (messageId != null && !channelChanged) {
                if (!tryEdit(token, channelId, messageId, chunk)) {
                    deleteMessage(token, channelId, messageId);
                    messageId = null;
                }
            }

            if (messageId == null) {
                messageId = postMessage(token, channelId, chunk);
            }

            if (messageId != null) {
                newMessageIds[i] = messageId;
            } else {
                allSucceeded = false;
                break;
            }

            // Add small delay between messages to avoid rate limiting
            if (i < messageChunks.size() - 1) {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        if (allSucceeded) {
            // Clean up any extra messages
            if (persistedMessageIds != null) {
                for (int i = newMessageIds.length; i < persistedMessageIds.length; i++) {
                    if (persistedMessageIds[i] != null) {
                        deleteMessage(token, channelId, persistedMessageIds[i]);
                    }
                }
            }

            persistedChannelId = channelId;
            persistedMessageIds = newMessageIds;
            lastContentHashHex = contentHash;
            lastUpdateEpochMs.set(now);
            saveStore();
            LOG.info("Large leaderboard updated successfully (" + messageChunks.size() + " messages)");
        } else {
            // Clean up any messages we created
            for (String messageId : newMessageIds) {
                if (messageId != null) {
                    deleteMessage(token, channelId, messageId);
                }
            }
            LOG.warning("Failed to update large leaderboard content");
        }
    }

    private static String buildCompleteMessage(DiscordLeaderboardManager.SessionMode mode, 
        String trackName, java.util.List<DiscordLeaderboardManager.LeaderboardEntry> entries) {
    String header = DiscordLeaderboardManagerContent.buildHeader(mode, trackName);
    
    // Check if we need to truncate
    java.util.List<DiscordLeaderboardManager.LeaderboardEntry> displayEntries = entries;
    String truncationNote = "";
    
    if (entries.size() > MAX_DRIVERS_DISPLAYED) {
        displayEntries = entries.subList(0, MAX_DRIVERS_DISPLAYED);
        int remaining = entries.size() - MAX_DRIVERS_DISPLAYED;
        truncationNote = String.format("\n*(Showing top %d of %d drivers - %d more not displayed)*", 
            MAX_DRIVERS_DISPLAYED, entries.size(), remaining);
    }
    
    String content = DiscordLeaderboardManagerContent.buildContent(displayEntries);
    return header + truncationNote + "\n\n```\n" + content + "```";
}

    private static String extractHeader(String fullContent) {
        int codeBlockStart = fullContent.indexOf("```");
        if (codeBlockStart > 0) {
            return fullContent.substring(0, codeBlockStart).trim();
        }
        return "Leaderboard";
    }

    private static String extractBody(String fullContent) {
        int codeBlockStart = fullContent.indexOf("```\n");
        int codeBlockEnd = fullContent.lastIndexOf("```");
        if (codeBlockStart >= 0 && codeBlockEnd > codeBlockStart) {
            return fullContent.substring(codeBlockStart + 4, codeBlockEnd);
        }
        return fullContent;
    }

    private static List<String> splitIntoChunks(String header, String bodyContent) {
        List<String> chunks = new ArrayList<>();
        String[] lines = bodyContent.split("\n");
        StringBuilder currentChunk = new StringBuilder();
        
        // Start with header and code block
        String chunkStart = header + "\n\n```\n";
        currentChunk.append(chunkStart);
        int currentLength = chunkStart.length();
        
        boolean hasContent = false;
        
        for (String line : lines) {
            int lineLength = line.length() + 1; // +1 for newline
            
            // If this line would make us exceed the limit, finalize current chunk
            if (currentLength + lineLength + 3 > MAX_MESSAGE_LENGTH && hasContent) { // +3 for closing ```
                currentChunk.append("```");
                chunks.add(currentChunk.toString());
                
                // Start new chunk
                currentChunk = new StringBuilder("```\n");
                currentLength = 4; // Length of "```\n"
                hasContent = false;
            }
            
            currentChunk.append(line).append("\n");
            currentLength += lineLength;
            hasContent = true;
        }
        
        // Add the final chunk if it has content
        if (hasContent) {
            currentChunk.append("```");
            chunks.add(currentChunk.toString());
        }
        
        return chunks;
    }

    private static boolean tryEdit(String token, String channelId, String messageId, String content) {
        try {
            String url = "https://discord.com/api/v10/channels/" + channelId + "/messages/" + messageId;
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .header("Authorization", "Bot " + token)
                    .header("Content-Type", "application/json; charset=utf-8")
                    .method("PATCH", HttpRequest.BodyPublishers.ofString(jsonContent(content)))
                    .build();
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            int code = resp.statusCode();
            if (code >= 200 && code < 300) {
                return true;
            }
            if (code == 404) {
                LOG.fine("Message not found for edit - will create new message");
                return false;
            }
            LOG.warning("Discord edit failed: status=" + code + ", body=" + resp.body());
            return false;
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Error editing Discord message", e);
            return false;
        }
    }

    private static String postMessage(String token, String channelId, String content) {
        try {
            String url = "https://discord.com/api/v10/channels/" + channelId + "/messages";
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .header("Authorization", "Bot " + token)
                    .header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonContent(content)))
                    .build();
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            int code = resp.statusCode();
            if (code < 200 || code >= 300) {
                LOG.warning("Discord post failed: status=" + code + ", body=" + resp.body());
                return null;
            }
            return extractMessageId(resp.body());
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Error posting Discord message", e);
            return null;
        }
    }

    private static String extractMessageId(String responseBody) {
        String marker = "\"id\":";
        int i = responseBody.indexOf(marker);
        if (i >= 0) {
            int start = i + marker.length();
            int q1 = responseBody.indexOf('"', start);
            int q2 = q1 >= 0 ? responseBody.indexOf('"', q1 + 1) : -1;
            if (q1 >= 0 && q2 > q1) {
                return responseBody.substring(q1 + 1, q2);
            }
        }
        return null;
    }

    private static String jsonContent(String content) {
        return "{\"content\":\"" + escapeJson(content) + "\"}";
    }

    private static String escapeJson(String text) {
        if (text == null) return "";
        return text
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "")
                .replace("\n", "\\n");
    }

    private static String sha256Hex(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(text.hashCode());
        }
    }

    private static void deleteAllMessages(String token, String channelId) {
        if (persistedMessageIds == null) return;
        
        for (String messageId : persistedMessageIds) {
            if (messageId != null) {
                deleteMessage(token, channelId, messageId);
            }
        }
    }

    private static void deleteMessage(String token, String channelId, String messageId) {
        try {
            String url = "https://discord.com/api/v10/channels/" + channelId + "/messages/" + messageId;
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .header("Authorization", "Bot " + token)
                    .DELETE()
                    .build();
            HTTP.send(request, HttpResponse.BodyHandlers.discarding());
            LOG.fine("Deleted message: " + messageId);
        } catch (Exception e) {
            LOG.log(Level.FINE, "Failed to delete message " + messageId, e);
        }
    }

    private static void loadStore() {
        var values = StoreIO.read(STORE_FILENAME);
        persistedChannelId = values.channelId;
        if (values.messageId != null && !values.messageId.isEmpty()) {
            persistedMessageIds = new String[]{values.messageId};
        }
    }

    private static void saveStore() {
        String firstMessageId = (persistedMessageIds != null && persistedMessageIds.length > 0) 
                ? persistedMessageIds[0] 
                : "";
        StoreIO.write(STORE_FILENAME, null, persistedChannelId, firstMessageId);
    }

    private static class StoreIO {
        static class Values { String webhookUrl; String channelId; String messageId; }
        static Values read(String filename) {
            Values v = new Values();
            java.io.File file = new java.io.File(System.getProperty("user.dir"), filename);
            if (!file.exists()) return v;
            try (java.io.BufferedReader br = new java.io.BufferedReader(new java.io.FileReader(file, java.nio.charset.StandardCharsets.UTF_8))) {
                v.webhookUrl = br.readLine();
                v.channelId = br.readLine();
                v.messageId = br.readLine();
            } catch (Exception ignored) {}
            return v;
        }
        static void write(String filename, String webhookUrl, String channelId, String messageId) {
            java.io.File file = new java.io.File(System.getProperty("user.dir"), filename);
            try (java.io.BufferedWriter bw = new java.io.BufferedWriter(new java.io.FileWriter(file, java.nio.charset.StandardCharsets.UTF_8, false))) {
                bw.write(webhookUrl == null ? "" : webhookUrl); bw.newLine();
                bw.write(channelId == null ? "" : channelId); bw.newLine();
                bw.write(messageId == null ? "" : messageId);
            } catch (Exception ignored) {}
        }
    }
}