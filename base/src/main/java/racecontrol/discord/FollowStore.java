package racecontrol.discord;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Persists which Discord users are following which drivers.
 * Keys: Discord user ID (String) -> list of driver full names.
 * Keyed on driver name since the ACC broadcasting API exposes no stable GUID.
 */
public final class FollowStore {

    private static final Logger LOG = Logger.getLogger(FollowStore.class.getName());
    private static final String STORE_FILE = "DiscordFollows.json";
    private static final ObjectMapper JSON = new ObjectMapper();

    // discordUserId -> list of driver full names being followed
    private static Map<String, List<String>> store = new HashMap<>();

    static {
        load();
    }

    private FollowStore() {}

    public static synchronized void add(String discordUserId, String driverName) {
        store.computeIfAbsent(discordUserId, k -> new ArrayList<>()).remove(driverName);
        store.get(discordUserId).add(driverName);
        save();
    }

    public static synchronized void removeAll(String discordUserId) {
        store.remove(discordUserId);
        save();
    }

    /** Returns the driver names the given Discord user is following. */
    public static synchronized List<String> driversFollowedBy(String discordUserId) {
        return Collections.unmodifiableList(
            store.getOrDefault(discordUserId, Collections.emptyList()));
    }

    /** Returns all Discord user IDs following the given driver name. */
    public static synchronized List<String> followersOf(String driverName) {
        List<String> followers = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : store.entrySet()) {
            if (entry.getValue().contains(driverName)) {
                followers.add(entry.getKey());
            }
        }
        return followers;
    }

    private static void load() {
        File file = new File(System.getProperty("user.dir"), STORE_FILE);
        if (!file.exists()) return;
        try {
            store = JSON.readValue(file, new TypeReference<Map<String, List<String>>>() {});
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Could not load DiscordFollows.json — starting fresh", e);
            store = new HashMap<>();
        }
    }

    private static void save() {
        File file = new File(System.getProperty("user.dir"), STORE_FILE);
        try {
            JSON.writerWithDefaultPrettyPrinter().writeValue(file, store);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Could not save DiscordFollows.json", e);
        }
    }
}
