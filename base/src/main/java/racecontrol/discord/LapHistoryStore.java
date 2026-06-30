/*
 * Copyright (c) 2021 Leonard Schüngel
 *
 * For licensing information see the included license (LICENSE.txt)
 */
package racecontrol.discord;

import racecontrol.client.events.SessionChangedEvent;
import racecontrol.client.extension.laptimes.LapCompletedEvent;
import racecontrol.eventbus.Event;
import racecontrol.eventbus.EventBus;
import racecontrol.eventbus.EventListener;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/**
 * Stores the last 3 completed lap times per car for the current session.
 * Used by the /pace slash command.
 * Clears on session change.
 */
public final class LapHistoryStore implements EventListener {

    private static final Logger LOG = Logger.getLogger(LapHistoryStore.class.getName());
    private static final AtomicBoolean REGISTERED = new AtomicBoolean(false);
    private static final int MAX_LAPS = 3;

    /** carId → last MAX_LAPS completed lap times in ms, oldest first. */
    private static final ConcurrentHashMap<Integer, Deque<Integer>> history =
            new ConcurrentHashMap<>();

    private LapHistoryStore() {}

    public static void register() {
        if (!REGISTERED.compareAndSet(false, true)) return;
        EventBus.register(new LapHistoryStore());
        LOG.info("LapHistoryStore registered.");
    }

    @Override
    public void onEvent(Event e) {
        if (e instanceof LapCompletedEvent lc) {
            int ms = lc.getLapTime();
            if (ms <= 0 || ms == Integer.MAX_VALUE) return;
            int carId = lc.getCar().id;
            history.compute(carId, (id, q) -> {
                if (q == null) q = new ArrayDeque<>(MAX_LAPS);
                if (q.size() >= MAX_LAPS) q.pollFirst();
                q.addLast(ms);
                return q;
            });
        } else if (e instanceof SessionChangedEvent) {
            history.clear();
        }
    }

    /**
     * Returns a copy of the last (up to 3) lap times for the given car, oldest first.
     * Returns an empty list if no laps have been recorded yet.
     */
    public static List<Integer> get(int carId) {
        Deque<Integer> q = history.get(carId);
        if (q == null) return Collections.emptyList();
        return new ArrayList<>(q);
    }
}
