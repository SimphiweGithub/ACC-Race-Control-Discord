package racecontrol.discord;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Persists Discord user -> ACC driver name claims between sessions.
 *
 * Backed by {workingDir}/data/iam.json so claims survive app restarts.
 * One claim per user, one user per driver (claiming overwrites any prior entry).
 *
 * Lookup uses the canonical fullName() stored at claim time, so
 * IamStore.userOf(car.getDriver().fullName()) is always an exact match.
 *
 * All public methods are thread-safe.
 */
public final class IamStore {

    private static final Logger LOG = Logger.getLogger(IamStore.class.getName());
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final File FILE = new File(
            System.getProperty("user.dir"), "data/iam.json");

    /** userId -> canonical ACC driver fullName(). */
    private static final Map<String, String> userToDriver = new LinkedHashMap<>();
    /** Canonical driver name (lower-case) -> userId for fast reverse lookup. */
    private static final Map<String, String> driverKeyToUser = new LinkedHashMap<>();

    static { load(); }

    private IamStore() {}

    /**
     * Register a Discord user as a specific ACC driver.
     * Overwrites any existing claim for this user or this driver name.
     */
    public static synchronized void claim(String userId, String driverName) {
        // Remove any stale mapping for this user
        String oldDriver = userToDriver.remove(userId);
        if (oldDriver != null) driverKeyToUser.remove(oldDriver.toLowerCase());
        // Remove any stale mapping for this driver (one user per driver)
        String oldUser = driverKeyToUser.remove(driverName.toLowerCase());
        if (oldUser != null) userToDriver.remove(oldUser);

        userToDriver.put(userId, driverName);
        driverKeyToUser.put(driverName.toLowerCase(), userId);
        save();
        LOG.info("IamStore: " + userId + " claimed driver '" + driverName + "'");
    }

    /** Returns the canonical driver name claimed by this Discord user, or null. */
    public static synchronized String driverOf(String userId) {
        return userToDriver.get(userId);
    }

    /**
     * Returns the Discord userId for a driver name (exact, case-insensitive).
     * Pass car.getDriver().fullName() for a reliable hit when the claim was
     * made against the same canonical name.
     */
    public static synchronized String userOf(String driverName) {
        return driverKeyToUser.get(driverName == null ? null : driverName.toLowerCase());
    }

    /** Snapshot of all current claims (userId -> driverName). */
    public static synchronized Map<String, String> all() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(userToDriver));
    }

    private static synchronized void load() {
        if (!FILE.exists()) return;
        try {
            Map<String, String> loaded = MAPPER.readValue(FILE,
                    new TypeReference<Map<String, String>>() {});
            loaded.forEach((userId, driver) -> {
                userToDriver.put(userId, driver);
                driverKeyToUser.put(driver.toLowerCase(), userId);
            });
            LOG.info("IamStore: loaded " + loaded.size() + " driver claim(s) from "
                    + FILE.getAbsolutePath());
        } catch (IOException e) {
            LOG.log(Level.WARNING, "IamStore: could not read " + FILE.getAbsolutePath(), e);
        }
    }

    private static synchronized void save() {
        try {
            FILE.getParentFile().mkdirs();
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(FILE, userToDriver);
        } catch (IOException e) {
            LOG.log(Level.WARNING, "IamStore: could not write " + FILE.getAbsolutePath(), e);
        }
    }
}
