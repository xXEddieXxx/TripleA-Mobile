# Development notes

Technical documentation for TripleA Mobile. For an overview of the app, see the
[README](../README.md).

## Project layout

| Module    | What it is                                                                                   |
|-----------|----------------------------------------------------------------------------------------------|
| `engine/` | The TripleA game engine (delegates, data model, XML parser, AI), stripped of Swing/AWT, networking, lobby, chat and desktop forum posting (Play by Forum has its own client in `org.triplea.mobile.forum`). Plain Java 17 library, runs on the JVM and on Android. |
| `app/`    | The Android application (Kotlin, Jetpack Compose). Map rendering, phase interaction, dialogs. |

### Engine

The engine sources were copied from the desktop project (`game-core`, `ai`, `map-data`,
`domain-data`, `xml-reader`, `java-extras`) and then reduced. It is synced with the upstream
release tag in `engine/src/main/resources/META-INF/triplea/product.properties` (upstream keeps a
placeholder there); *About* shows it after seven taps on the version. To sync, diff the upstream
engine modules from that tag to the new one, apply the changes, and update the tag in that file.
Notable changes compared to the desktop engine:

- `java.awt.{Point,Polygon,Rectangle,Dimension,Color}` are replaced by `org.triplea.geom.*`.
- `javax.swing.tree.*` (used by the game history) is replaced by `org.triplea.tree.*`.
- `ResourceLoader` uses plain file lookups instead of a `URLClassLoader`.
- `ClientSetting` is an in-memory stub holding only the settings the engine reads.
- Websocket message types, `PbemMessagePoster` and `Chat` are compile-time stubs. Play by Forum
  does not post through `PbemMessagePoster`: the app posts while the game thread waits at the
  end-turn step (see *Play by Forum* below).
- `games.strategy.triplea.ui.history.HistoryLog` keeps only the turn summary text of the desktop
  class (no Swing dialog, no territory/dice/production summaries); it builds the forum post.
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

The mobile specific API lives in `org.triplea.mobile`:

- `MobileEngine` – configuration (data folder), map discovery, parse/load/save games
- `LocalGameSession` – creates and runs a local `ServerGame`
- `HumanPlayerUi` / `HumanPlayerUiAdapter` – the blocking questions the engine asks a human
- `MobilePlayer` – the human `Player` implementation (port of the desktop `TripleAPlayer`)
- `GameEventListener` / `MobileDisplay` – battle and message events
- `UnitImageNames` – unit icon file naming rules
- `forum.*` – Play by Forum on the two NodeBB forums desktop posts to, `Forum`
  (forums.triplea-game.org: games in category 45; www.axisandallies.org/forums: new games in
  "Play Boardgames" 16, games anywhere below "Play Online Axis & Allies" 65; each with desktop's
  `FORUM_POSTER_NAME`, so saves cross between desktop and mobile; every `GameThread` carries its
  forum): `NodeBbClient` (port of desktop
  `NodeBbGameThreads` from the `feat/modern-ui` branch: games, threads, save downloads, uploads,
  replies, new threads; plus editing and deleting the player's own posts and loading post
  pictures), `ForumMarkdown` (the editor's Markdown as HTML, for the preview), `MartiDice` (dice.triplea-game.org as `IRandomSource`, never a local
  fallback), `PbfPost` (desktop's exact post title, text and save name), `TurnPoster` (upload
  once, retry the reply), `PbfProperties` (desktop's game property names) and `Http` (the one
  network seam, `HttpURLConnection`; forum requests follow no redirect so the session cookie
  stays on the forum). Tests in `engine/src/test/java/org/triplea/mobile/forum` answer from fixed
  JSON.

`engine/src/test` contains a smoke test that runs an AI-only game on the minimap for three rounds
and round-trips a save game.

### App

- `GameController` – singleton that owns the session, implements `HumanPlayerUi` and publishes
  `UiRequest`s (move, purchase, place, battle, casualties, ...) as Kotlin flows
- `MapSnapshot` – rendering data (territory paths, owner colors, unit stacks) built under the
  engine read lock whenever the game data changes
- `MapView` – Compose canvas with pinch zoom/pan, tile drawing and tap-to-territory hit testing
- `GameScreen` / `Dialogs` – phase panel and the dialogs for engine questions
- `forum/` – Play by Forum: `ForumLoginScreen` (the forum's login page in a WebView; the
  `express.sid` cookie is kept by `ForumAccount`, AES-GCM encrypted with an Android Keystore
  key), `ForumGamesScreen` (my games with a "your turn" guess as desktop makes it, browse
  the first 3 pages of the forum's game category, a switch between the two forums, each with its
  own sign-in, search and a 60-day inactive filter via `GameSearch`), `ForumThreadScreen` (posts on cards with their pictures, a "new posts" line,
  pull to refresh, quote / edit / delete per post, a reply kept as a draft per thread with
  pictures and the player's own saves attached, Join (Start game in a one-page thread without a
  save: the setup's game list and dice emails, the first turn posted to that thread), open a save with a
  missing-map download; each save asks Watch or Join: Watch is `GameController.watchSave`, a
  session whose game loop is never started, so nothing is asked, rolled or posted),
  `ForumTurnSheet` (end-turn review: Keep playing or Send; the same dialog posts the game as it
  stands from the game menu's "Post save to forum", desktop's "Manual Gamesave Post" with the
  turn so far, not marking the turn posted; "Ask opponent" in every must-answer dialog and in the
  battle window footer opens it with the question quoted and a screenshot (`ForumScreenshot`,
  PixelCopy of the game window) attached, then offers to stop until the answer; the save is taken
  while the engine waits for the answer and resumes at that question, see
  `HumanPlayerFlowTest.saveWhileAskedResumesAtTheQuestion`), `ForumEditor` (the shared Markdown
  editor: toolbar, preview, @mention of the thread's players), `ForumTurnJob` (a JobScheduler
  job every 15 minutes, Android's shortest period, while signed in, and once when the app comes to
  the front: one "your turn" notification per unseen last post of a game active in the last 60
  days; the tap opens the thread; the forum has no web push plugin, so there is no push. While
  open, My games refreshes quietly every minute and a thread offers newer posts every minute),
  `ForumThreadState` (drafts, last
  post seen, last notified, and games the player stopped following: off My games, no
  notifications, a "Stopped" chip lists them; the bell in the thread's top bar switches it),
  `ForumThreadPicker` (a new game in an existing thread: the player's and the forum's open threads,
  searchable, those opened in the app most recently first) and
  `ForumSaves` (which thread a downloaded save belongs to, and the rule that a forum game keeps
  only its newest save: a download, a sent turn or a manual post deletes the thread's older saves;
  a turn put off with "send later" survives downloads; the load screen applies the rule to saves
  already on the device and shows the thread title). A forum game's setup writes the dice
  emails: the player's as `DICE_SERVER_EMAIL_1`, every other player's in `DICE_SERVER_EMAIL_2`
  separated by spaces (desktop's "CC"; "Add player" adds one); every address used or read from a
  save is remembered (`AppSettings.diceContacts`) and offered in each field. `GameController` sets
  `FORUM_POSTER_NAME`/`FORUM_POSTER_TOPIC_ID` and the dice source.
  At Send it marks the turn posted, saves as `triplea_<topic>_<round><abc>.tsvg`, uploads and
  replies, as desktop does. Unlike desktop, the player's message stands right under the title, set
  apart by a rule from the game line and summary (`PbfPost.content`); the title stays first, as
  desktop reads the turn from the post's teaser. The thread view shows the message highlighted
  above the summary for both layouts (`PbfPost.parts`). The turn review lists the nations the
  post covers ("Keep playing") and says when the next nation is an ally (`PbfTurn.nextAlly`).

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
of both modules and the server computes the findings. The bundled maps and images under
`assets/` are excluded.

```powershell
$env:JAVA_HOME = "C:\Users\<you>\AppData\Local\Programs\Android Studio\jbr"   # the scanner needs Java 17+
$env:SONAR_TOKEN = "<token>"
.\gradlew :engine:testClasses :app:compileDebugKotlin sonar    # the sonar task itself does not compile
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

Branches: `develop` has everything, including experimental work. `main` holds only the commits
picked for the next release (`git cherry-pick` from `develop`); the Release workflow is started on
`main`. The branch is not called `dev` because the rolling pre-release below owns the tag `dev`.

Every push to `develop` runs `.github/workflows/android.yml`: engine tests, debug APK, and a rolling
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
- The app asks for no permissions beyond INTERNET, VIBRATE, POST_NOTIFICATIONS,
  RECEIVE_BOOT_COMPLETED (the forum's "your turn" check survives a restart) and
  ACCESS_NETWORK_STATE (JobScheduler refuses a job that waits for a network without it; on
  Android 16 a missing one crashed the app at start once signed in) and stores
  everything in its private app folder.
- Post pictures load over https only; the session cookie goes with forum-hosted pictures only.
  The Markdown preview escapes the player's text before it adds its own tags.
- Play by Forum: each forum's session cookie is stored encrypted (Android Keystore AES-GCM),
  never logged, sent only to the forum it belongs to (no redirects followed), and save games are only
  downloaded from the forum's own host and path. The password never passes through the app: the
  login is the forum's own page in a WebView.

## How a game runs

1. `SetupScreen` parses the chosen game XML and lets the user assign Human/AI per nation.
2. `LocalGameSession.create` builds the `ServerGame` with a `LocalNoOpMessenger` (no network) and
   starts the game loop on a background thread.
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

## Edit mode

Edit mode lets a player correct a game by hand, like the desktop "Edit Mode" tools: add and
remove units, change a territory's owner, unit damage, PUs and other resources, technologies and
political relationships. It also lets the player move own units freely, without movement limits.

- The engine's edit delegate is unchanged. The app calls it through `EditHelper`
  (`app/.../game/`); the tool strip and dialogs live in `EditTools.kt`. Every `EditHelper` call
  takes the engine lock, so the dialogs run them off the main thread and show the engine's
  refusal text, if any.
- It is offered only at a phase-level human request (the map is free), and the engine switches it
  off for AI turns.
- In edit mode the engine's casualty questions kill nothing by default (desktop shows a free
  chooser), so ending combat move and the battle list first ask to turn edit mode off (a lone
  battle is fought without the list, `fightBattleIfOnlyOne`). Casualty questions still asked in
  edit mode (AA fire, a hotseat defender) accept any losses from none up to every listed unit.
- Play by Forum: as on desktop, edits are written to the history as "EDIT: ..." and so to the
  posted turn summary; the turn review shows a note when the summary has such lines. Unlike
  desktop, `GameController.waitForEndTurn` turns edit mode off before the turn summary, so the
  next player does not open the posted save in edit mode. A watched save (`watching`) never shows
  edit mode.
- Relationship changes in the politics dialog are not checked against the map's rules (as on
  the desktop).

## Known gaps

- Technology, repairs, scramble, kamikaze suicide attacks and the random start's "pick territory
  and units" question have dialogs (`RuleDialogs.kt`), but no allied help paying for tech rolls
  (`whoPaysHowMuch` is always empty) and no fuel check before scrambling; the engine rejects a
  scramble the player cannot fuel.
- The remaining `HumanPlayerUiAdapter` defaults: "select fixed dice" (edit mode) rolls randomly.
- Save games can be exchanged with the desktop client as long as both run the same engine
  version: the history is serialized as a change list (`History.writeReplace` to
  `SerializedHistory`), so no Swing classes end up in the file. A save written by a newer
  engine may still fail to load on the other side.
- Maps that switch territory effect markers off in `map.properties` show their effects only in
  the zone panel, as the desktop client does.
- Play by Forum covers forums.triplea-game.org and www.axisandallies.org/forums (no other NodeBB
  forum). www.axisandallies.org does not work from the app yet: its Cloudflare bot protection answers
  every API request of the app with a challenge (403, `Cf-Mitigated: challenge`), so after the web
  login the app shows that (`NodeBbClient.BlockedException`) instead of the games. Its login itself
  works (`express.sid` below `/forums`); RSS feeds are challenged the same way. It is hidden for now:
  `ForumAccount.shown` lists only the TripleA forum (the switch shows from two forums on). Shows a post's
  pictures below its text rather than in line, notifies a "your turn" from the same guess as the
  games list (someone else posted last), and does not know which nations a player owns (desktop
  does not either).
