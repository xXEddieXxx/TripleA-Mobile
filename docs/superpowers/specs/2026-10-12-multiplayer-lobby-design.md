# Multiplayer lobby and online play – design

Date: 2026-10-12 · Branch: `multiplayer-beta` · Status: approved in chat, awaiting written review

## Goal

A lobby server the user runs on their own hardware so that TripleA Mobile can be played online:
accounts, friends, public and private games, free chat, fully playable games with time controls,
and joining from the desktop TripleA client. It runs locally on the development PC first, then on
the mini PC (Ryzen 5 5500U, 16 GB RAM, 512 GB SSD) behind the webtropia Linux server.

Success: two phones (or emulators) and one desktop client play a full game through the local
`docker compose` stack; a server restart in the middle of a mobile game loses nothing.

## Decisions

- **Separate private repo** `triplea-mobile-server` (next to this one). The app stays public under
  GPL-3.0; running a server is not distribution.
- **One host engine for both clients.** The server runs the desktop engine (`game-core`, `ai`,
  `game-headless`) at a pinned desktop tag; mobile players connect over WSS/JSON, desktop players
  over the classic TripleA network protocol.
- **Pinned desktop tag:** `2026-10.16.15815` (commit `1f6b0edfd2`, 2026-10-04).
- **Order of work:**
  1. Engine resync to the tag.
  2. Host with mobile players.
  3. Lobby, clocks, chat, standing orders.
  4. Desktop access through a filtered port.

  Each of these milestones gets its own implementation plan and must work on its own before the
  next one starts.

Out of scope for v1: rating (Glicko-2 later), push via FCM/UnifiedPush (v1 polls with WorkManager),
clocks and standing orders for desktop seats, a real version check for desktop clients.

## 1. Repositories and engine

### Server repo layout

- Git submodules: `triplea` at tag `2026-10.16.15815`, `triplea-mobile` at `multiplayer-beta`.
- A Gradle composite build uses desktop `:game-core`, `:ai`, `:game-headless` and mobile
  `:protocol`.
- Java 25, matching the desktop toolchain `JavaLanguageVersion.of(25)` (a locally installed JDK 25 is needed; Gradle auto-detects it).

### Engine resync in this repo

- The mobile `engine/` moves to the tag: 613 files are identical, 71 differ, 22 are mobile-only.
  - The mobile copy dates from about `b1f796364f` (2026-09-20). Only three upstream changes are
    missing: the `UnitAttachment` limit fix (`aa382fc97e`), the `GameParser.parse` cleanup and the
    `ExitStatus` shutdown guard.
  - The remaining differences are mobile adaptations: AWT/Swing removed, `// mobile:` fixes.
- Kept mobile-only: `org.triplea.geom`, `org.triplea.tree`, `SafeObjectInputStream`.
- Mobile `IDisplay.unitsMoved` stays. The mobile engine never speaks the Java wire protocol (the app
  talks JSON to the server), so the remote interfaces only have to match between the server and
  desktop clients, which both run the desktop engine.
- Save compatibility is what matters: the server (desktop engine) writes saves that the mobile
  engine must load. `SafeObjectInputStream` drops `java.awt.` (Android has no AWT), so JVM tests
  fail where Android would.
- Security fix that ships with the resync: `GameDataManager.loadDelegates` loads the class without
  initializing it and checks that it is an `IDelegate` before creating it.
- `MapDownloadManager` stays as it is. Map downloads are HTTPS-only already, and GitHub archive
  downloads need the redirect to `codeload.github.com`.
- `docs/DEVELOPMENT.md` records the base tag.

### New module `protocol/` (this repo)

- Pure Kotlin/JVM, kotlinx.serialization JSON.
- IDs: units by UUID, territories and nations by name.
- `PROTOCOL_VERSION` plus the engine build id, both checked on connect.

## 2. Server components and data flow

```
               ┌──────────────────── docker network "internal" ────────────────┐
Android app ─WSS/HTTPS─> caddy ─> server (Java 25)                    postgres
                                  ├─ accounts, lobby, friends, chat       ▲
                                  ├─ clocks, standing orders             │ server only
                                  └─ game host for mobile games ─────────┘
                                         ▲ internal API (token)
Desktop client ─TCP 3300–3319─> desktop-host (Java 25, isolated, no DB)
                                  └─ live games with desktop seats
```

### `server`

- REST and WebSocket for the app: login, lobby, chat, friends, presence and clocks.
- Runs every game that has only mobile and AI seats, one `ServerGame` with its own thread per game.
- The only process with database access.

### `desktop-host`

- The same engine, only for games that have desktop seats.
- One desktop `ServerMessenger` per game, on a port from 3300–3319.
- No database, no secrets, no network route to the DB.
- Talks to `server` over a narrow internal API: start a game, relay questions for mobile seats,
  report the result.

### Players on the host

- **`RemoteMobilePlayer`:** a desktop `Player` (built like `MobilePlayer` on `AbstractBasePlayer`,
  registered via `PlayerTypes`). Each engine question becomes a `Question` message, and the game
  thread blocks until one of these happens:
  - an answer arrives,
  - the clock runs out,
  - a standing order applies.
- **AI seats:** the desktop AI runs in-process.
- **Launch:** a custom `LaunchAction`, forked from `HeadlessLaunchAction`.

### Messages (WebSocket, JSON from `protocol/`)

| Direction | Message | Content |
|---|---|---|
| server → app | `GameVersion` | game X is now at version N; the app fetches the save over HTTPS (`GET /games/X/save`, gzip) |
| server → app | `Question` | id, seat, type (move, purchase, casualties …), payload, deadline |
| app → server | `Answer` | question id plus the answer; the server validates it and rejects invalid answers with a reason |
| server → app | `GameEvent` | battle display, dice, messages (what `GameEventListener` delivers today), so `BattlePanel` runs live |
| server → app | `Clock`, `Presence`, `Chat`, `LobbyUpdate` | clocks, who is online, chat, lobby list |

In the app, `GameController` gets a second source for `pending` (local or server). The dialogs stay
the same.

### Storage (Postgres, Flyway migrations)

Tables:

- Accounts: `users`, `refresh_tokens`, `friends`, `blocks`, `reports`
- Games: `games` (rules, time control, status, visibility), `seats`
- Game state: `snapshots` (save bytes as `bytea`, one per step), `journal` (answers and dice after
  the snapshot)
- `chat_messages`, `standing_orders`

**Recovery:**

- After a restart, `server` loads a running mobile game lazily: on the next access, or when a clock
  expires.
- It takes the last snapshot and replays the journal. A replay random source returns the logged
  dice.
- Desktop live games are not recovered. As on the desktop, an autosave lets players restart them.

## 3. Game flow

### Creating a game

The host picks:

- **Map and rules:** the map default, a community preset, or custom rules. Custom rules are shown
  as chips and are unrated.
- **Time control:**
  - blitz (3–10 min plus increment)
  - rapid
  - correspondence (1–14 days per turn, with vacation mode)
- **Dice:** always rolled on the server.
- **Nations:** assigned by the host, random, or bid.
- **Empty seats:** AI fill.
- **Visibility:** public, friends only, or by link. Spectators see the game with a delay.

Edit mode is always off. The game starts when every seat is filled and every player has pressed
"Ready". From then on the map, save and options are locked.

### A turn

1. The engine asks a question, and `RemoteMobilePlayer` sends a `Question` to the player who owns
   the seat. That player's clock runs.
2. The app opens the same dialog as today and sends an `Answer`.
3. The server validates the answer through `MoveValidator` and the delegate.

- Undo works within the current phase, as today.
- Opponents see each move live (as `aiMoves` does today). A player who checks in later gets a
  "what happened" replay (`HistoryReplay`).

### Clocks

Each human player has one clock, also when they play several nations. When it runs out:

1. The current phase ends with what is already done. Unplaced units stay in the pool.
2. The AI plays the seat until the player returns.
3. After 3 missed turns the player counts as resigned, and the AI plays out the game.

Steps 2 and 3 are host settings.

### Out-of-turn questions

Defender casualties, scramble, retreat, kamikaze and `acceptAction`.

- **Standing orders** are per-game preset answers, for example "casualties: cheapest first,
  aircraft last", "never scramble", "decline political offers".
- **Defender mode:**
  - **Live:** always ask.
  - **Hybrid** (default): ask while the defender is online, with a short window that runs on their
    own clock; otherwise the standing order applies.
  - **Standing only.**
- With no order set, the engine default answer applies (as `HumanPlayerUiAdapter` does today).

### Disconnect and server restart

- The app reconnects, fetches `GameVersion` and the save, and gets the open `Question` again.
- The server restores the game from snapshot plus journal.

### Chat

- Free text in the lobby and in games, plus quick messages. Spectator chat is separate from player
  chat.
- **Block:** hides that user's chat and stops their invites.
- **Report:** sends a report to the admin.
- Stored for 90 days.

### Desktop seats in v1

- Live games only. No clock and no standing orders, because the desktop client has no UI for them.
- A dropped desktop player triggers a save and a pause, as on the desktop
  (`ServerLauncher.connectionLost`).

### Game end

- A game ends by victory, resignation or timeout.
- The result is stored. Rating comes later.
- Every participant can download the save, also to continue on the desktop.

## 4. Security

### Network

- **Public entry point:** webtropia only, with Caddy or the existing nginx on 443 and TCP 3300–3319.
  It reaches the mini PC through a WireGuard tunnel; no port is opened on the home router.
- **Mini PC:** its own VLAN. SSH is key-only and only over WireGuard.
- **Admin panel:** WireGuard only.
- **Proxy:** CrowdSec or fail2ban, plus rate limits on login, registration and WebSocket connects.

### Accounts

- Passwords are hashed with Argon2id.
- Access tokens last 15 minutes. Refresh tokens rotate on use and are stored only as hashes.
- New accounts are limited per IP and day.
- Public lobbies need a verified e-mail or Google sign-in; friend games do not.
- Minimum age 16. Accounts can be deleted from the app.

### App ↔ server

- **Format:** only `protocol/` JSON with fixed types; no Java serialization on this path.
- **Size limits:** 64 KB per message, 10 MB per save.
- **Version check:** `PROTOCOL_VERSION` and the engine build id are checked on connect; a mismatch
  shows "please update the app".
- **Checks on every message:**
  - an `Answer` must come from the seat owner and match the open question;
  - the engine validates its content, and the app is never trusted;
  - spectators only get delayed state.
- Downloaded saves do not contain the dice seed.

### Desktop access (`desktop-host`)

**Container:**

- No network route to the DB, no secrets.
- Read-only filesystem, non-root, memory and CPU limits.

**`-Djdk.serialFilter` allow-list:**

- Allowed: the prefixes from `SafeObjectInputStream`, plus `java.net.InetSocketAddress`,
  `InetAddress`, `Inet4Address` and `Inet6Address`. Then `!*`.
- Limits on `maxbytes`, `maxdepth`, `maxrefs` and `maxarray`.
- This closes the pre-authentication deserialization in `nio/Decoder`.
- Whether `java.awt.Color` or `Point` are needed is checked in test 6 (see "Open points").

**Seat binding:**

- A custom `ILoginValidator` uses the username it receives in `getChallengeProperties(username)` to
  check a one-time password per reserved seat. The desktop client shows its normal password dialog;
  no client change is needed.
- A subclass of `ServerModel` restricts `takePlayer` and `releasePlayer` to the seat reserved for
  that name. It also blocks `changeToGameSave` and `changeToGameOptions` after creation.

**Desktop versions:**

- The desktop client does not send its version (`ClientLogin.ENGINE_VERSION_PROPERTY` is declared
  but never set), so v1 has no enforced version check.
- The lobby shows "requires TripleA 2026-10.16" and hands out the one-time password only after the
  player confirms it.
- A wrong version normally fails while deserializing. The host then disconnects the client and logs
  a clear message.
- A real version check needs a desktop client change (an upstream PR), outside v1.

### Data and legal

- **Secrets** live in `.env`, which is gitignored.
- **Retention:** IP logs at most 7 days, chat 90 days.
- **Backups:** restic, encrypted and off-site.
- **Before the public launch:** Impressum, privacy policy and terms of service on webtropia.

## 5. Local test setup

- **Full stack:** `triplea-mobile-server/docker-compose.yml` runs `postgres`, `server`,
  `desktop-host` and `caddy`.
  - Locally, Caddy uses `tls internal` (a self-signed certificate).
  - The same file runs on the mini PC with a different `.env`.
  - Start it with `docker compose up --build`.
- **Fast loop:** `./gradlew :server:run` against a Postgres container, without `desktop-host`.
- **App against the local server:**
  - A "Server URL" setting, in debug builds only.
  - Defaults: `wss://10.0.2.2:8443` on the emulator, the PC's LAN IP from a phone.
  - The self-signed certificate is trusted only in debug builds, through `network_security_config`
    `debug-overrides`.
- **Two players:** two emulators, or an emulator and a phone. `--seed` creates three test users who
  are friends.
- **Desktop:** the desktop client built from the submodule at the tag joins `localhost:3300` with
  the one-time password from the app.
- **Time travel:** a dev-profile-only admin endpoint moves clocks forward, so correspondence
  timeouts can be tested without waiting days.

## 6. Testing

### This repo (game-level tests only)

- `LocalGameSmokeTest` and `HumanPlayerFlowTest` stay green after the resync.
- **Desktop save compatibility:** a new test method in `LocalGameSmokeTest` loads a minimap save
  written by the desktop engine at the tag and plays one more round in the mobile engine.
  `engine/upstream/generate-desktop-save.sh` regenerates the fixture from the desktop checkout.
- **`protocol/`:** a Kotlin → JSON → Kotlin roundtrip for every message type, plus golden JSON files
  so that a protocol change without a version bump fails.

### Server repo (Testcontainers Postgres)

1. **Full headless game:** two `RemoteMobilePlayer`s with scripted answers against AI on `minimap`,
   played to the end. The winner must be stored.
2. **Recovery:** abort a game mid-turn, restore it from snapshot plus journal and play on. The
   resulting save must be byte-identical to an uninterrupted run, including the dice.
3. **Clocks:** with time travel, the phase must end cleanly, the AI must take over, and after 3
   missed turns the resignation must apply.
4. **Standing orders:** with the defender offline in hybrid and in standing-only mode, the order
   must apply instead of the engine default.
5. **Permissions:** each of these must be rejected:
   - another player's `Answer`
   - a wrong question id
   - an invalid move
   - an expired token
   - a spectator asking for undelayed state
6. **Desktop host:**
   - A real desktop `ClientMessenger` from the submodule connects with the one-time password and
     plays a few moves.
   - A wrong password or an unknown name is rejected.
   - `takePlayer` on someone else's seat is rejected.
   - A payload outside the allow-list fails at the `serialFilter`.

### Manual checklist before each milestone (kept in `docs/`)

- two emulators against `docker compose`
- a phone over Wi-Fi
- one desktop client
- disconnect and reconnect
- a server restart during a running game

## Open points (resolved by the tests above, not by more design)

- Whether the engine can end a running phase cleanly from code when a clock expires (test 3).
- Whether `ColorProperty` with `org.triplea.geom.Color` versus `java.awt.Color` breaks save
  compatibility (the desktop save test in `LocalGameSmokeTest`).
- Whether the serial filter needs `java.awt.Color` or `Point` (test 6).
