# Development notes

Technical documentation for TripleA Mobile. For an overview of the app, see the
[README](../README.md).

## Project layout

| Module    | What it is                                                                                   |
|-----------|----------------------------------------------------------------------------------------------|
| `engine/` | The TripleA game engine (delegates, data model, XML parser, AI), stripped of Swing/AWT, networking, lobby, chat and forum posting. Plain Java 17 library, runs on the JVM and on Android. |
| `protocol/` | The JSON messages between the app and the game server (Kotlin, kotlinx.serialization). No engine dependency. |
| `online/` | Human seats played over those messages: `RemoteHumanUi` (host side, one per seat), `OnlineClient` (device side), `EventEncoder` and the placeable-units query. Kotlin on top of `engine/`; the server compiles the same sources against the desktop engine. |
| `online-client/` | The device's link to the game server (Kotlin on the JVM, OkHttp): `ServerApi` (REST, login and token refresh, save download), `GameConnection` (the WebSocket, question runner, reconnect) and `TokenStore`. Kept out of `online/` because the server compiles that one. |
| `app/`    | The Android application (Kotlin, Jetpack Compose). Map rendering, phase interaction, dialogs. |

### Engine

The engine is based on desktop tag `2026-10.16.15815` (commit `1f6b0edfd2`); the multiplayer
server runs the desktop engine at the same tag. `sh engine/upstream/diff.sh <desktop checkout> [tag]`
lists the files that differ from a tag: `mobile-only` files are expected (the `org.triplea.mobile`,
`org.triplea.geom` and `org.triplea.tree` packages and the other replacements listed below);
`differs` files are the adaptations listed below, and edits that are not part of a whole-file
replacement carry a `// mobile:` comment. To move to a newer tag: list the desktop commits since the base
tag that touch the files the engine has, port them, update the tag here, in
`engine/upstream/*.sh`, in `ENGINE_BASE_TAG` (`protocol/.../Protocol.kt`) and in the server repo, rename the resource in
`LocalGameSmokeTest.desktopSaveLoadsAndPlaysOn` and delete the old
`engine/src/test/resources/saves/desktop-<old tag>.tsvg`, then regenerate the desktop save fixture with
`sh engine/upstream/generate-desktop-save.sh <desktop checkout>` (needs git, internet (Gradle dependencies)
and a locally installed JDK 25 that Gradle can auto-detect, e.g. one downloaded by IntelliJ or Android
Studio into `~/.jdks`, plus any JDK 17 or newer on `JAVA_HOME` to start the Gradle wrapper; builds the
desktop engine in a temp folder, about a minute after the first run).

The engine sources were copied from the desktop project (`game-core`, `ai`, `map-data`,
`domain-data`, `xml-reader`, `java-extras`) and then reduced. Notable changes compared to the
desktop engine:

- `java.awt.{Point,Polygon,Rectangle,Dimension,Color}` are replaced by `org.triplea.geom.*`.
- `javax.swing.tree.*` (used by the game history) is replaced by `org.triplea.tree.*`.
- `ResourceLoader` uses plain file lookups instead of a `URLClassLoader`.
- `ClientSetting` is an in-memory stub holding only the settings the engine reads.
- Websocket message types, `PbemMessagePoster` and `Chat` are compile-time stubs.
- Language level and library usage are Java 17 (no `List.getFirst()` etc.).
- `MapData.getTerritoryAt` takes a filter for the current game's territory names: a map folder
  shared by several games (world_at_war) has polygons of other games on top of this game's ones,
  which otherwise swallow the tap ("Lower Burma" over "Burma").
- Small fixes for SonarQube findings, each marked with a `// mobile:` comment: `Integer.compare`
  in comparators, a mutable copy in `FinishedBattle.unitsLostInPrecedingBattle` and
  `ProBattleUtils`, `BigDecimal.compareTo` in `MovableUnitsFilter`, linear regexes in
  `PointFileReaderWriter`, and the constants `AbstractTriggerAttachment.NOTIFICATION_TYPE` and
  `UnitAttachment.BOMBARD_PROPERTY` (renamed because they clashed with the serialized fields
  `notification` and `bombard`, which must keep their names for save games). Unused code was
  removed instead of fixed: `Timers.executeAfterDelay` (Swing), `PlayerEmailValidation` (lobby),
  `FileUtils.newTempFolder/createTempFile/replaceFolder`, `InstalledMap.readGameNotes` and
  `LocalizeHtml`. `org.triplea.geom.Color` has no lowercase aliases (`Color.black` is
  `Color.BLACK`).
- `SafeObjectInputStream` does not allow `java.awt.*` (Android has none), so a save that carries
  AWT objects fails on the JVM tests exactly as on the phone. `GameDataManager.loadDelegates`
  loads delegate classes without initializing them before the `IDelegate` check.

The mobile specific API lives in `org.triplea.mobile`:

- `MobileEngine` – configuration (data folder), map discovery, parse/load/save games
- `LocalGameSession` – creates and runs a local `ServerGame`
- `HumanPlayerUi` / `HumanPlayerUiAdapter` – the blocking questions the engine asks a human
- `MobilePlayer` – the human `Player` implementation (port of the desktop `TripleAPlayer`)
- `GameEventListener` / `MobileDisplay` – battle and message events
- `UnitImageNames` – unit icon file naming rules

`engine/src/test` contains a smoke test that runs an AI-only game on the minimap for three rounds
and round-trips a save game.

### App

- `GameController` – singleton that owns the session, implements `HumanPlayerUi` and publishes
  `UiRequest`s (move, purchase, place, battle, casualties, ...) as Kotlin flows
- `MapSnapshot` – rendering data (territory paths, owner colors, unit stacks) built under the
  engine read lock whenever the game data changes
- `MapView` – Compose canvas with pinch zoom/pan, tile drawing and tap-to-territory hit testing
- `GameScreen` / `Dialogs` – phase panel and the dialogs for engine questions

## Building

Requirements (nothing needs to be installed system wide):

- Any JDK 17 or newer to run Gradle. Android Studio's bundled JDK works; on the command line a
  portable JDK in `C:\dev\tools\jdk-17` was used. The engine is compiled with `--release 17`, so
  the JDK version running Gradle does not matter.
- Android SDK with platform 36 and build-tools 36.0.0 – `C:\dev\tools\android-sdk`
  (`local.properties` points to it; adjust `sdk.dir` for another machine)

In Android Studio simply open the project folder; the Gradle wrapper (9.6) and the Android Gradle
plugin (9.4) are configured in the project. Do not run a command line build while Android Studio
is building: both write to the same `build/` folders and the outputs end up incomplete.

```powershell
$env:JAVA_HOME = "C:\dev\tools\jdk-17"
.\gradlew :engine:test            # engine unit/smoke tests (JVM)
.\gradlew :protocol:test :online:test   # message golden file, online seats playing the minimap
.\gradlew :online-client:test     # REST client and WebSocket against a scripted MockWebServer
.\gradlew :app:assembleDebug      # APK in app\build\outputs\apk\debug\
.\gradlew :app:installDebug       # install on a connected device / emulator
```

The app runs on Android 10 (API 29) and newer. The engine relies on Java 11/17 library methods
(`String.isBlank`, `Stream.toList`, ...), provided through desugaring.

### Release builds

`assembleRelease` runs R8 (the engine itself is kept unobfuscated, see `app/proguard-rules.pro`,
because it relies on reflection and Java serialization). The APK is signed only when a keystore
is configured; it is never signed with the debug key:

```
RELEASE_STORE_FILE=release.keystore      # path relative to the project root
RELEASE_STORE_PASSWORD=...
RELEASE_KEY_ALIAS=...
RELEASE_KEY_PASSWORD=...
```

Put these into `~/.gradle/gradle.properties` (never into the repository). Without them the
build produces `app-release-unsigned.apk`.

### Code quality: SonarQube Cloud

The project is analysed on [SonarQube Cloud](https://sonarcloud.io/project/overview?id=xXEddieXxx_TripleA-Mobile)
(organization `xxeddiexxx`, project key `xXEddieXxx_TripleA-Mobile`; free for the public GPL
repository). The Gradle plugin `org.sonarqube` in the root `build.gradle.kts` uploads an analysis
of all five modules (engine, protocol, online, online-client, app) and the server computes the findings. The bundled maps and images under
`assets/` are excluded.

```powershell
$env:JAVA_HOME = "C:\Users\<you>\AppData\Local\Programs\Android Studio\jbr"   # the scanner needs Java 17+
$env:SONAR_TOKEN = "<token>"
.\gradlew :engine:testClasses :protocol:testClasses :online:testClasses :online-client:testClasses :app:compileDebugKotlin sonar    # the sonar task itself does not compile
```

The token is a personal one from SonarQube Cloud (My Account, Security); keep it in the
`SONAR_TOKEN` environment variable, never in the repository or in Gradle files. Automatic
Analysis must be off for the project (Administration, Analysis Method), otherwise the server
refuses scanner uploads. Android Studio's SonarQube for IDE plugin is bound to the same project
in connected mode (`.idea/sonarlint.xml`), so the IDE shows the server's rules and issue states.

Locally the Claude Code Stop hook runs this analysis after every turn that edited code and blocks
until no unresolved issue sits on a line the working tree changes; the older findings in the
copied upstream engine are left alone until their lines are touched. A false positive is
resolved in SonarQube Cloud (Accept or False positive) rather than with `NOSONAR` comments.

## Releases

Official releases are built by `.github/workflows/release.yml`, started by hand: GitHub →
Actions → *Release* → *Run workflow* → enter the version (for example `0.2.0`). The workflow
refuses an existing version, runs the engine tests, builds `assembleRelease` signed with the
release key, tags the commit `v0.2.0` and publishes a GitHub release with generated notes and the
APK attached as `triplea-mobile-0.2.0.apk` and `triplea-mobile.apk`. The README links
`releases/latest/download/triplea-mobile.apk`, which GitHub resolves to the newest release.

`versionName` is the entered version and `versionCode` is derived from it (`0.2.0` → 200,
`1.2.3` → 10203), so every release has a higher code than the one before, as Android requires.
Local builds show `0.0.0-local`, dev builds `dev-<date>-<commit>`.

The signing key lives only in repository secrets: `RELEASE_KEYSTORE_B64` (the keystore,
base64), `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`. Create it once
and keep a backup outside the repository; Android only updates an installed app when the new APK
is signed with the same key:

```
keytool -genkeypair -v -keystore release.keystore -alias triplea-mobile -keyalg RSA \
  -keysize 4096 -validity 10000 -dname "CN=TripleA Mobile"
base64 -w0 release.keystore      # -> secret RELEASE_KEYSTORE_B64
```

Release and dev builds use different keys, so one does not install over the other.

Release builds run R8. `app/proguard-rules.pro` keeps the engine untouched and keeps the names and
serialized fields of every `Serializable` class (Guava included): a save game stores class names,
`SafeObjectInputStream` allows them by name, and a save must load in every build. After touching
the rules, check that a save made by the release build loads again and that a save made by a debug
build loads in the release build.

## Dev builds

Every push to `main` runs `.github/workflows/android.yml`: engine tests, debug APK, and a rolling
pre-release named **dev** with the APK attached twice: once with the date and commit in its name
(`triplea-mobile-dev-<date>-<commit>.apk`) and once as `triplea-mobile-dev.apk`, so the dev link
in the README always points at the newest build. The release page is
`https://github.com/xXEddieXxx/TripleA-Mobile/releases/tag/dev`. Android only updates an installed
app when the new APK is signed with the same key; the workflow header explains how to store a
fixed debug keystore as the secret `DEV_KEYSTORE_B64`.

## Security notes

- Network: only HTTPS, to the TripleA server (the map list from
  `prod.triplea-game.org/support/maps/listing`, as the desktop client loads it) and to GitHub
  (map archives and previews); cleartext traffic is refused by the
  network security config (and `usesCleartextTraffic="false"` in the manifest) and by the
  download code, also after redirects.
- Map archives are unpacked with path traversal checks and size/entry limits.
- Save games are Java serialized; they are read through `SafeObjectInputStream`, which only
  resolves engine, JDK and Guava classes, so a crafted save cannot instantiate arbitrary classes.
- Game XML is parsed with DTDs and external entities disabled.
- The app asks for no permissions beyond INTERNET and VIBRATE and stores everything in its
  private app folder.
- Online play (debug builds only for now, see "Online play"):
  - Cleartext `http://` and `ws://` to a local game server is allowed only in debug builds, through
    the `app/src/debug/` override of the network security config. The
    debug manifest also adds `ACCESS_LOCAL_NETWORK`: Android 17 blocks private addresses (the
    emulator's `10.0.2.2`, a LAN server) without it. Release builds have neither, and no Online
    entry or Server URL setting.
  - Access and refresh tokens live in the private SharedPreferences file `online`
    (`PrefsTokenStore`), never in logs, and are cleared when the Server URL changes.
  - The app never uploads a save. It only downloads the server's saves, at most 64 MB compressed
    and 256 MB unzipped, and reads them through `SafeObjectInputStream`
    (`GameDataManager.loadGameUncompressed`).

## How a game runs

1. `SetupScreen` parses the chosen game XML and lets the user assign Human/AI per nation.
2. `LocalGameSession.create` builds the `ServerGame` with a `LocalNoOpMessenger` (no network) and
   starts the game loop on a background thread. The UI sees it only through the app interface
   `GameSession` (`LocalSession` wraps the `LocalGameSession`), so an online session can stand in.
3. When the engine reaches a human phase, `MobilePlayer` calls into `GameController`, which
   publishes a `UiRequest` and blocks the game thread until the UI completes it.
4. Every game data change bumps a version counter; `GameScreen` rebuilds the `MapSnapshot` and
   redraws the map.
5. The history replay works like the desktop's "Show History" mode: `HistoryView` clones the game
   data under the write lock (`GameDataUtils.cloneGameDataKeepSameHistory`) and winds the clone to
   the tapped event with `History.gotoNode`; the map, stats and territory panel are built from the
   clone while the game goes on. Unlike the desktop there is no `HistorySynchronizer`: the clone
   is dropped when the replay closes and made anew for an event it does not contain. "Back to
   game", the back key or a battle window (the game needs the player) end the mode.

## Online play (protocol and online modules)

Milestone 2 of `docs/superpowers/specs/2026-10-12-multiplayer-lobby-design.md`. The server runs
the game; a human seat on a phone is played through JSON messages (`protocol/`):

- The host gives every remote seat a `RemoteHumanUi` (`LocalGameSession.create` with a UI per
  nation, an overload taking a `Function<String, HumanPlayerUi>`). Each `HumanPlayerUi` question
  becomes an `Ask`, sent through a `HostLink`.
  - The answer (`Reply`) is checked against the game before the engine sees it: ids exist, chosen
    items were offered, amounts are not negative, the casualty count matches.
  - Beyond that the host checks: affordability counted in 64-bit (no int overflow), duplicate unit
    ids, casualty damage limits, tech payers (only the player and its `helpPayTechCost` allies) and
    repair (only own or allowed units).
  - A bad answer gets `Rejected` with a reason, and the question is asked again.
  - Tech answers follow the map's tech model as `TechnologyDelegate` reads it: a category of the
    player in WW2V3, exactly one available advance in the revised model (WW2V2 or Selectable Tech
    Roll), no target in the classic model; at least one roll. Amounts above `MAX_AMOUNT` (10,000)
    are rejected.
  - "Select fixed dice" (edit mode, and under Selectable Tech Roll which advances the hits
    discover) is never asked online; the host rolls those dice.
- The device keeps a read-only copy of the game, loaded from the host's save of the question's
  `version`. `OnlineClient` decodes the question against that copy and calls the same
  `HumanPlayerUi` that plays local games.
  - Units travel by UUID, territories and nations by name.
  - The save carries the move and place delegates' "moves made", so the device reads them
    locally.
  - To undo, the device UI throws `UndoRequest(index)` from `getMove`/`getPlacement`; that is
    sent as `Reply.Undo` and the question follows again. An undo the engine refuses comes back
    as `Rejected` with the engine's reason, so the host takes no new save for it.
  - "Which units can be placed here" is the only query to the host
    (`PlaceableQuery`/`PlaceableResult`).
- Battle, dice and step events go to every device as `GameEvent`s (`EventEncoder` on the host,
  `OnlineClient.event` on the device). Units the device's copy does not know are left out of an
  event. `GameEnded` carries `aborted` (v3) for every server abort; the app plays no victory sound then
  (`GameEventListener.gameAborted`).
- Messages are limited to 64 KB (`MAX_MESSAGE_CHARS`). The app's first message is `Hello` with
  `PROTOCOL_VERSION` and `ENGINE_BASE_TAG`; a mismatch is answered with `UpdateRequired`.
- **Changing a message:**
  1. Bump `PROTOCOL_VERSION`.
  2. Run `.\gradlew :protocol:test`; it fails and writes `protocol/build/golden/protocol-v<N>.json`.
  3. Read that file and copy it to `protocol/src/test/resources/golden/`.

  A golden file that was released is never edited.
- **Clocks** (milestone 3b, protocol v3): a game may have a time control (`CreateGameRequest.timeControl`:
  blitz, rapid or correspondence, plus `aiTakesOver` and `resignAfterMissedTurns`). The server sends
  `Clock` (remaining time per player, whose clock runs, its deadline) and sets `Question.deadline`.
  When a clock runs out the server answers the open question itself (`Done` for phase questions,
  the engine default otherwise), then the AI plays the seat until the player comes back (an
  accepted answer, game chat, or `POST /games/{id}/back`, the app's "I'm back").
  In the app, `SocialConnection` passes every `Clock` to `Social.clocks` for the clock bar.
  The online game screen shows a clock bar (`OnlinePanels.kt`: every player's time, the running one
  counting down from `deadline`, and "AI is playing your seat" when `GET /games/{id}` lists one of
  the user's seats as an AI; "Resigned" when `SeatDto.resigned`), a "Standing orders" sheet and, in
  correspondence games, "Vacation".
  The server never says that it answered a question itself. `GameConnection` therefore watches the
  game's `Clock`s, which name their question (`Clock.questionId`; question ids only grow): the
  server stops a question's clock when it closes (naming it) and starts the next one's (naming that).
  A stopped clock closes its question and every older one, a running clock every older one; then
  `Listener.questionExpired` cancels the dialog without an answer. Other Clocks (vacation, time
  travel, the reconnect catch-up) name the question open at the moment they are sent, or none, so one
  that arrives out of order never closes the open question. Only games without clocks (an untimed
  hybrid window) fall back to the device's time: `Question.deadline` + 2 s.
  All server calls go through `OnlineApi.forUrl`, one `ServerApi` per process, because refresh tokens
  are single-use and two instances refreshing at once would log the user out.
- **Spectators** (milestone 3b, protocol v3): the Online screen's Lobby tab lists the running games
  the server lets the user watch (public or a friend's friends-only game that allows spectators,
  `CreateGameRequest.allowSpectators`, no seat of theirs). "Watch" opens the game screen read-only:
  `GameController.startOnline(..., spectate = true)` builds an `OnlineSession` whose `GameConnection`
  sends `Watch(gameId)` after `Hello`, downloads `GET /games/{id}/save?spectate=true&version=<the
  announced one>` (so held events match the copy; the newest allowed once it is no longer kept) and drops every
  `Question`; the server sends it none, so `pending` stays empty. The server sends spectators each
  `GameVersion` and its events `spectator_delay_versions` (3) saves late, and the final state at once
  when the game ends. `WatchRefused` closes the connection. The chat sheet uses channel
  `spectate:<id>` (the users watching right now), not `game:<id>`; clocks, standing orders and
  vacation are hidden.
- **Turn notifications** (milestone 3b): `TurnCheckWorker` (WorkManager) polls `GET /me/turns`
  (`TurnDto`: the open questions for the user's seats) every 15 minutes on a network, while logged
  in (enqueued and cancelled by the Online screen). It notifies once per new waiting question
  (`turn-check` prefs: game to question id), skips the game open on the screen, and remembers nothing
  while notifications are off. Since milestone 3c `TurnsDto.unreadDirect` > 0 also fetches the
  friends list and shows "New message from <name>" per friend whose unread count is higher than the
  count saved by the last check (prefs key `dm:<name>`, updated per friend on every check; kept when the
  friends list fails). Reading the friend's chat in the app (`Social.markRead`) resets the saved count
  to 0 once the server accepted the read, so the next message after reading notifies again; a check
  running at the same time does not write that friend's old count back. A chat read on another device
  resets the count only once a check sees 0 unread for that friend; a new message before that notifies
  only when the count exceeds the saved one. `POST_NOTIFICATIONS` is in the debug manifest only and asked on the
  Online screen; debug builds have "Check turns now" in its menu. `app/src/release/AndroidManifest.xml`
  removes WorkManager's permissions, start-up initializer, services and receivers from release
  builds, which have no online play.
- **Standing orders** (milestone 3b, protocol v3): out-of-turn questions (the seat is not the current
  step's player) follow the player's defender mode (`live`, `hybrid` with a 60 s window on their
  clock while they are online, `standing`) and their standing orders (`StandingOrdersDto`,
  `DefenderModeRequest`; REST `GET`/`PUT /games/{id}/orders`, `PUT /games/{id}/defender-mode`). The
  server's answers go through `RemoteHumanUi` like any other. Details in the server README.
- Copying the game for a device takes its write lock. Events are therefore not synced while the
  engine emits them (the game thread may hold the read lock then). `OnlineGameTest` syncs at every
  question under a timeout.
- The game server lives in `../triplea-mobile-server` (its README has the run steps). It compiles
  `protocol/`, `online/` and six `org.triplea.mobile` files against the desktop engine at tag
  `2026-10.16.15815` instead of the mobile engine fork; `MobileDisplay.unitsMoved` therefore has no
  `@Override`. Its WebSocket frame limit (`maxFrameSize` = 64 KB) refuses oversized frames before
  they are buffered, which closes the earlier "64 KB check after buffering" gap.
- **In the app** (milestone 2c, debug builds only until a public server exists): Home has an
  "Online" button and Settings a "Server URL" field (default `http://10.0.2.2:8080`, the host seen
  from the emulator). The Online screen logs in or registers (registering needs the "I am 16 or
  older" checkbox; the server refuses `ageConfirmed = false`). On Android 17 it asks for
  `ACCESS_LOCAL_NETWORK` first. Its menu has "Log out", "Account" and "Privacy policy".
  Account, privacy and password reset (milestone 3c):
  - The app asks `GET /server/features`; with `email: true` the register form shows an optional
    e-mail field and "Forgot password?" appears under the password. Registering also needs the
    checkbox "I have read the privacy policy" (its link opens the policy). After registering with
    an e-mail the Account screen opens, where "I have a code" takes the mailed code.
  - **Account** (`AccountScreen`): username and confirmed e-mail (`GET /account`); add or change
    the e-mail (new address and the current password, "Send code", then the 6-digit code; the
    server refuses a wrong password with 403, so a stolen token alone cannot move the reset mails;
    the address is stored only once confirmed);
    change password (`POST /account/password`: the server revokes every token, also on this
    device, and answers with a new pair, which the app stores; open sockets close and reconnect);
    "Delete account" (moved here from the menu; password confirmation, a wrong one is refused
    with 403 and changes nothing).
  - **Forgot password** (`ForgotPasswordScreen`, two steps): username or e-mail, then the code and
    a new password; success logs in (`POST /auth/reset` revokes every older token).
  - **Privacy policy** (`PrivacyScreen`): the asset `app/src/main/assets/privacy_de.md`, a copy of
    the server's `PRIVACY.md` (German, with placeholders), drawn by a small line-based renderer
    (headings, quotes, lists, paragraphs). The server file is the source: change both together
    (`cmp` must find them equal). Reached from the register checkbox, the menu and the "Privacy
    policy" footer of the Online screen. It is a template, not legal advice: a lawyer must check it
    before a public launch.

  Three tabs (milestone 3a):
  - **Games:** the player's games and "New online game". A running game opens on tap ("Your
    turn"), a finished one shows its winners, an aborted one its reason (`GameInfoDto.error`). A
    lobby game (every new game starts in status `lobby`) opens the setup screen.
  - **Lobby:** visible lobby games with an open seat the player does not hold yet (a tap opens the
    setup screen); "Join by code" takes the `<game id>:<code>` invite and a nation typed in (a link
    game is invisible before joining, so its open nations cannot be listed), then opens the setup
    screen; the lobby chat underneath.
  - **Friends** (redesigned in milestone 3c): cards in sections Requests (Accept / ✕), Online and
    Offline, then Blocked ("Unblock"). Each friend card has an avatar initial with a status dot
    (green online, amber in a running game, grey offline), a status line (`activity`, "online" or
    "last seen …"), the unread badge, a chat button and a ⋮ menu (Remove friend, Block, Report; Remove and Block ask first). A
    floating "Add friend" button asks for the username.
  - **Look of the online screens** (`OnlineComponents.kt`): built from the friends list. Icons, pills
    and nation tiles replace sentences: the games list has the sections Your turn (least time first),
    Waiting, Setting up and Finished (folded), each card a map thumbnail, a status pill and a row of
    `SeatTile`s (flag with the player's initial, a robot for an AI, a green + for a free seat; nations
    without an engine flag show two initials); tabs carry count badges (my turns; friend requests plus
    unread messages). Chat messages are bubbles, profile stats are tiles, and explanations moved
    behind (i) buttons (`InfoButton`); destructive consequences stay visible.
  - **Private chat** (`DirectChatScreen`, route `chat/{username}`, milestone 3c): `ChatPanel` on the
    friend's `dm:<a>:<b>` channel (`FriendDto.channel`). While the screen is resumed it marks the
    newest message read (`POST /chat/{channel}/read`). `Social.unread` holds the counts per channel:
    the friends list's `unread`, plus live `dm:` messages from others since.
  - **Setup screen** (`OnlineSetupScreen`, route `onlineSetup?game=<id>`, milestone 3c): "New online
    game" first picks an installed game (the single player chooser; the nations come from parsing
    its XML on the device) and creates it with defaults (first nation me, the others Fast AI,
    visibility Friends), then everything is edited live. A map card (preview, nations, "Change
    map" while nobody else holds a seat: a new game with the same options replaces the old one,
    as there is no server call to change the map; so the game id changes, an invite code shared
    for a link game no longer works and the old game's chat is not carried over), chips for visibility, clock and "More" (AI takes
    over, resign after N, spectators), the nation rows of `SetupScreen` (`NationRow`,
    `PickerButton`), Ready / Leave / Delete and a chat bar for `game:<id>` (last message, a count
    of messages that came since the screen opened, unfolds in place). The creator's dropdown: own
    name, Free, "Invite friend…" (accepted friends), the AIs, Does nothing (`PUT
    /games/{id}/seats/{nation}`, one seat, so a join that happened meanwhile is kept); options go through `PATCH /games/{id}`. Others take a free seat ("Take
    seat"), give their own back ("Free") and toggle Ready. Ready also needs the map installed on this
    device (the game is played with the local map files, so a start without them left the player
    stuck on "map not installed"): the map banner then shows "Not installed · Get map", which opens
    the map browser, and the installed maps are read again on resume. While `mapReady` is false it shows "Map
    is loading on the server…" and Ready is disabled (the server answers 409); an aborted game
    shows its `error`. The screen reloads on every `LobbyUpdate` and on resume (the start arrives as a
    running game's `LobbyUpdate` and replaces the shown game directly); seat holders of a
    lobby game also poll every 10 s while the screen is resumed (a dropped social socket must not
    make them miss the start; taking a seat starts the poll, losing the last one stops it); once the game is `running`, every seat holder on
    the screen calls `GameController.startOnline` and switches into the game (back from the game
    goes to the Online lists). Ruling: the chat unfolds in the screen instead of a bottom sheet,
    whose window got no keyboard insets on the emulator (the keyboard hid the text field).
  - The game screen of an online game has a chat button: a bottom sheet with the `game:<id>` chat
    and quick messages (Good luck, Well played, Thanks, One moment).
  - Long-pressing someone else's chat message offers "Block user" (their messages disappear at
    once; the server stops sending new ones and leaves them out of history) and "Report" (a
    reason of up to 1000 characters, with the message id).
  - `SocialConnection` (`online-client`) is a second WebSocket next to the game's
    `GameConnection`, open while the Online screen or an online game is shown (`Social` in
    `app/.../online/` starts it when logged in and stops it on log out and when the Online screen
    is left with Back). The server sends every message to all of a user's sockets, so each socket
    ignores the other's messages; the social one hands `ChatMessage`, `ChatError`, `Presence` and
    `LobbyUpdate` to `Social`, whose `StateFlow`s the screens collect. A `LobbyUpdate` of a lobby,
    finished or deleted game reloads the lists (300 ms debounce); one of a running game replaces its
    card in place (`Social.gameUpdates`, see milestone 3d below), and an open game takes its own as its seat info (also fetched 3 s after a clock
    change or a new step, which catch AI takeovers and hand-backs); chat is merged by id and sorted (ids may arrive out of order), at most 200 per
    channel. Ruling: a second socket instead of splitting `GameConnection` keeps its 2c behaviour
    untouched; cost: two connections per device during a game.
  - `OnlineSession` (`app/.../online/`) is the online `GameSession`. It owns the `GameConnection`;
    its game data is the connection's copy, and its map images come from the installed map
    ("The map X is not installed on this device" otherwise).
  - `currentDelegate` returns a reflection proxy: `getMovesMade` reads the
    copy's current delegate, `undoMove` fails the open request with `UndoRequest` (which
    `GameController.ask` rethrows to the engine side), `getPlaceableUnits` asks the server.
    Any other delegate method throws `UnsupportedOperationException`.
  - Undo works one move per question: a second undo before the server's next question gets
    "Undo already sent", and "Undo all" is hidden online.
  - `GameController.startOnline` waits up to 60 s for the first copy. A drop, a closed connection
    ("please log in again"), `UpdateRequired`, a save that does not load or a missing map before
    that fails the start with the reason on the Online screen. Afterwards a drop cancels the open
    question, shows a "Reconnecting" banner and holds new questions until the connection is back;
    the server then sends the question again.
  - The per-round autosave is off online (the server keeps the game); "Save" still writes the
    copy to a local file.
  - The server address must be reachable from the device: `10.0.2.2` on the emulator, the PC's
    LAN address on a phone (with the server's port open in the firewall). The manual two-device
    check is `docs/online-test-checklist.md`.
- **Milestone 3d** (profiles, history, AI limits; spec
  `docs/superpowers/specs/2026-10-13-profiles-history-ai-limits-design.md`, protocol v3 extended):
  - **Chess clocks in the lists:** `GameInfoDto.turn` (nation, username, ai), `turnRemainingMs`,
    `turnDeadline` and `turnSince` for running games. `TurnPill` in `OnlineScreen.kt` shows "Your turn ·
    4:12", "bob · 2:03" or "AI" with a timer icon, or, without a clock, how long the turn has lasted with an
    hourglass icon ("bob · 2 h"). In the game the header shows the same next to the round (`TurnTimer`):
    the running clock counting down, else how long the turn has lasted (online from the server's
    `turnSince`, offline from when the screen saw the turn start). The game's latest `Clock` (`Social.clocks`) wins over the card: it says whose clock
    runs (also a defender in a battle) and until when, so a Clock never reloads a list.
  - **Turn push:** when the turn passes to another nation the server sends `LobbyUpdate` (the full
    `GameInfoDto`) to the seat holders, and the spectators' view to those who may watch (also for the
    start: only seat holders get a running game live). A human's turn with a clock is sent once their
    first question started the clock, so the card has the deadline. `Social` hands
    a running game's `LobbyUpdate` to `gameUpdates`; the Online screen replaces that card in both lists
    (also in the result of a reload that was in flight), and the setup screen takes it as the start. No
    REST list reload per turn. A game in neither list waits for the next reload. When a game ends (finished or aborted) the same `LobbyUpdate` goes out
    once more; it is no longer running, so `Social` bumps `lobbyVersion` and the lists reload.
  - **Clock skew:** deadlines are server time. `Social.clockSkewMs` (server minus phone clock) is learned
    from every received game with a running clock (`turnDeadline - turnRemainingMs - receive time`, the
    smallest wins; samples over 1 h off are vacations and ignored); the card countdown, `turnSince` and the in-game clock bar add it to the phone's time.
    The server sends no `Date` header, so 0 until a game with a running clock was received.
  - **Final round:** the server stores the round of every turn start (V13), so finished and aborted
    cards show "Round N" again; a game that never reached its first turn shows none.
  - **Two-human rule:** Ready is refused (409 "At least 2 human players needed – play against the AI
    offline") while fewer than 2 distinct humans hold or may take seats (HUMAN holders plus OPEN seats).
    The setup screen counts the same way, shows the hint and disables Ready; running games are not
    affected.
  - **Teams, giving up and games against the AI:** at the start the server stores each seat's team (its
    alliance, `SeatDto.team`) and marks the game `vsAi` when all humans share one team. A player leaves by
    "Give up" in the game menu (`POST /games/{id}/resign`, `ResignDialog` in `OnlinePanels.kt` says what
    happens to the team first), by missing too many turns in a row, or by deleting the account; the AI then
    plays their nations for good (a clock that runs out once is only a stand-in until "I'm back"). No game
    goes on with AI players only: once one team has players left it wins, once none is left the game
    ends without a winner. Games against the AI count in `ProfileDto.aiStats` ("Versus AI" on the
    profile) and not in the win rate ("Versus players"), so wins against the AI cannot be farmed.
  - **Profiles** (`ProfileScreen.kt`, route `profile/{username}`; `GET /users/{u}/profile`,
    `PUT /account/profile`): an icon, a status (trimmed, no control or format characters, at most 60
    code points) and stats from finished games (played, won, lost, draws, resigned, abandoned, win rate,
    most played map and nation) plus the 10 most recent games. Icons are ids from an allow-list:
    `app/src/main/assets/profile-icons.txt` is a copy of the server's file and must stay byte-identical
    (`cmp`), as with the privacy policy. Opened from the Online menu, friend cards, seat lines, the setup
    seat picker and the chat long-press dialog.
  - **Replay:** `GET /games/{id}/replay` returns a finished or aborted game's final save to participants
    and to whoever the game's visibility would have let see it. "Replay" on a profile's recent game opens
    a read-only `ReplaySession` in the history view. A daily job deletes final saves 365 days after the
    end; stats and game rows stay.
  - **AI queue and timeout:** every AI step takes one of `AI_MAX_PARALLEL` permits (default: CPU cores;
    below 1 refuses to start), and the AI move pause grows with the queue. An AI turn longer than
    `AI_TURN_TIMEOUT_S` (300 s) stops the game and resumes it from the last save with that nation as Fast
    AI until its next turn; a timed-out Fast AI aborts the game.
  - **Hard AI is serialised JVM-wide:** `ProAi` keeps one static battle calculator per JVM, so Hard AI
    steps of all games take one lock (released while a human is asked). The calculator is re-prepared
    with the game's data whenever the lock passes to another game. A Hard AI holding the lock longer than
    the timeout plus 60 s is logged and its game aborted; a thread that ignores the stop keeps the lock
    until a restart.
  - **Unload and reload:** at most `MAX_LOADED_GAMES` (40) engines; games idle `IDLE_UNLOAD_MIN` (30 min)
    are unloaded, keeping their save and the open question, and reload on hello (all whose question is
    unknown, at most 3 parked ones per hello),
    an answer, a clock expiry (over the limit if it must) or a placeable query. Creating or starting a
    game with nothing to unload answers 503 "Server busy".
  - **Crash resume:** an unexpected game-thread crash resumes from the last save, up to 3 times in 10
    min, then aborts the game with the error.
  - **`GET /admin/status`** (open with `DEV_MODE`, else only with `X-Admin-Token`): loaded games, AI
    permits, queue, running and slowest AI turns, timeouts, heap and the Hard AI lock holder.
  - **Also:** `FriendsChanged` reloads the friends list on both devices; B1 (clock bar, takeover banner
    and reconnect chip drawn above the battle window), B3 (unread badge on the in-game chat), B2 (the
    host answers `ConfirmCasualties` itself), online wording of the quit dialog.
- Known gap: messages the engine addresses to some players (`reportMessageToPlayers`) reach every
  device, because `GameEventListener.message` has no recipients. The server (2b) decides recipients
  in its own display and may need a recipients field in the protocol.

## Known gaps

- Technology, repairs, scramble, kamikaze suicide attacks and the random start's "pick territory
  and units" question have dialogs (`RuleDialogs.kt`), but no allied help paying for tech rolls
  (`whoPaysHowMuch` is always empty) and no fuel check before scrambling; the engine rejects a
  scramble the player cannot fuel.
- The remaining `HumanPlayerUiAdapter` defaults: "select fixed dice" (edit mode) rolls randomly.
- Save games can be exchanged with the desktop client as long as both run the base tag above:
  the history is serialized as a change list (`History.writeReplace` to `SerializedHistory`), so
  no Swing classes end up in the file, and `LocalGameSmokeTest.desktopSaveLoadsAndPlaysOn` loads a
  save written by the desktop engine at that tag. A save from another desktop version may fail to
  load; it then fails as a whole with the normal error message.
- Maps that switch territory effect markers off in `map.properties` show their effects only in
  the zone panel, as the desktop client does.
- Online play (debug builds only):
  - finished and aborted games are listed but cannot be opened from the Online list (their replay
    opens from a player's profile); a replay of a map not installed on the device shows an error and
    offers no download;
  - a turn push for a watchable public game goes to every connection; the app updates a card in
    place, but a game not in its lists waits for the next reload;
  - the clock skew is learned only from a game with a running clock; before that, the in-game clock
    bar counts down against the phone's clock; a phone clock more than 1 h wrong is not corrected;
  - a hello loads at most 3 unloaded games with a parked question; the user's other games answer from
    their parked question and load when it is answered;
  - a stuck Hard AI thread that ignores the stop keeps the JVM-wide Hard AI lock until a restart, so
    every Hard AI waits (visible as `aiHardLock` in `/admin/status`);
  - "resigned after an AI takeover" counts as resigned in the stats, even when that nation won;
  - the app has no Abort button, although the server has `POST /games/{id}/abort` for the
    game's creator;
  - the "your turn" notification polls every 15 minutes (WorkManager's minimum), so it can come
    that late; there is no push;
  - spectators lag a little more after an AI takeover or a server restart (the held events are
    lost, see the server README), and the delay counts saves, not turns;
  - battles were not part of the two-player test run;
  - public lobby games are open to every account: the spec wants a verified e-mail or Google
    sign-in first; mail delivery and confirmed addresses exist now, the rule is not enforced;
  - the privacy policy is a German template with placeholders, bundled as a copy that must be kept
    equal to the server's `PRIVACY.md` by hand;
  - "Join by code" needs the nation typed in (the server has no lookup by invite code);
  - chat shows the newest 50 messages of a channel's history (no paging back) plus what arrives
    live; Presence is only sent on changes, so the online dots come from the last friends list
    plus the changes since;
  - the chat sheet in the game needs Back twice on the emulator (the first press reached nothing
    visible in the test run).
