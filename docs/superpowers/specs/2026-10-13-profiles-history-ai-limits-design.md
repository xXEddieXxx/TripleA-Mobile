# Profiles, game history, chess clocks, AI limits – design

Date: 2026-10-13 · Branch: `multiplayer-beta` · Status: approved in chat ("yes that fits, go ahead")

Builds on milestones 2b–3c (server `triplea-mobile-server`, app online mode, debug-only).

## 1. Chess clocks in the lists

- `GameInfoDto` gains these fields (protocol v3, unreleased, extended):
  - `turn`: who is to move. It holds `{username?, nation, ai: Boolean}`, or null while in the lobby or after
    the end.
  - `turnRemainingMs` and `turnDeadline`: the remaining time of the player whose turn it is. They are
    null without a time control.
  - `turnSince`: when the current turn started.
- The server fills these from the open question, the current step's player and `Clocks`, for running
  games in `GET /games` and `GET /lobby` and in `LobbyUpdate`.
- The app shows them on each card:
  - "Your turn · 4:12" when the user is to move;
  - "Waiting for bob · Germans · 2:03";
  - "AI moving (Germans)" during an AI turn;
  - "Waiting for bob · since 2 h" when the game has no clock.

  It counts down locally from `turnDeadline` and refreshes on `Clock` and `LobbyUpdate`.

## 2. No single-player online games

- Ready (and therefore the start) is refused while fewer than 2 seats hold human players. The answer is
  409 "At least 2 human players needed – play against the AI offline".
- The setup screen shows this hint and disables Ready.
- Running games are not affected: resignations or AI takeovers can leave one human, and that is fine.

## 3. Profiles

- Each user has an `icon` (an id from a fixed set) and a `status` (0–60 characters, trimmed, no control
  characters).
- **Icon set:** nation flags and unit images that ship with the app (engine assets plus the bundled maps).
  - Ids look like `flag:Germans` or `unit:fighter`.
  - The server checks ids against an allow-list copied from the app; unknown ids give 400.
  - The default is a neutral icon.
- **Endpoints:**
  - `GET /users/{username}/profile` → `ProfileDto(username, icon, status, stats, recentGames ≤ 10)`.
    Any logged-in user may call it.
  - `PUT /account/profile {icon?, status?}`.
- **Stats**, computed from the seats of finished games:
  - games played, won, lost, resigned, abandoned (aborted after start);
  - win rate;
  - most played map;
  - most played nation.
- **Deleted users** have no profile (404).

## 4. Game history

- Finished games keep their final snapshot.
- `GET /games/{id}/replay` returns the final save. It is allowed for participants and for any logged-in
  user whose visibility rules would have let them see the game (public: anyone; friends: friends of the
  creator; link: participants only).
- **Retention:** a daily job deletes final saves of games finished more than 365 days ago. Stats and the
  game rows stay.
- The privacy policy gets one sentence about this, in both copies.
- **App:** "Replay" loads the save into the existing local history view (`HistoryReplay`), read-only.

## 5. AI limits and capacity

- **AI queue:**
  - Every AI player turn takes a permit from a global semaphore before it starts and gives it back after.
    The number of permits comes from env `AI_MAX_PARALLEL`, default the number of CPU cores.
  - When the queue grows (waiting turns > permits), the AI move pause rises automatically: 0 ms
    normally, up to 500 ms per move under load. This is the "wait timer" for the AI.
- **Turn deadline:** env `AI_TURN_TIMEOUT_S`, default 300 s. When an AI turn exceeds it:
  1. the game is stopped;
  2. it is resumed from the last snapshot with that nation as `FAST_AI` until that nation's next turn
     start;
  3. the event is logged and counted.

  A timed-out Fast AI turn aborts the game with an error.
- **Memory:**
  - At most `MAX_LOADED_GAMES` (default 40) games are loaded.
  - Games idle for `IDLE_UNLOAD_MIN` (default 30 min) are unloaded. Their snapshot is kept, and they are
    reloaded on the next access: a socket hello, an answer, a clock expiry or a REST call that needs the
    engine.
  - When the limit is reached and nothing can be unloaded, creating or starting a game answers 503
    "Server busy".
- **Crash isolation:** an unexpected game-thread crash resumes the game from its last snapshot, up to 3
  times within 10 min, then aborts it with the error. Other games are untouched.
- **Admin:** `GET /admin/status` (DEV_MODE or `ADMIN_TOKEN` header) returns loaded games, queue length,
  running AI turns, heap use and the slowest AI turns.
- **Load test:** a Gradle task/test (`-Pload`) runs N big AI-only games and reports the turn times and
  heap use. The README gets the numbers measured on the dev PC.

## 6. Fixes from the two-emulator test

- **B1:** the clock bar, the AI-takeover banner and the reconnect chip are drawn above the battle window.
- **B2:** `Ask.ConfirmCasualties` is answered by the host without asking the phone, so the online battle
  never waits on it. The local "pause after casualties" setting stays local-only.
- **B3:** an unread badge on the in-game chat button.
- **B4:** a new `FriendsChanged` socket message to both users on request, accept, decline, remove and
  block. The app reloads the friends list.
- **Minor fixes:**
  - the quit dialog has online wording;
  - finished or aborted games show their final round;
  - after registering with an e-mail: "A confirmation code was sent to …";
  - after a ready reset: "Settings changed – press Ready again".
