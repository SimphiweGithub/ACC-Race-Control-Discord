package racecontrol.discord;

import net.dv8tion.jda.api.EmbedBuilder;

import racecontrol.client.AccBroadcastingClient;
import racecontrol.client.events.SessionChangedEvent;
import racecontrol.client.model.Model;
import racecontrol.client.extension.contact.ContactEvent;
import racecontrol.client.extension.contact.ContactInfo;
import racecontrol.client.extension.laptimes.LapCompletedEvent;
import racecontrol.client.extension.vsc.events.VSCEndEvent;
import racecontrol.client.extension.vsc.events.VSCStartEvent;
import racecontrol.client.extension.vsc.events.VSCViolationEvent;
import racecontrol.client.model.Car;
import racecontrol.eventbus.Event;
import racecontrol.eventbus.EventBus;
import racecontrol.eventbus.EventListener;
import racecontrol.utility.TimeUtils;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public final class RaceFeedPublisher implements EventListener {

    private static final Logger LOG = Logger.getLogger(RaceFeedPublisher.class.getName());

    /** Guard against double-registration (Main auto-start + GUI Connect both call register()). */
    private static final java.util.concurrent.atomic.AtomicBoolean REGISTERED =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    private Integer sessionBestMs = null;

    private RaceFeedPublisher() {}

    public static void register() {
        if (!REGISTERED.compareAndSet(false, true)) {
            LOG.warning("RaceFeedPublisher already registered — ignoring duplicate call.");
            return;
        }
        EventBus.register(new RaceFeedPublisher());
        LOG.info("RaceFeedPublisher registered.");
    }

    @Override
    public void onEvent(Event e) {
        DiscordService discord = DiscordService.get();
        if (discord == null) return;

        if (e instanceof ContactEvent ce) {
            handleContact(discord, ce.getInfo());
        } else if (e instanceof VSCStartEvent vs) {
            discord.postFeedEmbed(new EmbedBuilder()
                .setTitle("Virtual Safety Car deployed")
                .setDescription("Speed limit: **" + vs.getSpeedLimit() + " km/h**")
                .setColor(Color.YELLOW)
                .build());
        } else if (e instanceof VSCEndEvent) {
            discord.postFeed("VSC ending — green flag");
        } else if (e instanceof VSCViolationEvent vv) {
            Model model = AccBroadcastingClient.getClient().getModel();
            new ArrayList<>(model.getCars()).stream()
                .filter(c -> c.id == vv.getCarId())
                .findFirst()
                .ifPresentOrElse(
                    car -> discord.postFeed("VSC violation — " + car.getDriver().fullName()),
                    ()  -> discord.postFeed("VSC violation (car id " + vv.getCarId() + ")")
                );
        } else if (e instanceof LapCompletedEvent lc) {
            handleLap(discord, lc);
        } else if (e instanceof SessionChangedEvent sc) {
            handleSessionChange(discord, sc);
        }
    }

    private void handleContact(DiscordService discord, ContactInfo info) {
        List<Car> cars = info.getCars();
        String involved = cars.stream()
            .map(c -> c.getDriver().fullName() + " (P" + c.realtimePosition + ")")
            .collect(Collectors.joining(" vs "));

        EmbedBuilder embed = new EmbedBuilder()
            .setTitle("Contact")
            .setDescription(involved.isEmpty() ? "Incident detected" : involved)
            .setColor(new Color(0xE74C3C));

        discord.postFeedEmbed(embed.build());

        // Ping followers of any driver involved
        for (Car car : cars) {
            String name = car.getDriver().fullName();
            List<String> followers = FollowStore.followersOf(name);
            if (!followers.isEmpty()) {
                String pings = followers.stream().map(id -> "<@" + id + ">").collect(Collectors.joining(" "));
                discord.postFeed(pings + " **" + name + "** was involved in a contact (P" + car.realtimePosition + ")");
            }
        }
    }

    private void handleLap(DiscordService discord, LapCompletedEvent lc) {
        int ms = lc.getLapTime();
        if (ms <= 0 || ms == Integer.MAX_VALUE) return;

        String driverName = lc.getCar().getDriver().fullName();

        // Session-best (fastest lap) alert
        if (sessionBestMs == null || ms < sessionBestMs) {
            sessionBestMs = ms;
            discord.postFeedEmbed(new EmbedBuilder()
                .setTitle("Fastest Lap")
                .setDescription("**" + driverName + "**  " + TimeUtils.asLapTime(ms))
                .setColor(new Color(0x9B59B6))
                .build());
        }

        // Personal-best ping for followers
        // (session best already covers P1 driver; followers get a ping on their driver's PB)
        int pos = lc.getCar().realtimePosition;
        List<String> followers = FollowStore.followersOf(driverName);
        if (!followers.isEmpty()) {
            String pings = followers.stream().map(id -> "<@" + id + ">").collect(Collectors.joining(" "));
            discord.postFeed(pings + " **" + driverName + "** completed a lap: "
                + TimeUtils.asLapTime(ms) + " (P" + pos + ")");
        }
    }

    private void handleSessionChange(DiscordService discord, SessionChangedEvent sc) {
        sessionBestMs = null;
        discord.resetLiveBoard();
        String sessionType = sc.getSessionId().getType() != null
            ? sc.getSessionId().getType().name() : "SESSION";
        discord.postFeed("**" + sessionType + "** is starting");
    }
}
