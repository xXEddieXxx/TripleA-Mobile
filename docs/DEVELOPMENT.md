# Development notes

Technical documentation for TripleA Mobile. For an overview of the app, see the
[README](../README.md).

## Project layout

| Module    | What it is                                                                                   |
|-----------|----------------------------------------------------------------------------------------------|
| `engine/` | The TripleA game engine (delegates, data model, XML parser, AI), stripped of Swing/AWT, networking, lobby, chat and forum posting. Plain Java 17 library, runs on the JVM and on Android. |
| `app/`    | The Android application (Kotlin, Jetpack Compose). Map rendering, phase interaction, dialogs. |

### Engine

The engine sources were copied from the desktop project (`game-core`, `ai`, `map-data`,
`domain-data`, `xml-reader`, `java-extras`) and then reduced. Notable changes compared to the
desktop engine:

- `java.awt.{Point,Polygon,Rectangle,Dimension,Color}` are replaced by `org.triplea.geom.*`.
- `javax.swing.tree.*` (used by the game history) is replaced by `org.triplea.tree.*`.
- `ResourceLoader` uses plain file lookups instead of a `URLClassLoader`.
- `ClientSetting` is an in-memory stub holding only the settings the engine reads.
- Websocket message types, `PbemMessagePoster` and `Chat` are compile-time stubs.
- Language level and library usage are Java 17 (no `List.getFirst()` etc.).
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

Every push to `main` runs `.github/workflows/android.yml`: engine tests, debug APK, and a rolling
pre-release named **dev** with the APK attached twice: once with the date and commit in its name
(`triplea-mobile-dev-<date>-<commit>.apk`) and once as `triplea-mobile-dev.apk`, so the dev link
in the README always points at the newest build. The release page is
`https://github.com/xXEddieXxx/TripleA-Mobile/releases/tag/dev`. Android only updates an installed
app when the new APK is signed with the same key; the workflow header explains how to store a
fixed debug keystore as the secret `DEV_KEYSTORE_B64`.

## Security notes

- Network: only HTTPS to GitHub (map list and map archives); cleartext traffic is refused by the
  network security config and by the download code, also after redirects.
- Map archives are unpacked with path traversal checks and size/entry limits.
- Save games are Java serialized; they are read through `SafeObjectInputStream`, which only
  resolves engine, JDK and Guava classes, so a crafted save cannot instantiate arbitrary classes.
- Game XML is parsed with DTDs and external entities disabled.
- The app asks for no permissions beyond INTERNET and VIBRATE and stores everything in its
  private app folder.

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
