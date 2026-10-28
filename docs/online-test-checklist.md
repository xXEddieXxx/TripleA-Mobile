# Online play: manual checklist

Run this before each online milestone, with a debug build of the app (release builds have no
Online entry). Commands are for Git Bash on Windows; `ADB` and `EMU` are the SDK tools:

```bash
ADB="$LOCALAPPDATA/Android/Sdk/platform-tools/adb"
EMU="$LOCALAPPDATA/Android/Sdk/emulator/emulator"
```

## 0. Server

In `../triplea-mobile-server` (its README has the details), with JDK 25 on `JAVA_HOME`:

```bash
cp .env.example .env                   # once
docker compose up -d postgres
./gradlew :server:run --args=--seed    # alice, bob, carol with password password123
```

Afterwards: stop the server (Ctrl+C) and run `docker compose down -v` (drops the test data).

In the mobile repo build the debug APK once: `./gradlew :app:assembleDebug`.

## 1. Two emulators against the server

1. Start two AVDs and wait for both (`emulator-5554`, `emulator-5556`):
   ```bash
   "$EMU" -avd <first AVD> -no-window -no-audio -no-boot-anim &
   "$EMU" -avd <second AVD> -no-window -no-audio -no-boot-anim &
   $ADB -s emulator-5556 wait-for-device; $ADB -s emulator-5556 shell getprop sys.boot_completed
   ```
2. Install on both: `$ADB -s <serial> install -r --user 0 app/build/outputs/apk/debug/app-debug.apk`.
3. On each: Home > Online. On Android 17 allow "find, connect to ... nearby devices"
   (`ACCESS_LOCAL_NETWORK`); without it the login times out on `10.0.2.2`.
4. Make alice and bob friends (Friends tab: alice "Add friend" `bob`, bob Accept). Emulator 1:
   log in as `alice`. New online game > Minimap: the setup screen opens with Russians "alice" and
   Italians "Fast AI". Italians' dropdown > "Invite friend…" > bob. Press "Ready".
5. Emulator 2: log in as `bob`. The game is in his Games tab; open it, press "Ready". Both lists change to
   "Round 1" without Refresh (LobbyUpdate); alice's card shows "Your turn", bob's "you: Italians".
   Alice opens the game at Round 1, Russians, Purchase.
6. Alice plays her turn: buy, move, undo one move (the undo list refreshes; "Undo all" is not
   shown), move again, place a unit (the dialog shows how many can be placed), end turn.
7. Bob: Refresh shows "Your turn"; open the game. He sees Alice's moves and placement on the map (and
   the battles of her turn, if she attacked). He plays his turn the same way; Alice's list then shows "Your turn" again.
8. Expected: no crash (`$ADB -s <serial> logcat -d -t 300 | grep -i exception`), both maps agree
   after each turn.

## 2. A phone over Wi-Fi

1. Find the PC's LAN address (`ipconfig`) and allow inbound TCP 8080 in the Windows firewall
   for the private network.
2. Install the debug APK on the phone (`install -r --user 0`, see the `device` skill).
3. Settings > Server URL: `http://<PC LAN address>:8080`. Changing the URL logs out (the stored
   tokens are cleared).
4. Online: allow the local network permission on Android 17, log in as `bob`, open the game
   from step 1 and play a turn against the emulator.

## 3. Disconnect and reconnect

1. While it is your turn, cut the device's network: on the emulator
   `$ADB -s <serial> shell svc wifi disable; $ADB -s <serial> shell svc data disable`, on the
   phone airplane mode.
2. Expected: a red "Reconnecting: ..." banner, and the open question (Undo/Done) disappears.
3. Turn the network back on (`svc wifi enable; svc data enable`).
4. Expected: the banner goes away and the same question is back with the same state; the turn
   plays on. A move made before the drop is still there.
5. Quit to the Online list during "Reconnecting" and reopen the game: it loads normally.

## 4. A server restart during a running game

1. In the middle of a human turn (for example Combat Move with one move made), stop the server
   (Ctrl+C in its terminal).
2. Expected on the device: the "Reconnecting" banner, no question.
3. Start it again: `./gradlew :server:run` (no `--seed` needed; the users are in Postgres).
4. Expected: the banner goes away, the question is asked again from the last save (moves made
   after the last question may be asked again), and the game finishes the round.

## 5. The end of a game

1. Alice: New online game > Minimap; on the setup screen Italians' dropdown > "Hard AI", then
   "Ready": the game opens by itself. Press Done on every question until the AI wins (on minimap that takes a few
   rounds).
2. Expected: one "Game over" message, no question dialog left open, Menu goes back to the Online
   list and the card shows the result.
3. Abort instead: start another such game and, while a question is open, abort it as its creator:
   ```bash
   TOKEN=$(curl -s -X POST localhost:8080/auth/login -H 'Content-Type: application/json' \
     -d '{"username":"alice","password":"password123"}' | sed 's/.*"accessToken":"\([^"]*\)".*/\1/')
   curl -s -X POST localhost:8080/games/<game id>/abort -H "Authorization: Bearer $TOKEN" -w '%{http_code}\n'
   ```
4. Expected: one "Game over" message "Game aborted by alice", no win sound, and the open
   dialog closes.
5. Restart the server while the game screen is still open. Expected: after the reconnect no
   second "Game over" message.

## 6. Lobby, friends and chat

One emulator as alice is enough; bob can be curl (login as in section 5, `$BOB` his token) or the
second emulator.

1. Bob asks alice: `curl -X POST localhost:8080/friends/requests -H "Authorization: Bearer $BOB"
   -H 'Content-Type: application/json' -d '{"username":"alice"}'`. Alice > Friends: "bob wants to
   be friends" > Accept. Bob shows "offline" (grey dot) and turns "online" (green) when bob
   connects (second emulator, or any WebSocket client sending `Hello`).
2. Alice: New online game > Minimap. Setup screen: visibility chip "Friends" (the default),
   Italians' dropdown > "Free". The Games tab card shows Italians as "Free".
3. Bob sees it in his Lobby tab (or `GET /lobby`) and joins Italians (`POST /games/<id>/join`
   with `{"nation":"Italians"}`). Alice's card shows "Italians: bob · not ready" without Refresh.
   Both press Ready (bob: `POST /games/<id>/ready` with `{"ready":true}`): the card turns into
   "Round 1 ... Your turn".
4. Lobby tab: send a message; it appears with "alice:" and reaches bob; bob's reply appears too.
5. Open the game > chat button: the `game:<id>` chat, the quick messages send at a tap.
6. Long-press one of bob's messages > Block user: his messages disappear, a new one from him does
   not show. Friends tab lists him under Blocked; Unblock brings his messages back. Long-press >
   Report with a reason: a row in the `reports` table.
7. Link game: bob creates one with an open seat; alice > Lobby > Join by code with
   `<game id>:<code>` and the open nation. The setup screen opens; alice sees "Leave" (bob is the
   creator), Leave takes her back and removes it from her list. On a game alice created the setup
   screen shows "Delete", and the game disappears with it.
   Chat: seven quick lobby messages give "Too many messages, wait a moment" under the lobby chat
   only (not in a game chat); a report shows "Report sent".
8. Log out; Register is disabled until "I am 16 or older" and "I have read the privacy policy"
   are ticked. Register a new user, then menu > Account > Delete account: a wrong password shows
   "Wrong password" and the lobby chat still works; the right one returns to the login form, and
   the user is `deleted-<id>` in the `users` table.

## 7. Clocks, standing orders and the turn notification (milestone 3b)

Start the server with `DEV_MODE=true` in `.env` (time travel). One emulator as alice; `$ALICE` is her
token (login as in section 5).

1. Online screen: the system asks for notifications (and local network) once; allow both.
2. New online game > Minimap; on the setup screen the clock chip > "Blitz" 3 min + 0 s (the chip reads
   "Blitz 3 min/turn"), Italians "Fast AI", "More" > "AI takes over" ticked; Ready. In the game the
   clock bar shows alice's time counting down in bold with ▸.
3. Time travel past the clock: `curl -X POST localhost:8080/admin/clock/advance -H "Authorization:
   Bearer $ALICE" -H 'Content-Type: application/json' -d '{"gameId":"<id>","millis":200000}'`.
   The open dialog closes by itself (no answer is sent), the next phase follows, and soon "AI is
   playing your seat" shows with "I'm back". Press it: the text reads "…until your next turn", and
   at alice's next turn the questions come to the phone again, with a full 3:00 on her clock.
4. A correspondence game (Clock "Correspondence", 1 day): menu > Vacation > 2 days. The clock bar's
   deadline moves by two days, and the open dialog stays open.
5. Menu > Standing orders: defender mode "Standing", casualties "Cheapest first", Save; open it
   again, the values are kept. When the Italian AI attacks, no casualty dialog appears on the phone.
6. Turn notification: quit to the Online screen, then menu > "Check turns now" while a question of
   alice waits: a notification "Your turn: Minimap" appears; tapping it opens the app. Repeating it
   for the same question shows nothing new.
7. Leaving the app (needs push, see `app/google-services.json`): two humans in a blitz game, alice
   ends her turn and presses Home. After 5 s the server shows her offline (`/admin/status` or bob's
   friend list); when bob attacks her, a "Battle decision · Choose casualties · <territory> · Minimap"
   notification arrives within the 30 s window and its tap opens the casualty dialog. Back in the
   app the Online lists reload and the game reconnects without a lasting "Reconnecting" banner.

## 8. Spectators (milestone 3b)

1. Alice (curl or an emulator) creates a public Minimap game against an AI and plays until its
   version is at least 5 (`GET /games/<id>`), then stops.
2. Carol (Online screen, log out and in as carol): Lobby tab > "Running games" lists the game with
   Watch. Watch: the game screen opens read-only with "Watching · save N (a few saves behind)" at
   the bottom, N = the server's version − 3. No dialog ever opens; the menu has no Standing orders or
   Vacation.
3. Carol's chat button: the spectator chat (`spectate:<id>`). A message from carol shows; alice's
   `GET /chat/spectate:<id>` gives "No such chat", and her game chat does not contain it.
4. `curl localhost:8080/games/<id>/save?spectate=true&version=<latest>` as carol: 403; without
   `spectate`: 404; with `spectate=true` only: 200 with `X-Game-Version` = latest − 3.
5. Alice aborts the game (`POST /games/<id>/abort`): carol's screen shows the game over message
   with the final state.

## 9. E-mail, password reset and privacy policy (milestone 3c)

Server with mail, in `../triplea-mobile-server`: `docker compose up -d postgres mailpit`, then
`:server:run` with `SMTP_HOST=localhost SMTP_PORT=1025 SMTP_TLS=false
SMTP_FROM="TripleA Mobile <noreply@localhost>"` (the values of `.env.example`). The codes are in
the Mailpit UI at <http://localhost:8025> or in the subject of the newest mail:

```bash
curl -s localhost:8025/api/v1/messages | python -c "import json,sys; [print(m['To'][0]['Address'], m['Subject']) for m in json.load(sys.stdin)['messages']]"
```

1. Online, logged out: "Forgot password?", the e-mail field and the "I have read the privacy
   policy" checkbox show. Without `SMTP_HOST` the first two are gone. Register stays disabled until
   both checkboxes are ticked; the "privacy policy" link opens the policy.
2. Register a new user with an e-mail. The Account screen opens; Mailpit has "TripleA Mobile: Code
   NNNNNN". "I have a code", type it, Confirm: "Confirmed: <address>".
   Change it: a new address and a wrong current password, "Send code": "Wrong password", no mail.
   With the right password a code goes to the new address; Confirm shows the new address.
3. Account, Password: current and new password, "Change password": "Password changed". The lists
   still load (the new tokens were stored).
4. Menu > Log out. "Forgot password?": the e-mail (or username), "Send code"; a new mail arrives.
   Type its code and a new password, "Set password and log in": the Games tab shows, logged in.
   An unknown username gives the same answer and no mail.
5. Menu > Privacy policy and the footer link: the German text with headings and bullet lists.
6. Account > Delete account with the password: back at the login form.

Afterwards: `docker compose stop mailpit && docker compose rm -f mailpit`.

## 10. Setup screen with two users and private chat (milestone 3c)

Two emulators as in section 1, alice and bob friends, the server without mail is enough.

1. Alice: New online game > Minimap. Setup screen: Italians' dropdown > "Free" (the row turns
   green). Visibility "Friends".
2. Bob: Lobby tab lists the game; tap it: the same setup screen, Italians "Take seat". Alice's
   screen shows bob on Italians without a reload (LobbyUpdate).
3. Alice changes her own row (Russians dropdown > alice again, or a chip such as the clock): bob
   keeps Italians, and both Ready marks are cleared.
4. Bob turns off his network for a moment (`svc wifi disable; svc data disable`, then enable):
   his screen still sees the start, because seat holders poll every 10 s.
5. Both press Ready: both screens switch into the game at Round 1.
6. Private chat: Friends tab, alice's card for bob > chat button; send "hi". On bob's Friends
   tab the card shows a badge "1 unread"; opening the chat clears it.
7. Alice: bob's ⋮ > Block: a dialog "Block bob?" asks first (Cancel keeps him); Block moves him to
   Blocked and his new messages do not show. ⋮ > Remove friend asks the same way.

## 11. Profiles, replay, list clocks and limits (milestone 3d)

Two emulators as in section 1 (alice and bob friends), server with `DEV_MODE=true`.

1. Profile: alice, menu > Profile: pick an icon, type a status (at most 60 characters), Save:
   "Profile saved." and the header shows the icon. Bob opens alice's profile (Friends ⋮ > Profile,
   or a tap on her name in a card's seat line): icon, status, stats and recent games.
2. Two-human rule: alice, New online game with every other seat an AI: the hint "At least 2 human
   players needed – play against the AI offline" shows and Ready is disabled. Forcing it
   (`curl -X POST /games/{id}/ready` with `{"ready":true}`) answers 409. Inviting bob to a seat
   removes the hint.
3. Clocks in the lists: start a game with a clock (Blitz). Both Games tabs show "Your turn · m:ss"
   or "Waiting for … · m:ss", counting down. When the turn passes, the card changes without
   Refresh (a `LobbyUpdate`; the server log shows no `GET /games` for it).
4. Friends push: alice removes bob and sends a new request; bob's Friends tab shows the change and
   the request without Refresh; bob accepts and alice sees him at once.
5. B1 and B3: during a battle the clock bar (and the takeover banner after a time travel past the
   deadline: `POST /admin/clock/advance`) shows above the battle window. A chat message from the
   other device puts an unread badge on the in-game chat button.
6. Final round: a finished game (e.g. resign both seats to the AI with time travel, or an AI win)
   and an aborted one show "Round N" and their result on the Games card.
7. Replay: a finished game's Replay on a profile opens the history view at step 1 with "Back to
   game"; Back asks "Leave replay?" and returns to the profile.
8. `curl localhost:<port>/admin/status`: loaded games, AI permits and `aiHardLock`.

## 12. One desktop client (milestone 4)

Desktop seats are not built yet. When they are: build the desktop client from the server's
`triplea` submodule at the engine tag, join `localhost:3300` with the one-time password from the
app, and play one turn against a phone seat.
