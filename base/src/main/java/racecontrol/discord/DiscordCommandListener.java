package racecontrol.discord;

import net.dv8tion.jda.api.Permission;
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
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

public final class DiscordCommandListener extends ListenerAdapter {

    private final long feedChannelId;

    DiscordCommandListener(long feedChannelId) {
        this.feedChannelId = feedChannelId;
    }

    // ?? Dispatch ??????????????????????????????????????????????????????????????

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        switch (event.getName()) {
            case "standings" -> handleStandings(event);
            case "follow"    -> handleFollow(event);
            case "unfollow"  -> handleUnfollow(event);
            case "following" -> handleFollowing(event);
            case "gap"       -> handleGap(event);
            case "battle"    -> handleBattle(event);
            case "pace"      -> handlePace(event);
            case "pitstops"  -> handlePitstops(event);
            case "iam"       -> handleIam(event);
            case "quiet"     -> handleQuiet(event);
        }
    }

    @Override
    public void onCommandAutoCompleteInteraction(CommandAutoCompleteInteractionEvent event) {
        String cmd     = event.getName();
        String focused = event.getFocusedOption().getName();

        // Autocomplete for any "driver", "driver1", or "driver2" option
        boolean isDriverOption = focused.equals("driver")
                              || focused.equals("driver1")
                              || focused.equals("driver2");
        boolean isDriverCmd    = cmd.equals("follow") || cmd.equals("gap") || cmd.equals("pace")
                              || cmd.equals("iam");
        if (!isDriverOption || !isDriverCmd) return;

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

    // ?? /standings ????????????????????????????????????????????????????????????

    private void handleStandings(SlashCommandInteractionEvent event) {
        Model model = AccBroadcastingClient.getClient().getModel();
        if (!model.gameConnected) {
            event.reply("Not connected to an ACC session.").setEphemeral(true).queue();
            return;
        }

        List<Car> cars = LiveBoardPublisher.dedupedCars(model.getCars());
        SessionMode mode  = mapMode(model.currentSessionId.getType());
        String      track = model.trackInfo != null ? model.trackInfo.getTrackName() : "Unknown";

        List<LeaderboardEntry> rows = buildRows(cars, mode);
        String content = DiscordLeaderboardManager.buildMessage(mode, track, rows);
        if (content.length() > 1990) content = LiveBoardPublisher.truncateLeaderboard(content, 1990);

        event.reply(content).setEphemeral(true).queue();
    }

    // ?? /follow, /unfollow, /following ????????????????????????????????????????

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

    // ?? /gap <driver1> <driver2> ??????????????????????????????????????????????

    private void handleGap(SlashCommandInteractionEvent event) {
        Model model = AccBroadcastingClient.getClient().getModel();
        if (!model.gameConnected) {
            event.reply("Not connected to an ACC session.").setEphemeral(true).queue();
            return;
        }

        var opt1 = event.getOption("driver1");
        var opt2 = event.getOption("driver2");
        if (opt1 == null || opt2 == null) {
            event.reply("Provide two driver names.").setEphemeral(true).queue();
            return;
        }

        List<Car> cars = LiveBoardPublisher.dedupedCars(model.getCars());
        Car c1 = findCar(cars, opt1.getAsString());
        Car c2 = findCar(cars, opt2.getAsString());

        if (c1 == null) {
            event.reply("Driver **" + opt1.getAsString() + "** not found in the current session.")
                 .setEphemeral(true).queue();
            return;
        }
        if (c2 == null) {
            event.reply("Driver **" + opt2.getAsString() + "** not found in the current session.")
                 .setEphemeral(true).queue();
            return;
        }
        if (c1.id == c2.id) {
            event.reply("That's the same driver!").setEphemeral(true).queue();
            return;
        }

        // Determine who is ahead by race position
        Car ahead  = c1.realtimePosition < c2.realtimePosition ? c1 : c2;
        Car behind = c1.realtimePosition < c2.realtimePosition ? c2 : c1;

        // Use gapToLeader difference - works for any pair regardless of adjacency
        int gapMs = Math.abs(behind.gapToLeader - ahead.gapToLeader);

        String gapStr;
        if (gapMs <= 0) {
            gapStr = "< 0.001s";
        } else if (gapMs < 60_000) {
            gapStr = String.format(Locale.US, "%.3fs", gapMs / 1000.0);
        } else {
            gapStr = TimeUtils.asDurationShort(gapMs);
        }

        event.reply(String.format("**%s** (P%d) -> **%s** (P%d): +%s",
                ahead.getDriver().fullName(),  ahead.realtimePosition,
                behind.getDriver().fullName(), behind.realtimePosition,
                gapStr))
             .setEphemeral(true).queue();
    }

    // ?? /battle ???????????????????????????????????????????????????????????????

    private void handleBattle(SlashCommandInteractionEvent event) {
        Model model = AccBroadcastingClient.getClient().getModel();
        if (!model.gameConnected) {
            event.reply("Not connected to an ACC session.").setEphemeral(true).queue();
            return;
        }

        List<Car> cars = LiveBoardPublisher.dedupedCars(model.getCars());
        StringBuilder sb = new StringBuilder("**Battles on track** (gap < 1.0s)\n");
        boolean any = false;

        for (Car car : cars) {
            if (car.realtimePosition <= 1) continue;
            if (car.isInPit()) continue;
            int gap = car.gapPositionAhead;
            if (gap <= 0 || gap == Integer.MAX_VALUE || gap >= 1_000) continue;

            Car ahead = cars.stream()
                    .filter(c -> c.realtimePosition == car.realtimePosition - 1)
                    .findFirst().orElse(null);
            if (ahead == null || ahead.isInPit()) continue;

            sb.append(String.format(Locale.US, "P%d **%s** vs P%d **%s** - %.3fs\n",
                    car.realtimePosition,   car.getDriver().fullName(),
                    ahead.realtimePosition, ahead.getDriver().fullName(),
                    gap / 1000.0));
            any = true;
        }

        if (!any) sb.append("_No close battles right now._");

        event.reply(sb.toString().trim()).setEphemeral(true).queue();
    }

    // ?? /pace <driver> ????????????????????????????????????????????????????????

    private void handlePace(SlashCommandInteractionEvent event) {
        Model model = AccBroadcastingClient.getClient().getModel();
        if (!model.gameConnected) {
            event.reply("Not connected to an ACC session.").setEphemeral(true).queue();
            return;
        }

        var opt = event.getOption("driver");
        if (opt == null) {
            event.reply("Provide a driver name.").setEphemeral(true).queue();
            return;
        }

        List<Car> cars = LiveBoardPublisher.dedupedCars(model.getCars());
        Car car = findCar(cars, opt.getAsString());
        if (car == null) {
            event.reply("Driver **" + opt.getAsString() + "** not found in the current session.")
                 .setEphemeral(true).queue();
            return;
        }

        List<Integer> laps = LapHistoryStore.get(car.id);
        String driverName  = car.getDriver().fullName();

        if (laps.isEmpty()) {
            event.reply("No completed laps recorded for **" + driverName + "** yet.")
                 .setEphemeral(true).queue();
            return;
        }

        StringBuilder sb = new StringBuilder("**" + driverName + "** - last " + laps.size() + " lap(s)\n");
        int total = 0;
        for (int i = 0; i < laps.size(); i++) {
            int ms = laps.get(i);
            total += ms;
            boolean isLatest = (i == laps.size() - 1);
            sb.append(String.format("  Lap %d: %s%s\n", i + 1, TimeUtils.asLapTime(ms),
                    isLatest ? "  <- latest" : ""));
        }
        if (laps.size() > 1) {
            sb.append(String.format("  Average: %s\n", TimeUtils.asLapTime(total / laps.size())));
        }

        event.reply(sb.toString().trim()).setEphemeral(true).queue();
    }

    // ?? /pitstops ?????????????????????????????????????????????????????????????

    private void handlePitstops(SlashCommandInteractionEvent event) {
        Model model = AccBroadcastingClient.getClient().getModel();
        if (!model.gameConnected) {
            event.reply("Not connected to an ACC session.").setEphemeral(true).queue();
            return;
        }

        List<Car> cars = LiveBoardPublisher.dedupedCars(model.getCars()).stream()
                .filter(c -> c.realtimePosition > 0)
                .collect(Collectors.toList());

        if (cars.isEmpty()) {
            event.reply("No cars on track yet.").setEphemeral(true).queue();
            return;
        }

        // "Running long" = still on 0 stops when at least half the field has pitted
        long carsWithStop = cars.stream().filter(c -> c.pitlaneCount > 0).count();
        boolean markLong = carsWithStop > cars.size() / 2;

        StringBuilder sb = new StringBuilder("**?? Pit Stops**\n");
        for (Car car : cars) {
            String pos    = String.format("P%-2d", car.realtimePosition);
            String name   = car.getDriver().fullName();
            int    stops  = car.pitlaneCount;
            boolean inPit = car.isInPit();

            String stopStr = stops == 1 ? "1 stop" : stops + " stops";
            String suffix  = "";
            if (inPit) {
                suffix = " IN PIT";
            } else if (stops == 0 && markLong) {
                suffix = " (!)";
            }

            sb.append(String.format("%s %-20s %s%s\n", pos, name, stopStr, suffix));
        }

        String reply = sb.toString().trim();
        // Wrap in code block for alignment if it fits
        String wrapped = "```\n" + reply + "\n```";
        event.reply(wrapped.length() <= 2000 ? wrapped : reply).setEphemeral(true).queue();
    }

    // ?? /iam <driver> ????????????????????????????????????????????????????????

    private void handleIam(SlashCommandInteractionEvent event) {
        var opt = event.getOption("driver");
        if (opt == null) {
            event.reply("Provide your driver name.").setEphemeral(true).queue();
            return;
        }

        Model model = AccBroadcastingClient.getClient().getModel();
        if (!model.gameConnected) {
            event.reply("No ACC session active yet - try again once the race is loaded.")
                 .setEphemeral(true).queue();
            return;
        }

        List<Car> cars = LiveBoardPublisher.dedupedCars(model.getCars());
        Car car = findCar(cars, opt.getAsString());
        if (car == null) {
            event.reply("Driver **" + opt.getAsString()
                    + "** not found. Type your name as it appears in ACC, "
                    + "or wait until you are in an active session.")
                 .setEphemeral(true).queue();
            return;
        }

        String driverName = car.getDriver().fullName();
        IamStore.claim(event.getUser().getId(), driverName);
        event.reply("Got it! You are **" + driverName
                + "**. You will receive a personal recap DM after each race.")
             .setEphemeral(true).queue();
    }

    // ?? /quiet <mute> ????????????????????????????????????????????????????????

    private void handleQuiet(SlashCommandInteractionEvent event) {
        if (event.getMember() == null
                || !event.getMember().hasPermission(Permission.ADMINISTRATOR)) {
            event.reply("This command requires Administrator permission.")
                 .setEphemeral(true).queue();
            return;
        }

        var opt = event.getOption("mute");
        if (opt == null) {
            event.reply("Provide true (mute) or false (unmute).").setEphemeral(true).queue();
            return;
        }

        boolean mute = opt.getAsBoolean();
        DiscordService discord = DiscordService.get();
        if (discord == null) {
            event.reply("Discord service is not running.").setEphemeral(true).queue();
            return;
        }

        discord.setQuietMode(mute);
        if (mute) {
            event.reply("Feed muted. Use `/quiet mute:false` to resume.").setEphemeral(true).queue();
        } else {
            discord.postFeed("Race Control feed is **active** again.");
            event.reply("Feed unmuted.").setEphemeral(true).queue();
        }
    }

    // ?? Helpers ???????????????????????????????????????????????????????????????

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

    /**
     * Find a car by partial, case-insensitive driver name match.
     * Exact matches are preferred over partial matches.
     */
    private static Car findCar(List<Car> cars, String query) {
        String lower = query.toLowerCase();
        // Prefer exact match
        return cars.stream()
                .filter(c -> c.getDriver().fullName().equalsIgnoreCase(query))
                .findFirst()
                .orElseGet(() -> cars.stream()
                        .filter(c -> c.getDriver().fullName().toLowerCase().contains(lower))
                        .findFirst()
                        .orElse(null));
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
