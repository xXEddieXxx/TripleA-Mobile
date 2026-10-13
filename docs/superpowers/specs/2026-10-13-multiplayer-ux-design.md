# Multiplayer UX round 2 – design

Date: 2026-10-13 · Branch: `multiplayer-beta` · Status: approved in chat (user delegated the remaining
decisions: "entscheide von nun an selbständig")

Builds on milestones 2b–3b (server `triplea-mobile-server`, app online mode). Online stays debug-only.

## Goals (from the user)

1. Creating an online game offers **every map of the normal game selection** (bundled and downloaded).
2. The **nation selection in multiplayer looks like the single-player setup**, plus:
   - a chat,
   - free slots other players can take,
   - the player's own name instead of "Human".
3. **Forgot password** via an e-mail linked to the account.
4. A **privacy policy** shown in multiplayer.
5. A **friends list with private chat**, and a nicer friends screen.

## 1. Maps on demand

- The app's map list is `MobileEngine.listInstalledGames()`, the same as single player.
  `POST /games` sends `mapName` and `gameName` only.
- The server keeps its installed maps (bundled zips from 2b) and, when a requested map is missing,
  downloads it from the TripleA map list `https://raw.githubusercontent.com/triplea-game/triplea/master/triplea_maps.yaml`,
  the same list the app's map browser uses.
  - Only that fixed list URL. Downloads only over HTTPS, redirects only to HTTPS. No client-supplied URL.
  - Size cap 300 MB per zip. Unpack with `\` → `/`, zip-slip check, temp folder plus atomic move (as `Engine`).
  - One download per map at a time; concurrent creates wait for the same download.
- A game created for a missing map is created in status `lobby` with `mapReady = false`. The download
  runs in the background, and a `LobbyUpdate` announces `mapReady = true`, or the game is aborted with
  the error "Map could not be downloaded: …". Ready is refused while `mapReady` is false.
- Seats are validated against the game's nations once the map is installed. A mismatch aborts the game
  with a clear error.

## 2. Online setup screen (replaces the create dialog and the lobby card)

The approved mockup is Variante 2 in `.superpowers/brainstorm/*/content/setup-online.html`.

- **Top:** a map card (thumbnail if the map has a preview, name, number of nations, "Change map", which
  the creator can use before anyone else joined). Under it, chips for visibility, time control and
  "More" (AI takes over, resign after N, spectators allowed).
- **Nation list:** the same rows and dropdown style as `SetupScreen`. Dropdown entries:
  - the player's own username (instead of "Human"),
  - "Free": an open seat that other players take with "Take seat",
  - "Invite friend…": pick from the friends list,
  - the AI levels and "Does nothing".
  A seat held by a player shows their name and ✓ when ready.
- **Rights:**
  - Before start the creator can change every seat and the options.
  - The others can take a free seat, give back their own seat ("Free") and toggle Ready.
  - Any change resets everyone's Ready (the setup changed).
- **Bottom:** Ready / Leave (others) / Delete (creator). A collapsible chat bar at the very bottom with the
  last message and an unread counter. It opens the existing `game:<id>` chat, which continues in the
  game.
- When the game starts, every participant on this screen switches straight into the game.
- **Server:**
  - `PUT /games/{id}/seats` (creator) sets seat kinds and invites while in `lobby`.
  - `PATCH /games/{id}` (creator) sets visibility, time control and options while in `lobby`.
  - Both send `LobbyUpdate` and reset ready.
  - Existing join, leave, ready and delete stay.

## 3. Friends screen and private chat

The approved mockup is Variante A plus the floating "Add friend" button from B.

- **Sections:**
  - Requests (Accept / ✕) and Online on top, then Offline.
  - Each card has an avatar initial with a status dot (green online, amber in a game, grey offline).
  - A status line: "online", "playing Minimap · round 4", or "last seen 2 h ago".
  - Each card also shows an unread counter, a chat button and a ⋮ menu (Remove friend, Block, Report).
- **"＋ Add friend"** is a floating button that opens a username field.
- **Private chat:**
  - Channel `dm:<smallerUserId>:<largerUserId>`. Only accepted friends may send.
  - Both can read the history after an unfriend or block. A block also stops new messages, and the
    blocker never sees new messages from the blocked user.
  - Same limits (500 characters, 5 per 10 s), 90-day retention, reports, and "deleted user" for deleted
    accounts.
  - Unread state is a `chat_reads(user_id, channel, last_read_id)` table; opening the chat marks it read.
    `GET /friends` returns unread counts and the activity line. `GET /me/turns` adds an unread
    private-message count, so the 15-minute worker also notifies about new private messages.
- **Server activity:** `FriendDto` gets `activity` ("playing <game> · round N" for a running game, else
  null) and `lastSeen` (time of the last connection close).

## 4. E-mail and password reset

- **Account e-mail:**
  - Optional at registration, and can be added or changed later in the Online menu under "Account".
  - Unique per account (case-insensitive).
  - Stored only after confirmation with a 6-digit code sent by mail.
- **Forgot password:**
  - "Forgot password?" under the login form takes a username or an e-mail.
  - The server sends a 6-digit code, valid 15 minutes, to the confirmed address. The answer is always
    the same, so the server does not reveal accounts.
  - The app then takes the code and a new password. On success all of the account's tokens are
    revoked and the user is logged in.
- **Abuse limits:**
  - At most 3 code mails per account per hour and 10 per IP per hour.
  - A code dies after 5 wrong tries.
  - Codes are stored hashed (SHA-256), like tokens.
- **Sending:**
  - SMTP settings come from env (`SMTP_HOST`, `SMTP_PORT`, `SMTP_USER`, `SMTP_PASSWORD`,
    `SMTP_FROM`, `SMTP_TLS`).
  - Without `SMTP_HOST`, e-mail features are off: `GET /server/features` answers `{ email: false }`,
    and the app hides the e-mail field and "Forgot password".
  - Locally `docker compose` runs Mailpit (`axllent/mailpit`), SMTP on 1025 and the web UI on
    `127.0.0.1:8025`.
  - Mails are plain text, in German and English.

## 5. Privacy policy

- German text (`Datenschutzerklärung`) with placeholders `[Name des Betreibers]`, `[Anschrift]` and
  `[E-Mail-Adresse]`. It describes exactly what the server stores:
  - account (username, password hash, optional e-mail);
  - tokens;
  - games, seats and saves;
  - chat (90 days);
  - friends and blocks;
  - reports;
  - IP addresses only in rate limits and logs, at most 7 days;
  - no tracking, no ads, no third parties apart from the hosting and the SMTP provider.
- It also covers the legal bases, the rights (access, erasure via "Delete account", complaint to a
  supervisory authority), the minimum age of 16, and the location of the server.
- Bundled in the app as an asset and shown:
  - in the register form as a required checkbox "I have read the privacy policy" with a link;
  - in the Online menu as "Privacy policy";
  - in the Online screen footer.
- A copy is in the server repo as `PRIVACY.md`, for the website later.
- Not legal advice: the docs say a lawyer should check it before the public launch.

## Protocol

v3 is not released yet. The new fields and types extend v3 and its golden file; v1 and v2 stay
unchanged.

## Testing

- **Server (Testcontainers):**
  - map download with a fake HTTPS source: success, too large, zip-slip, concurrent creates;
  - seat edit rights;
  - ready reset on change;
  - private chat membership, blocks and unread counts;
  - the e-mail confirm and reset flow against a fake SMTP sink, with rate limits, wrong-code limit,
    token revocation and no account enumeration.
- **online-client:** the new REST calls.
- **App, on the emulator:**
  - create a game on a non-bundled map (download it on the server);
  - the setup screen with two users (free seat taken, ready, start);
  - the chat bar;
  - the friends screen with private chat and unread counts;
  - register with e-mail, confirm via Mailpit, forgot password, login with the new password;
  - the privacy policy screen.
