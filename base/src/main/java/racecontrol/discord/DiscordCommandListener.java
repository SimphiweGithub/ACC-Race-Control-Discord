package racecontrol.discord;

import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.Command;

import racecontrol.client.AccBroadcastingClient;
import racecontrol.client.model.Car;
import racecontrol.client.model.Model;
import racecontrol.client.protocol.enums.SessionType;
import racecontrol.utility.DiscordLeaderboardManager;
import racecontrol.utility.DiscordLeaderboardManager.LeaderboardEntry;
import racecontrol.utility.DiscordLeaderboardManager.SessionMode;
import racecontrol.utility.TimeUtils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

public final class DiscordCommandListener extends ListenerAdapter {

    private final long feedChannelId;

    DiscordCommandListener(long feedChannelId) {
        this.feedChannelId = feedChannelId;
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        switch (event.getName()) {
            case "standings" -> handleStandings(event);
            case "follow"    -> handleFollow(event);
            case "unfollow"  -> handleUnfollow(event);
            case "following" -> handleFollowing(event);
        }
    }

    @Override
    public void onCommandAutoCompleteInteraction(CommandAutoCompleteInteractionEvent event) {
        if (!event.getName().equals("follow")) return;
        if (!event.getFocusedOption().getName().equals("driver")) return;

        String typed = event.getFocusedOption().getValue().toLowerCase();
        Model model = AccBroadcastingClient.getClient().getModel();
        List<Command.Choice> choices = new ArrayList<>(model.getCars()).stream()
            .map(c -> c.getDriver().fullName())
            .filter(n -> !n.isBlank() && n.toLowerCase().contains(typed))
            .distinct()
            .limit(25)
            .map(n -> new Command.Choice(n, n))
            .collect(Collectors.toList());

        event.replyChoices(choices).queue();
    }

    private void handleStandings(SlashCommandInteractionEvent event) {
        Model model = AccBroadcastingClient.getClient().getModel();
        if (!model.gameConnected) {
            event.reply("Not connected to an ACC session.").setEphemeral(true).queue();
            return;
        }

        List<Car> cars = new ArrayList<>(model.getCars());
        cars.sort(Comparator.comparingInt(c -> c.realtimePosition <= 0 ? Integer.MAX_VALUE : c.realtimePosition));

        SessionMode mode = mapMode(model.currentSessionId.getType());
        String track = model.trackInfo != null ? model.trackInfo.getTrackName() : "Unknown";

        List<LeaderboardEntry> rows = buildRows(cars, mode);
        String content = DiscordLeaderboardManager.buildMessage(mode, track, rows);

        // Truncate if needed (Discord ephemeral reply limit is 2000 chars)
        if (content.length() > 1990) {
            content = content.substring(0, 1987) + "...";
        }
        event.reply(content).setEphemeral(true).queue();
    }

    private void handleFollow(SlashCommandInteractionEvent event) {
        var option = event.getOption("driver");
        if (option == null) {
            event.reply("Provide a driver name.").setEphemeral(true).queue();
            return;
        }
        String driver = option.getAsString();
        FollowStore.add(event.getUser().getId(), driver);
        event.reply("You'll be pinged on **" + driver + "**'s race moments in <#" + feedChannelId + ">.")
             .setEphemeral(true).queue();
    }

    private void handleUnfollow(SlashCommandInteractionEvent event) {
        FollowStore.removeAll(event.getUser().getId());
        event.reply("Stopped following all drivers.").setEphemeral(true).queue();
    }

    private void handleFollowing(SlashCommandInteractionEvent event) {
        List<String> drivers = FollowStore.driversFollowedBy(event.getUser().getId());
        if (drivers.isEmpty()) {
            event.reply("You are not following anyone. Use `/follow <driver>` to start.")
                 .setEphemeral(true).queue();
        } else {
            event.reply("You are following: **" + String.join(", ", drivers) + "**")
                 .setEphemeral(true).queue();
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private List<LeaderboardEntry> buildRows(List<Car> cars, SessionMode mode) {
        List<LeaderboardEntry> rows = new ArrayList<>();
        for (Car car : cars) {
            if (car.realtimePosition <= 0) continue;
            Duration last = ms(car.lastLap.getLapTimeMS());
            Duration best = ms(car.bestLap.getLapTimeMS());
            Duration gap  = mode == SessionMode.RACE && car.realtimePosition > 1
                ? ms(car.gapPositionAhead)
                : (car.realtimePosition > 1 ? ms(car.deltaToSessionBest) : null);
            rows.add(new LeaderboardEntry(
                car.realtimePosition,
                car.getDriver().fullName(),
                last, best, gap,
                null, null, null,
                car.lapCount, car.pitlaneCount, false));
        }
        return rows;
    }

    private static Duration ms(int ms) {
        if (ms <= 0 || ms == Integer.MAX_VALUE) return null;
        return Duration.ofMillis(ms);
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
