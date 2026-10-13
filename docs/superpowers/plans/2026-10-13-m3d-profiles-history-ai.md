# Milestone 3d: Profiles, History, Chess Clocks, AI Limits Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax.

**Goal:** The online mode gets four things:
- chess clocks in the game and lobby lists;
- no single-human online games;
- profiles with icon, status, stats and recent games, plus a replay of finished games;
- a server that keeps AI turns in check under load.

The 4 bugs from the two-emulator test are fixed along the way.

**Spec:** `docs/superpowers/specs/2026-10-13-profiles-history-ai-limits-design.md`

## Global Constraints

- **Earlier constraints still apply.** Those are the M2b–M3c constraints:
  - no git;
  - app/src and engine/src change only through Edit/Write, never scripts or heredocs;
  - load the build/device skills first;
  - always run adb with `-s emulator-5554` or `-s emulator-5556` (the user's phone is connected too);
  - a device check for every app/src/main change;
  - online mode stays debug-only;
  - no secrets;
  - LF line endings;
  - the release manifest stays unchanged.
- **Protocol:** extend v3 (it is unreleased) and its golden file. Leave v1 and v2 untouched.
- **Port 8080** may be held by a stale server the user started: use port 8090 for your own runs and
  never kill other processes.
- **Icon allow-list:** one source of truth, a text file in the server repo that the app bundles a copy
  of. Keep the two copies byte-identical, as with PRIVACY.md.

## Review Focus

1. **AI queue and timeout.**
   - A permit is always released, including on exceptions and stops.
   - A timed-out AI turn reloads cleanly, with no lost or duplicated question.
   - The queue cannot deadlock the game thread against a hand-over or replaceSeat.
2. **Unload and reload.**
   - An unloaded game reloads on every entry path: hello, answer, clock expiry, REST.
   - Nothing operates on a stopped `HostedGame`.
3. **Replay access** follows the visibility rules. Retention deletes only saves.
4. **Profile input:** the icon is checked against the allow-list, and the status is trimmed, has no
   control characters and is at most 60 code points.
5. **Two-human rule:** Ready is refused with fewer than 2 human players, and the app explains why.

---

### Task 1: Server — clocks in game info, two-human rule, profiles, stats, replay, friends push, B2
- `GameInfoDto`: `turn`, `turnRemainingMs`, `turnDeadline`, `turnSince` (spec 1).
- Two-human rule (spec 2), with tests.
- Profiles:
  - migration adds `icon` and `status`;
  - `GET /users/{u}/profile` and `PUT /account/profile`;
  - stats query;
  - recent games;
  - icon allow-list file `server/src/main/resources/profile-icons.txt` (build it from the engine flags and
    the bundled maps' unit images: list the actual files in the app's assets zips).
- Replay route plus the 365-day retention in the daily job, and a PRIVACY.md sentence. Keep the app's
  `privacy_de.md` byte-identical: copy it with the Write tool.
- B4: `FriendsChanged(username)` message to both parties on every friendship change.
- B2: in mobile `online/RemoteHumanUi.confirmCasualties`, return without asking (the host answers). Run
  mobile `:online:test`.
- Tests for each point. Run `:server:test` and mobile `:protocol:test :online:test :online-client:test`.

### Task 2: Server — AI limits and capacity (spec 5)
- AI queue: a permit-wrapping Player for AI seats in `HostedGame`, adaptive move pause, env config.
- Turn timeout:
  - a watchdog stops the game;
  - it resumes from the snapshot with that nation as FAST_AI until the nation's next turn start;
  - a timed-out Fast AI turn aborts the game.
- Unload and reload: a lazy `hosted(id)` load path on every entry point, idle unload, `MAX_LOADED_GAMES`,
  503 "Server busy".
- Crash isolation: resume up to 3 times within 10 min.
- `GET /admin/status`.
- Load test task `-Pload` (excluded from normal `:server:test`). Run it once with e.g. 4 World War II
  Classic AI-only games and record turn times and heap in the README.
- Tests:
  - the permit is released on exception;
  - the timeout reloads with FAST_AI (use a test AI that blocks);
  - unload and reload on hello and answer;
  - the crash resume limit.

### Task 3: App — chess clocks in lists, two-human hint, profiles, replay, B1, B3, minors
- `online-client`: new calls and DTO fields; handle `FriendsChanged` by reloading friends.
- Games and lobby cards show the chess clock (spec 1) and count down locally.
- Setup screen: the two-human hint, with Ready disabled; a "Settings changed – press Ready again" note.
- `ProfileScreen`:
  - own profile: an icon picker grid from the allow-list rendered with the app's images, plus a status
    field;
  - other players' profiles: stats and recent games with "Replay";
  - opens from the Online menu "Profile" and from names in friends, lobby, setup and chat.
- Replay: download `/games/{id}/replay` and open it in the existing read-only history view.
- B1: the clock bar, takeover banner and reconnect chip sit above the battle window. B3: an unread badge
  on the in-game chat.
- Minors: the quit dialog wording online, the final round on finished cards, the "code sent" hint after
  registering.
- Device check on both emulators against a local server (port 8090).

### Task 4: Docs and a two-emulator end-to-end run
- `docs/DEVELOPMENT.md` (online section, known gaps), `docs/online-test-checklist.md`, server README
  (env vars, admin status, load-test numbers).
- A two-emulator run of the new features:
  - profile edit and view;
  - a replay of a finished game (an AI win on the minimap);
  - the clocks in the lists;
  - the two-human rule;
  - the friends push;
  - B1 and B3.

  Screenshots and a report in `.superpowers/sdd/e2e-m3d/`.
