/*
 * Copyright (c) 2021 Leonard Sch�ngel
 * 
 * For licensing information see the included license (LICENSE.txt)
 */
package racecontrol.utility;

import java.time.Duration;
import java.util.List;

public final class DiscordLeaderboardManagerContent {

    private DiscordLeaderboardManagerContent() {}

    /**
     * Builds the complete message (header + content) for backward compatibility.
     */
    public static String buildMessage(DiscordLeaderboardManager.SessionMode mode, String trackName, List<DiscordLeaderboardManager.LeaderboardEntry> entries) {
        String header = buildHeader(mode, trackName);
        String content = buildContent(entries);
        return header + "\n\n```\n" + content + "```";
    }

    /**
     * Builds just the header part of the message.
     */
    public static String buildHeader(DiscordLeaderboardManager.SessionMode mode, String trackName) {
        String modeStr = "";
        switch (mode) {
            case PRACTICE: modeStr = "Practice"; break;
            case QUALIFYING: modeStr = "Qualifying"; break;
            case RACE: modeStr = "Race"; break;
        }
        return String.format("**%s - %s**", trackName, modeStr);
    }

    /**
     * Builds just the content part of the message (without header or code blocks).
     */
    public static String buildContent(List<DiscordLeaderboardManager.LeaderboardEntry> entries) {
        StringBuilder sb = new StringBuilder();
        
        // Define column widths and formats
        final String POS_FORMAT = "%3s";    // Position (3 chars, right-aligned)
        final String DRIVER_FORMAT = "%-6s"; // Driver (6 chars, left-aligned)
        final String TIME_FORMAT = "%8s";    // Time (8 chars, right-aligned)
        final String GAP_FORMAT = "%9s";     // Gap (9 chars, right-aligned)
        final String LAPS_FORMAT = "%4s";    // Laps (4 chars, right-aligned)
        final String PITS_FORMAT = "%3s";    // Pits (3 chars, right-aligned)
        
        // Build header
        String header = String.format(
            POS_FORMAT + " " + DRIVER_FORMAT + " " + TIME_FORMAT + " " + TIME_FORMAT + " " + GAP_FORMAT + " " + LAPS_FORMAT + " " + PITS_FORMAT + "\n",
            "#", "Driver", "Last", "Best", "Gap", "L", "P"
        );
        
        // Add separator line (matching the header width)
        String separator = "-".repeat(header.length() - 1) + "\n";
        
        sb.append(header).append(separator);
        
        for (DiscordLeaderboardManager.LeaderboardEntry entry : entries) {
            String driverName = truncate(entry.driverName, 6);
            String lastLap = formatDuration(entry.lastLap);
            String bestLap = formatDuration(entry.bestLap);
            String gap = entry.gap != null ? formatGap(entry.gap) : "-";
            
            // Fix pit display logic
            String pitsDisplay;
            if (entry.retired) {
                pitsDisplay = "R";
            } else {
                pitsDisplay = String.valueOf(entry.pits);  // Always show pit count, including 0
            }
            
            // Format the row with proper alignment
            String row = String.format(
                POS_FORMAT + " " + DRIVER_FORMAT + " " + TIME_FORMAT + " " + TIME_FORMAT + " " + GAP_FORMAT + " " + LAPS_FORMAT + " " + PITS_FORMAT + "\n",
                entry.position,
                driverName,
                lastLap,
                bestLap,
                gap,
                entry.laps,
                pitsDisplay
            );
            
            sb.append(row);
        }
        
        return sb.toString();
    }
    
    private static String formatDuration(Duration duration) {
        if (duration == null) return "--:--.---";
        long minutes = duration.toMinutes();
        long seconds = duration.minusMinutes(minutes).getSeconds();
        long millis = duration.minusMinutes(minutes).minusSeconds(seconds).toMillis();
        // Return in format M:SS.mmm
        return String.format("%02d:%02d.%03d", minutes, seconds, millis);
    }
    
    private static String formatGap(Duration gap) {
        if (gap == null) return "-";
        
        long totalMillis = gap.toMillis();
        long totalSeconds = totalMillis / 1000;
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        long millis = totalMillis % 1000;
        
        if (minutes > 9) {
            // For gaps > 9 minutes, show M:SS
            return String.format("+%d:%02d", minutes, seconds);
        } else if (minutes > 0) {
            // For gaps 1-9 minutes, show M:SS.mmm
            return String.format("+%d:%02d.%03d", minutes, seconds, millis);
        } else if (seconds >= 10) {
            // For gaps 10-59 seconds, show S.mmm
            return String.format("+%d.%03d", seconds, millis);
        } else {
            // For gaps < 10 seconds, show S.mmm
            return String.format("+%d.%03d", seconds, millis);
        }
    }
    
    private static String truncate(String s, int maxLength) {
        if (s == null) return "".repeat(maxLength);
        if (s.length() <= maxLength) {
            return s;
        }
        return s.substring(0, maxLength);
    }
}


