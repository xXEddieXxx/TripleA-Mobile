# TripleA Mobile

**[Download the newest dev APK](https://github.com/xXEddieXxx/TripleA-Mobile/releases/download/dev/triplea-mobile-dev.apk)**
(Android 10 or newer; built automatically from `main`, see [Dev builds](#dev-builds))

An Android port of the [TripleA](https://github.com/triplea-game/triplea) turn based strategy
engine with a touch UI written in Jetpack Compose.

Scope of this first version, on purpose:

- single device only: hot seat and human vs. AI (Easy, Fast, Hard/Pro AI)
- no lobby, no network multiplayer, no play-by-email
- two maps are bundled (`minimap`, `world_war_ii_classic`); the map browser downloads any map
  from the desktop client's map list (`triplea_maps.yaml`), and maps can also be copied into the
  app's `downloadedMaps` folder in the same layout as on the desktop
- save/load of local games (`.tsvg` files, not compatible with desktop saves)

## License

TripleA Mobile is free software under the **GNU General Public License v3.0** (see `LICENSE`).
It is an unofficial port and is not affiliated with the TripleA project.

It is based on the [TripleA](https://github.com/triplea-game/triplea) game engine,
Copyright © the TripleA developers, licensed under the GPL-3.0. The engine sources in `engine/`
were modified for Android as described below (Swing/AWT and networking removed, Java 17 language
level, Android resource loading); the unit images, flags and sounds in `app/src/main/assets` also
come from the TripleA project. The complete corresponding source of the app is this repository.

Third party libraries (Android Jetpack, Kotlin, Guava, Gson, Apache Commons, Woodstox, SnakeYAML
Engine, SLF4J, Lombok, Jakarta XML Binding, JSR-305, JetBrains annotations, desugar_jdk_libs) are
used under their permissive licenses; the app lists them with their licenses under "About".

Maps are made by the TripleA community and live in their own repositories under
[triplea-maps](https://github.com/triplea-maps); each map belongs to its authors and carries its
own terms. The bundled `minimap` comes from the TripleA repository (GPL-3.0); the bundled
`world_war_ii_classic` comes from its triplea-maps repository, which states no license, and is
included in the same way the desktop client distributes it.

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

## Dev builds

Every push to `main` runs `.github/workflows/android.yml`: engine tests, debug APK, and a rolling
pre-release named **dev** with the APK attached twice: once with the date and commit in its name
(`triplea-mobile-dev-<date>-<commit>.apk`) and once as `triplea-mobile-dev.apk`, so the direct
link at the top of this file always points at the newest build. The release page is
`https://github.com/xXEddieXxx/TripleA-Mobile/releases/tag/dev`. Android only updates an installed app when
the new APK is signed with the same key; the workflow header explains how to store a fixed debug
keystore as the secret `DEV_KEYSTORE_B64`.

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

## Known gaps

- Technology rolls and repairs use engine defaults (no UI yet); politics and user actions have dialogs.
- Scramble, kamikaze and "pick territory and units" questions use the adapter defaults.
- Sea transport loading is automatic (units are mapped to transports in the destination sea zone).
- No map notes, no battle calculator UI.
