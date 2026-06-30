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
import racecontrol.client.protocol.enums.SessionType;
import racecontrol.eventbus.Event;
import racecontrol.eventbus.EventBus;
import racecontrol.eventbus.EventListener;
import racecontrol.utility.TimeUtils;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public final class RaceFeedPublisher implements EventListener {

    private static final Logger LOG = Logger.getLogger(RaceFeedPublisher.class.getName());

    /** Guard against double-registration (Main auto-start + GUI Connect both call register()). */
    private static final java.util.concurrent.atomic.AtomicBoolean REGISTERED =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /** Minimum gap between personal-best DM pings for the same driver (3 minutes). */
    private static final long PB_PING_COOLDOWN_MS = 3 * 60 * 1_000L;

    private Integer sessionBestMs     = null;
    /** Full name of the driver who currently holds the session fastest lap. */
    private String  sessionBestDriver = null;
    /** carId -> personal best lap time ms for this session. */
    private final Map<Integer, Integer> personalBestMs = new HashMap<>();
    /**
     * carId -> race-start (grid) position, snapshotted when a RACE session begins.
     * Used at race end to compute biggest-mover for the recap.
     */
    private final Map<Integer, Integer> raceStartPositions = new HashMap<>();
    /** carId -> last time (epoch ms) a PB ping DM was sent for that driver. */
    private final Map<Integer, Long> pbPingLastFire = new HashMap<>();
    /**
     * Last session key we announced — prevents double-fire when ACC emits
     * SessionChangedEvent more than once for the same session transition.
     */
    private String      lastAnnouncedSessionKey = "";
    /** Type of the previous session — used to detect when a race finishes. */
    private SessionType lastSessionType         = null;

    // Contact coalescing — merges all ContactEvents within a 3-second window
    // into one embed so T1 pile-ups don't flood the feed.
    private final ScheduledExecutorService contactFlusher =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "discord-contact-flush");
                t.setDaemon(true);
                return t;
            });
    private final List<ContactInfo> pendingContacts  = new ArrayList<>();
    private final Object            contactLock      = new Object();
    private       boolean           contactFlushScheduled = false;

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
        // Buffer the event; the flush fires 3 seconds later and posts ONE coalesced
        // embed for the whole incident window (handles T1 pile-ups without flooding).
        synchronized (contactLock) {
            pendingContacts.add(info);
            if (!contactFlushScheduled) {
                contactFlushScheduled = true;
                contactFlusher.schedule(this::flushContacts, 3, TimeUnit.SECONDS);
            }
        }
    }

    /**
     * Posts a single contact embed for all buffered ContactEvents.
     * Cars are deduplicated by id so a car appearing in multiple events
     * is listed only once.
     */
    private void flushContacts() {
        List<ContactInfo> snapshot;
        synchronized (contactLock) {
            if (pendingContacts.isEmpty()) {
                contactFlushScheduled = false;
                return;
            }
            snapshot = new ArrayList<>(pendingContacts);
            pendingContacts.clear();
            contactFlushScheduled = false;
        }

        DiscordService discord = DiscordService.get();
        if (discord == null) return;

        // Merge all cars, deduplicating by car id (insertion order kept).
        Map<Integer, Car> involvedMap = new LinkedHashMap<>();
        for (ContactInfo ci : snapshot) {
            for (Car c : ci.getCars()) {
                involvedMap.putIfAbsent(c.id, c);
            }
        }

        List<Car> involved = new ArrayList<>(involvedMap.values());
        String description = involved.isEmpty()
                ? "Incident detected"
                : involved.stream()
                          .map(c -> c.getDriver().fullName() + " (P" + c.realtimePosition + ")")
                          .collect(Collectors.joining(" | "));

        EmbedBuilder embed = new EmbedBuilder()
                .setTitle(involved.size() > 2 ? "Multi-car contact" : "Contact")
                .setDescription(description)
                .setColor(new Color(0xE74C3C));
        discord.postFeedEmbed(embed.build());

        // DM followers for each car involved.
        for (Car car : involved) {
            String name = car.getDriver().fullName();
            String msg  = "**" + name + "** was involved in a contact (P" + car.realtimePosition + ")";
            for (String userId : FollowStore.followersOf(name)) {
                discord.dmUser(userId, msg);
            }
        }
    }

    private void handleLap(DiscordService discord, LapCompletedEvent lc) {
        int ms = lc.getLapTime();
        if (ms <= 0 || ms == Integer.MAX_VALUE) return;

        Car car = lc.getCar();
        String driverName = car.getDriver().fullName();

        // Session-best (fastest lap) alert.
        // Suppress the embed on the very first recorded lap — no prior reference
        // exists, so formation laps and out-laps would fire a spurious announcement.
        // Every genuine improvement after the baseline is set is worth posting.
        if (sessionBestMs == null || ms < sessionBestMs) {
            boolean firstLap  = (sessionBestMs == null);
            sessionBestMs     = ms;
            sessionBestDriver = driverName;
            if (!firstLap) {
                discord.postFeedEmbed(new EmbedBuilder()
                    .setTitle("Fastest Lap")
                    .setDescription("**" + driverName + "**  " + TimeUtils.asLapTime(ms))
                    .setColor(new Color(0x9B59B6))
                    .build());
            }
        }

        // DM followers when the driver sets a new personal best.
        // Skips the very first lap (no previous time to beat yet).
        // Rate-limited to once per PB_PING_COOLDOWN_MS per driver to avoid qualifying spam.
        Integer prevBest = personalBestMs.get(car.id);
        if (prevBest == null || ms < prevBest) {
            personalBestMs.put(car.id, ms);
            if (prevBest != null) {
                long now = System.currentTimeMillis();
                Long lastFire = pbPingLastFire.get(car.id);
                if (lastFire == null || (now - lastFire) >= PB_PING_COOLDOWN_MS) {
                    pbPingLastFire.put(car.id, now);
                    String msg = "**" + driverName + "** new personal best: "
                            + TimeUtils.asLapTime(ms) + " (P" + car.realtimePosition + ")";
                    for (String userId : FollowStore.followersOf(driverName)) {
                        discord.dmUser(userId, msg);
                    }
                }
            }
        }
    }

    private void handleSessionChange(DiscordService discord, SessionChangedEvent sc) {
        // ACC sometimes fires SessionChangedEvent twice for the same transition.
        // Guard against duplicate announcements by tracking the last session key.
        var sid = sc.getSessionId();
        String key = sid.getType() + "_" + sid.getIndex();
        if (key.equals(lastAnnouncedSessionKey)) return;
        lastAnnouncedSessionKey = key;

        // Post recap before wiping state - positions and lap data are still valid here.
        if (lastSessionType == SessionType.RACE) {
            postRaceRecap(discord);
        }
        lastSessionType = sid.getType();

        // If entering a race, snapshot grid positions now for the end-of-race recap.
        if (sid.getType() == SessionType.RACE) {
            snapshotStartPositions();
        } else {
            raceStartPositions.clear();
        }

        sessionBestMs     = null;
        sessionBestDriver = null;
        personalBestMs.clear();
        pbPingLastFire.clear();
        synchronized (contactLock) {
            pendingContacts.clear();
            // If a flush is already queued it will fire, find nothing, and reset itself.
        }
        discord.resetLiveBoard();
        String sessionType = sid.getType() != null
            ? sid.getType().name() : "SESSION";
        discord.postFeed("**" + sessionType + "** is starting");
    }

    private void snapshotStartPositions() {
        raceStartPositions.clear();
        try {
            for (Car car : AccBroadcastingClient.getClient().getModel().getCars()) {
                if (car.realtimePosition > 0) {
                    raceStartPositions.put(car.id, car.realtimePosition);
                }
            }
            LOG.fine("Snapshotted " + raceStartPositions.size() + " grid positions for recap.");
        } catch (Exception ignored) {}
    }

    private void postRaceRecap(DiscordService discord) {
        Model model = AccBroadcastingClient.getClient().getModel();
        List<Car> cars = new ArrayList<>(model.getCars());

        // Podium
        List<Car> podium = cars.stream()
            .filter(c -> c.realtimePosition >= 1 && c.realtimePosition <= 3)
            .sorted(Comparator.comparingInt(c -> c.realtimePosition))
            .collect(Collectors.toList());
        if (podium.isEmpty()) return;

        StringBuilder podiumSb = new StringBuilder();
        String[] medals = {"P1", "P2", "P3"};
        for (Car car : podium) {
            podiumSb.append(medals[car.realtimePosition - 1])
                    .append("  **").append(car.getDriver().fullName()).append("**\n");
        }

        EmbedBuilder embed = new EmbedBuilder()
            .setTitle("Race Recap")
            .setDescription(podiumSb.toString().trim())
            .setColor(new Color(0xF1C40F));

        // Fastest lap
        if (sessionBestDriver != null && sessionBestMs != null) {
            embed.addField("Fastest Lap",
                    "**" + sessionBestDriver + "**\n" + TimeUtils.asLapTime(sessionBestMs),
                    true);
        }

        // Biggest mover (grid position vs. finish position)
        Car biggestMover = null;
        int maxGain = 0;
        for (Car car : cars) {
            Integer start = raceStartPositions.get(car.id);
            if (start == null || car.realtimePosition <= 0) continue;
            int gain = start - car.realtimePosition;
            if (gain > maxGain) { maxGain = gain; biggestMover = car; }
        }
        if (biggestMover != null && maxGain > 0) {
            Integer start = raceStartPositions.get(biggestMover.id);
            embed.addField("Biggest Mover",
                    "**" + biggestMover.getDriver().fullName() + "**\n"
                    + "P" + start + " -> P" + biggestMover.realtimePosition
                    + " (+" + maxGain + ")",
                    true);
        }

        // Most overtakes (from RaceAlertPublisher's detection)
        Map<Integer, Integer> overtakeCounts = RaceAlertPublisher.getOvertakeCounts();
        Car topOvertaker = null;
        int maxOvertakes = 0;
        for (Map.Entry<Integer, Integer> e : overtakeCounts.entrySet()) {
            if (e.getValue() > maxOvertakes) {
                maxOvertakes = e.getValue();
                int carId = e.getKey();
                topOvertaker = cars.stream()
                        .filter(c -> c.id == carId).findFirst().orElse(null);
            }
        }
        if (topOvertaker != null && maxOvertakes > 0) {
            embed.addField("Most Overtakes",
                    "**" + topOvertaker.getDriver().fullName() + "**\n"
                    + maxOvertakes + (maxOvertakes == 1 ? " pass" : " passes"),
                    true);
        }

        discord.postFeedEmbed(embed.build());

        // Personal DMs to drivers who have claimed themselves via /iam
        final Car finalBiggestMover = biggestMover;
        final int finalMaxGain = maxGain;
        final Map<Integer, Integer> overtakeSnapshot = new HashMap<>(overtakeCounts);
        for (Car car : cars) {
            if (car.realtimePosition <= 0) continue;
            String userId = IamStore.userOf(car.getDriver().fullName());
            if (userId == null) continue;

            StringBuilder dm = new StringBuilder();
            dm.append("Race over! You finished **P").append(car.realtimePosition).append("**");
            if (finalBiggestMover != null && finalBiggestMover.id == car.id) {
                Integer start = raceStartPositions.get(car.id);
                dm.append(" - biggest mover of the race (from P").append(start).append(")!");
            }
            if (sessionBestDriver != null && sessionBestDriver.equals(car.getDriver().fullName())) {
                dm.append(" You set the fastest lap!");
            }
            int myOvertakes = overtakeSnapshot.getOrDefault(car.id, 0);
            if (myOvertakes > 0) {
                dm.append(" You made ").append(myOvertakes)
                  .append(myOvertakes == 1 ? " overtake." : " overtakes.");
            } else {
                dm.append(".");
            }
            discord.dmUser(userId, dm.toString());
        }
    }
}
