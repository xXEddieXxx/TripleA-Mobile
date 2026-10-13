# Milestone 3a: Lobby, Friends, Chat Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Players find each other without typing usernames into a create form:
- friends and presence;
- public, friends-only and link games with open seats, joining and "Ready";
- lobby and game chat with block and report;
- account deletion and the age confirmation.

The game starts only when every seat is filled and every human pressed Ready.

**Architecture:**
- **Server** (`triplea-mobile-server`, built in milestone 2b):
  - migration `V2`;
  - new services `Friends`, `Lobby` and `Chat` next to `GameService`;
  - REST routes in `Http.kt`;
  - `Connections` (2b) gets per-user channels for presence, chat and lobby updates.
- **Protocol** (this repo): new WebSocket messages `ChatSend`, `ChatMessage`, `Presence` and
  `LobbyUpdate`, plus REST types. This bumps `PROTOCOL_VERSION` to 2: add golden file v2, keep v1.
- **App:** the Online screen (2c) becomes tabs (Games, Lobby, Friends). A chat panel sits on the
  lobby tab and in the online game screen.

**Tech Stack:** as milestones 2b and 2c.

**Spec:** `docs/superpowers/specs/2026-10-12-multiplayer-lobby-design.md`:
- section 3: "Creating a game" (visibility, Ready, locking), "Chat";
- section 4: "Accounts" (minimum age 16, account deletion, rate limits);
- section 2: "Messages" (`Presence`, `Chat`, `LobbyUpdate`).

**Prerequisites:** milestones 2b and 2c are complete (their ledgers say so). Read
`triplea-mobile-server/README.md` and the "Online play" section of `docs/DEVELOPMENT.md` first.

## Global Constraints

- The milestone 2b/2c global constraints apply unchanged:
  - no git;
  - Edit/Write only under `engine/src` and `app/src`;
  - skills before Gradle and adb;
  - device check for app changes;
  - debug-only online entry;
  - no secrets.
- Protocol change rule (`docs/DEVELOPMENT.md`, "Changing a message"): bump to
  `PROTOCOL_VERSION = 2` and add `protocol-v2.json`; `protocol-v1.json` stays as it is. Server and
  app ship together.
- Text limits:
  - chat messages 1 to 500 characters, trimmed;
  - report reasons up to 1000;
  - usernames as in 2b.
- Chat rate limit: at most 5 messages per user per 10 seconds; more are dropped with a
  `Notice`-style error message.
- Chat is kept 90 days. A daily job deletes older rows.
- Blocking hides the blocked user's chat from the blocker and stops their friend requests and
  invites to the blocker.
- "Public lobbies need a verified e-mail or Google sign-in" needs mail delivery, which does not
  exist yet. It is recorded as a known gap. Public games are allowed for every account, and the
  README says so. Ruling, cost: spam risk before launch, acceptable for a local or friends-only
  server.
- Spectators are out of scope here; they move to milestone 3b's backlog.

## Review Focus

1. **Seat races.** Two users join the same open seat at the same moment. Exactly one gets it
   (database constraint plus a conditional update). Test in Task 2.
2. **A game must not start with an empty or un-ready human seat.** Leaving after Ready resets the
   start check. Test in Task 2.
3. **The visibility leak.** A friends-only game must not appear in a stranger's `GET /lobby`, and a
   link game needs the invite code to join. Test in Task 2.
4. **Blocked users.** A blocked user's messages never reach the blocker, live or in history.
   Test in Task 3.
5. **Deleting an account.**
   - Seats in running games turn into AI seats.
   - Chat rows stay with the author shown as "deleted user".
   - Tokens are gone.
   - Test in Task 1.

---

### Task 1: Friends, blocks, reports, account deletion (server + protocol)

**Files:**
- Server: `server/src/main/resources/db/migration/V2__social.sql`.
  ```sql
  create table friends (
    user_id bigint not null references users on delete cascade,
    friend_id bigint not null references users on delete cascade,
    status text not null,                 -- pending (user_id asked friend_id) | accepted
    created_at timestamptz not null default now(),
    primary key (user_id, friend_id)
  );
  create table blocks (
    user_id bigint not null references users on delete cascade,
    blocked_id bigint not null references users on delete cascade,
    primary key (user_id, blocked_id)
  );
  create table reports (
    id bigserial primary key,
    reporter_id bigint references users on delete set null,
    reported_id bigint references users on delete set null,
    chat_message_id bigint,
    reason text not null,
    created_at timestamptz not null default now()
  );
  alter table users add column deleted_at timestamptz;
  alter table users add column age_confirmed boolean not null default false;
  ```
  Accepted friendships are stored once per direction pair. Implement "are friends" as an
  `accepted` row in either direction.
- Server: `org.triplea.server.social.Friends` and its tests.
- Server: register now requires `ageConfirmed = true` in `RegisterRequest`, otherwise 400 "You must
  be 16 or older".
- Server: `DELETE /account`, with the password in the body:
  - sets `deleted_at`;
  - anonymises the username to `deleted-<id>`;
  - deletes the password hash (set it to an unusable marker) and all tokens;
  - turns the user's HUMAN seats in running games into `FAST_AI`.

  `GameService` needs `replaceSeat(gameId, nation, kind)`. It does save, stop and resume with the
  new seat kinds, at a question point. If the game thread is waiting on that seat's question, the
  wait ends with `GameOverException` for that `HostedGame`, which is stopped and resumed. Reuse the
  2b recovery path.
- Protocol `Rest.kt` (v2):
  - `FriendDto(username, status, online)`, `FriendRequest(username)`, `BlockRequest(username)`,
    `ReportRequest(username, reason, chatMessageId?)`, `DeleteAccountRequest(password)`;
  - `RegisterRequest` gets `ageConfirmed: Boolean`.
- Routes:
  - `GET /friends`: accepted friends plus pending requests both ways, with `online` from
    `Connections`;
  - `POST /friends/requests`;
  - `POST /friends/{username}/accept`;
  - `DELETE /friends/{username}`;
  - `POST /blocks`, `DELETE /blocks/{username}`, `GET /blocks`;
  - `POST /reports`;
  - `DELETE /account`.
- WebSocket: `Presence(username, online)` goes to every online accepted friend when a user's first
  connection opens or their last one closes.

- [ ] Step 1: Write tests (Testcontainers), then implement. The tests cover:
  - friend request, accept and remove;
  - a blocked user's request is refused, and blocking removes an existing friendship;
  - a report is stored;
  - register without `ageConfirmed` gives 400;
  - account deletion as in Review Focus 5. Use a running minimap game where the deleted user's seat
    becomes AI and the game continues.
- [ ] Step 2: Bump the protocol to v2 (golden file v2, v1 kept). Run `:protocol:test`
  (mobile) and `:server:test` (server); both green.

### Task 2: Lobby games (server + protocol)

**Files:**
- Server: migration `V3__lobby.sql`:
  - `games.visibility` text: `public` | `friends` | `link`;
  - `games.invite_code` text, null unless `link`;
  - `seats.ready` boolean default false;
  - `games.status` gains `lobby`.

  A unique constraint is not needed: a seat row exists per nation, and join is
  `update seats set user_id = ? where game_id = ? and nation = ? and kind = 'HUMAN' and user_id is null`.
  One updated row means success.
- Server: `org.triplea.server.lobby.Lobby` and its tests.
- `CreateGameRequest` (v2):
  - gets `visibility`;
  - seat kind `OPEN` means a HUMAN seat without a user;
  - HUMAN seats with a username are invitations: filled, not ready.

  The creator's own seat is filled and not ready.
- The game is created in status `lobby`, and no `HostedGame` runs yet.
- Routes:
  - `GET /lobby`: lobby games the caller may see: public ones, friends-only ones whose creator is an
    accepted friend, and games the caller already holds a seat in. Each with its open seats.
  - `POST /games/{id}/join` (`JoinRequest(nation, inviteCode?)`):
    - only into an `OPEN` seat;
    - visibility rules apply (friends: the creator is a friend; link: the code matches);
    - a user may hold several nations of the same game.
  - `POST /games/{id}/leave` (`LeaveRequest(nation)`): the seat becomes `OPEN`. The creator cannot
    leave; they delete the lobby game with `DELETE /games/{id}`, allowed only while in `lobby`.
  - `POST /games/{id}/ready` (`ReadyRequest(ready)`): sets all of the caller's seats in that game.
    - When every HUMAN seat has a user and is ready, the game starts: status `running`,
      `HostedGame.start`, exactly once. Guard it with a conditional status update from `lobby` to
      `running`.
    - From then on seats and options are locked: join, leave and ready return 409.
- WebSocket: `LobbyUpdate(game: GameInfoDto)` goes to every user who can see the game in the lobby
  when it is created, changed, started or deleted (`deleted` flag). Keep it simple: compute the
  audience per change.
- `GameInfoDto` gets `visibility`, `inviteCode` (only for seat holders) and per-seat `ready`.

- [ ] Step 1: Write tests, then implement. The tests cover:
  - Review Focus 1: two threads join the same seat, and exactly one wins;
  - Review Focus 2: start only when everyone is ready, a leave resets, and the game starts exactly
    once under concurrent ready calls;
  - Review Focus 3: visibility for a friend, a stranger and a holder of the link code;
  - `LobbyUpdate` reaches a connected friend.
- [ ] Step 2: Update the 2b tests that create games directly. A test helper creates a lobby game
  and readies every seat, so the running-game tests stay as they were. Run `:server:test`; green.

### Task 3: Chat (server + protocol)

**Files:**
- Server: migration `V4__chat.sql`.
  ```sql
  create table chat_messages (
    id bigserial primary key,
    channel text not null,          -- 'lobby' | 'game:<id>'
    user_id bigint references users on delete set null,
    text text not null,
    sent_at timestamptz not null default now()
  );
  create index chat_messages_channel_id on chat_messages (channel, id desc);
  ```
- Server: `org.triplea.server.chat.Chat` and its tests. The retention job is a scheduled task in
  `Main` (daily) that deletes rows older than 90 days.
- Protocol (v2), WebSocket:
  - `ChatSend(channel, text)` from client to server;
  - `ChatMessage(id, channel, username, text, sentAt)` from server to client.
- Membership:
  - `lobby` is everyone connected;
  - `game:<id>` is the users holding a seat.

  Anything else is refused.
- Delivery:
  - store, then send to every member's connections, except members who block the sender;
  - the sender gets their own message back as confirmation;
  - the rate limit is in Global Constraints.
- REST: `GET /chat/{channel}?before=<id>&limit=<=50`, newest first, with the same membership and
  block filtering.
- Quick messages are client-side preset texts; the server sees ordinary text.

- [ ] Step 1: Write tests, then implement. The tests cover:
  - membership is enforced;
  - Review Focus 4, live and in history;
  - the rate limit;
  - the retention job deletes old rows (insert a row with an old `sent_at`).
- [ ] Step 2: Run `:server:test`; green.

### Task 4: App: lobby, friends, chat, account

**Files:**
- `online-client` (`ServerApi`): the new REST calls. `GameConnection` becomes a per-user
  `OnlineConnection` that is open while the Online screen or an online game is shown.
  - It routes `ChatMessage`, `Presence` and `LobbyUpdate` to `StateFlow`s.
  - Game messages still go to the open game's handler.
  - Keep `GameConnection`'s 2c behavior. If splitting it is too invasive, a second WebSocket for
    the social messages is acceptable; record the ruling.
- App `OnlineScreen` gets tabs:
  - **Games:** my running and finished games, as in 2c, plus my lobby games with seats, Ready
    toggles and "Leave"/"Delete".
  - **Lobby:** visible lobby games with open seats, "Join" (with a nation picker), "Join by code",
    and the lobby chat underneath.
  - **Friends:** friends with online dots, pending requests (accept or decline), "Add friend",
    blocked users (unblock).
  - **Create dialog:** a visibility choice, and the seat choices gain "Open seat". The invite code
    is shown with a copy button for link games.
- Online game screen: a chat button that opens a bottom sheet with the `game:<id>` chat and quick
  messages ("Good luck", "Well played", "Thanks", "One moment").
- Long-pressing a chat message offers "Block user" and "Report".
- Register form: the checkbox "I am 16 or older", required.
- Online screen menu: "Delete account", with a password confirmation dialog.
- [ ] Step 1: Write `online-client` tests (MockWebServer) for the new calls and the message
  routing.
- [ ] Step 2: Implement the app screens.
- [ ] Step 3: Device check on the emulator against the local server (`--seed` users):
  - alice creates a friends-only game with an open seat;
  - bob is alice's friend; log in as bob through the API with curl, or on a second emulator;
  - bob joins and both press Ready, so the game starts;
  - chat in the lobby and in the game, then block and check the message is hidden.

  Screenshot each screen.
- [ ] Step 4: Docs: the `docs/DEVELOPMENT.md` online play section, the server README API list and
  known gaps (e-mail verification), and `docs/online-test-checklist.md`.
