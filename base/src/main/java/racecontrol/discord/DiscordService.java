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
    /**
     * Separate channel for the live leaderboard, or 0 to share with feedChannelId.
     * When non-zero, updateLiveBoard() posts here so alerts and the board stay in
     * different channels.
     */
    private final long boardChannelId;
    private volatile String liveBoardMessageId;

    private DiscordService(JDA jda, long feedChannelId, long boardChannelId) {
        this.jda           = jda;
        this.feedChannelId = feedChannelId;
        this.boardChannelId = boardChannelId;
    }

    /**
     * Start with a dedicated board channel.
     * Pass {@code boardChannelId = 0} to use the feed channel for the leaderboard too.
     */
    public static synchronized DiscordService start(String token, long guildId,
            long feedChannelId, long boardChannelId)
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

        instance = new DiscordService(jda, feedChannelId, boardChannelId);
        LOG.info("Discord bot connected and ready.");

        // Fire stop() on JVM exit (app closed, crash, task-killed) so the
        // offline message is always sent and JDA shuts down cleanly.
        Runtime.getRuntime().addShutdownHook(new Thread(DiscordService::stop, "discord-shutdown-hook"));

        // Let the channel know the bot is live
        instance.postFeed("**Race Control is online** - /follow /standings /gap /battle /pace /pitstops");
        return instance;
    }

    public static synchronized void stop() {
        if (instance != null) {
            // Send shutdown message synchronously so it fires before JDA closes
            try {
                TextChannel ch = instance.jda.getChannelById(TextChannel.class, instance.feedChannelId);
                if (ch != null) ch.sendMessage("**Race Control is going offline**").complete();
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Could not send shutdown message", e);
            }
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

    public long getBoardChannelId() {
        return boardChannelId > 0 ? boardChannelId : feedChannelId;
    }

    /**
     * Edit the live board message in place.
     * Uses the dedicated board channel when configured; otherwise falls back to the
     * feed channel. Creates and pins the message on first call; re-creates it if
     * the message was deleted.
     */
    public void updateLiveBoard(String content) {
        long targetChannelId = boardChannelId > 0 ? boardChannelId : feedChannelId;
        TextChannel ch = jda.getChannelById(TextChannel.class, targetChannelId);
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

    /**
     * Send a private DM to a Discord user by their ID.
     * Silently ignored if the user has DMs disabled.
     */
    public void dmUser(String userId, String message) {
        jda.openPrivateChannelById(userId)
           .flatMap(ch -> ch.sendMessage(message))
           .queue(null, err -> {
               if (err instanceof net.dv8tion.jda.api.exceptions.ErrorResponseException ere
                       && ere.getErrorResponse()
                              == net.dv8tion.jda.api.requests.ErrorResponse.CANNOT_SEND_TO_USER) {
                   LOG.fine("Cannot DM user " + userId + " - DMs are disabled");
               } else {
                   LOG.log(Level.WARNING, "Failed to DM user " + userId, err);
               }
           });
    }
}
