package racecontrol.discord;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.requests.GatewayIntent;

import java.util.EnumSet;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class DiscordService {

    private static final Logger LOG = Logger.getLogger(DiscordService.class.getName());
    private static volatile DiscordService instance;

    private final JDA jda;
    private final long feedChannelId;
    private volatile String liveBoardMessageId;

    private DiscordService(JDA jda, long feedChannelId) {
        this.jda = jda;
        this.feedChannelId = feedChannelId;
    }

    public static synchronized DiscordService start(String token, long guildId, long feedChannelId)
            throws InterruptedException {
        if (instance != null) {
            LOG.info("DiscordService already running");
            return instance;
        }
        LOG.info("Starting Discord bot...");
        JDA jda = JDABuilder.createLight(token, EnumSet.noneOf(GatewayIntent.class))
                .setActivity(Activity.watching("the race"))
                .build()
                .awaitReady();

        // Guild commands appear instantly; global commands take ~1 hour to propagate
        var guild = jda.getGuildById(guildId);
        if (guild == null) {
            LOG.warning("Guild " + guildId + " not found — is the bot invited to the server?");
        } else {
            guild.updateCommands().addCommands(
                Commands.slash("standings", "Show the current order"),
                Commands.slash("follow", "Get pinged on a driver's race moments")
                        .addOption(OptionType.STRING, "driver", "Driver name", true, true),
                Commands.slash("unfollow", "Stop following all drivers"),
                Commands.slash("following", "See who you are currently following"),
                Commands.slash("gap", "Gap between any two drivers right now")
                        .addOption(OptionType.STRING, "driver1", "First driver",  true, true)
                        .addOption(OptionType.STRING, "driver2", "Second driver", true, true),
                Commands.slash("battle", "List all battles on track (gap < 1 s)"),
                Commands.slash("pace", "Last 3 lap times for a driver")
                        .addOption(OptionType.STRING, "driver", "Driver name", true, true),
                Commands.slash("pitstops", "Pit stop summary for the field")
            ).queue();
        }

        jda.addEventListener(new DiscordCommandListener(feedChannelId));

        instance = new DiscordService(jda, feedChannelId);
        LOG.info("Discord bot connected and ready.");
        return instance;
    }

    public static synchronized void stop() {
        if (instance != null) {
            instance.jda.shutdown();
            instance = null;
            LOG.info("Discord bot shut down.");
        }
    }

    public static DiscordService get() {
        return instance;
    }

    public static boolean isRunning() {
        return instance != null;
    }

    public long getFeedChannelId() {
        return feedChannelId;
    }

    /** Post a plain-text message to the feed channel. */
    public void postFeed(String text) {
        TextChannel ch = jda.getChannelById(TextChannel.class, feedChannelId);
        if (ch != null) ch.sendMessage(text).queue(null, err -> LOG.log(Level.WARNING, "Discord send failed", err));
    }

    /** Post an embed to the feed channel. */
    public void postFeedEmbed(MessageEmbed embed) {
        TextChannel ch = jda.getChannelById(TextChannel.class, feedChannelId);
        if (ch != null) ch.sendMessageEmbeds(embed).queue(null, err -> LOG.log(Level.WARNING, "Discord embed failed", err));
    }

    /**
     * Edit the live board message in place.
     * Creates and pins it on first call; re-creates it if the message was deleted.
     */
    public void updateLiveBoard(String content) {
        TextChannel ch = jda.getChannelById(TextChannel.class, feedChannelId);
        if (ch == null) return;

        if (liveBoardMessageId == null) {
            ch.sendMessage(content).queue(m -> {
                liveBoardMessageId = m.getId();
                m.pin().queue(null, err -> LOG.log(Level.FINE, "Pin failed (needs Manage Messages perm)", err));
            });
        } else {
            ch.editMessageById(liveBoardMessageId, content).queue(
                null,
                err -> {
                    liveBoardMessageId = null;
                    ch.sendMessage(content).queue(m -> {
                        liveBoardMessageId = m.getId();
                        m.pin().queue(null, pinErr -> LOG.log(Level.FINE, "Pin failed", pinErr));
                    });
                }
            );
        }
    }

    /** Call on session change so the next session gets a fresh board message. */
    public void resetLiveBoard() {
        liveBoardMessageId = null;
    }
}
