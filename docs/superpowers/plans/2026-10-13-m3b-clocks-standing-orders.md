# Milestone 3b: Clocks, Standing Orders, Turn Notifications Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Online games keep moving without everyone being online at once:
- every human has a clock;
- an expired clock ends the phase, and the AI takes over the seat;
- three missed turns count as resignation;
- questions outside one's turn are answered by standing orders when the defender is away;
- the app tells the player when it is their turn.

**Architecture:**
- **Server:**
  - `Clocks`: time controls, one clock per human per game, and a scheduler.
  - `StandingOrders`: preset answers per game and user, plus the defender mode.
  - Both hook into `GameLink.ask` (2b): before a question is sent, a standing order may answer it;
    while it is open, the owner's clock runs.
  - Seat takeover by the AI and the return reuse the 2b save/resume path:
    `GameService.replaceSeat`, built in 3a.
  - Dev-only time travel moves clocks forward.
- **Protocol (v3):**
  - `Clock(gameId, clocks, running, deadline)` and the time control in REST types;
  - standing order REST types;
  - `GET /me/turns` for polling.
- **App:**
  - a clock bar in the online game screen;
  - a "Standing orders" sheet per game;
  - time control in the create dialog;
  - a WorkManager job (every 15 minutes) that polls `GET /me/turns` and shows a notification.

**Tech Stack:** as before, plus `androidx.work:work-runtime-ktx` in the app.

**Spec:** `docs/superpowers/specs/2026-10-12-multiplayer-lobby-design.md`:
- section 3: "Creating a game" (time control), "Clocks", "Out-of-turn questions";
- section 5: "Time travel";
- section 6: server tests 3 (clocks) and 4 (standing orders);
- "Out of scope for v1": push (v1 polls with WorkManager).

**Prerequisites:** milestone 3a is complete.

## Global Constraints

- The 2b/2c/3a constraints apply.
- Protocol bump to v3, with golden file v3; v1 and v2 stay.
- Time controls (spec):
  - **blitz:** 3 to 10 minutes base plus a 0 to 30 s increment per question answered;
  - **rapid:** 15 to 60 minutes plus 0 to 60 s;
  - **correspondence:** 1 to 14 days per turn. The clock resets to the full amount at the start of
    each of the player's turns, and there is vacation mode.
    - Vacation: at most 14 days per game. Clocks of a player on vacation do not run, and their
      seats' questions wait.
- One clock per human player per game, also when they play several nations (spec).
- A clock runs only while a question for one of that player's seats is open:
  - in their own turn, any question;
  - out of turn, only in hybrid mode's short window.
- On expiry (spec "Clocks"):
  1. The current question is answered with the phase-ending reply: `Reply.Done` for purchase,
     move, place, tech, politics and the end turn.
     - Questions without a Done (casualties, retreat, scramble, battle choice) get the standing or
       default answer below.
     - `Ask.Battle` (choose battle) has no default, so the seat goes straight to the AI (step 2).
  2. From the next question of that seat on, the AI plays the seat, through
     `GameService.replaceSeat(game, nation, FAST_AI)`, until the player comes back. Coming back
     means the player opens the game or sends any message for it. The seat is then switched back
     at the next question point of another seat, or at the start of their next turn.
  3. After 3 missed turns in a row the player is resigned: their seats stay AI for good and
     `seats.resigned = true`.

  Steps 2 and 3 are host settings in `CreateGameRequest`: `aiTakesOver` (default true) and
  `resignAfterMissedTurns` (default 3; 0 means never).
- Default answers (the engine defaults the spec asks for, as `HumanPlayerUiAdapter` gives them):
  - casualties: the engine's default selection (`Ask.Casualties` carries it; check `Ask.kt`);
  - retreat: none;
  - scramble: none;
  - kamikaze: none;
  - `AcceptAction`: no;
  - `Confirm`: yes;
  - `ConfirmCasualties`: ok.

  Implement this once, as a server-side function `defaultReply(ask: Ask): Reply?`, and test it per
  type.
- Standing orders (spec examples):
  - casualties: `default` | `cheapest first` | `aircraft last`, implemented by ordering the
    candidate units by cost or by "is air";
  - scramble: `never`;
  - political and user-action offers: `decline` | `ask`;
  - retreat: `never`.

  With no order set, the default answer applies.
- Defender mode per user and game: `live` | `hybrid` (default) | `standing`.
  - **Live:** always ask.
  - **Hybrid:** ask if the player has a connection open, with a 60 s window on their own clock;
    otherwise, or when the window runs out, the standing order or default answers.
  - **Standing:** answer at once.
- Time travel: `POST /admin/clock/advance {gameId, millis}` exists only when `DEV_MODE=true`.
  Without it the route is not even registered. The README says so.
- The scheduler: one thread that wakes at the earliest deadline. It must not block game threads;
  expiry hands an action to the game's link.

## Review Focus

1. **The spec's open point:** can a running phase be ended cleanly from code? Answering `Done` to
   the open question is the mechanism. Test 3 proves the phase ends and the next one runs, on a
   real game.
2. **A clock must never run for two questions at once, or keep running after the answer.** The
   increment is applied once per answered question.
3. **The AI takeover and the human's return must not lose or duplicate the open question.** Test
   on the minimap.
4. **Hybrid mode with a connection that drops during the window.** The standing order applies when
   the window ends; the answer is not lost.
5. **Notification polling must not drain the battery.** It runs only while the user is logged in,
   only on a network connection, and at the 15-minute minimum interval.

---

### Task 1: Time controls and clocks (server + protocol)

- Migration `V5__clocks.sql`:
  - games: `time_control` (json text), `ai_takes_over`, `resign_after`;
  - `clocks`: `game_id`, `user_id`, `remaining_ms`, `running_since`, `missed_turns`,
    `vacation_until`, `vacation_used_ms`;
  - `seats.resigned`.
- `org.triplea.server.clock.Clocks`:
  - start and stop on question open and close;
  - increment;
  - correspondence reset at turn start;
  - persistence on every start and stop, so a restart continues with the stored remaining time;
  - the scheduler.
- `GameLink.ask` (2b): it computes `Question.deadline`, sends
  `Clock(gameId, clocks: Map<username, remainingMs>, running: username?, deadline)` to the
  participants, and handles expiry as in Global Constraints.
- `defaultReply(ask)` with a test per Ask type.
- Time travel route.
- Tests (spec server test 3), with time travel on the minimap:
  - the phase ends cleanly (Review Focus 1);
  - the AI takes over;
  - after 3 missed turns the resignation applies;
  - the increment is applied;
  - the clock survives a server restart.

### Task 2: Standing orders and defender mode (server + protocol)

- Migration `V6__standing_orders.sql`: `standing_orders(game_id, user_id, kind, value)` and
  `seats.defender_mode`.
- REST:
  - `GET /games/{id}/orders`;
  - `PUT /games/{id}/orders` (`StandingOrdersDto`);
  - `PUT /games/{id}/defender-mode`.
- `GameLink.ask` checks for out-of-turn asks, meaning the seat is not the current step's player,
  or the Ask type is one of casualties, retreat, scramble, kamikaze, `AcceptAction` or
  `ConfirmCasualties`. Then it decides per defender mode, and the answer goes through
  `RemoteHumanUi`'s normal validation like any answer.
- Tests (spec server test 4):
  - the defender is offline in hybrid and in standing-only mode, and the order applies instead of
    the engine default;
  - in hybrid mode with the defender online, the question is asked, and the window applies
    (Review Focus 4);
  - an order set to `cheapest first` picks the cheapest units.

### Task 3: App: clocks, orders, notifications

- `online-client`: `Clock` messages go to a `StateFlow`, and there are new REST calls.
- Online game screen:
  - a clock bar: every player's remaining time, the running clock counting down locally from
    `deadline`, and "AI is playing your seat" when the AI has taken over;
  - a "Standing orders" sheet: casualty order, scramble, offers, retreat and defender mode.
- Create dialog: the time control with type, base and increment or days, plus "AI takes over" and
  "Resign after N missed turns".
- Vacation: a button in the online game menu.
- `TurnCheckWorker` (WorkManager):
  - every 15 minutes, network constraint, enqueued while logged in and cancelled on logout;
  - calls `GET /me/turns` and shows one notification per game that newly became "your turn";
  - remembers the last notified question per game in prefs;
  - needs the notification permission (`POST_NOTIFICATIONS`), requested on the Online screen.
- Device check:
  - a blitz game against the local server;
  - time travel through curl;
  - check that the clock bar shows the takeover;
  - set standing orders;
  - run the worker once with `adb shell cmd jobscheduler run`, or a test trigger in a debug menu,
    and screenshot the notification.

### Task 4: Spectators (backlog from 3a)

- `games.allow_spectators` and `spectator_delay_versions` (default 3).
- A spectator:
  - may `GET /games/{id}/save?spectate=true` only for a version at least `delay` behind the latest;
  - gets `GameVersion`s delayed by the same amount and no `Question`s;
  - gets events only after the delay.

  Keep a ring of the last `delay + 1` snapshots per game in a table `snapshot_history`.
- Spectator chat channel `spectate:<id>`, separate from player chat (spec).
- Spec server test 5's "spectator asking for undelayed state" is rejected.
- App: "Watch" on public running games in the lobby; the game screen in read-only mode (no
  `pending` source).
