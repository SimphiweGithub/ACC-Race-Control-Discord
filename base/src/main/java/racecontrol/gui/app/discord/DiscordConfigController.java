package racecontrol.gui.app.discord;

import racecontrol.discord.DiscordService;
import racecontrol.discord.LapHistoryStore;
import racecontrol.discord.LiveBoardPublisher;
import racecontrol.discord.RaceAlertPublisher;
import racecontrol.discord.RaceFeedPublisher;
import racecontrol.gui.RaceControlApplet;
import racecontrol.gui.app.Menu.MenuItem;
import racecontrol.gui.app.PageController;
import racecontrol.gui.lpui.LPContainer;
import racecontrol.persistance.PersistantConfig;
import static racecontrol.persistance.PersistantConfigKeys.*;

import java.util.logging.Level;
import java.util.logging.Logger;

public class DiscordConfigController implements PageController {

    private static final Logger LOG = Logger.getLogger(DiscordConfigController.class.getName());

    private final DiscordConfigPanel panel = new DiscordConfigPanel();
    private final MenuItem menuItem = new MenuItem("Discord", null);

    public DiscordConfigController() {
        panel.connectButton.setAction(this::onConnectClicked);

        // Reflect current state if the bot was already started at app launch
        if (DiscordService.isRunning()) {
            panel.setStatus("Connected", true);
        }
    }

    @Override
    public LPContainer getPanel() {
        return panel;
    }

    @Override
    public MenuItem getMenuItem() {
        return menuItem;
    }

    private void onConnectClicked() {
        if (DiscordService.isRunning()) {
            DiscordService.stop();
            panel.setStatus("Disconnected", false);
            return;
        }

        String token      = panel.tokenField.getValue().trim();
        String guildStr   = panel.guildField.getValue().trim();
        String channelStr = panel.channelField.getValue().trim();
        String boardStr   = panel.boardField.getValue().trim();  // optional

        if (token.isEmpty() || guildStr.isEmpty() || channelStr.isEmpty()) {
            panel.setStatus("Fill in Bot Token, Server ID and Feed Channel ID.", false);
            return;
        }

        long guildId, channelId;
        try {
            guildId   = Long.parseLong(guildStr);
            channelId = Long.parseLong(channelStr);
        } catch (NumberFormatException ex) {
            panel.setStatus("Server ID and Channel ID must be numbers.", false);
            return;
        }

        long boardChannelId = 0;
        if (!boardStr.isEmpty()) {
            try {
                boardChannelId = Long.parseLong(boardStr);
            } catch (NumberFormatException ex) {
                panel.setStatus("Board Channel ID must be a number (or leave it blank).", false);
                return;
            }
        }

        panel.setStatus("Connecting...", false);

        // Save to PersistantConfig so values are restored on next launch
        PersistantConfig.put(DISCORD_BOT_TOKEN,        token);
        PersistantConfig.put(DISCORD_GUILD_ID,         guildStr);
        PersistantConfig.put(DISCORD_CHANNEL_ID,       channelStr);
        PersistantConfig.put(DISCORD_BOARD_CHANNEL_ID, boardStr);

        final long finalBoardChannelId = boardChannelId;
        // Connect on a background thread so the UI doesn't freeze during JDA awaitReady()
        Thread t = new Thread(() -> {
            try {
                DiscordService.start(token, guildId, channelId, finalBoardChannelId);
                RaceFeedPublisher.register();
                LapHistoryStore.register();
                LiveBoardPublisher.start();
                RaceAlertPublisher.start();
                RaceControlApplet.runLater(() -> panel.setStatus("Connected", true));
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                RaceControlApplet.runLater(() -> panel.setStatus("Connection interrupted.", false));
            } catch (Exception ex) {
                LOG.log(Level.WARNING, "Discord connection failed", ex);
                RaceControlApplet.runLater(() -> panel.setStatus("Connection failed: " + ex.getMessage(), false));
            }
        }, "discord-connect");
        t.setDaemon(true);
        t.start();
    }
}
