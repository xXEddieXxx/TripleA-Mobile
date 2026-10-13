# Milestone 2c: App Online Mode Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The app can log in to the game server from milestone 2b, list and create online games, and
play its seats with the same screens and dialogs as a local game. Done means an emulator plays a
minimap game against the server's AI through the real app.

**Architecture:**
- **New module `online-client/`** (Kotlin/JVM, testable without Android). It holds the device side
  of the network:
  - `ServerApi` (REST through OkHttp);
  - `GameConnection` (one WebSocket per open game). It keeps the read-only game copy in sync with the
    server's saves and answers each `Question` through `OnlineClient` from milestone 2a with the
    app's own `HumanPlayerUi`.
  - It is not added to `online/`, because the server compiles `online/src/main` and must not pull
    in OkHttp.
- **App seam.** The UI talks to a small app interface `GameSession` instead of the engine class
  `LocalGameSession`.
  - `LocalSession` wraps `LocalGameSession` and keeps local play unchanged.
  - `OnlineSession` wraps a `GameConnection`. Its delegate proxy reads "moves made" from the copy,
    turns undo into `UndoRequest` (the 2a contract) and the placeable query into a server round
    trip.
- **Screens.** An "Online" screen with login or register, the game list and a create form. It is
  only in debug builds, until a public server exists, and so is the "Server URL" setting.

**Tech Stack:** Kotlin 2.4.20, Jetpack Compose (as the app), OkHttp 5 (WebSocket and HTTP, plus
`mockwebserver3` for tests), kotlinx-serialization through `:protocol`, JUnit 6 and AssertJ. Pin
the current stable OkHttp version that is at least two weeks old in `gradle/libs.versions.toml`.

**Spec:** `docs/superpowers/specs/2026-10-12-multiplayer-lobby-design.md`:
- section 2, "Messages": "`GameController` gets a second source for `pending` (local or server).
  The dialogs stay the same";
- section 3, "A turn" and "Disconnect and server restart";
- section 4, "App ↔ server";
- section 5, "App against the local server".

Research: `.superpowers/sdd/m2c-research-app.md` lists every app call site of the session and its
needs, and the navigation, settings and manifest details.

## Global Constraints

- No `git commit`, `git push` or `git add`. Leave the changes in the working tree.
- Files under `engine/src` and `app/src` are edited with Edit/Write only, never with shell tools.
- `./gradlew` needs the `build` skill; `adb` needs the `device` skill.
- Every change under `app/src/main` must be seen working in the real app:
  - built with `:app:assembleDebug`;
  - installed with `adb install -r --user 0` after the last edit;
  - checked with a `screencap` or `uiautomator dump` of the changed screen.
- **Release builds stay as they are:** no cleartext, no Online entry, no Server URL setting.
  - Debug builds allow cleartext `ws://`/`http://` through `app/src/debug/` overrides of the
    network security config.
  - Default server URL: `http://10.0.2.2:8080` (the emulator's host loopback).
- **Security:**
  - The app never trusts or uploads saves. It only downloads saves from the server, through the
    mobile engine's `SafeObjectInputStream` (`GameDataManager.loadGame`).
  - Tokens live in private SharedPreferences, never in logs.
- **Local play must behave exactly as before** after the seam refactor. Verify it on the device:
  start a local game, make a move, undo it, place a unit.
- UI text is English, like the rest of the app.
- `docs/DEVELOPMENT.md` stays current: the module table, online play in the app, and the security
  note on debug-only cleartext.

## Review Focus

1. **Undo online.** Tapping undo on a move must send `Reply.Undo`, and the move list must refresh
   from the next question's save, not from a local delegate call that only changes the copy. Covered
   by an `online-client` test and by the device check.
2. **A message arrives while a dialog is open.** `PlaceableResult` arrives while the game thread
   (the question runner) is blocked. It must not be queued behind the open question. Covered by a
   test in Task 1.
3. **Connection drop mid-question.** The app reconnects with backoff and gets `GameVersion` and the
   `Question` again. The open dialog is cancelled and replaced by the re-sent question; it must not
   send an answer for a stale question id. Covered by a test in Task 1.
4. **The save download fails or the map is not installed.** Show an error on the online screen. No
   crash and no endless spinner.
5. **Expired access token.** REST refreshes once on 401 and retries. The WebSocket connects with a
   fresh token.

---

### Task 1: Module `online-client` (network side, JVM-tested)

**Files:**
- Modify: `settings.gradle.kts` (`include(":online-client")`) and `gradle/libs.versions.toml`
  (okhttp, mockwebserver3).
- Create: `online-client/build.gradle.kts`, the same shape as `online/build.gradle.kts`.
  - `api(project(":online"))` and `api(libs.okhttp)`.
  - Test deps: junit, assertj, mockwebserver3 and slf4j-simple.
  - Test resources: the engine test resources (minimap), as `online` does.
- Create: `online-client/src/main/kotlin/org/triplea/mobile/online/client/ServerApi.kt`.
- Create: `online-client/src/main/kotlin/org/triplea/mobile/online/client/GameConnection.kt`.
- Create: `online-client/src/main/kotlin/org/triplea/mobile/online/client/TokenStore.kt`.
- Create: `online-client/src/test/kotlin/org/triplea/mobile/online/client/ServerApiTest.kt`.
- Create: `online-client/src/test/kotlin/org/triplea/mobile/online/client/GameConnectionTest.kt`.

**Interfaces produced:**
```kotlin
/** Where tokens are kept; the app stores them in private SharedPreferences. */
interface TokenStore { var tokens: TokensDto?; fun clear() }
class MemoryTokenStore : TokenStore

class ServerApi(baseUrl: String, private val tokens: TokenStore, client: OkHttpClient = OkHttpClient()) {
    fun register(username: String, password: String): TokensDto   // stores tokens
    fun login(username: String, password: String): TokensDto
    fun logout()                                                   // clears tokens
    fun maps(): List<MapGameDto>
    fun games(): List<GameInfoDto>
    fun game(id: String): GameInfoDto
    fun createGame(request: CreateGameRequest): GameInfoDto
    fun downloadSave(id: String): Pair<Long, ByteArray>           // version from X-Game-Version
    fun accessToken(): String                                     // refreshes if needed
    val webSocketUrl: String                                       // http->ws, https->wss, + "/ws"
}
class ServerException(val status: Int, message: String) : RuntimeException(message)

class GameConnection(
    private val api: ServerApi,
    private val gameId: String,
    private val ui: HumanPlayerUi,
    private val events: GameEventListener,
    private val listener: Listener,
    client: OkHttpClient = OkHttpClient(),
) {
    interface Listener {
        fun copyChanged(data: GameData, version: Long)    // new copy loaded (bump dataVersion)
        fun questionCancelled()                           // the open question was replaced (reconnect)
        fun connectionState(state: State, detail: String?)
    }
    enum class State { CONNECTING, CONNECTED, RECONNECTING, UPDATE_REQUIRED, CLOSED }
    val data: GameData?                                   // the current copy
    fun open()
    fun close()
    /** Blocking query for the open placement question; call off the main thread. */
    fun placeable(territory: Territory, units: List<GameUnit>): PlaceableUnits
}
```

**Behavior:**
- `ServerApi`:
  - JSON through `ProtocolJson`'s `Json` (or an equal `Json { ignoreUnknownKeys = false }` built the
    same way as `ProtocolJson`). The REST types come from `protocol/.../Rest.kt`, added by milestone
    2b Task 3; read that file for the exact names.
  - A 401 on an authenticated call: refresh once with the refresh token, then retry once. If the
    refresh fails, clear the tokens and throw `ServerException(401, "please log in again")`.
  - Errors: the `ErrorDto.error` text becomes the exception message.
- `GameConnection`:
  - **Open:** the WebSocket to `webSocketUrl` with header `Authorization: Bearer <token>`. The first
    frame is `Hello(PROTOCOL_VERSION, ENGINE_BASE_TAG)`.
  - **Inbound frames:**
    - Decode with `ProtocolJson.decodeServerMessage`.
    - Messages of other games are ignored.
    - `PlaceableResult` completes the pending placeable future directly on the OkHttp thread.
    - `UpdateRequired` sets `State.UPDATE_REQUIRED` and closes.
    - Everything else goes, in order, to one single-thread executor (the "question runner").
  - **On the runner:**
    - `GameVersion(v)`, if `v` is newer than the copy's version: `downloadSave`, then
      `GameDataManager.loadGame(ByteArrayInputStream(bytes))`, replace the copy, and call
      `listener.copyChanged`.
    - `Question(q)`:
      1. Make sure the copy is at least `q.version`; download if not.
      2. `answer = OnlineClient(ui, events).answer(q, copy)`. This blocks until the user answers.
      3. Send `Answer`, unless the connection was replaced meanwhile (generation counter), in which
         case drop it.
    - `GameEvent`: `OnlineClient.event(message, copy)`.
    - `Notice`: `OnlineClient.notice(message, copy)`.
    - `Rejected`: `OnlineClient.rejected(...)`. Check its exact name in `OnlineClient.kt`.
  - **Reconnect:**
    - On failure or unexpected close, reconnect with backoff (1, 2, 4, 8, max 30 s) while not
      `close()`d.
    - On reconnect, `listener.questionCancelled()` is called. The app cancels the pending
      `UiRequest`, so the runner's blocked `answer` ends with an exception, which is caught and
      logged. The server then re-sends `GameVersion` and `Question`.
  - **`close()`:** stop reconnecting, close the socket and shut the runner down.
  - **`placeable`:**
    - Send `PlaceableQuery(openQuestionId, territory.name, units ids)` and wait up to 15 s.
    - Map the result with `toPlaceableUnits(copy)` from `online/`.
    - A timeout gives a `PlaceableUnits` with the error text "server did not answer".

- [ ] **Step 1: Write `ServerApiTest`** (MockWebServer):
  - login stores the tokens;
  - an authenticated call after a 401 refreshes and retries, and the second request carries the
    new token;
  - a failed refresh clears the tokens and throws 401;
  - `downloadSave` reads `X-Game-Version`;
  - `webSocketUrl` maps `http://h:8080` to `ws://h:8080/ws`.
- [ ] **Step 2: Write `GameConnectionTest`** (MockWebServer WebSocket upgrade, a scripted server
  side, and a minimap save made with `MobileEngine` as `online`'s `Minimap.kt` does):
  - the first frame is `Hello`;
  - `GameVersion` then triggers a save download and `copyChanged`;
  - a `Question` (purchase on the minimap) is answered by a scripted `HumanPlayerUi` and arrives as
    `Answer` with the same id;
  - a `PlaceableQuery` is answered while a question is blocked in `ui` (Review Focus 2);
  - `UpdateRequired` gives state `UPDATE_REQUIRED`;
  - after a dropped socket the client reconnects, sends `Hello` again and calls
    `questionCancelled`, and the answer of the cancelled question is never sent (Review Focus 3);
  - an undo in `getMove` (the UI throws `UndoRequest(0)`) arrives as `Reply.Undo(0)` (Review
    Focus 1).
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** `./gradlew :online-client:test :online:test :protocol:test --console=plain`;
  green.

### Task 2: App session seam (no behavior change)

**Files:**
- Create: `app/src/main/java/org/triplea/mobile/app/game/GameSession.kt`:
  ```kotlin
  /** What the UI needs from a running game, local or online. */
  interface GameSession {
      val gameData: GameData
      val mapData: MapData
      val resourceLoader: ResourceLoader
      val isGameOver: Boolean
      /** The active step's delegate as [type] (move, place, ...). */
      fun <T : IRemote> currentDelegate(type: Class<T>): T
      fun saveGame(file: Path)
      fun stop()
  }
  class LocalSession(val local: LocalGameSession) : GameSession { /* delegates 1:1 */ }
  ```
  Check the real types of `getMapData()`/`getResourceLoader()` in `LocalGameSession.java`. Expose
  anything else a call site needs, for example the game thread for the watchdog, on `LocalSession`
  only, and keep that call site local-only.
- Modify (Edit tool):
  - Every app file that names `LocalGameSession` (see the research file, section 2):
    `GameController.kt`, `MoveHelper.kt`, `MapSnapshot.kt`, `BattleStrength.kt`, `GameScreen.kt`,
    `BattleCalcScreen.kt`, `Dialogs.kt` and `RuleDialogs.kt`.
  - The `session` type becomes `GameSession`, and `getCurrentRemoteDelegate(X::class.java)` becomes
    `currentDelegate(X::class.java)`.
  - `GameController.startSession` wraps the new `LocalGameSession` in `LocalSession`.
- Modify: `GameController.ask`. When `request.result.get()` fails with an `ExecutionException`
  whose cause is `org.triplea.mobile.online.UndoRequest`, rethrow that cause unchanged; everything
  else stays as it is. This is the online undo path. The app gets `:online` as a dependency in this
  task: `implementation(project(":online"))` in `app/build.gradle.kts`.
- Add `fun fail(error: Throwable)` to `UiRequest`, which calls `result.completeExceptionally(error)`.

- [ ] **Step 1: Refactor.** Keep every behavior; this is a type change plus the `ask` unwrap.
- [ ] **Step 2: Build** `./gradlew :app:assembleDebug --console=plain` and
  `./gradlew :engine:test --console=plain`; both green.
- [ ] **Step 3: Device check of local play** (`device` skill):
  - New game, minimap or classic, then Start.
  - Make one move.
  - Open the undo list and undo it.
  - In the placement phase, place a unit.
  - Take screenshots; record what you saw in the report.

### Task 3: App online mode

**Files:**
- Modify: `app/build.gradle.kts`:
  - `implementation(project(":online-client"))`;
  - `buildFeatures { buildConfig = true }`;
  - packaging excludes if OkHttp brings duplicate `META-INF` files;
  - R8 `-dontwarn` lines for OkHttp optional platforms in `app/proguard-rules.pro`, only if
    `:app:assembleRelease` complains. Check it once.
- Create: `app/src/debug/res/xml/network_security_config.xml`.
  - The debug copy of the main config, plus `cleartextTrafficPermitted="true"` in the base config.
    This is debug only; release is unchanged.
  - Plus `app/src/debug/AndroidManifest.xml` with `tools:replace="android:usesCleartextTraffic"`
    set to true, if the main manifest's `usesCleartextTraffic="false"` would otherwise block it.
    Check the merged manifest.
- Modify: `AppSettings.kt`:
  - `serverUrl: String = "http://10.0.2.2:8080"`, loaded and saved like the other string settings;
  - online tokens in a separate private SharedPreferences file `online`, through a `TokenStore`
    implementation `PrefsTokenStore` in the app.
- Modify: `SettingsScreen.kt`: a "Server URL" text field, shown only when `BuildConfig.DEBUG`.
- Create: `app/src/main/java/org/triplea/mobile/app/online/OnlineSession.kt`, which implements
  `GameSession`:
  - `gameData`: the connection's current copy.
  - `mapData`/`resourceLoader`: built for the copy's map the way `MobileLaunchAction` builds them
    (read it). Rebuild only when the map name changes.
  - `currentDelegate(type)`: a `java.lang.reflect.Proxy` for `type`.
    - `getMovesMade`/`getPlacementsMade` (and any other read-only getter MoveHelper uses) come from
      the copy's delegate of the current step: `gameData.sequence.step.delegate`, loaded with the
      save.
    - `undoMove(index)`: `GameController.pending.value?.fail(UndoRequest(index))`, then return null.
    - `getPlaceableUnits(units, territory)`: `connection.placeable(territory, units)`.
    - Any other method: `UnsupportedOperationException(method.name)`.
  - `isGameOver`: set when `Event.GameEnded` arrives (through `GameController.gameEnded`) or the
    connection reaches `CLOSED` or `UPDATE_REQUIRED`.
  - `saveGame(file)`: `MobileEngine.saveGame(copy, file)`. Online, the copy is what the device has;
    this lets the player keep a local save.
  - `stop()`: `connection.close()`.
- Modify: `GameController.kt`, add
  `fun startOnline(api: ServerApi, game: GameInfoDto, me: String): OnlineSession`:
  1. `quit()`, then set `humanPlayers` to the seats where `username == me`.
  2. Open a `GameConnection` with `this` as both `ui` and `events`. Its `listener`:
     - `copyChanged` bumps `_dataVersion` and calls `updateStatus(data)`;
     - `questionCancelled` cancels the pending request;
     - `connectionState` goes to a new `StateFlow<String?> onlineState` that the game screen shows
       as a small banner while it is not `CONNECTED`.
  3. Wait (on the calling background thread) until the first copy is loaded, or fail with the error.
  - The watchdog and sounds stay local-only where they need the game thread.
  - `ask()` checks `session?.isGameOver` as before.
- Make sure `MoveHelper.placeableUnits` is never called on the main thread, because online it blocks
  on the network. Check the call site at `GameScreen.kt` around line 603 and move the call to
  `Dispatchers.IO` if it is not there yet.
- Create: `app/src/main/java/org/triplea/mobile/app/ui/OnlineScreen.kt` (route `online`):
  - **Logged out:** the server URL in small text, username and password fields, "Log in" and
    "Register". Errors appear as text.
  - **Logged in:**
    - the username, "Log out" and "Refresh";
    - the list of my games from `GET /games`: map, game, round, current player, and a "Your turn"
      chip when the current player is one of my seats, or "Finished" plus the winners;
    - "New online game".
  - Tapping a running game calls `startOnline` on IO, then navigates to `game`. A finished game
    shows its result only.
  - **"New online game"** is a dialog or sub-screen:
    - pick a game from `GET /maps`, limited to games whose map is installed on this device
      (`MobileEngine.listInstalledGames()`);
    - per nation a choice of Me, Player (username text field), Easy AI, Fast AI, Hard AI or Does
      nothing; default Me for the first nation and Fast AI for the rest;
    - "Create" posts the game and opens it.
  - Errors from `ServerException` appear inline. `UPDATE_REQUIRED` shows "Please update the app".
- Modify: `MainActivity.kt` (route `online`) and `HomeScreen.kt` (an "Online" `OutlinedButton`,
  only when `BuildConfig.DEBUG`).
- `GameScreen` exits to `online` instead of `home` for an online session if that is a one-line
  change; otherwise leave it.

- [ ] **Step 1: Implement.**
- [ ] **Step 2: Build** `:app:assembleDebug` and `:app:assembleRelease`. If release signing
  properties are missing, an unsigned release build is fine. Check that release still has no
  Online button: `BuildConfig.DEBUG` is false there.
- [ ] **Step 3: End-to-end on the emulator** (`device` skill):
  1. Start the 2b server, as its README says:
     - `docker compose up -d postgres` in `../triplea-mobile-server`;
     - `./gradlew :server:run --args=--seed` in the background, with JDK 25.
  2. Install the debug APK and open Online.
  3. Log in as `alice` / `password123`.
  4. Create a minimap game: alice plays the first nation, the rest Fast AI.
  5. Play the first purchase and the move phase: one move, undo it, move again, end. Then place.
  6. Screenshot each step. Check that the server log shows the answers.
  7. Kill and restart the server, then check that the app reconnects and the question returns.
  8. Stop the server afterwards.

  If the emulator is not running, start the AVD named in the `device` skill (or an existing AVD,
  `emulator -list-avds`) yourself.

### Task 4: Docs and two-player check

**Files:**
- Modify: `docs/DEVELOPMENT.md`:
  - the module table gets an `online-client` row;
  - the "Online play" section gets an "In the app" paragraph: the `GameSession` seam,
    `OnlineSession` and its delegate proxy, undo, placeable, debug-only entry and server URL;
  - the security notes: debug-only cleartext, tokens in private prefs, saves only downloaded;
  - the Building section: `:online-client:test`.
- Create: `docs/online-test-checklist.md`, the manual checklist from spec section 6: two emulators
  against the server, a phone over Wi-Fi, disconnect and reconnect, a server restart during a game.
  Use concrete steps and commands; the desktop client part is marked "milestone 4".
- [ ] **Step 1: Write the docs.**
- [ ] **Step 2: Two-player run, if a second emulator can be started.** Start a second AVD. Then:
  - alice on emulator 1 and bob on emulator 2;
  - create the game with bob as Player for the second human nation;
  - play one round each;
  - take screenshots.

  If a second emulator cannot run (RAM), run bob with the 2b test phone (`TestPhone`) through a
  small Gradle `JavaExec`. If that is not possible either, say so in the report.
- [ ] **Step 3: Full test run** `./gradlew :protocol:test :online:test :online-client:test :engine:test`
  (mobile) and `./gradlew :server:test` (server); all green.
