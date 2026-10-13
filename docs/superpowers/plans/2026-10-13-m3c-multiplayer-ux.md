# Milestone 3c: Multiplayer UX Round 2 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The online mode gets six things:
- every installed map, which the server downloads on demand;
- a setup screen like single player, with free slots, player names and a chat bar;
- a nicer friends screen with private chat;
- an optional e-mail with password reset;
- a privacy policy.

**Architecture:**
- **Server (`triplea-mobile-server`):**
  - `MapCatalog` downloads maps on demand.
  - Lobby edit routes.
  - `Mailer`, plus e-mail and reset in `Accounts`.
  - A DM channel in `Chat`, with read markers.
- **Protocol:** v3 (unreleased) is extended.
- **App:**
  - `OnlineSetupScreen` replaces the create dialog and the lobby card.
  - The Friends tab is redesigned, and a DM chat screen is added.
  - Account screens and the privacy policy screen.

**Tech Stack:** as M3b, plus `org.eclipse.angus:angus-mail` (Jakarta Mail) on the server and the Mailpit
container locally.

**Spec:** `docs/superpowers/specs/2026-10-13-multiplayer-ux-design.md`. Approved mockups:
`.superpowers/brainstorm/*/content/friends-layout.html` (Variante A) and `setup-online.html`
(the friends screen on the left, setup Variante 2).

## Global Constraints

- The M2b–M3b constraints apply:
  - no git;
  - app/src and engine/src edited with Edit/Write only, never scripts;
  - the build/device skills;
  - a device check for every app/src/main change;
  - debug-only online mode;
  - no secrets in files;
  - LF line endings;
  - Keep the release build unchanged: the merged release manifest has no new permissions.
- Protocol: extend v3 and its golden file; v1 and v2 stay unchanged.
- Map downloads: only the fixed list URL from the spec, HTTPS only, redirects only to HTTPS, at most
  300 MB per zip, zip-slip safe.
- E-mail: optional. Without `SMTP_HOST` every e-mail feature is off (`GET /server/features`). No account
  enumeration: forgot-password always gives the same answer.
- Mails and the privacy policy: German and English text for mails; the privacy policy in German with
  placeholders.
- App UI text stays English, like the rest of the app. The privacy policy is shown in German, with an
  English heading.

## Review Focus

1. **A client must not make the server fetch arbitrary URLs.** Map names resolve only through the fixed
   list.
2. **Seat edits.** Only the creator edits, only in `lobby`, and every change resets ready. A free seat
   taken by two users at once goes to exactly one of them.
3. **Private chat.** Non-friends cannot send. A block hides the blocked user's new messages from the
   blocker. Unread counts are per user.
4. **Password reset.**
   - The code is single use, expires, and dies after 5 wrong tries.
   - Rate limits apply.
   - All tokens are revoked after a reset.
   - The same answer for unknown users.
5. **The setup screen.** Every participant switches into the game on start. Nobody gets stuck on the
   setup screen.

---

### Task 1: Server — maps on demand

- `MapCatalog` (server):
  - parse the YAML list (snakeyaml is already on the desktop classpath; otherwise add snakeyaml-engine);
  - find an entry by map name, normalising case, spaces, `_` and `-` like `InstalledMapsListing`;
  - download its zip (HTTPS only, at most 300 MB) into `<dataDir>/maps/<name>/` with `Engine`'s unpack
    logic (reuse, don't copy);
  - refresh `Engine.installedGames()`.
- One download per map name at a time; others wait on the same future.
- Tests use a local HTTPS-free fake: inject the list URL and the HTTP client through constructor
  parameters, so `http://` is allowed only in tests.
- `POST /games` for a missing map: create the `lobby` game with `mapReady = false`, start the download in
  the background, then send `LobbyUpdate(mapReady = true)` or abort the game with the error.
  - Validate seats against the nations once the map is installed.
  - Ready returns 409 "Map is still loading" while `mapReady` is false.
- `GameInfoDto.mapReady` (v3).
- Tests:
  - a missing map is downloaded and the game becomes ready;
  - an unknown map name gives 400;
  - an oversize zip and a zip-slip entry are refused;
  - two concurrent creates download once;
  - a nation mismatch aborts the game.

### Task 2: Server — lobby edits, private chat, friends activity

- `PUT /games/{id}/seats` (creator, `lobby` only): the list of `SeatDto`, with kinds HUMAN with username
  (invite), OPEN, the AI kinds and DOES_NOTHING.
  - The creator's own seats are HUMAN with the creator's name.
  - It validates nations, resets every ready flag and sends `LobbyUpdate`.
- `PATCH /games/{id}` (creator, `lobby` only): visibility, time control and options (`aiTakesOver`,
  `resignAfterMissedTurns`, `allowSpectators`). It resets ready and sends `LobbyUpdate`.
- Take seat: the existing join into an OPEN seat. Give back: the existing leave. Both reset ready? No:
  join and leave reset only the affected seat's ready (as today).
- Private chat:
  - Channel `dm:<a>:<b>` with a < b user ids. Send only between accepted friends; read for both parties
    always.
  - Block rules as in the spec.
  - Migration with `chat_reads(user_id, channel, last_read_id)`.
  - `POST /chat/{channel}/read` (`lastReadId`).
  - `GET /friends` adds `unread`, `activity` and `lastSeen` per friend. `users.last_seen_at` is set when
    the last connection closes.
  - `GET /me/turns` adds `unreadDirect: Int`; the response becomes an object if needed (v3 unreleased).
- Tests: Review Focus 2 and 3, `activity` for a friend in a running game, and the unread count rising
  and falling.

### Task 3: Server — e-mail, password reset, features, privacy text

- Migration:
  - `users.email` (unique on lower(email), nullable) and `users.email_confirmed`;
  - `email_codes(user_id, purpose 'confirm'|'reset', code_hash, email, expires_at, tries, created_at)`.
- `Mailer` interface with an SMTP implementation (Angus Mail) and a `RecordingMailer` for tests.
  Configured from env; null when `SMTP_HOST` is missing.
- Routes:
  - `GET /server/features` → `{email: Boolean}` (no auth).
  - `POST /account/email {email}`: auth, sends a confirm code.
  - `POST /account/email/confirm {code}`: auth.
  - `POST /auth/forgot {login}`: no auth, always 204.
  - `POST /auth/reset {login, code, newPassword}`: returns `TokensDto` and revokes all old tokens.
  - `RegisterRequest.email` is optional; when given, the confirm mail is sent after registration.
- Limits: 3 mails per account per hour, 10 per IP per hour, 5 tries per code, 15-minute expiry. Codes are
  6 digits from `SecureRandom`, stored as SHA-256.
- `docker-compose.yml`: a `mailpit` service, with SMTP 1025 on the internal network and the UI on
  `127.0.0.1:8025`. `.env.example` gets the SMTP vars pointing at mailpit. README section.
- `PRIVACY.md` in the server repo: the German text from spec section 5, with placeholders.
- Tests: Review Focus 4 end to end with `RecordingMailer`, confirm and change of the e-mail, the unique
  e-mail rule, and features with and without SMTP.

### Task 4: App — online setup screen

- `online-client`: the new calls (`putSeats`, `patchGame`, features, the new DTO fields).
- New `OnlineSetupScreen` per the approved Variante 2:
  - map card plus "Change map" (installed games, same list as single player);
  - chips (visibility, time control, More);
  - nation rows with the SetupScreen dropdown style and the entries from the spec;
  - Ready / Leave / Delete;
  - a collapsible chat bar (`ChatPanel` in a bottom sheet with the last message and an unread count);
  - "Map is loading on the server…" while `mapReady` is false.
- Opened:
  - by "New online game", which creates the game with defaults (first nation = me, others Fast AI,
    visibility friends) and then edits it live;
  - from the lobby and games lists for lobby games.
- When the game becomes `running`, every participant on the screen calls `startOnline` and navigates to
  the game.
- Remove the old create dialog and the lobby game card actions they replace.
- Device check with two users (emulator plus curl or a second emulator):
  - a free seat is taken;
  - ready, then the game starts;
  - the chat bar works;
  - a non-bundled map downloaded in the map browser and used online.

### Task 5: App — friends screen and private chat

- Friends tab per Variante A plus the floating "＋ Add friend" button (mockup): sections, avatar with
  status dot, activity line, unread badge, chat button, ⋮ menu (Remove, Block, Report).
- `DirectChatScreen` (route `chat/{username}`): `ChatPanel` for `dm:` with mark-read on open and on new
  messages while open.
- `Social` tracks `dm:` messages and unread counts. `TurnCheckWorker` also notifies about unread private
  messages ("New message from bob").
- Device check: alice and bob (second emulator or curl as bob):
  - a message arrives and the unread badge shows;
  - opening the chat clears it;
  - a block stops new messages.

### Task 6: App — account, e-mail, forgot password, privacy policy, docs

- The register form gets an optional e-mail (shown only if `features.email`) and the required checkbox
  "I have read the privacy policy" with a link.
- "Forgot password?" under login opens a two-step screen: login → code + new password.
- Online menu "Account":
  - e-mail add or change with code confirmation;
  - change password (`POST /account/password {old, new}` on the server, small route added here with a
    test);
  - delete account (moved here).
- `PrivacyScreen` shows the bundled asset `privacy_de.md` (rendered as plain text with headings) and is
  reachable from register, the Online menu and the Online screen footer.
- Docs:
  - `docs/DEVELOPMENT.md` online section and known gaps;
  - `docs/online-test-checklist.md` (Mailpit steps);
  - server README (Mailpit, SMTP vars, maps on demand).
- Device check:
  - register with e-mail;
  - read the code in Mailpit (`http://localhost:8025`, or its API through curl);
  - confirm it;
  - log out, then Forgot password, then the code from Mailpit, a new password and login;
  - the privacy screen.
