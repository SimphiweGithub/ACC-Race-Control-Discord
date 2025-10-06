/*
 * Copyright (c) 2021 Leonard Sch�ngel
 * 
 * For licensing information see the included license (LICENSE.txt)
 */
/* Lightweight utility to send Discord webhook messages. */
package racecontrol.utility;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public class DiscordWebhookNotifier {

    private static final Logger LOG = Logger.getLogger(DiscordWebhookNotifier.class.getName());

    private final String webhookUrl;

    public DiscordWebhookNotifier(String webhookUrl) {
        this.webhookUrl = webhookUrl;
    }

    public static Optional<DiscordWebhookNotifier> fromEnvOrProperty() {
        String url = System.getenv("DISCORD_WEBHOOK_URL");
        if (url == null || url.isBlank()) {
            url = System.getProperty("discord.webhook.url");
        }
        if (url == null || url.isBlank()) {
            url = readFromFileConfig().orElse(null);
        }
        if (url == null || url.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new DiscordWebhookNotifier(url));
    }

    private static Optional<String> readFromFileConfig() {
        try {
            Path[] paths = {
                Path.of("config", "discord.properties"),
                Path.of(System.getProperty("user.dir"), "config", "discord.properties"),
                Path.of("..", "config", "discord.properties"),
                Path.of("..", "..", "config", "discord.properties")
            };
            Path path = null;
            for (Path p : paths) {
                if (Files.exists(p)) {
                    path = p;
                    break;
                }
            }
            if (path == null) {
                return Optional.empty();
            }
            Properties p = new Properties();
            try (var in = Files.newInputStream(path)) {
                p.load(in);
            }
            String v = p.getProperty("discord.webhook.url", "").trim();
            if (v.isEmpty()) return Optional.empty();
            return Optional.of(v);
        } catch (Throwable t) {
            Logger.getLogger(DiscordWebhookNotifier.class.getName()).log(Level.WARNING, "Cannot read config/discord.properties", t);
            return Optional.empty();
        }
    }

    public void send(String content) {
        // Discord's message limit is 2000 characters
        final int MAX_LENGTH = 1900; // Use 1900 to be safe and account for any JSON formatting
        
        // If message is short enough, send it as is
        if (content.length() <= MAX_LENGTH) {
            sendMessage(content);
            return;
        }
        
        // Split the message into chunks
        int start = 0;
        int end = MAX_LENGTH;
        int totalLength = content.length();
        int part = 1;
        
        while (start < totalLength) {
            // Find the last newline or space before MAX_LENGTH
            if (end > totalLength) {
                end = totalLength;
            } else {
                int lastNewline = content.lastIndexOf('\n', end);
                int lastSpace = content.lastIndexOf(' ', end);
                
                // Prefer splitting at newline, then space, otherwise hard split
                if (lastNewline > start && (end - lastNewline) < 200) {
                    end = lastNewline + 1; // Include the newline
                } else if (lastSpace > start && (end - lastSpace) < 200) {
                    end = lastSpace + 1; // Include the space
                }
            }
            
            // Extract the chunk and send it
            String chunk = content.substring(start, end).trim();
            if (!chunk.isEmpty()) {
                sendMessage("[" + part++ + "] " + chunk);
            }
            
            // Move to the next chunk
            start = end;
            end = start + MAX_LENGTH;
        }
    }
    
    private void sendMessage(String content) {
        try {
            String payload = "{\"content\": " + quote(content) + "}";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(webhookUrl))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                    .build();
            HttpClient.newHttpClient().sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .whenComplete((resp, err) -> {
                        if (err != null) {
                            LOG.log(Level.WARNING, "Discord webhook failed", err);
                        } else if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                            LOG.info("Discord webhook sent successfully");
                        } else {
                            LOG.log(Level.WARNING, "Discord webhook failed: " + resp.statusCode() + " - " + resp.body());
                        }
                    });
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "Error sending Discord webhook", t);
        }
    }

    private static String quote(String s) {
        String escaped = s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
        return "\"" + escaped + "\"";
    }
}


