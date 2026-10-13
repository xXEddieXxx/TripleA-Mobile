# Milestone 1: Engine Resync Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Bring the mobile engine to desktop tag `2026-10.16.15815` and prove that the mobile
engine loads and plays on a save written by the desktop engine at that tag.

**Architecture:** The mobile engine is a trimmed copy of the desktop engine from about
`b1f796364f` (2026-09-20). We pull in the three upstream changes it is missing and add a small
script that shows how far the copy is from any tag. The deliverable is a fixture save written by
the real desktop engine at the tag, plus a game-level test that loads it in the mobile engine and
plays on. The server built in milestone 2 runs the desktop engine and hands such saves to the app.

**Tech Stack:** Java 17 (engine), JUnit 5 + AssertJ, Gradle; the desktop build (needs a local
JDK 25) only to generate the fixture; POSIX shell (Git Bash).

**Spec:** `docs/superpowers/specs/2026-10-12-multiplayer-lobby-design.md`, section 1 "Engine
resync in this repo" and section 6 "This repo".

## Global Constraints

- Base tag: `2026-10.16.15815` (commit `1f6b0edfd2`).
- Desktop checkout: `C:\dev\triplea-game.github.io\triplea`. It is read-only, so never write into
  it. To build from it, export it with `git archive` into a temp folder.
- Engine code stays Java 17 (`options.release.set(17)`): no `List.getFirst()`/`removeFirst()`.
- Files under `engine/src` and `app/src` are edited with Edit/Write only, never with shell tools.
- Engine tests stay at game level: add test methods to `LocalGameSmokeTest` or
  `HumanPlayerFlowTest`, never a new test class for engine internals.
- `./gradlew` needs the `build` skill loaded first.
- No `git commit`, `git push` or `git add`. Each task ends with the changes left in the working
  tree and a suggested commit message.
- Every edit to copied upstream code that is not a straight copy from the tag gets a `// mobile:`
  comment, as `docs/DEVELOPMENT.md` describes.

## Review Focus

1. **A desktop save that holds AWT or Swing objects** (e.g. a map with a color game option uses
   `ColorProperty` with `java.awt.Color`). On Android this must fail with the normal "could not
   load" message, never crash the app. Pinned by Task 2: dropping `java.awt.` from the allow-list
   makes the JVM test fail exactly where Android would.
2. **A desktop save taken mid-turn** (not at a round boundary) loads and continues from the step it
   was saved in. Pinned by Task 2: the fixture is written 3 steps into round 2.
3. **A desktop save names a delegate class the mobile engine lacks, or a class that is not a
   delegate.** Loading returns empty (the app shows its error) without running that class's static
   initializer. Pinned by Task 3 through the existing load path. The behaviour is checked by
   reading the code; no crafted-save test, because that would be a class-level test.
4. **A save from a newer desktop tag.** It loads or fails cleanly, but never half-loads. This is
   documented in `docs/DEVELOPMENT.md` (Task 4); milestone 2 pins the server and app versions
   together.
5. **The resync breaks local play.** `LocalGameSmokeTest` and `HumanPlayerFlowTest` must stay green
   after every task.

## File Structure

| File | Action | Responsibility |
|---|---|---|
| `engine/src/main/java/games/strategy/triplea/attachments/UnitAttachment.java` | modify | upstream fix `aa382fc97e` (movement/placement limit mixup) |
| `engine/src/main/java/games/strategy/engine/data/gameparser/GameParser.java` | replace with tag version | upstream cleanup `1f6b0edfd2` |
| `engine/src/main/java/org/triplea/util/ExitStatus.java` | replace with tag version | upstream shutdown guard `2a8b4401d9` |
| `engine/upstream/diff.sh` | create | lists files that differ from a desktop tag |
| `engine/upstream/WriteDesktopSaveTest.java` | create | runs inside the exported desktop build and writes the fixture |
| `engine/upstream/generate-desktop-save.sh` | create | exports the tag, runs the writer, copies the fixture |
| `engine/src/test/resources/saves/desktop-2026-10.16.15815.tsvg` | create (generated) | the fixture |
| `engine/src/main/java/games/strategy/engine/framework/SafeObjectInputStream.java` | modify | drop `java.awt.` |
| `engine/src/test/java/org/triplea/mobile/LocalGameSmokeTest.java` | modify | new test `desktopSaveLoadsAndPlaysOn` |
| `engine/src/main/java/games/strategy/engine/framework/GameDataManager.java` | modify | `loadDelegates` without static init |
| `docs/DEVELOPMENT.md` | modify | base tag, upstream tools, save compatibility |

`engine/upstream/` is outside `src`, so Gradle never compiles `WriteDesktopSaveTest.java` in this
repo.

---

### Task 1: Pull the three missing upstream changes and add the diff script

**Files:**
- Modify: `engine/src/main/java/games/strategy/triplea/attachments/UnitAttachment.java:2388,2458`
- Replace: `engine/src/main/java/games/strategy/engine/data/gameparser/GameParser.java`
- Replace: `engine/src/main/java/org/triplea/util/ExitStatus.java`
- Create: `engine/upstream/diff.sh`

**Interfaces:**
- Consumes: nothing.
- Produces: `engine/upstream/diff.sh <desktop checkout> [tag]`. It prints `mobile-only <path>`
  and `differs <path>` lines and ends with `identical: <n>`. Task 4 documents it.

- [ ] **Step 1: Write the diff script** `engine/upstream/diff.sh`:

```sh
#!/bin/sh
# Compares engine/src/main/java with the desktop engine at a tag.
# usage: engine/upstream/diff.sh <desktop checkout> [tag]
set -eu
DESKTOP=${1:?path to the desktop triplea checkout}
TAG=${2:-2026-10.16.15815}
SRC=$(cd "$(dirname "$0")/../src/main/java" && pwd)
LIST=$(mktemp)
git -C "$DESKTOP" ls-tree -r --name-only "$TAG" | grep '/src/main/java/.*\.java$' > "$LIST"
same=0
cd "$SRC"
for f in $(find . -name '*.java' | sed 's|^\./||' | sort); do
  m=$(grep -F "/src/main/java/$f" "$LIST" | head -1 || true)
  if [ -z "$m" ]; then
    echo "mobile-only $f"
  elif git -C "$DESKTOP" show "$TAG:$m" | diff -q --strip-trailing-cr - "$f" >/dev/null; then
    same=$((same + 1))
  else
    echo "differs     $f"
  fi
done
rm -f "$LIST"
echo "identical: $same"
```

- [ ] **Step 2: Record the baseline.**

Run: `sh engine/upstream/diff.sh ../triplea | tail -1`
Expected: `identical: 613`. Also check `sh engine/upstream/diff.sh ../triplea | grep -c differs`
→ `71`.

- [ ] **Step 3: Apply upstream fix `aa382fc97e` in `UnitAttachment.java`.** Two lines change.
  The file keeps its mobile `BOMBARD_PROPERTY` rename, so it is edited, not replaced.

In `setMovementLimit(final String value)`:
```java
    movementLimit = parseStackingLimit("movementLimit", value);
```
(was `placementLimit = ...`)

In `getPlacementLimitOrNull()`:
```java
    return placementLimit;
```
(was `return movementLimit;`)

- [ ] **Step 4: Replace `GameParser.java` and `ExitStatus.java` with the tag versions.** The
  mobile copies equal the base `b1f796364f` exactly, so the tag version is the merge. Run
  `git -C ../triplea show 2026-10.16.15815:game-app/game-core/src/main/java/games/strategy/engine/data/gameparser/GameParser.java`
  and the same for `game-app/game-core/src/main/java/org/triplea/util/ExitStatus.java`. Then
  write each output over the mobile file with the Write tool; no shell redirect into `engine/src`.

- [ ] **Step 5: Run the engine tests** (load the `build` skill first).

Run: `./gradlew :engine:test`
Expected: BUILD SUCCESSFUL. `LocalGameSmokeTest` and `HumanPlayerFlowTest` pass.

- [ ] **Step 6: Check the diff script.**

Run: `sh engine/upstream/diff.sh ../triplea | tail -1` → `identical: 615`, and
`sh engine/upstream/diff.sh ../triplea | grep -c differs` → `69`.

- [ ] **Step 7: Leave it in the working tree.** Suggested message:
  `engine: pull upstream fixes up to 2026-10.16.15815, add upstream diff script`

---

### Task 2: Desktop-written save loads and plays on in the mobile engine

**Files:**
- Create: `engine/upstream/WriteDesktopSaveTest.java`
- Create: `engine/upstream/generate-desktop-save.sh`
- Create (generated): `engine/src/test/resources/saves/desktop-2026-10.16.15815.tsvg`
- Modify: `engine/src/main/java/games/strategy/engine/framework/SafeObjectInputStream.java`
  (`ALLOWED_PREFIXES`)
- Test: `engine/src/test/java/org/triplea/mobile/LocalGameSmokeTest.java`

**Interfaces:**
- Consumes: the minimap XML at `engine/src/test/resources/maps/minimap/map/games/minimap.xml`, and
  the existing test helpers `NoInputUi` and `NoOpEvents` in `LocalGameSmokeTest`.
- Produces: the classpath resource `/saves/desktop-2026-10.16.15815.tsvg`, used again in
  milestone 2.

- [ ] **Step 1: Write the failing test.** Add it to `LocalGameSmokeTest`, after
  `aiOnlyGameRunsSeveralRounds`. The `@BeforeAll` minimap install already covers the map:

```java
  @Test
  void desktopSaveLoadsAndPlaysOn() throws Exception {
    // written by the desktop engine at the base tag, see engine/upstream/generate-desktop-save.sh
    final Path save =
        Path.of(
            LocalGameSmokeTest.class
                .getResource("/saves/desktop-2026-10.16.15815.tsvg")
                .toURI());
    final GameData gameData = MobileEngine.loadSaveGame(save).orElseThrow();
    final int round = gameData.getSequence().getRound();
    final String step = gameData.getSequence().getStep().getName();
    assertThat(round).isEqualTo(2);

    final LocalGameSession session =
        LocalGameSession.create(gameData, Map.of(), new NoInputUi(), new NoOpEvents());
    session.setUpForSteps();
    int steps = 0;
    while (gameData.getSequence().getRound() == round && steps < 200 && !session.isGameOver()) {
      session.runNextStep();
      steps++;
    }
    assertThat(steps).as("continued from step %s", step).isPositive();
    assertThat(gameData.getSequence().getRound() > round || session.isGameOver()).isTrue();
    session.stop();
  }
```

- [ ] **Step 2: Run it and confirm it fails.**

Run: `./gradlew :engine:test --tests org.triplea.mobile.LocalGameSmokeTest.desktopSaveLoadsAndPlaysOn`
Expected: FAIL with a `NullPointerException` from `getResource`, because the fixture does not
exist yet.

- [ ] **Step 3: Drop `java.awt.` from the allow-list in `SafeObjectInputStream`.** Android has no
  AWT, so on the phone such a class can never resolve. Without the entry, the JVM test fails where
  the phone would. Remove this line from `ALLOWED_PREFIXES`:

```java
          "java.awt.",
```

Then add one line to the class Javadoc: `{@code java.awt} is not allowed: Android has no AWT, so
tests on the JVM must fail where the phone would.`

- [ ] **Step 4: Write the fixture writer** `engine/upstream/WriteDesktopSaveTest.java`. It runs
  inside the exported desktop build (`game-app/smoke-testing`) and reuses the desktop
  `GameTestUtils`:

```java
package games.strategy.engine.data;

import games.strategy.engine.framework.GameDataManager;
import games.strategy.engine.framework.ServerGame;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Not a test: writes the TripleA Mobile fixture save. Run by generate-desktop-save.sh. */
class WriteDesktopSaveTest {
  @BeforeAll
  static void setUp() throws Exception {
    GameTestUtils.setUp();
  }

  @Test
  void writeSave() throws Exception {
    final ServerGame game = GameTestUtils.setUpGameWithAis("minimap.xml");
    game.setStopGameOnDelegateExecutionStop(true);
    while (!game.isGameOver() && game.getData().getSequence().getRound() < 2) {
      game.runNextStep();
    }
    // a few steps into round 2, so the save is taken mid-turn
    for (int i = 0; i < 3 && !game.isGameOver(); i++) {
      game.runNextStep();
    }
    try (OutputStream out = Files.newOutputStream(Path.of(System.getenv("DESKTOP_SAVE_OUT")))) {
      GameDataManager.saveGame(out, game.getData());
    }
    game.stopGame();
  }
}
```

- [ ] **Step 5: Write the generator** `engine/upstream/generate-desktop-save.sh`:

```sh
#!/bin/sh
# Regenerates the desktop-written fixture save: minimap, all AI, 3 steps into round 2.
# usage: engine/upstream/generate-desktop-save.sh <desktop checkout> [tag]
# Needs internet on the first run (Gradle dependencies) and a locally installed JDK 25 that Gradle auto-detects.
set -eu
DESKTOP=$(cd "${1:?path to the desktop triplea checkout}" && pwd)
TAG=${2:-2026-10.16.15815}
HERE=$(cd "$(dirname "$0")" && pwd)
OUT="$HERE/../src/test/resources/saves/desktop-$TAG.tsvg"
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
git -C "$DESKTOP" archive "$TAG" | tar -x -C "$WORK"
cp "$HERE/WriteDesktopSaveTest.java" \
  "$WORK/game-app/smoke-testing/src/test/java/games/strategy/engine/data/"
cp "$HERE/../src/test/resources/maps/minimap/map/games/minimap.xml" \
  "$WORK/game-app/smoke-testing/src/test/resources/map-xmls/"
mkdir -p "$(dirname "$OUT")"
(cd "$WORK" && DESKTOP_SAVE_OUT="$OUT" ./gradlew --no-daemon \
  :smoke-testing:test --tests games.strategy.engine.data.WriteDesktopSaveTest)
ls -l "$OUT"
```

- [ ] **Step 6: Generate the fixture.**

Run: `sh engine/upstream/generate-desktop-save.sh ../triplea` (timeout 10 min)
Expected: BUILD SUCCESSFUL, then an `ls` line for `desktop-2026-10.16.15815.tsvg` (tens of KB).
If `GameTestUtils.setUpGameWithAis` cannot find `minimap.xml`, check the working directory: the
desktop test reads `src/test/resources/map-xmls/` relative to `game-app/smoke-testing`.

- [ ] **Step 7: Run the test.**

Run: `./gradlew :engine:test --tests org.triplea.mobile.LocalGameSmokeTest.desktopSaveLoadsAndPlaysOn`
Expected: PASS.

If it fails, the log line `Error loading game data` names the cause. Fix the mobile engine, never
the fixture:
- **`InvalidClassException ... class not allowed in a save game`** for `java.awt.*` or
  `javax.swing.*`: find the field that holds it in the desktop class (e.g.
  `ColorProperty.color`). Give the mobile class a matching serial form: the same field names and
  types it can resolve, plus `readObject`/`readResolve` mapping to `org.triplea.geom.*`. Mark it
  `// mobile:`.
- **`InvalidClassException ... serialVersionUID`:** copy the desktop `serialVersionUID` into the
  mobile class.
- **`ClassNotFoundException`** for a class removed in mobile: add a minimal serializable stub with
  the desktop name and `serialVersionUID`, as is already done for `PbemMessagePoster`.

After each fix, re-run Step 7, and repeat until it passes.

- [ ] **Step 8: Run all engine tests.**

Run: `./gradlew :engine:test`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 9: Leave it in the working tree.** Suggested message:
  `engine: desktop-written save fixture and load test, drop java.awt from save allow-list`

---

### Task 3: `loadDelegates` without static initialization of foreign classes

**Files:**
- Modify: `engine/src/main/java/games/strategy/engine/framework/GameDataManager.java:103-107`
- Test: covered by `LocalGameSmokeTest.aiOnlyGameRunsSeveralRounds` (mobile save round-trip) and
  `desktopSaveLoadsAndPlaysOn` (desktop save)

**Interfaces:**
- Consumes: nothing new.
- Produces: no API change.

`Class.forName(className)` runs the static initializer of any class named in the save before
`asSubclass` rejects it. With `initialize = false`, only a real `IDelegate` is ever created, and
its class is initialized only at that point.

- [ ] **Step 1: Change the lookup.**

```java
        // mobile: initialize = false, so a crafted save cannot run a static initializer of a
        // non-delegate class before asSubclass rejects it
        instance =
            Class.forName(className, false, GameDataManager.class.getClassLoader())
                .asSubclass(IDelegate.class)
                .getDeclaredConstructor()
                .newInstance();
```

- [ ] **Step 2: Run the engine tests.**

Run: `./gradlew :engine:test`
Expected: BUILD SUCCESSFUL, because both save round-trips still load their delegates.

- [ ] **Step 3: Leave it in the working tree.** Suggested message:
  `engine: load save-game delegate classes without static initialization`

---

### Task 4: Document the base tag and the upstream tools

**Files:**
- Modify: `docs/DEVELOPMENT.md`, sections "Engine" (after the paragraph "The engine sources were
  copied …") and "Known gaps" (the save-game bullet)

- [ ] **Step 1: Add the base tag and the tools to "Engine",** right after the first paragraph:

```markdown
The engine is based on desktop tag `2026-10.16.15815` (commit `1f6b0edfd2`); the multiplayer
server runs the desktop engine at the same tag. `engine/upstream/diff.sh <desktop checkout> [tag]`
lists the files that differ from a tag; apart from the changes below, all differences are
adaptations marked in the code. To move to a newer tag: list the desktop commits since the base
tag that touch the files the engine has, port them, update the tag here, in
`engine/upstream/*.sh` and in the server repo, then regenerate the desktop save fixture with
`engine/upstream/generate-desktop-save.sh <desktop checkout>` (needs internet; builds the desktop
engine with Java 25 in a temp folder).
```

- [ ] **Step 2: Add the allow-list and delegate fixes** to the bullet list of engine changes:

```markdown
- `SafeObjectInputStream` does not allow `java.awt.*` (Android has none), so a save that carries
  AWT objects fails on the JVM tests exactly as on the phone. `GameDataManager.loadDelegates`
  loads delegate classes without initializing them before the `IDelegate` check.
```

- [ ] **Step 3: Rewrite the save-game bullet in "Known gaps":**

```markdown
- Save games can be exchanged with the desktop client as long as both run the base tag above:
  the history is serialized as a change list (`History.writeReplace` to `SerializedHistory`), so
  no Swing classes end up in the file, and `LocalGameSmokeTest.desktopSaveLoadsAndPlaysOn` loads a
  save written by the desktop engine at that tag. A save from another desktop version may fail to
  load; it then fails as a whole with the normal error message.
```

- [ ] **Step 4: Leave it in the working tree.** Suggested message: `docs: record engine base tag
  and upstream sync tools`

---

## Done when

- `./gradlew :engine:test` is green, including `desktopSaveLoadsAndPlaysOn`.
- `sh engine/upstream/diff.sh ../triplea` reports `identical: 615` or more, and every remaining
  `differs` file is a known mobile adaptation.
- No `app/src/main` change. If Task 2 Step 7 needed engine class changes, the app gets one manual
  check: load an existing save on the phone.
