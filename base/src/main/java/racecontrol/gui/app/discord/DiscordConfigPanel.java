package racecontrol.gui.app.discord;

import processing.core.PApplet;
import static racecontrol.gui.LookAndFeel.COLOR_DARK_GRAY;
import static racecontrol.gui.LookAndFeel.LINE_HEIGHT;
import racecontrol.gui.lpui.LPButton;
import racecontrol.gui.lpui.LPContainer;
import racecontrol.gui.lpui.LPLabel;
import racecontrol.gui.lpui.LPTextField;
import racecontrol.persistance.PersistantConfig;
import static racecontrol.persistance.PersistantConfigKeys.*;

public class DiscordConfigPanel extends LPContainer {

    private final LPLabel tokenLabel      = new LPLabel("Bot Token:");
    final LPTextField tokenField          = new LPTextField();

    private final LPLabel guildLabel      = new LPLabel("Server (Guild) ID:");
    final LPTextField guildField          = new LPTextField();

    private final LPLabel channelLabel    = new LPLabel("Channel ID:");
    final LPTextField channelField        = new LPTextField();

    private final LPLabel statusLabel     = new LPLabel("");

    final LPButton connectButton          = new LPButton("Connect");

    public DiscordConfigPanel() {
        setName("Discord");

        tokenLabel.setPosition(20, LINE_HEIGHT * 0);
        tokenLabel.setSize(200, LINE_HEIGHT);
        addComponent(tokenLabel);
        tokenField.setPosition(20, LINE_HEIGHT * 1);
        tokenField.setValue(PersistantConfig.get(DISCORD_BOT_TOKEN));
        addComponent(tokenField);

        guildLabel.setPosition(20, LINE_HEIGHT * 2);
        guildLabel.setSize(200, LINE_HEIGHT);
        addComponent(guildLabel);
        guildField.setPosition(20, LINE_HEIGHT * 3);
        guildField.setValue(PersistantConfig.get(DISCORD_GUILD_ID));
        addComponent(guildField);

        channelLabel.setPosition(20, LINE_HEIGHT * 4);
        channelLabel.setSize(200, LINE_HEIGHT);
        addComponent(channelLabel);
        channelField.setPosition(20, LINE_HEIGHT * 5);
        channelField.setValue(PersistantConfig.get(DISCORD_CHANNEL_ID));
        addComponent(channelField);

        connectButton.setPosition(20, LINE_HEIGHT * 7);
        connectButton.setSize(200, LINE_HEIGHT);
        addComponent(connectButton);

        statusLabel.setPosition(20, LINE_HEIGHT * 8);
        statusLabel.setSize(600, LINE_HEIGHT);
        addComponent(statusLabel);

        setSize(660, LINE_HEIGHT * 10);
    }

    @Override
    public void onResize(float w, float h) {
        tokenField.setSize(w - 40, LINE_HEIGHT);
        guildField.setSize(w - 40, LINE_HEIGHT);
        channelField.setSize(w - 40, LINE_HEIGHT);
    }

    @Override
    public void draw(PApplet applet) {
        applet.fill(COLOR_DARK_GRAY);
        applet.rect(0, 0, getWidth(), getHeight());
    }

    public void setStatus(String message, boolean connected) {
        statusLabel.setText(message);
        connectButton.setText(connected ? "Disconnect" : "Connect");
        setInputEnabled(!connected);
        invalidate();
    }

    private void setInputEnabled(boolean enabled) {
        tokenField.setEnabled(enabled);
        guildField.setEnabled(enabled);
        channelField.setEnabled(enabled);
    }
}
