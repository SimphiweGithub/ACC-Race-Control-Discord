/*
 * Copyright (c) 2021 Leonard Sch�ngel
 *
 * For licensing information see the included license (LICENSE.txt)
 */
package racecontrol;

import racecontrol.utility.Version;
import racecontrol.gui.RaceControlApplet;
import racecontrol.discord.DiscordService;
import racecontrol.discord.LapHistoryStore;
import racecontrol.discord.LiveBoardPublisher;
import racecontrol.discord.RaceAlertPublisher;
import racecontrol.discord.RaceFeedPublisher;
import racecontrol.persistance.PersistantConfig;
import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;
import processing.core.PApplet;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.SplashScreen;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.ServiceLoader;
import java.util.concurrent.TimeUnit;
import javax.swing.UIManager;
import javax.swing.UnsupportedLookAndFeelException;
import racecontrol.appextension.AppExtensionModule;
// DiscordRotatingPublisher and DiscordF1Ticker removed in favor of webhook-based extension

/**
 *
 * @author Leonard
 */
public class Main {

    /**
     * This classes logger.
     */
    private static final Logger LOG = Logger.getLogger(Main.class.getName());

    private static final List<AppExtensionModule> extensionModules = new ArrayList<>();

    public static void main(String[] args) throws InterruptedException {
        Thread.setDefaultUncaughtExceptionHandler(new UncoughtExceptionHandler());
        setupLogging();
        LOG.info("Version: " + Version.VERSION);
        PersistantConfig.init();

        // Discord webhook is configured via the settings panel and PersistantConfig now

        loadModules();

        setupSplash();
        TimeUnit.SECONDS.sleep(2);

        // Auto-start Discord bot if credentials were saved in a previous session
        startDiscordIfConfigured();

        //Set system look and feel.
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (ClassNotFoundException
                | InstantiationException
                | IllegalAccessException
                | UnsupportedLookAndFeelException ex) {
            LOG.log(Level.WARNING, "Error setting system look and feel.", ex);
        }

        //start visualisation.
        String[] a = {"MAIN"};
        PApplet.runSketch(a, RaceControlApplet.getApplet());
    }

    private static void setupSplash() {
        SplashScreen splash = SplashScreen.getSplashScreen();
        if (splash != null) {
            Graphics2D g = splash.createGraphics();
            g.setComposite(AlphaComposite.Clear);
            g.fillRect(120, 140, 200, 40);
            g.setPaintMode();
            g.setColor(Color.WHITE);
            g.drawString("Created by Leonard Sch�ngel", 10, 330);
            g.drawString("Version: " + Version.VERSION, 500, 330);
            splash.update();
        }
    }

    private static void setupLogging() {
        //set logging file.
        LogManager logManager = LogManager.getLogManager();
        try {
            SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss");
            String logPath = System.getProperty("user.dir") + "/log/" + dateFormat.format(new Date()) + ".log";
            Properties prop = new Properties();
            prop.load(Main.class.getResourceAsStream("/logging.properties"));
            prop.put("java.util.logging.FileHandler.pattern", logPath);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            prop.store(out, "");
            logManager.readConfiguration(new ByteArrayInputStream(out.toByteArray()));
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "An error happened while setting up the logger.", e);
        }
    }

    public static class UncoughtExceptionHandler
            implements Thread.UncaughtExceptionHandler {

        @Override
        public void uncaughtException(Thread t, Throwable e) {
            LOG.log(Level.SEVERE, "Uncought exception:", e);
        }

    }

    private static void startDiscordIfConfigured() {
        String token   = PersistantConfig.get(racecontrol.persistance.PersistantConfigKeys.DISCORD_BOT_TOKEN);
        String guildStr = PersistantConfig.get(racecontrol.persistance.PersistantConfigKeys.DISCORD_GUILD_ID);
        String chanStr  = PersistantConfig.get(racecontrol.persistance.PersistantConfigKeys.DISCORD_CHANNEL_ID);
        if (token.isBlank() || guildStr.isBlank() || chanStr.isBlank()) return;
        try {
            long guildId   = Long.parseLong(guildStr);
            long channelId = Long.parseLong(chanStr);
            DiscordService.start(token, guildId, channelId, 0);
            RaceFeedPublisher.register();
            LapHistoryStore.register();
            LiveBoardPublisher.start();
            RaceAlertPublisher.start();
            LOG.info("Discord bot started from saved config.");
        } catch (Exception e) {
            LOG.log(java.util.logging.Level.WARNING, "Discord auto-start failed", e);
        }
    }

    private static void configureDiscordFromProperties() {
        String userDir = System.getProperty("user.dir");
        File propertiesFile = new File(userDir + File.separator + "discord.properties");
        LOG.info("Discord properties file path: " + propertiesFile.getAbsolutePath());

        // Default: clear property so env vars are ignored unless file is present
        System.clearProperty("discord.webhook.url");

        if (!propertiesFile.exists()) {
            LOG.info("No Discord properties found at " + propertiesFile.getAbsolutePath() + ". Discord integration disabled.");
            return;
        }

        Properties props = new Properties();
        try (FileInputStream in = new FileInputStream(propertiesFile)) {
            props.load(new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Failed to read Discord properties. Discord integration disabled.", e);
            return;
        }

        String webhookUrl = props.getProperty("discord.webhook.url", "").trim();
        if (webhookUrl.isEmpty()) {
            LOG.info("Property 'discord.webhook.url' missing or empty in " + propertiesFile.getAbsolutePath() + ". Discord integration disabled.");
            return;
        }

        System.setProperty("discord.webhook.url", webhookUrl);
        LOG.info("Discord webhook configured from properties file.");
    }

    private static void loadModules() {
        ServiceLoader.load(AppExtensionModule.class).forEach(module -> {
            LOG.info("Loading extension " + module.getName());
            extensionModules.add(module);
        });
    }

    public static List<AppExtensionModule> getModules() {
        return Collections.unmodifiableList(extensionModules);
    }

}
