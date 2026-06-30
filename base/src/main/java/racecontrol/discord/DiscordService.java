package racecontrol.discord;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.session.SessionRecreateEvent;
import net.dv8tion.jda.api.events.session.SessionResumeEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
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
    /** When true, postFeed/postFeedEmbed are silently suppressed (operator kill switch). */
    private volatile boolean quietMode          = false;
    private volatile String  liveBoardMessageId = null;
    /**
     * True while an async pin-retrieval + message-creation is in flight.
     * Prevents the 5-second tick from firing multiple creation attempts in parallel.
     */
    private volatile boolean boardRecovering    = false;

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
                Commands.slash("pitstops", "Pit stop summary for the field"),
                Commands.slash("iam", "Claim your ACC driver name for personal race recaps")
                        .addOption(OptionType.STRING, "driver", "Your driver name in ACC", true, true),
                Commands.slash("quiet", "Mute or unmute the race feed (admin only)")
                        .addOption(OptionType.BOOLEAN, "mute", "true = mute, false = unmute", true)
            ).queue();
        }

        jda.addEventListener(new DiscordCommandListener(feedChannelId));

        // Log and announce whenever JDA recovers from a gateway drop.
        // SessionRecreateEvent = full reconnect (new session); SessionResumeEvent = invisible resume.
        jda.addEventListener(new ListenerAdapter() {
            @Override
            public void onSessionRecreate(SessionRecreateEvent event) {
                LOG.info("Discord gateway session recreated (full reconnect).");
                if (instance != null) instance.postFeed("Race Control reconnected.");
            }
            @Override
            public void onSessionResume(SessionResumeEvent event) {
                LOG.info("Discord gateway session resumed.");
            }
        });

        instance = new DiscordService(jda, feedChannelId, boardChannelId);
        LOG.info("Discord bot connected and ready.");

        // Fire stop() on JVM exit (app closed, crash, task-killed) so the
        // offline message is always sent and JDA shuts down cleanly.
        Runtime.getRuntime().addShutdownHook(new Thread(DiscordService::stop, "discord-shutdown-hook"));

        // Let the channel know the bot is live
        instance.postFeed("**Race Control is online** - /iam /follow /standings /gap /battle /pace /pitstops | admin: /quiet");
        return instance;
    }

    public static synchronized void stop() {
        if (instance != null) {
            // Send shutdown message synchronously so it fires before JDA closes
            try {
                TextChannel ch = instance.jda.getChannelById(TextChannel.class, instance.feedChannelId);
                if (ch != null) ch.sendMessage("**Race Control is going offline**")
                        .timeout(2, java.util.concurrent.TimeUnit.SECONDS)
                        .complete();
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

    /**
     * Enable or disable quiet mode. When quiet, postFeed and postFeedEmbed are
     * suppressed so the operator can silence the bot mid-race without restarting.
     */
    public void setQuietMode(boolean q) {
        quietMode = q;
        LOG.info("Discord feed quiet mode: " + (q ? "ON" : "OFF"));
    }

    public boolean isQuietMode() { return quietMode; }

    /** Post a plain-text message to the feed channel. Suppressed in quiet mode. */
    public void postFeed(String text) {
        if (quietMode) return;
        TextChannel ch = jda.getChannelById(TextChannel.class, feedChannelId);
        if (ch != null) ch.sendMessage(text).queue(null, err -> LOG.log(Level.WARNING, "Discord send failed", err));
    }

    /** Post an embed to the feed channel. Suppressed in quiet mode. */
    public void postFeedEmbed(MessageEmbed embed) {
        if (quietMode) return;
        TextChannel ch = jda.getChannelById(TextChannel.class, feedChannelId);
        if (ch != null) ch.sendMessageEmbeds(embed).queue(null, err -> LOG.log(Level.WARNING, "Discord embed failed", err));
    }

    public long getBoardChannelId() {
        return boardChannelId > 0 ? boardChannelId : feedChannelId;
    }

    /**
     * Edit the live board message in place.
     * Uses the dedicated board channel when configured; otherwise falls back to the
     * feed channel.
     *
     * On first call (or after a session reset / app restart), the method:
     *   1. Retrieves pinned messages in the board channel.
     *   2. Unpins any stale live-board messages left by previous sessions or restarts
     *      so only ONE pinned message exists at any time.
     *   3. Creates a fresh message and pins it.
     *
     * If the message is manually deleted it is automatically recreated on the next tick.
     */
    public void updateLiveBoard(String content) {
        long targetChannelId = boardChannelId > 0 ? boardChannelId : feedChannelId;
        TextChannel ch = jda.getChannelById(TextChannel.class, targetChannelId);
        if (ch == null) return;

        if (liveBoardMessageId == null) {
            if (boardRecovering) return;  // creation already in flight — wait for next tick
            boardRecovering = true;

            // Unpin any stale board from a previous session / restart, then post fresh.
            ch.retrievePinnedMessages().queue(
                pins -> {
                    for (net.dv8tion.jda.api.entities.Message pin : pins) {
                        if (pin.getAuthor().getIdLong() == jda.getSelfUser().getIdLong()) {
                            ch.unpinMessageById(pin.getId()).queue(
                                null, e -> LOG.log(Level.FINE, "Unpin stale board failed", e));
                        }
                    }
                    sendAndPinBoard(ch, content);
                },
                err -> {
                    // Can't read pins (no Manage Messages perm) — just create without cleanup
                    LOG.log(Level.FINE, "Could not retrieve pins for board cleanup", err);
                    sendAndPinBoard(ch, content);
                }
            );
            return;
        }

        // Normal path: edit the existing message in place.
        ch.editMessageById(liveBoardMessageId, content).queue(
            null,
            err -> {
                // Message was deleted — recreate on next tick
                liveBoardMessageId = null;
            }
        );
    }

    private void sendAndPinBoard(TextChannel ch, String content) {
        ch.sendMessage(content).queue(
            m -> {
                liveBoardMessageId = m.getId();
                boardRecovering    = false;
                m.pin().queue(null, e -> LOG.log(Level.FINE, "Pin failed (needs Manage Messages perm)", e));
            },
            e -> {
                boardRecovering = false;
                LOG.log(Level.FINE, "Board send failed", e);
            }
        );
    }

    /** Call on session change so the next session gets a fresh pinned board message. */
    public void resetLiveBoard() {
        liveBoardMessageId = null;
        boardRecovering    = false;
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
