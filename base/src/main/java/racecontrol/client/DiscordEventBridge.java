/*
 * Copyright (c) 2021 Leonard Sch�ngel
 * 
 * For licensing information see the included license (LICENSE.txt)
 */
package racecontrol.client;

import racecontrol.client.events.ConnectionOpenedEvent;
import racecontrol.client.events.ConnectionClosedEvent;
import racecontrol.client.events.CarConnectedEvent;
import racecontrol.client.events.CarDisconnectedEvent;
import racecontrol.client.events.RealtimeUpdateEvent;
import racecontrol.client.events.SessionChangedEvent;
import racecontrol.client.events.SessionPhaseChangedEvent;
import racecontrol.client.extension.laptimes.LapCompletedEvent;
import racecontrol.eventbus.Event;
import racecontrol.eventbus.EventBus;
import racecontrol.eventbus.EventListener;
import racecontrol.utility.DiscordWebhookNotifier;
import racecontrol.utility.TimeUtils;

import java.util.Optional;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

public class DiscordEventBridge implements EventListener {

    private static final Logger LOG = Logger.getLogger(DiscordEventBridge.class.getName());
    private final Optional<DiscordWebhookNotifier> notifier;
    private final Map<Integer, Integer> bestByCarMs = new HashMap<>();
    private Integer sessionBestMs = null;

    public DiscordEventBridge(Optional<DiscordWebhookNotifier> notifier) {
        this.notifier = notifier;
    }

    public static void registerIfConfigured() {
        LOG.info("Attempting to register Discord event bridge...");
        Optional<DiscordWebhookNotifier> n = DiscordWebhookNotifier.fromEnvOrProperty();
        if (n.isPresent()) {
            EventBus.register(new DiscordEventBridge(n));
            n.get().send("ACC Race Control started. Webhook configured.");
            LOG.info("Discord webhook configured and event bridge registered.");
        } else {
            LOG.info("Discord webhook not configured. Skipping event bridge registration.");
            LOG.info("Checked: DISCORD_WEBHOOK_URL env var, -Ddiscord.webhook.url property, and /config/discord.properties file");
        }
    }

    @Override
    public void onEvent(Event e) {
        if (notifier.isEmpty()) {
            return;
        }
        if (e instanceof ConnectionOpenedEvent) {
            notifier.get().send("ACC connection opened");
        } else if (e instanceof ConnectionClosedEvent) {
            ConnectionClosedEvent c = (ConnectionClosedEvent) e;
            notifier.get().send("ACC connection closed: " + c.getExitState());
        } else if (e instanceof SessionChangedEvent) {
            SessionChangedEvent s = (SessionChangedEvent) e;
            bestByCarMs.clear();
            sessionBestMs = null;
            String msg = "Session changed: " + s.getSessionId().getType().name() + "  " + s.getSessionInfo().getPhase().name();
            notifier.get().send(msg);
        } else if (e instanceof SessionPhaseChangedEvent) {
            SessionPhaseChangedEvent sp = (SessionPhaseChangedEvent) e;
            String msg = "Phase changed: " + sp.getSessionInfo().getPhase().name();
            notifier.get().send(msg);
        } else if (e instanceof LapCompletedEvent) {
            LapCompletedEvent lc = (LapCompletedEvent) e;
            int carId = lc.getCar().id;
            int timeMs = lc.getLapTime();
            String base = "Lap: #" + lc.getCar().carNumber + " " + lc.getCar().getDriver().fullName() + "  " + TimeUtils.asLapTime(timeMs);
            boolean isPB = bestByCarMs.getOrDefault(carId, Integer.MAX_VALUE) > timeMs;
            boolean isSB = (sessionBestMs == null) || timeMs < sessionBestMs;
            if (isPB) {
                bestByCarMs.put(carId, timeMs);
            }
            if (isSB) {
                sessionBestMs = timeMs;
            }
            StringBuilder sb = new StringBuilder(base);
            if (isPB) sb.append(" [PB]");
            if (isSB) sb.append(" [SB]");
            notifier.get().send(sb.toString());
        } else if (e instanceof CarConnectedEvent) {
            CarConnectedEvent c = (CarConnectedEvent) e;
            notifier.get().send("Car connected: #" + c.getCar().carNumber + " - " + c.getCar().getDriver().fullName());
        } else if (e instanceof CarDisconnectedEvent) {
            CarDisconnectedEvent c = (CarDisconnectedEvent) e;
            notifier.get().send("Car disconnected: #" + c.getCar().carNumber + " - " + c.getCar().getDriver().fullName());
        } else if (e instanceof RealtimeUpdateEvent) {
            // keep it light; don't spam on every tick
        } else {
            // ignore other high-frequency events to avoid console spam
        }
    }
}


