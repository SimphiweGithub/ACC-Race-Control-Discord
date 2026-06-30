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

Discord splits pinning into two separate permissions. The bot needs **both**:

- **Manage Messages** — to delete/remove messages
- **Pin Messages** — to actually pin or unpin (a separate toggle in newer Discord)

Grant both in the channel's Permissions tab (right-click channel → Edit Channel
→ Permissions). After granting Pin Messages, **delete the leaderboard message**
so the bot recreates and pins it on the next 5-second tick. If you only grant
the permission without deleting, the bot already has a message ID and will keep
editing it in place — the pin attempt only runs on creation.

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

## Tips & Tricks

### Operator

**Test with a practice session before race night**
Connect Race Control during a practice or qualifying session and run through
`/standings`, `/gap`, `/pace`, and `/iam`. This confirms the bot can see live
data before the league night starts, without the pressure of a live race.

**Use /standings as your health check**
If you are unsure whether the bot is seeing ACC data, `/standings` is the
fastest way to confirm. A "Not connected" reply means Race Control has lost
the ACC session; a live grid reply means everything is working.

**Mute before the formation lap, unmute when the lights go out**
Pre-race chatter (session start message, any leftover state) can clutter the
channel. Use `/quiet mute:true` while the grid forms and `/quiet mute:false`
the moment the race starts. The "feed is active again" message acts as a
natural race-start signal for spectators watching the channel.

**Admin commands work from any channel**
`/quiet` does not need to be typed in the race channel. Use it from a private
or admin channel so it does not draw attention mid-race.

**Don't share your screen on the Settings panel**
The Bot Token is displayed in plain text in the Settings → Discord panel.
Avoid sharing your screen while that panel is open. If the token is ever
exposed, reset it immediately in the Discord Developer Portal (Bot tab →
Reset Token) and update Race Control with the new one.

**Force a leaderboard re-pin after changing permissions**
The pin attempt only runs when the board message is first created. If you
grant Pin Messages after the board is already posted, delete the message —
the bot recreates and pins it within 5 seconds.

**Re-tell spectators to /follow after a restart**
`/iam` claims survive restarts (saved to `data/iam.json`). `/follow`
subscriptions do not — they reset when Race Control closes. Remind spectators
to re-run `/follow` if the app was restarted between sessions.

---

### Spectators

**Use both /iam and /follow for maximum coverage**
They do different things and do not overlap:
- `/iam` → receive a **personal recap DM** at race end (set once, lasts forever)
- `/follow` → receive a **live DM** mid-race when your driver has a contact or
  sets a new personal best (resets on app restart)

Run both before the race starts.

**You can follow multiple drivers**
Use `/follow` more than once with different names — each call adds to your
subscription list. Use `/following` to see everyone you are tracking.

**Autocomplete only works during an active session**
Driver name autocomplete pulls from the live ACC car list. If no session is
running you will need to type the name in full. Wait until Race Control is
connected before running `/iam` or `/follow`.

**You only need /iam once**
Your claim is stored permanently. Unless your ACC driver name changes between
seasons (or you join a new team with a different name), you do not need to
re-run it.

**Ephemeral replies keep the channel clean**
All command replies are private — only you see them. The channel feed stays
clean with only real race events.

---

### Known Quirks (not bugs)

**Pit stop alerts fire at 00:00 elapsed on the grid**
During the pre-race grid phase the session clock has not started, so the
elapsed time shown is 00:00. These alerts fire because pit counts from
qualifying carry over into the race session before the first lap is complete.
They are cosmetic noise and stop once the race is underway.

**No battle or overtake alerts in the first lap**
This is intentional. All three battle triggers (CLOSING IN, SIDE BY SIDE,
OVERTAKE) are suppressed until the race leader completes at least one lap.
The formation lap and standing start cause ACC to shuffle positions in ways
that would generate dozens of false positives. Expect the first alerts around
lap 2 onward.

**CLOSING IN will not repeat for the same pair for 90 seconds**
One update per chase phase is the design. If a car is hunting down another
for several laps the channel gets one "hunting down" message, then silence
until they are either through or have dropped back and re-closed. The SIDE BY
SIDE and OVERTAKE alerts are not affected by this cooldown.

**SIDE BY SIDE needs about 4 seconds of sustained proximity**
The alert requires two consecutive 2-second ticks both under 0.3s gap. A
one-frame GPS blip at 0.2s will not trigger it. If a pass happens very quickly
you may only see OVERTAKE without a preceding SIDE BY SIDE — that is correct.

**Passes from someone else pitting do not count as overtakes**
If Hamilton pits and you inherit P4, that position gain does not register as an
OVERTAKE and does not count toward the "Most Overtakes" stat in the recap. Only
on-track position swaps between two cars both running (not in the pit lane) are
counted. This is intentional — pit-window position shuffles are not passes.

**Two cars at the same position in the leaderboard**
Occasionally two cars briefly share the same race position during a pit
transition (both are in the pits simultaneously and ACC has not yet resolved
the order). This appears as duplicate position numbers in the leaderboard for
a few seconds. It resolves itself on the next update and does not affect alerts.

**Biggest Mover may be absent or slightly off**
The grid position snapshot is taken the instant the RACE session is announced
by ACC. If ACC takes a moment to publish grid positions (common on some
servers), some cars may have position 0 at snapshot time and are excluded from
the biggest-mover calculation. The field is simply omitted from the recap
embed if there is no valid data — it will not show wrong information.

**Race Recap fields only appear when data exists**
If no on-track overtakes were detected, the "Most Overtakes" field does not
appear in the recap embed. Same for Fastest Lap if no lap was completed, or
Biggest Mover if the grid snapshot was empty. A minimal recap with only the
podium is still posted.

---

*For build and runtime issues encountered during development, see `KNOWN_ISSUES.md`.*
