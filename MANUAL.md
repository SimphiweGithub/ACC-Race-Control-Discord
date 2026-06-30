# ACC Race Control – Discord Integration Manual

A live race feed and interactive spectator bot built into ACC Race Control.
Spectators who can't drive that night can follow the race, get pinged on their
favourite driver's moments, and receive a personal recap when the race ends.

---

## Table of Contents

1. [How It Works](#how-it-works)
2. [What Gets Posted Automatically](#what-gets-posted-automatically)
3. [Setup: Create the Discord Bot](#setup-create-the-discord-bot)
4. [Setup: Invite the Bot to Your Server](#setup-invite-the-bot-to-your-server)
5. [Setup: Configure Race Control](#setup-configure-race-control)
6. [Spectator Commands](#spectator-commands)
7. [Operator Commands](#operator-commands)
8. [Personal Race Recaps (/iam)](#personal-race-recaps-iam)
9. [The Live Leaderboard](#the-live-leaderboard)
10. [Data Stored on Disk](#data-stored-on-disk)
11. [Troubleshooting](#troubleshooting)

---

## How It Works

Race Control runs on the same machine as the ACC dedicated server (or a client
connected to it). Once configured, it connects to your Discord server as a bot
and posts race events in real time to a channel you choose. Spectators interact
with the bot using slash commands from their own Discord client — they do not
need to be on the same network or machine.

The bot is self-contained inside the Race Control application. No separate
Python script, Docker container, or external service is required.

---

## What Gets Posted Automatically

Everything below appears as a plain message or embed in the configured channel.

| Event | Example message |
|---|---|
| Session starts | `**RACE** is starting` |
| Fastest lap | Embed — driver name + lap time |
| Lead change | `**LEAD CHANGE** - Hamilton takes the lead from Verstappen (12:34 elapsed)` |
| Battle building | `**CLOSING IN** - Leclerc is hunting down Russell - 1.4s and dropping (P4)` |
| Wheel-to-wheel | `**SIDE BY SIDE** - Russell and Leclerc are wheel-to-wheel for P4 - 0.180s` |
| Overtake | `**OVERTAKE** - Leclerc passes Russell for P4` |
| Contact / incident | Embed — drivers involved and their positions |
| VSC deployed | Embed — speed limit |
| VSC ending | `VSC ending - green flag` |
| VSC violation | Driver name |
| Pit stop | `HAMILTON pits (stop 1 | 18:42 elapsed)` |
| Halfway | `**Halfway** - 18:42 elapsed | 18:42 remaining` |
| Race recap (end) | Embed — podium, fastest lap, biggest mover, most overtakes |
| Bot online | `Race Control is online - /iam /follow /standings ...` |
| Bot reconnected | `Race Control reconnected.` |

Battle alerts (closing, side by side, overtake) only fire once the race leader
has completed at least one lap, suppressing false positives during the formation
lap and standing start.

---

## Setup: Create the Discord Bot

You only need to do this once. You need a Discord account and access to the
[Discord Developer Portal](https://discord.com/developers/applications).

1. Go to [discord.com/developers/applications](https://discord.com/developers/applications)
   and click **New Application**. Give it a name (e.g. "Race Control").

2. In the left sidebar, click **Bot**.

3. Click **Reset Token**, confirm, and copy the token that appears.
   **Keep this token private** — it gives full control of the bot account.
   Paste it somewhere safe; you will need it in Step 5.

4. On the same Bot page, scroll down to **Privileged Gateway Intents**.
   You do NOT need to enable any intents for this bot — leave them all off.

5. Still on the Bot page, make sure **Public Bot** is unchecked if you only
   want your own server to use it.

---

## Setup: Invite the Bot to Your Server

1. In the Developer Portal, click **OAuth2** in the left sidebar, then
   **URL Generator**.

2. Under **Scopes**, tick: `bot` and `applications.commands`.

3. Under **Bot Permissions**, tick the following:

   | Permission | Why it is needed |
   |---|---|
   | View Channels | Read the channel |
   | Send Messages | Post feed alerts |
   | Embed Links | Post embeds (recap, fastest lap, contact) |
   | Read Message History | Needed for slash command context |
   | Manage Messages | Pin and unpin the live leaderboard |

4. Copy the generated URL at the bottom of the page and open it in your
   browser. Select your server and click **Authorise**.

5. The bot will appear in your server's member list (offline until Race Control
   starts).

### Finding Your Server ID and Channel ID

You need Developer Mode enabled:
Discord **User Settings** → **Advanced** → turn on **Developer Mode**.

- **Server (Guild) ID** — right-click the server icon in the left sidebar →
  **Copy Server ID**.
- **Channel ID** — right-click the channel you want alerts in →
  **Copy Channel ID**.

---

## Setup: Configure Race Control

1. Launch `ACC Race Control Discord.exe`.

2. Open **Settings** (top menu) → **Discord**.

3. Fill in the three fields:

   | Field | What to paste |
   |---|---|
   | Bot Token | The token from the Developer Portal (Step 3 above) |
   | Server ID | Your copied Guild / Server ID |
   | Channel ID | Your copied Channel ID |

4. Click **Connect**. The bot will come online and post a message in the
   channel confirming it is live.

The credentials are saved locally and the bot will reconnect automatically the
next time Race Control starts.

---

## Spectator Commands

All commands are slash commands. Type `/` in Discord and select the command
from the list. Driver name fields support autocomplete — start typing and
Discord will suggest matching names from the current session.

All replies are **ephemeral** (only visible to you).

### /iam
```
/iam driver:<your name>
```
Claim your ACC driver name so the bot can send you a **personal race recap DM**
when the race ends. Do this once per account; it is remembered between races.

Example: `/iam driver:Lewis Hamilton`

You will receive a DM like:
> Race over! You finished P3. You made 2 overtakes.

### /follow
```
/follow driver:<name>
```
Subscribe to live ping DMs whenever that driver is involved in a contact
incident or sets a new personal best. You can follow multiple drivers by using
the command more than once.

### /unfollow
```
/unfollow
```
Stop following all drivers.

### /following
```
/following
```
List every driver you are currently following.

### /standings
```
/standings
```
Show the current race order with gaps, lap counts, and pit stops.

### /gap
```
/gap driver1:<name> driver2:<name>
```
Show the time gap between any two drivers, regardless of whether they are
running near each other.

### /battle
```
/battle
```
List every pair of cars currently within 1.0 second of each other on track.

### /pace
```
/pace driver:<name>
```
Show the last three completed lap times for a driver, plus their average.

### /pitstops
```
/pitstops
```
Full pit stop summary for the field — number of stops per car, who is
currently in the pits, and who is running long compared to the rest.

---

## Operator Commands

These require **Administrator** permission in the Discord server.

### /quiet
```
/quiet mute:true
/quiet mute:false
```
Mute or unmute the race feed. When muted, no alerts or embeds are posted
(the live leaderboard continues to update). Use this if the bot is flooding
the channel mid-race and you need to silence it quickly without restarting
the application.

The command replies to you privately (only you see it). When unmuting,
the bot also posts a public "feed is active again" message so the channel
knows alerts have resumed.

---

## Personal Race Recaps (/iam)

When a race session ends, the bot posts a **Race Recap** embed in the channel:

- **Podium** — P1, P2, P3
- **Fastest Lap** — driver name and time
- **Biggest Mover** — who gained the most positions from grid to finish
- **Most Overtakes** — who made the most on-track passes detected during the race

Additionally, every spectator who has used `/iam` receives a **private DM**
with their personal result, including any special achievements (biggest mover,
fastest lap, number of overtakes they made).

The `/iam` claim is stored in `data/iam.json` in the Race Control working
directory and persists between races and app restarts. Spectators only need
to run it once.

---

## The Live Leaderboard

The bot maintains a **pinned message** in the channel that updates every
5 seconds with the current race order, gaps, pit counts, and session info.
Because it is pinned, spectators can always find it — even as alerts scroll
past in the feed.

If the message is manually deleted, it is automatically recreated on the next
update. On restart or session change, stale pinned leaderboards from previous
sessions are cleaned up and a fresh one is created.

The bot requires the **Manage Messages** permission to pin and unpin messages.
If that permission is missing, the leaderboard will still post but will not be
pinned (a warning will appear in the Race Control log).

---

## Data Stored on Disk

The bot stores two small files in the Race Control working directory:

| File | Contents |
|---|---|
| `data/iam.json` | Discord user ID -> ACC driver name claims (from /iam) |
| `PersistantConfig` | Bot token, server ID, and channel ID (saved by the GUI) |

Neither file is uploaded anywhere. The bot token is stored in plain text in
`PersistantConfig` — keep that file private, or rotate the token in the
Developer Portal if the machine is compromised.

Follow subscriptions (`/follow`) are held in memory and reset when Race
Control restarts. Tell spectators to re-run `/follow` after a restart.
(This is a known limitation; only `/iam` claims persist to disk.)

---

## Troubleshooting

**Bot comes online but no slash commands appear**

Guild commands take a few seconds to register after the bot connects. Wait
10–15 seconds, then try `/` again. If they still don't appear, check that the
bot was invited with the `applications.commands` scope (see Setup section).

**Leaderboard posts but is not pinned**

The bot is missing the **Manage Messages** permission in that channel. Update
the bot's role permissions or the channel's permission overrides in Discord's
Server Settings.

**No alerts firing during the race**

- Check that Race Control is connected to the ACC session (green indicator in
  the main window).
- Battle/overtake alerts are suppressed until the race leader completes at
  least one lap — this is intentional.
- Use `/standings` to confirm the bot can see live car data.

**Feed is unexpectedly quiet**

An administrator may have enabled quiet mode with `/quiet mute:true`. An
administrator can restore the feed with `/quiet mute:false`.

**Local build fails with "Unable to establish loopback connection"**

See `KNOWN_ISSUES.md` Issue 1 for the exact fix (this is a JDK 25 + Windows
username path issue; it does not affect the distributed EXE).

**Bot token or IDs changed / need resetting**

Open Settings → Discord in Race Control, update the fields, and click
Connect again. The old connection is closed and a new one is established.

---

*For build and runtime issues encountered during development, see `KNOWN_ISSUES.md`.*
