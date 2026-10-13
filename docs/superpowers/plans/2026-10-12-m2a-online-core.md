# Milestone 2a: Online Core (Protocol and Seat Bridge) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A human seat of a TripleA game can be played from another device through JSON
messages only. The host turns every engine question into a message and checks every answer
before the engine sees it. The device answers the question with the same `HumanPlayerUi` that
plays local games. A game-level test plays the minimap this way.

**Architecture:** Milestone 2 ("host with mobile players") is split into three plans, each
working on its own:
- **2a (this plan), this repo.**
  - Module `protocol/`: pure Kotlin, kotlinx.serialization. It holds the messages.
  - Module `online/`: Kotlin on top of `:engine`. It holds the bridge:
    - `RemoteHumanUi`, the host side of one seat, a `HumanPlayerUi` that asks over a `HostLink`;
    - `OnlineClient`, the device side, which answers a `Question` with a local `HumanPlayerUi`;
    - `EventEncoder` and the event replay for battle displays;
    - the placeable-units query.
- **2b, server repo `triplea-mobile-server`.**
  - It compiles `online/src/main` (and `MobilePlayer`, `HumanPlayerUi`, `HumanPlayerUiAdapter`,
    `GameEventListener` from `engine/src`) against the desktop engine at the tag.
  - It adds WebSocket transport, accounts, Postgres snapshots plus journal, and recovery.
- **2c, this repo.** The app's online mode: server URL setting, login, a second source for
  `GameController.pending`, and `MoveHelper` reading moves from the synced copy.

The device keeps a read-only copy of the game, re-loaded from the host's save whenever the host's
version moved. The save already carries the move and place delegates' "moves made" lists, so the
device reads them locally. Undo is an answer to the open move/place question, and only
"placeable units" needs a query to the host.

**Tech Stack:**
- Kotlin 2.4.20 (JVM, target 17)
- kotlinx-serialization-json 1.11.0
- the engine (Java 17)
- JUnit 6 + AssertJ
- Gradle 9.6

**Spec:** `docs/superpowers/specs/2026-10-12-multiplayer-lobby-design.md`, sections 1 ("New
module `protocol/`"), 2 ("Players on the host", "Messages"), 4 ("App ↔ server"), 6 ("This repo").

## Global Constraints

- No `git commit`, `git push` or `git add`. Every task ends with its changes in the working tree
  and a suggested commit message.
- Files under `engine/src` and `app/src` are edited with Edit/Write only, never with shell tools.
- `./gradlew` needs the `build` skill loaded first.
- `protocol/` is pure Kotlin/JVM with kotlinx.serialization JSON and no engine dependency (spec:
  "Pure Kotlin/JVM, kotlinx.serialization JSON").
- IDs: units by UUID, territories and nations by name (spec: "IDs: units by UUID, territories and
  nations by name").
- No Java serialization on the message path (spec: "only `protocol/` JSON with fixed types; no
  Java serialization on this path"). Saves are the separate HTTPS download.
- Size limit: 64 KB per message (spec), enforced as `MAX_MESSAGE_CHARS = 64 * 1024` on decode.
- `PROTOCOL_VERSION` plus the engine build id are checked on connect (spec). The engine build id is
  `ENGINE_BASE_TAG = "2026-10.16.15815"`, the desktop tag both engines are based on.
- The engine stays Java 17 (`--release 17`). Kotlin modules use `jvmTarget = 17` and Java
  `sourceCompatibility = 17`.
- `online/src/main` must also compile against the desktop engine at the tag (milestone 2b does
  that).
  - It uses only engine classes that exist in both engines, plus `org.triplea.mobile.HumanPlayerUi`,
    `HumanPlayerUiAdapter` and `GameEventListener`.
  - It never uses `LocalGameSession` or `MobileEngine`; tests may.
  - The desktop `PlaceData` constructor is package-private, so it is only called from
    `games/strategy/triplea/ui/OnlinePlaceData.kt`.
- Engine tests stay at game level (`LocalGameSmokeTest`, `HumanPlayerFlowTest`). `online` tests
  play the minimap, and `protocol` tests are pure message tests (spec section 6).
- In Kotlin files, `games.strategy.engine.data.Unit` is imported as `GameUnit`; Kotlin has its own
  `Unit`.

## Review Focus

1. **Hostile or malformed answers.** This covers unknown ids or names, units that were not offered,
   negative amounts, a reply of the wrong type, "done" for a battle that must be fought, a wrong
   casualty count and a negative undo index. Each is rejected with a reason and the question is
   asked again. Nothing reaches the engine or kills the game thread. Pinned by Task 2 (purchase
   cases) and Task 3 (negative undo, battle "done", casualty count).
2. **Undo of a move that does not exist.** The player gets the engine's error ("No moves to
   undo"), and the phase goes on. Pinned by Task 3.
3. **A question asked while the game thread holds the game's read lock.** Copying the game for the
   device needs the write lock and would deadlock. Pinned by Tasks 2 and 3: the test copies the
   game at every question under a 5-minute `@Timeout`, so a hang fails the test.
4. **Events that name units the device's copy does not know.** The replay skips those units and
   does not throw. Pinned by Task 3.
5. **A message over 64 KB.** Decoding refuses it before parsing. Pinned by Task 1.

---

## File Structure

| File | Action | Responsibility |
|---|---|---|
| `gradle/libs.versions.toml` | modify | kotlinx-serialization version, Kotlin JVM and serialization plugins |
| `build.gradle.kts` | modify | declare the two Kotlin plugins `apply false` |
| `settings.gradle.kts` | modify | include `:protocol`, `:online` |
| `protocol/build.gradle.kts` | create | Kotlin JVM + serialization module |
| `protocol/src/main/kotlin/org/triplea/mobile/protocol/Protocol.kt` | create | versions, size limit, `ProtocolJson` |
| `protocol/src/main/kotlin/org/triplea/mobile/protocol/Messages.kt` | create | `ServerMessage`, `ClientMessage` and their envelopes |
| `protocol/src/main/kotlin/org/triplea/mobile/protocol/Ask.kt` | create | one `Ask` per engine question |
| `protocol/src/main/kotlin/org/triplea/mobile/protocol/Reply.kt` | create | the answers |
| `protocol/src/main/kotlin/org/triplea/mobile/protocol/Note.kt` | create | notices that need no answer |
| `protocol/src/main/kotlin/org/triplea/mobile/protocol/Event.kt` | create | battle/step/move events for every player |
| `protocol/src/main/kotlin/org/triplea/mobile/protocol/Dto.kt` | create | dice, move, scramble value objects |
| `protocol/src/test/kotlin/org/triplea/mobile/protocol/Samples.kt` | create | one sample of every message type |
| `protocol/src/test/kotlin/org/triplea/mobile/protocol/ProtocolTest.kt` | create | round trip, coverage, golden, size, hello |
| `protocol/src/test/resources/golden/protocol-v1.json` | create | golden encoding of the samples |
| `engine/src/main/java/org/triplea/mobile/PlayerKind.java` | modify | human type built from a per-nation UI function |
| `engine/src/main/java/org/triplea/mobile/LocalGameSession.java` | modify | `create` overload with a per-nation UI function |
| `online/build.gradle.kts` | create | Kotlin JVM module on `:engine` + `:protocol` |
| `online/src/main/kotlin/org/triplea/mobile/online/Names.kt` | create | wire ids ↔ engine objects, dice conversion, `InvalidAnswerException` |
| `online/src/main/kotlin/org/triplea/mobile/online/HostLink.kt` | create | `HostLink`, `DelegateAccess`, `UndoRequest` |
| `online/src/main/kotlin/org/triplea/mobile/online/RemoteHumanUi.kt` | create | host side of one seat |
| `online/src/main/kotlin/org/triplea/mobile/online/OnlineClient.kt` | create | device side: questions, notices, events |
| `online/src/main/kotlin/org/triplea/mobile/online/EventEncoder.kt` | create | host side: engine events → `Event` |
| `online/src/main/kotlin/org/triplea/mobile/online/Placeable.kt` | create | placeable-units query, both sides |
| `online/src/main/kotlin/games/strategy/triplea/ui/OnlinePlaceData.kt` | create | `PlaceData` factory (package-private constructor on desktop) |
| `online/src/test/kotlin/org/triplea/mobile/online/Wire.kt` | create | test link: host and devices in one process, all JSON |
| `online/src/test/kotlin/org/triplea/mobile/online/ScriptedPlayer.kt` | create | scripted device-side player |
| `online/src/test/kotlin/org/triplea/mobile/online/OnlineGameTest.kt` | create | game-level tests |
| `docs/DEVELOPMENT.md` | modify | modules, online play, protocol versioning, resync step |

---

### Task 1: The `protocol` module

**Files:**
- Modify: `gradle/libs.versions.toml`, `build.gradle.kts`, `settings.gradle.kts`
- Create: `protocol/build.gradle.kts`, `protocol/src/main/kotlin/org/triplea/mobile/protocol/{Protocol,Messages,Ask,Reply,Note,Event,Dto}.kt`
- Test: `protocol/src/test/kotlin/org/triplea/mobile/protocol/{Samples,ProtocolTest}.kt`, `protocol/src/test/resources/golden/protocol-v1.json`

**Interfaces:**
- Consumes: nothing.
- Produces (package `org.triplea.mobile.protocol`):
  - `const val PROTOCOL_VERSION: Int`, `const val ENGINE_BASE_TAG: String`, `const val MAX_MESSAGE_CHARS: Int`
  - `object ProtocolJson { fun encode(message: ServerMessage): String; fun encode(message: ClientMessage): String; fun decodeServerMessage(text: String): ServerMessage; fun decodeClientMessage(text: String): ClientMessage }`. Decoding throws `IllegalArgumentException` (and its subclass `SerializationException`) for bad or oversized text.
  - `sealed interface ServerMessage` with: `UpdateRequired`, `GameVersion`, `Question(id: Long, gameId: String, seat: String, version: Long, ask: Ask, deadline: Long? = null)`, `Rejected(questionId: Long, reason: String)`, `Notice(gameId, seat, version, note: Note)`, `GameEvent(gameId, version, event: Event)`, `PlaceableResult(questionId, territory, units: List<String>, maxUnits: Int, error: String? = null)`
  - `sealed interface ClientMessage` with: `Hello(protocolVersion, engineBaseTag)` and `fun isCompatible(): Boolean`, `Answer(questionId: Long, reply: Reply)`, `PlaceableQuery(questionId, territory, units: List<String>)`
  - `sealed interface Ask`, `Reply`, `Note`, `Event` with the subclasses below; `DieDto`, `DiceDto`, `MoveDto`, `ScrambleOptionDto`.

- [ ] **Step 1: Add the libraries and plugins to the version catalog**

In `gradle/libs.versions.toml` add under `[versions]` (after `coroutines`):

```toml
kotlinx-serialization = "1.11.0"
```

under `[libraries]` (after `kotlinx-coroutines-android`):

```toml
kotlinx-serialization-json = { module = "org.jetbrains.kotlinx:kotlinx-serialization-json", version.ref = "kotlinx-serialization" }
```

and under `[plugins]` (after `kotlin-compose`):

```toml
kotlin-jvm = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
```

In the root `build.gradle.kts` add two lines to the `plugins` block, after
`alias(libs.plugins.kotlin.compose) apply false`:

```kotlin
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
```

In `settings.gradle.kts` replace

```kotlin
include(":engine")
include(":app")
```

with

```kotlin
include(":engine")
include(":protocol")
include(":app")
```

(`:online` is added in Task 2, together with its folder: Gradle 9 refuses a project whose folder
does not exist.)

- [ ] **Step 2: Create `protocol/build.gradle.kts`**

```kotlin
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

description = "JSON messages between the TripleA Mobile app and the game server"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    api(libs.kotlinx.serialization.json)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
```

- [ ] **Step 3: Write `Protocol.kt` and `Dto.kt`**

`protocol/src/main/kotlin/org/triplea/mobile/protocol/Protocol.kt`:

```kotlin
package org.triplea.mobile.protocol

import kotlinx.serialization.json.Json

/**
 * Bump on every change to the messages that the other side cannot read. ProtocolTest fails until
 * the golden file of the new version exists.
 */
const val PROTOCOL_VERSION = 1

/** The desktop TripleA tag both engines are based on; saves only travel between equal tags. */
const val ENGINE_BASE_TAG = "2026-10.16.15815"

/** Longest message either side accepts, in characters of JSON text. */
const val MAX_MESSAGE_CHARS = 64 * 1024

/** The one JSON configuration both sides use. */
object ProtocolJson {
    internal val json = Json { classDiscriminator = "type" }

    fun encode(message: ServerMessage): String = json.encodeToString(ServerMessage.serializer(), message)

    fun encode(message: ClientMessage): String = json.encodeToString(ClientMessage.serializer(), message)

    fun decodeServerMessage(text: String): ServerMessage =
        json.decodeFromString(ServerMessage.serializer(), checkSize(text))

    fun decodeClientMessage(text: String): ClientMessage =
        json.decodeFromString(ClientMessage.serializer(), checkSize(text))

    private fun checkSize(text: String): String {
        require(text.length <= MAX_MESSAGE_CHARS) { "message too long: ${text.length} characters" }
        return text
    }
}
```

`protocol/src/main/kotlin/org/triplea/mobile/protocol/Dto.kt`:

```kotlin
package org.triplea.mobile.protocol

import kotlinx.serialization.Serializable

/** One die: [value] 0 based, [rolledAt] 1 based, [kind] HIT, MISS or IGNORED. */
@Serializable
data class DieDto(val value: Int, val rolledAt: Int, val kind: String)

@Serializable
data class DiceDto(val dice: List<DieDto>, val hits: Int, val expectedHits: Double, val player: String?)

/** A move: unit ids, the route's territories start to end, and which transport carries what. */
@Serializable
data class MoveDto(
    val units: List<String>,
    val route: List<String>,
    val seaTransports: Map<String, String> = emptyMap(),
    val airTransportDependents: Map<String, List<String>> = emptyMap(),
)

/** Air bases of one territory and the aircraft there that may scramble. */
@Serializable
data class ScrambleOptionDto(val airbases: List<String>, val units: List<String>)
```

- [ ] **Step 4: Write `Messages.kt`**

```kotlin
package org.triplea.mobile.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Everything the server sends to the app. */
@Serializable
sealed interface ServerMessage

/** Everything the app sends to the server. */
@Serializable
sealed interface ClientMessage

/** The app's first message on every connection. */
@Serializable
@SerialName("hello")
data class Hello(val protocolVersion: Int, val engineBaseTag: String) : ClientMessage {
    fun isCompatible(): Boolean = protocolVersion == PROTOCOL_VERSION && engineBaseTag == ENGINE_BASE_TAG
}

/** The answer to an incompatible [Hello]: "please update the app". The server then closes. */
@Serializable
@SerialName("update_required")
data class UpdateRequired(val protocolVersion: Int, val engineBaseTag: String) : ServerMessage

/** Game [gameId] changed. The app fetches the save of [version] before it handles anything newer. */
@Serializable
@SerialName("game_version")
data class GameVersion(val gameId: String, val version: Long) : ServerMessage

/**
 * The engine asks the player of [seat] (a nation name). Ids and names refer to the game at
 * [version]; [deadline] is epoch milliseconds, null without a clock.
 */
@Serializable
@SerialName("question")
data class Question(
    val id: Long,
    val gameId: String,
    val seat: String,
    val version: Long,
    val ask: Ask,
    val deadline: Long? = null,
) : ServerMessage

/** The answer to [questionId] did not fit; the same question follows again. */
@Serializable
@SerialName("rejected")
data class Rejected(val questionId: Long, val reason: String) : ServerMessage

/** Information for the player of [seat] that needs no answer. */
@Serializable
@SerialName("notice")
data class Notice(val gameId: String, val seat: String, val version: Long, val note: Note) : ServerMessage

/** Something every player of the game sees, e.g. a battle step or dice. */
@Serializable
@SerialName("game_event")
data class GameEvent(val gameId: String, val version: Long, val event: Event) : ServerMessage

/** Which of [units] the player can place in [territory] while placement question [questionId] is open. */
@Serializable
@SerialName("placeable_query")
data class PlaceableQuery(val questionId: Long, val territory: String, val units: List<String>) : ClientMessage

/** The answer to a [PlaceableQuery]; [error] says why nothing can be placed there. */
@Serializable
@SerialName("placeable_result")
data class PlaceableResult(
    val questionId: Long,
    val territory: String,
    val units: List<String>,
    val maxUnits: Int,
    val error: String? = null,
) : ServerMessage

/** The player's answer to question [questionId]. */
@Serializable
@SerialName("answer")
data class Answer(val questionId: Long, val reply: Reply) : ClientMessage
```

- [ ] **Step 5: Write `Ask.kt`**

One subclass per `HumanPlayerUi` question (`org.triplea.mobile.HumanPlayerUi`). "Select fixed
dice" is missing on purpose: the engine asks it only in edit mode, which is always off online.

```kotlin
package org.triplea.mobile.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** What the engine asks a human player. Players and territories by name, units by id. */
@Serializable
sealed interface Ask {
    @Serializable @SerialName("purchase")
    data class Purchase(val player: String, val bid: Boolean, val keepCurrentPurchase: Boolean) : Ask

    @Serializable @SerialName("repair")
    data class Repair(val player: String, val bid: Boolean, val allowedPlayers: List<String>) : Ask

    @Serializable @SerialName("tech")
    data class Tech(val player: String) : Ask

    /** Answered with [Reply.Move], [Reply.Undo] or [Reply.Done] (end of the phase). */
    @Serializable @SerialName("move")
    data class Move(val player: String, val nonCombat: Boolean, val step: String) : Ask

    @Serializable @SerialName("air_cant_land")
    data class AirCantLand(val player: String, val territories: List<String>, val movePhase: Boolean) : Ask

    @Serializable @SerialName("units_cant_fight")
    data class UnitsCantFight(val territories: List<String>) : Ask

    /** [battles]: battle type name (IBattle.BattleType) to territories. One must be fought. */
    @Serializable @SerialName("battle")
    data class Battle(val player: String, val battles: Map<String, List<String>>) : Ask

    /** Answered with [Reply.Place], [Reply.Undo] or [Reply.Done] (end of the phase). */
    @Serializable @SerialName("place")
    data class Place(val player: String, val bid: Boolean) : Ask

    @Serializable @SerialName("end_turn")
    data class EndTurn(val player: String) : Ask

    @Serializable @SerialName("politics")
    data class Politics(val player: String, val firstRun: Boolean, val actions: List<String>) : Ask

    @Serializable @SerialName("user_action")
    data class UserAction(val player: String, val firstRun: Boolean, val actions: List<String>) : Ask

    /** A yes/no question; [territory] set when it belongs to the battle there. */
    @Serializable @SerialName("confirm")
    data class Confirm(val title: String, val question: String, val territory: String? = null) : Ask

    @Serializable @SerialName("select_units")
    data class SelectUnits(
        val candidates: List<String>,
        val title: String,
        val message: String,
        val max: Int,
        val territory: String? = null,
    ) : Ask

    @Serializable @SerialName("casualties")
    data class Casualties(
        val selectFrom: List<String>,
        val count: Int,
        val message: String,
        val dice: DiceDto?,
        val hit: String,
        val defaultKilled: List<String>,
        val defaultDamaged: List<String>,
        val battleId: String,
        val allowMultipleHitsPerUnit: Boolean,
    ) : Ask

    @Serializable @SerialName("select_territory")
    data class SelectTerritory(
        val candidates: List<String>,
        val title: String,
        val message: String,
        val noneAllowed: Boolean,
    ) : Ask

    @Serializable @SerialName("retreat")
    data class Retreat(
        val battleId: String,
        val submerge: Boolean,
        val battleTerritory: String,
        val possible: List<String>,
        val message: String,
    ) : Ask

    /** [options]: territory the aircraft scramble from to its air bases and aircraft. */
    @Serializable @SerialName("scramble")
    data class Scramble(val player: String, val scrambleTo: String, val options: Map<String, ScrambleOptionDto>) : Ask

    @Serializable @SerialName("confirm_casualties")
    data class ConfirmCasualties(val battleId: String, val message: String) : Ask

    @Serializable @SerialName("accept_action")
    data class AcceptAction(val from: String, val question: String, val politics: Boolean) : Ask

    /** [targets]: territory to the enemy units there that may be attacked. */
    @Serializable @SerialName("kamikaze")
    data class Kamikaze(val player: String, val targets: Map<String, List<String>>, val resource: String, val max: Int) : Ask

    @Serializable @SerialName("pick_territory_and_units")
    data class PickTerritoryAndUnits(
        val player: String,
        val territories: List<String>,
        val units: List<String>,
        val unitsPerPick: Int,
    ) : Ask
}
```

- [ ] **Step 6: Write `Reply.kt`, `Note.kt` and `Event.kt`**

`Reply.kt`:

```kotlin
package org.triplea.mobile.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The player's answer to an [Ask]. */
@Serializable
sealed interface Reply {
    /** Nothing chosen / phase finished / acknowledged. */
    @Serializable @SerialName("done")
    data object Done : Reply

    @Serializable @SerialName("yes_no")
    data class YesNo(val yes: Boolean) : Reply

    /** Production rule name to amount. */
    @Serializable @SerialName("purchase")
    data class Purchase(val rules: Map<String, Int>) : Reply

    /** Unit id to repair rule name to amount. */
    @Serializable @SerialName("repair")
    data class Repair(val units: Map<String, Map<String, Int>>) : Reply

    /**
     * A tech roll. [category] names one of the player's technology frontiers; without it,
     * [advances] lists the chosen advances; with neither, the roll has no target.
     */
    @Serializable @SerialName("tech")
    data class Tech(
        val category: String?,
        val advances: List<String>,
        val rolls: Int,
        val newTokens: Int,
        val whoPays: Map<String, Int>,
    ) : Reply

    @Serializable @SerialName("move")
    data class Move(val move: MoveDto) : Reply

    /** Undo the move or placement at [index] of the current phase; the question follows again. */
    @Serializable @SerialName("undo")
    data class Undo(val index: Int) : Reply

    @Serializable @SerialName("battle")
    data class Battle(val territory: String, val bombingRaid: Boolean, val battleType: String) : Reply

    @Serializable @SerialName("place")
    data class Place(val units: List<String>, val territory: String) : Reply

    /** A political or user action by attachment name. */
    @Serializable @SerialName("choice")
    data class Choice(val name: String) : Reply

    @Serializable @SerialName("units")
    data class Units(val units: List<String>) : Reply

    @Serializable @SerialName("casualties")
    data class Casualties(val killed: List<String>, val damaged: List<String>, val autoCalculated: Boolean) : Reply

    @Serializable @SerialName("territory")
    data class TerritoryChoice(val territory: String) : Reply

    /** Territory scrambled from to the aircraft that scramble. */
    @Serializable @SerialName("scramble")
    data class Scramble(val units: Map<String, List<String>>) : Reply

    /** Territory to target unit id to number of attacks. */
    @Serializable @SerialName("kamikaze")
    data class Kamikaze(val attacks: Map<String, Map<String, Int>>) : Reply

    @Serializable @SerialName("pick")
    data class Pick(val territory: String?, val units: List<String>) : Reply
}
```

`Note.kt`:

```kotlin
package org.triplea.mobile.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Information for one player that needs no answer. */
@Serializable
sealed interface Note {
    @Serializable @SerialName("phase_started")
    data class PhaseStarted(val player: String, val step: String) : Note

    @Serializable @SerialName("error")
    data class Error(val text: String) : Note

    @Serializable @SerialName("message")
    data class Message(val title: String, val text: String) : Note

    @Serializable @SerialName("tech_results")
    data class TechResults(
        val rolls: List<Int>,
        val hits: Int,
        val remainder: Int,
        val advances: List<String>,
        val error: String? = null,
    ) : Note
}
```

`Event.kt` mirrors `org.triplea.mobile.GameEventListener`. The `dependents` maps are left out:
no screen uses them.

```kotlin
package org.triplea.mobile.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** What the engine shows every player while a game runs. */
@Serializable
sealed interface Event {
    @Serializable @SerialName("message")
    data class Message(val title: String, val text: String) : Event

    @Serializable @SerialName("game_ended")
    data class GameEnded(val message: String) : Event

    @Serializable @SerialName("step_changed")
    data class StepChanged(val step: String, val displayName: String, val player: String?, val round: Int) : Event

    @Serializable @SerialName("battle_started")
    data class BattleStarted(
        val battleId: String,
        val territory: String,
        val title: String,
        val attackingUnits: List<String>,
        val defendingUnits: List<String>,
        val attacker: String,
        val defender: String,
        val battleType: String,
    ) : Event

    @Serializable @SerialName("battle_steps")
    data class BattleSteps(val battleId: String, val steps: List<String>) : Event

    @Serializable @SerialName("battle_step")
    data class BattleStep(val battleId: String, val step: String) : Event

    @Serializable @SerialName("battle_ended")
    data class BattleEnded(val battleId: String, val message: String) : Event

    @Serializable @SerialName("dice_rolled")
    data class DiceRolled(val dice: DiceDto, val step: String) : Event

    @Serializable @SerialName("casualties")
    data class Casualties(
        val battleId: String,
        val step: String,
        val dice: DiceDto?,
        val player: String,
        val killed: List<String>,
        val damaged: List<String>,
    ) : Event

    @Serializable @SerialName("units_died")
    data class UnitsDied(val battleId: String, val player: String, val dead: List<String>) : Event

    @Serializable @SerialName("units_changed")
    data class UnitsChanged(val battleId: String, val player: String, val removed: List<String>, val added: List<String>) : Event

    @Serializable @SerialName("bombing_results")
    data class BombingResults(val battleId: String, val dice: List<DieDto>, val cost: Int) : Event

    @Serializable @SerialName("retreat")
    data class Retreat(val shortMessage: String, val message: String, val step: String, val player: String?) : Event

    @Serializable @SerialName("units_retreated")
    data class UnitsRetreated(val battleId: String, val units: List<String>) : Event

    @Serializable @SerialName("units_moved")
    data class UnitsMoved(val player: String, val units: List<String>, val route: List<String>) : Event

    @Serializable @SerialName("play_sound")
    data class PlaySound(val clip: String, val player: String?) : Event
}
```

- [ ] **Step 7: Write the samples and the tests**

`protocol/src/test/kotlin/org/triplea/mobile/protocol/Samples.kt`:

```kotlin
package org.triplea.mobile.protocol

/** One sample of every message type, used by the round trip and the golden file. */
internal object Samples {
    private const val UNIT = "2f1c6a4e-6d1b-4f0e-9a51-3c1f5c7d9e10"
    private const val UNIT2 = "7b9e2c11-0a4d-4c55-8f3e-1d2a3b4c5d6e"
    private val dice = DiceDto(listOf(DieDto(0, 2, "HIT"), DieDto(4, 2, "MISS")), 1, 0.66, "Russians")

    val asks: List<Ask> = listOf(
        Ask.Purchase("Russians", bid = false, keepCurrentPurchase = false),
        Ask.Repair("Russians", bid = false, allowedPlayers = listOf("Russians")),
        Ask.Tech("Russians"),
        Ask.Move("Russians", nonCombat = false, step = "russianCombatMove"),
        Ask.AirCantLand("Russians", listOf("sea zone 1"), movePhase = true),
        Ask.UnitsCantFight(listOf("blue north 2")),
        Ask.Battle("Russians", mapOf("NORMAL" to listOf("blue north 2"))),
        Ask.Place("Russians", bid = false),
        Ask.EndTurn("Russians"),
        Ask.Politics("Russians", firstRun = true, actions = listOf("politicalActionAttachment_Russians_toWar")),
        Ask.UserAction("Russians", firstRun = true, actions = listOf("userActionAttachment_Russians_bribe")),
        Ask.Confirm("Attack submarines", "Attack submarines in sea zone 1?", territory = "sea zone 1"),
        Ask.SelectUnits(listOf(UNIT, UNIT2), "Bombing target", "Select the unit to bomb", max = 1),
        Ask.Casualties(
            listOf(UNIT, UNIT2), count = 1, message = "Russians select casualties", dice = dice, hit = "Russians",
            defaultKilled = listOf(UNIT), defaultDamaged = emptyList(), battleId = UNIT2, allowMultipleHitsPerUnit = false,
        ),
        Ask.SelectTerritory(listOf("sea zone 1"), "Rocket attack", "Where should rockets attack?", noneAllowed = true),
        Ask.Retreat(UNIT2, submerge = false, battleTerritory = "blue north 2", possible = listOf("red north 2"), message = "Retreat?"),
        Ask.Scramble("Italians", "sea zone 2", mapOf("blue north 1" to ScrambleOptionDto(listOf(UNIT), listOf(UNIT2)))),
        Ask.ConfirmCasualties(UNIT2, "Russians lose 1 infantry"),
        Ask.AcceptAction("Italians", "Accept peace?", politics = true),
        Ask.Kamikaze("Italians", mapOf("sea zone 2" to listOf(UNIT)), resource = "suicideAttackTokens", max = 2),
        Ask.PickTerritoryAndUnits("Russians", listOf("red capitol"), listOf(UNIT), unitsPerPick = 1),
    )

    val replies: List<Reply> = listOf(
        Reply.Done,
        Reply.YesNo(true),
        Reply.Purchase(mapOf("buyInfantry" to 2)),
        Reply.Repair(mapOf(UNIT to mapOf("repairFactory" to 1))),
        Reply.Tech(category = null, advances = listOf("heavyBomber"), rolls = 1, newTokens = 0, whoPays = mapOf("Russians" to 5)),
        Reply.Move(MoveDto(listOf(UNIT), listOf("red north 2", "blue north 2"), seaTransports = mapOf(UNIT to UNIT2))),
        Reply.Undo(0),
        Reply.Battle("blue north 2", bombingRaid = false, battleType = "NORMAL"),
        Reply.Place(listOf(UNIT), "red capitol"),
        Reply.Choice("politicalActionAttachment_Russians_toWar"),
        Reply.Units(listOf(UNIT)),
        Reply.Casualties(listOf(UNIT), emptyList(), autoCalculated = false),
        Reply.TerritoryChoice("red north 2"),
        Reply.Scramble(mapOf("blue north 1" to listOf(UNIT2))),
        Reply.Kamikaze(mapOf("sea zone 2" to mapOf(UNIT to 1))),
        Reply.Pick("red capitol", listOf(UNIT)),
    )

    val notes: List<Note> = listOf(
        Note.PhaseStarted("Russians", "russianPurchase"),
        Note.Error("Not enough PUs"),
        Note.Message("Victory", "Russians win"),
        Note.TechResults(listOf(0, 5), hits = 1, remainder = 0, advances = listOf("heavyBomber")),
    )

    val events: List<Event> = listOf(
        Event.Message("Battle", "Russians attack"),
        Event.GameEnded("Russians win"),
        Event.StepChanged("russianPurchase", "Purchase", "Russians", 1),
        Event.BattleStarted(UNIT2, "blue north 2", "Russians attack Italians", listOf(UNIT), listOf(UNIT2), "Russians", "Italians", "NORMAL"),
        Event.BattleSteps(UNIT2, listOf("Russians fire", "Italians fire")),
        Event.BattleStep(UNIT2, "Russians fire"),
        Event.BattleEnded(UNIT2, "Russians win"),
        Event.DiceRolled(dice, "Russians fire"),
        Event.Casualties(UNIT2, "Italians select casualties", dice, "Italians", killed = listOf(UNIT2), damaged = emptyList()),
        Event.UnitsDied(UNIT2, "Italians", listOf(UNIT2)),
        Event.UnitsChanged(UNIT2, "Russians", removed = listOf(UNIT), added = listOf(UNIT2)),
        Event.BombingResults(UNIT2, listOf(DieDto(3, 0, "IGNORED")), cost = 4),
        Event.Retreat("retreats", "Russians retreat", "Russians retreat", "Russians"),
        Event.UnitsRetreated(UNIT2, listOf(UNIT)),
        Event.UnitsMoved("Russians", listOf(UNIT), listOf("red north 2", "blue north 2")),
        Event.PlaySound("battle_land", "Russians"),
    )

    val serverMessages: List<ServerMessage> =
        listOf(
            UpdateRequired(PROTOCOL_VERSION, ENGINE_BASE_TAG),
            GameVersion("g1", 7),
            Rejected(3, "unknown territory nowhere"),
            PlaceableResult(4, "red capitol", listOf(UNIT), maxUnits = 3),
            PlaceableResult(5, "red north 1", emptyList(), 0, error = "No factory"),
        ) +
            asks.mapIndexed { i, ask -> Question(i + 1L, "g1", "Russians", 7, ask, deadline = if (i == 0) 1_800_000_000_000 else null) } +
            notes.map { Notice("g1", "Russians", 7, it) } +
            events.map { GameEvent("g1", 7, it) }

    val clientMessages: List<ClientMessage> =
        listOf(Hello(PROTOCOL_VERSION, ENGINE_BASE_TAG), PlaceableQuery(4, "red capitol", listOf(UNIT))) +
            replies.mapIndexed { i, reply -> Answer(i + 1L, reply) }
}
```

`protocol/src/test/kotlin/org/triplea/mobile/protocol/ProtocolTest.kt`:

```kotlin
package org.triplea.mobile.protocol

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ProtocolTest {
    @Test
    fun everyMessageSurvivesTheRoundTrip() {
        Samples.serverMessages.forEach {
            assertThat(ProtocolJson.decodeServerMessage(ProtocolJson.encode(it))).isEqualTo(it)
        }
        Samples.clientMessages.forEach {
            assertThat(ProtocolJson.decodeClientMessage(ProtocolJson.encode(it))).isEqualTo(it)
        }
    }

    @Test
    fun samplesCoverEveryMessageType() {
        assertThat(typesOf(Samples.asks, Ask.serializer())).containsExactlyInAnyOrderElementsOf(subtypes(Ask.serializer().descriptor))
        assertThat(typesOf(Samples.replies, Reply.serializer())).containsExactlyInAnyOrderElementsOf(subtypes(Reply.serializer().descriptor))
        assertThat(typesOf(Samples.notes, Note.serializer())).containsExactlyInAnyOrderElementsOf(subtypes(Note.serializer().descriptor))
        assertThat(typesOf(Samples.events, Event.serializer())).containsExactlyInAnyOrderElementsOf(subtypes(Event.serializer().descriptor))
        assertThat(typesOf(Samples.serverMessages, ServerMessage.serializer()).distinct())
            .containsExactlyInAnyOrderElementsOf(subtypes(ServerMessage.serializer().descriptor))
        assertThat(typesOf(Samples.clientMessages, ClientMessage.serializer()).distinct())
            .containsExactlyInAnyOrderElementsOf(subtypes(ClientMessage.serializer().descriptor))
    }

    @Test
    fun messagesMatchTheGoldenFileOfThisProtocolVersion() {
        val actual = (Samples.serverMessages.map { ProtocolJson.encode(it) } + Samples.clientMessages.map { ProtocolJson.encode(it) })
            .joinToString("\n")
        val name = "protocol-v$PROTOCOL_VERSION.json"
        val output = Path.of("build", "golden")
        Files.createDirectories(output)
        Files.writeString(output.resolve(name), actual)
        val expected = javaClass.getResource("/golden/$name")?.readText()?.replace("\r\n", "\n")?.trim()
        assertThat(expected)
            .describedAs("golden file src/test/resources/golden/$name is missing; this run wrote build/golden/$name")
            .isNotNull()
        assertThat(actual)
            .describedAs("the messages changed: bump PROTOCOL_VERSION and add build/golden/$name as the new golden file")
            .isEqualTo(expected)
    }

    @Test
    fun oversizedMessagesAreRefused() {
        val tooLong = ProtocolJson.encode(Answer(1, Reply.Units(List(2000) { "x".repeat(36) })))
        assertThat(tooLong.length).isGreaterThan(MAX_MESSAGE_CHARS)
        assertThatThrownBy { ProtocolJson.decodeClientMessage(tooLong) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("too long")
    }

    @Test
    fun helloChecksProtocolVersionAndEngineTag() {
        assertThat(Hello(PROTOCOL_VERSION, ENGINE_BASE_TAG).isCompatible()).isTrue()
        assertThat(Hello(PROTOCOL_VERSION + 1, ENGINE_BASE_TAG).isCompatible()).isFalse()
        assertThat(Hello(PROTOCOL_VERSION, "2026-01.1.1").isCompatible()).isFalse()
    }

    private fun <T> typesOf(items: List<T>, serializer: KSerializer<T>): List<String> =
        items.map { ProtocolJson.json.encodeToJsonElement(serializer, it).jsonObject.getValue("type").jsonPrimitive.content }

    @OptIn(ExperimentalSerializationApi::class)
    private fun subtypes(sealed: SerialDescriptor): List<String> = sealed.getElementDescriptor(1).elementNames.toList()
}
```

- [ ] **Step 8: Run the tests and check that the golden test fails**

Run: `./gradlew :protocol:test --console=plain`
Expected: `messagesMatchTheGoldenFileOfThisProtocolVersion` FAILS with "golden file
src/test/resources/golden/protocol-v1.json is missing". The other four tests PASS.
`protocol/build/golden/protocol-v1.json` exists.

- [ ] **Step 9: Accept the golden file**

```bash
mkdir -p protocol/src/test/resources/golden
cp protocol/build/golden/protocol-v1.json protocol/src/test/resources/golden/protocol-v1.json
```

Read the file. It must have one JSON object per line: 5 + 21 + 4 + 16 = 46 server lines, then
2 + 16 = 18 client lines. Every polymorphic object must carry its `"type"` (e.g.
`{"type":"question",...,"ask":{"type":"purchase",...}}`).

- [ ] **Step 10: Run the tests again**

Run: `./gradlew :protocol:test --console=plain`
Expected: PASS, 5 tests.

- [ ] **Step 11: Leave the changes in the working tree**

Suggested commit message: `protocol: JSON messages between app and server, with golden file`

---

### Task 2: The `online` module, with seats played over JSON

**Files:**
- Modify: `engine/src/main/java/org/triplea/mobile/PlayerKind.java`, `engine/src/main/java/org/triplea/mobile/LocalGameSession.java`
- Modify: `settings.gradle.kts`
- Create: `online/build.gradle.kts`
- Create: `online/src/main/kotlin/org/triplea/mobile/online/{Names,HostLink,RemoteHumanUi,OnlineClient,Placeable,EventEncoder}.kt`
- Create: `online/src/main/kotlin/games/strategy/triplea/ui/OnlinePlaceData.kt`
- Test: `online/src/test/kotlin/org/triplea/mobile/online/{Wire,ScriptedPlayer,OnlineGameTest}.kt`

**Interfaces:**
- Consumes: everything Task 1 produces.
- Produces:
  - Engine: `LocalGameSession.create(GameData, Map<String, PlayerKind>, Function<String, HumanPlayerUi> humanUiFor, GameEventListener)`, `PlayerKind.toPlayerType(Function<String, HumanPlayerUi>)`, `PlayerKind.humanType(Function<String, HumanPlayerUi>)`. The old `create(..., HumanPlayerUi, ...)` stays.
  - `org.triplea.mobile.online`:
    - `class InvalidAnswerException(message: String) : RuntimeException`
    - `interface HostLink { fun ask(seat: String, ask: Ask): Answer; fun reject(questionId: Long, reason: String); fun notify(seat: String, note: Note) }`
    - `fun interface DelegateAccess { fun current(): IRemote }`
    - `class UndoRequest(val index: Int) : RuntimeException`
    - `class RemoteHumanUi(seat: String, data: GameData, link: HostLink, delegates: DelegateAccess) : HumanPlayerUi`
    - `class OnlineClient(ui: HumanPlayerUi, events: GameEventListener)` with `fun answer(question: Question, data: GameData): Answer`, `fun notice(notice: Notice, data: GameData)`, `fun rejected(rejected: Rejected)` and `fun event(gameEvent: GameEvent, data: GameData)`
    - `fun answerPlaceable(query: PlaceableQuery, data: GameData, delegates: DelegateAccess): PlaceableResult` (host; returns `error` instead of throwing) and `fun PlaceableResult.toPlaceableUnits(data: GameData): PlaceableUnits` (device)
    - `class EventEncoder(emit: (Event) -> Unit) : GameEventListener` (host)
  - Internal: `Names`, `invalid()`, `wireId`, `wireIds()`, `names()`, `requireOffered()`, `DiceRoll.toDto()`, `Die.toDto()`, `DiceDto.toDiceRoll()`, `DieDto.toDie()`.
  - Tests: `Wire`, `ScriptedPlayer` (Task 3 extends both).

- [ ] **Step 1: Engine: a UI per human nation**

In `engine/src/main/java/org/triplea/mobile/PlayerKind.java`:
- Add `import java.util.function.Function;`.
- Replace `toPlayerType(final HumanPlayerUi humanUi)` with:

```java
  /** Creates the engine player type for this kind; human nations get the UI {@code humanUiFor} returns. */
  public PlayerTypes.Type toPlayerType(final Function<String, HumanPlayerUi> humanUiFor) {
    return switch (this) {
      case HUMAN -> humanType(humanUiFor);
      case AI_EASY -> PlayerTypes.WEAK_AI;
      case AI_FAST -> PlayerTypes.FAST_AI;
      case AI_HARD -> PlayerTypes.PRO_AI;
      case AI_NONE -> DOES_NOTHING_AI;
    };
  }
```

- Replace `humanType(final HumanPlayerUi humanUi)` with:

```java
  /** Human player type; each nation is asked through the UI {@code humanUiFor} returns for it. */
  public static PlayerTypes.Type humanType(final Function<String, HumanPlayerUi> humanUiFor) {
    return new PlayerTypes.Type(HUMAN.label) {
      @Override
      public Player newPlayerWithName(final String name) {
        return new MobilePlayer(name, getLabel(), humanUiFor.apply(name));
      }
    };
  }
```

In `engine/src/main/java/org/triplea/mobile/LocalGameSession.java`:
- Add `import java.util.function.Function;`.
- Keep the Javadoc of the current `create` on a new four-line overload. Put this overload in front
  of the existing method:

```java
  public static LocalGameSession create(
      final GameData gameData,
      final Map<String, PlayerKind> playerKinds,
      final HumanPlayerUi humanUi,
      final GameEventListener listener) {
    return create(gameData, playerKinds, nation -> humanUi, listener);
  }

  /**
   * Like {@link #create(GameData, Map, HumanPlayerUi, GameEventListener)}, but every human nation
   * gets its own UI: online play asks each seat through its own connection.
   *
   * @param humanUiFor The UI bridge for a human nation, by nation name.
   */
  public static LocalGameSession create(
      final GameData gameData,
      final Map<String, PlayerKind> playerKinds,
      final Function<String, HumanPlayerUi> humanUiFor,
      final GameEventListener listener) {
```

- In the body of that (formerly only) method, change `PlayerKind.humanType(humanUi)` to
  `PlayerKind.humanType(humanUiFor)` and `kind.toPlayerType(humanUi)` to
  `kind.toPlayerType(humanUiFor)`.

Run: `./gradlew :engine:test --console=plain`
Expected: PASS (LocalGameSmokeTest 3, HumanPlayerFlowTest 1).

- [ ] **Step 2: Create `online/build.gradle.kts`**

In `settings.gradle.kts` add `include(":online")` after `include(":protocol")`.


```kotlin
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
}

description = "Human seats of a TripleA game played over the JSON protocol: host side and device side"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    api(project(":engine"))
    api(project(":protocol"))

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testRuntimeOnly(libs.slf4j.simple)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

sourceSets {
    // the game tests install the same minimap as the engine tests
    test { resources.srcDir(project(":engine").file("src/test/resources")) }
}

tasks.test {
    useJUnitPlatform()
    maxHeapSize = "2g"
}
```

- [ ] **Step 3: Write `Names.kt`, `HostLink.kt` and `OnlinePlaceData.kt`**

`online/src/main/kotlin/org/triplea/mobile/online/Names.kt`:

```kotlin
package org.triplea.mobile.online

import games.strategy.engine.data.GameData
import games.strategy.engine.data.GamePlayer
import games.strategy.engine.data.Territory
import games.strategy.engine.data.Unit as GameUnit
import games.strategy.triplea.delegate.DiceRoll
import games.strategy.triplea.delegate.Die
import java.util.UUID
import org.triplea.mobile.protocol.DiceDto
import org.triplea.mobile.protocol.DieDto

/** An answer that names something the game does not have, or that the question did not offer. */
class InvalidAnswerException(message: String) : RuntimeException(message)

internal fun invalid(message: String): Nothing = throw InvalidAnswerException(message)

internal val GameUnit.wireId: String get() = id.toString()

internal fun Collection<GameUnit>.wireIds(): List<String> = map { it.wireId }

internal fun Collection<Territory>.names(): List<String> = map { it.name }

/** Looks up wire ids in one game: units by UUID, territories and players by name. */
internal class Names(private val data: GameData) {
    fun unit(id: String): GameUnit =
        runCatching { UUID.fromString(id) }.getOrNull()?.let { data.units.get(it) } ?: invalid("unknown unit $id")

    fun units(ids: Collection<String>): List<GameUnit> = ids.map(::unit)

    /** For events, which only display: units this copy of the game does not know are left out. */
    fun knownUnits(ids: Collection<String>): List<GameUnit> =
        ids.mapNotNull { id -> runCatching { UUID.fromString(id) }.getOrNull()?.let { data.units.get(it) } }

    fun territory(name: String): Territory = data.map.getTerritoryOrNull(name) ?: invalid("unknown territory $name")

    fun territories(names: Collection<String>): List<Territory> = names.map(::territory)

    fun player(name: String): GamePlayer = data.playerList.getPlayerId(name) ?: invalid("unknown player $name")
}

/** Every chosen item must be one of the offered ones. */
internal fun <T> requireOffered(chosen: Collection<T>, offered: Collection<T>, what: String) {
    val allowed = offered.toSet()
    val extra = chosen.filterNot { it in allowed }
    if (extra.isNotEmpty()) invalid("$what not offered: $extra")
}

internal fun Die.toDto() = DieDto(value, rolledAt, type.name)

internal fun DieDto.toDie() = Die(value, rolledAt, Die.DieType.valueOf(kind))

internal fun DiceRoll.toDto() = DiceDto(rolls.map { it.toDto() }, hits, expectedHits, playerName)

internal fun DiceDto.toDiceRoll() = DiceRoll(dice.map { it.toDie() }, hits, expectedHits, player)
```

`online/src/main/kotlin/org/triplea/mobile/online/HostLink.kt`:

```kotlin
package org.triplea.mobile.online

import games.strategy.engine.message.IRemote
import org.triplea.mobile.protocol.Answer
import org.triplea.mobile.protocol.Ask
import org.triplea.mobile.protocol.Note

/**
 * How the host reaches the players. The server implements it on its connections; tests in one
 * process. Called on the game thread.
 */
interface HostLink {
    /**
     * Sends [ask] to the player of [seat] and blocks until they answer. The link adds the question
     * id, game id, version and deadline, and returns only the answer to this question.
     */
    fun ask(seat: String, ask: Ask): Answer

    /** Tells the player why their answer to [questionId] was not accepted; the question follows again. */
    fun reject(questionId: Long, reason: String)

    /** Information for the player of [seat] that needs no answer. */
    fun notify(seat: String, note: Note)
}

/** The game's active delegate, for undo and queries while a question is open. */
fun interface DelegateAccess {
    fun current(): IRemote
}

/**
 * Thrown by a device-side HumanPlayerUi from getMove or getPlacement to undo the move or placement
 * at [index] instead of answering; OnlineClient turns it into Reply.Undo.
 */
class UndoRequest(val index: Int) : RuntimeException("undo $index")
```

`online/src/main/kotlin/games/strategy/triplea/ui/OnlinePlaceData.kt`:

```kotlin
package games.strategy.triplea.ui

import games.strategy.engine.data.Territory
import games.strategy.engine.data.Unit as GameUnit

// The desktop engine, which the server compiles this module against, has a package-private
// PlaceData constructor; this file shares its package.
internal fun newPlaceData(units: Collection<GameUnit>, at: Territory) = PlaceData(units, at)
```

- [ ] **Step 4: Write `RemoteHumanUi.kt`**

`online/src/main/kotlin/org/triplea/mobile/online/RemoteHumanUi.kt`:

```kotlin
package org.triplea.mobile.online

import games.strategy.engine.data.DefaultAttachment
import games.strategy.engine.data.GameData
import games.strategy.engine.data.GamePlayer
import games.strategy.engine.data.MoveDescription
import games.strategy.engine.data.ProductionRule
import games.strategy.engine.data.RepairRule
import games.strategy.engine.data.Resource
import games.strategy.engine.data.Route
import games.strategy.engine.data.TechnologyFrontier
import games.strategy.engine.data.Territory
import games.strategy.engine.data.Unit as GameUnit
import games.strategy.triplea.attachments.PoliticalActionAttachment
import games.strategy.triplea.attachments.UserActionAttachment
import games.strategy.triplea.delegate.DiceRoll
import games.strategy.triplea.delegate.battle.IBattle.BattleType
import games.strategy.triplea.delegate.battle.ScrambleLogic
import games.strategy.triplea.delegate.data.BattleListing
import games.strategy.triplea.delegate.data.CasualtyDetails
import games.strategy.triplea.delegate.data.CasualtyList
import games.strategy.triplea.delegate.data.FightBattleDetails
import games.strategy.triplea.delegate.data.TechResults
import games.strategy.triplea.delegate.data.TechRoll
import games.strategy.triplea.delegate.remote.IAbstractMoveDelegate
import games.strategy.triplea.delegate.remote.IPoliticsDelegate
import games.strategy.triplea.delegate.remote.IUserActionDelegate
import games.strategy.triplea.ui.PlaceData
import games.strategy.triplea.ui.newPlaceData
import java.util.Optional
import java.util.UUID
import org.triplea.java.collections.IntegerMap
import org.triplea.mobile.HumanPlayerUi
import org.triplea.mobile.protocol.Ask
import org.triplea.mobile.protocol.Note
import org.triplea.mobile.protocol.Reply
import org.triplea.mobile.protocol.ScrambleOptionDto
import org.triplea.util.Tuple

/**
 * The host side of one human seat played on another device. Every engine question becomes an
 * [Ask] that [link] carries to the player. The answer is checked against this game and turned back
 * into engine objects; an answer that does not fit is rejected with a reason and the question is
 * asked again, so nothing a device sends reaches the engine unchecked. The engine validates the
 * content itself (legal moves, costs) as it does for the desktop's network players.
 */
class RemoteHumanUi(
    private val seat: String,
    private val data: GameData,
    private val link: HostLink,
    private val delegates: DelegateAccess,
) : HumanPlayerUi {
    private val names = Names(data)

    private fun <T> askUntilValid(question: Ask, decode: (Reply) -> T): T {
        while (true) {
            val answer = link.ask(seat, question)
            try {
                return data.acquireReadLock().use { decode(answer.reply) }
            } catch (e: InvalidAnswerException) {
                link.reject(answer.questionId, e.message.orEmpty())
            } catch (e: IllegalArgumentException) {
                // engine constructors (Route, MoveDescription, ...) check their arguments
                link.reject(answer.questionId, e.message ?: "invalid answer")
            }
        }
    }

    private inline fun <reified R : Reply> Reply.expect(): R = this as? R ?: invalid("unexpected answer ${this::class.simpleName}")

    /** What the player did in a move or placement phase. */
    private sealed interface PhaseAction<out T> {
        data object Finish : PhaseAction<Nothing>

        data class Undo(val index: Int) : PhaseAction<Nothing>

        data class Act<T>(val value: T) : PhaseAction<T>
    }

    /** A move or placement phase question: an action, an undo (the question follows again) or the end. */
    private fun <T : Any> phase(question: Ask, decode: (Reply) -> T): Optional<T> {
        while (true) {
            val action = askUntilValid(question) { reply ->
                when (reply) {
                    Reply.Done -> PhaseAction.Finish
                    is Reply.Undo ->
                        if (reply.index < 0) invalid("undo index must not be negative: ${reply.index}") else PhaseAction.Undo(reply.index)
                    else -> PhaseAction.Act(decode(reply))
                }
            }
            when (action) {
                PhaseAction.Finish -> return Optional.empty()
                is PhaseAction.Undo -> (delegates.current() as IAbstractMoveDelegate<*>).undoMove(action.index)?.let { notifyError(it) }
                is PhaseAction.Act -> return Optional.of(action.value)
            }
        }
    }

    override fun startPhase(player: GamePlayer, stepName: String) = link.notify(seat, Note.PhaseStarted(player.name, stepName))

    override fun getTechRolls(player: GamePlayer): Optional<TechRoll> =
        Optional.ofNullable(askUntilValid(Ask.Tech(player.name)) { reply -> if (reply == Reply.Done) null else techRoll(player, reply.expect()) })

    private fun techRoll(player: GamePlayer, reply: Reply.Tech): TechRoll {
        val tech = when {
            reply.category != null ->
                player.technologyFrontierList.frontiers.firstOrNull { it.name == reply.category }
                    ?: invalid("unknown technology category ${reply.category}")
            reply.advances.isNotEmpty() -> TechnologyFrontier("", data).also { frontier ->
                reply.advances.forEach { name ->
                    frontier.addAdvance(data.technologyFrontier.getAdvanceByName(name) ?: invalid("unknown technology $name"))
                }
            }
            else -> null
        }
        if (reply.rolls < 0 || reply.newTokens < 0) invalid("negative tech rolls or tokens")
        val whoPays = IntegerMap<GamePlayer>()
        reply.whoPays.forEach { (name, amount) ->
            if (amount < 0) invalid("negative payment by $name")
            whoPays.put(names.player(name), amount)
        }
        return TechRoll(tech, reply.rolls, reply.newTokens, if (whoPays.isEmpty()) null else whoPays)
    }

    override fun notifyTechResults(results: TechResults) =
        link.notify(
            seat,
            Note.TechResults(results.rolls?.toList().orEmpty(), results.hits, results.remainder, results.advances.orEmpty(), results.errorString),
        )

    override fun getRepair(
        player: GamePlayer,
        bid: Boolean,
        allowedPlayersToRepair: Collection<GamePlayer>,
    ): Optional<Map<GameUnit, IntegerMap<RepairRule>>> =
        Optional.ofNullable(
            askUntilValid(Ask.Repair(player.name, bid, allowedPlayersToRepair.map { it.name })) { reply ->
                if (reply == Reply.Done) {
                    null
                } else {
                    val rules = player.repairFrontier?.rules.orEmpty().associateBy { it.name }
                    reply.expect<Reply.Repair>().units.entries.associate { (unitId, amounts) ->
                        names.unit(unitId) to IntegerMap<RepairRule>().apply {
                            amounts.forEach { (rule, amount) ->
                                if (amount < 0) invalid("negative repair of $rule")
                                put(rules[rule] ?: invalid("unknown repair rule $rule"), amount)
                            }
                        }
                    }
                }
            },
        )

    override fun getPurchase(player: GamePlayer, bid: Boolean, keepCurrentPurchase: Boolean): Optional<IntegerMap<ProductionRule>> =
        Optional.ofNullable(
            askUntilValid(Ask.Purchase(player.name, bid, keepCurrentPurchase)) { reply ->
                if (reply == Reply.Done) {
                    null
                } else {
                    val rules = player.productionFrontier?.rules.orEmpty().associateBy { it.name }
                    IntegerMap<ProductionRule>().apply {
                        reply.expect<Reply.Purchase>().rules.forEach { (name, amount) ->
                            if (amount < 0) invalid("negative purchase of $name")
                            put(rules[name] ?: invalid("unknown production rule $name"), amount)
                        }
                    }
                }
            },
        )

    override fun getMove(player: GamePlayer, nonCombat: Boolean, stepName: String): Optional<MoveDescription> =
        phase(Ask.Move(player.name, nonCombat, stepName)) { reply -> moveDescription(reply.expect()) }

    private fun moveDescription(reply: Reply.Move): MoveDescription {
        val move = reply.move
        val route = names.territories(move.route)
        if (route.size < 2) invalid("a route needs a start and an end")
        return MoveDescription(
            names.units(move.units),
            Route(route),
            move.seaTransports.entries.associate { (unit, transport) -> names.unit(unit) to names.unit(transport) },
            move.airTransportDependents.entries.associate { (transport, units) -> names.unit(transport) to names.units(units) },
        )
    }

    override fun okToLetAirDie(player: GamePlayer, airCantLand: Collection<Territory>, movePhase: Boolean): Boolean =
        askUntilValid(Ask.AirCantLand(player.name, airCantLand.names(), movePhase)) { it.expect<Reply.YesNo>().yes }

    override fun okToLetUnitsDie(unitsCantFight: Collection<Territory>): Boolean =
        askUntilValid(Ask.UnitsCantFight(unitsCantFight.names())) { it.expect<Reply.YesNo>().yes }

    override fun chooseBattle(player: GamePlayer, battles: BattleListing): Optional<FightBattleDetails> {
        val offered = battles.battlesMap
        val question = Ask.Battle(player.name, offered.entries.associate { (type, places) -> type.name to places.names() })
        // the engine only asks while battles are left, so "done" is not an answer here
        return Optional.of(
            askUntilValid(question) { reply ->
                val choice = reply.expect<Reply.Battle>()
                val type = BattleType.entries.firstOrNull { it.name == choice.battleType } ?: invalid("unknown battle type ${choice.battleType}")
                val where = names.territory(choice.territory)
                if (where !in offered[type].orEmpty()) invalid("no ${choice.battleType} battle in ${choice.territory}")
                if (choice.bombingRaid != type.isBombingRun) invalid("bombing raid does not match ${choice.battleType}")
                FightBattleDetails.builder().where(where).bombingRaid(choice.bombingRaid).battleType(type).build()
            },
        )
    }

    override fun getPlacement(player: GamePlayer, bid: Boolean): Optional<PlaceData> =
        phase(Ask.Place(player.name, bid)) { reply ->
            val place = reply.expect<Reply.Place>()
            newPlaceData(names.units(place.units), names.territory(place.territory))
        }

    override fun waitForEndTurn(player: GamePlayer) {
        askUntilValid(Ask.EndTurn(player.name)) { it.expect<Reply.Done>() }
    }

    override fun getPoliticalActionChoice(player: GamePlayer, firstRun: Boolean, delegate: IPoliticsDelegate): PoliticalActionAttachment? {
        val actions = delegate.validActions
        return if (actions.isEmpty()) null else choose(actions, Ask.Politics(player.name, firstRun, actions.map { it.nameOrThrow }))
    }

    override fun getUserActionChoice(player: GamePlayer, firstRun: Boolean, delegate: IUserActionDelegate): UserActionAttachment? {
        val actions = delegate.validActions
        return if (actions.isEmpty()) null else choose(actions, Ask.UserAction(player.name, firstRun, actions.map { it.nameOrThrow }))
    }

    private fun <A : DefaultAttachment> choose(actions: Collection<A>, question: Ask): A? =
        askUntilValid(question) { reply ->
            if (reply == Reply.Done) {
                null
            } else {
                val name = reply.expect<Reply.Choice>().name
                actions.firstOrNull { it.nameOrThrow == name } ?: invalid("action $name not offered")
            }
        }

    override fun notifyError(error: String) = link.notify(seat, Note.Error(error))

    override fun notifyMessage(message: String, title: String) = link.notify(seat, Note.Message(title, message))

    override fun confirm(title: String, question: String): Boolean =
        askUntilValid(Ask.Confirm(title, question)) { it.expect<Reply.YesNo>().yes }

    override fun confirmInBattle(territory: Territory, title: String, question: String): Boolean =
        askUntilValid(Ask.Confirm(title, question, territory.name)) { it.expect<Reply.YesNo>().yes }

    override fun selectUnits(candidates: Collection<GameUnit>, title: String, message: String, max: Int): Collection<GameUnit> =
        selectUnits(candidates, title, message, max, null)

    override fun selectUnitsInBattle(
        territory: Territory,
        candidates: Collection<GameUnit>,
        title: String,
        message: String,
        max: Int,
    ): Collection<GameUnit> = selectUnits(candidates, title, message, max, territory.name)

    private fun selectUnits(candidates: Collection<GameUnit>, title: String, message: String, max: Int, territory: String?): Collection<GameUnit> =
        askUntilValid(Ask.SelectUnits(candidates.wireIds(), title, message, max, territory)) { reply ->
            val chosen = names.units(reply.expect<Reply.Units>().units)
            requireOffered(chosen, candidates, "units")
            if (chosen.toSet().size != chosen.size) invalid("a unit was chosen twice")
            if (chosen.size > max) invalid("at most $max units")
            chosen
        }

    override fun selectCasualties(
        selectFrom: Collection<GameUnit>,
        dependents: Map<GameUnit, Collection<GameUnit>>,
        count: Int,
        message: String,
        dice: DiceRoll?,
        hit: GamePlayer,
        defaultCasualties: CasualtyList,
        battleId: UUID,
        allowMultipleHitsPerUnit: Boolean,
    ): CasualtyDetails {
        val question = Ask.Casualties(
            selectFrom.wireIds(), count, message, dice?.toDto(), hit.name,
            defaultCasualties.killed.wireIds(), defaultCasualties.damaged.wireIds(), battleId.toString(), allowMultipleHitsPerUnit,
        )
        return askUntilValid(question) { reply ->
            val answer = reply.expect<Reply.Casualties>()
            val killed = names.units(answer.killed)
            val damaged = names.units(answer.damaged)
            requireOffered(killed + damaged, selectFrom, "casualties")
            // the engine only asks when its default selection has exactly `count` entries
            if (killed.size + damaged.size != count) invalid("choose exactly $count casualties")
            CasualtyDetails(killed, damaged, answer.autoCalculated)
        }
    }

    override fun selectFixedDice(numDice: Int, hitAt: Int, title: String, diceSides: Int): IntArray =
        throw IllegalStateException("fixed dice are only asked in edit mode, which is off in online games")

    override fun selectTerritory(candidates: Collection<Territory>, title: String, message: String, noneAllowed: Boolean): Territory? =
        askUntilValid(Ask.SelectTerritory(candidates.names(), title, message, noneAllowed)) { reply ->
            if (reply == Reply.Done) {
                if (!noneAllowed) invalid("a territory must be chosen")
                null
            } else {
                names.territory(reply.expect<Reply.TerritoryChoice>().territory).also { requireOffered(listOf(it), candidates, "territory") }
            }
        }

    override fun retreatQuery(
        battleId: UUID,
        submerge: Boolean,
        battleTerritory: Territory,
        possibleTerritories: Collection<Territory>,
        message: String,
    ): Optional<Territory> =
        Optional.ofNullable(
            askUntilValid(Ask.Retreat(battleId.toString(), submerge, battleTerritory.name, possibleTerritories.names(), message)) { reply ->
                if (reply == Reply.Done) {
                    null
                } else {
                    names.territory(reply.expect<Reply.TerritoryChoice>().territory)
                        .also { requireOffered(listOf(it), possibleTerritories, "retreat territory") }
                }
            },
        )

    override fun scrambleUnitsQuery(
        player: GamePlayer,
        scrambleTo: Territory,
        possibleScramblers: Map<Territory, Tuple<Collection<GameUnit>, Collection<GameUnit>>>,
    ): Map<Territory, Collection<GameUnit>> {
        val options = possibleScramblers.entries.associate { (from, bases) -> from.name to ScrambleOptionDto(bases.first.wireIds(), bases.second.wireIds()) }
        return askUntilValid(Ask.Scramble(player.name, scrambleTo.name, options)) { reply ->
            reply.expect<Reply.Scramble>().units.entries.associate { (from, ids) ->
                val territory = names.territory(from)
                val option = possibleScramblers[territory] ?: invalid("no scramble from $from")
                val units = names.units(ids)
                requireOffered(units, option.second, "scrambling aircraft")
                val max = ScrambleLogic.getMaxScrambleCount(option.first)
                if (units.size > max) invalid("at most $max aircraft may scramble from $from")
                territory to units
            }
        }
    }

    override fun confirmCasualties(battleId: UUID, message: String) {
        askUntilValid(Ask.ConfirmCasualties(battleId.toString(), message)) { it.expect<Reply.Done>() }
    }

    override fun acceptAction(playerSendingProposal: GamePlayer, question: String, politics: Boolean): Boolean =
        askUntilValid(Ask.AcceptAction(playerSendingProposal.name, question, politics)) { it.expect<Reply.YesNo>().yes }

    override fun selectKamikazeSuicideAttacks(
        player: GamePlayer,
        possibleUnitsToAttack: Map<Territory, Collection<GameUnit>>,
        attackResourceToken: Resource,
        maxNumberOfAttacksAllowed: Int,
    ): Map<Territory, IntegerMap<GameUnit>> {
        val targets = possibleUnitsToAttack.entries.associate { (territory, units) -> territory.name to units.wireIds() }
        return askUntilValid(Ask.Kamikaze(player.name, targets, attackResourceToken.name, maxNumberOfAttacksAllowed)) { reply ->
            var total = 0
            val attacks = reply.expect<Reply.Kamikaze>().attacks.entries.associate { (place, amounts) ->
                val territory = names.territory(place)
                val offered = possibleUnitsToAttack[territory] ?: invalid("no kamikaze targets in $place")
                territory to IntegerMap<GameUnit>().apply {
                    amounts.forEach { (id, amount) ->
                        val unit = names.unit(id)
                        if (unit !in offered || amount < 0) invalid("not a kamikaze target: $id")
                        total += amount
                        put(unit, amount)
                    }
                }
            }
            if (total > maxNumberOfAttacksAllowed) invalid("at most $maxNumberOfAttacksAllowed attacks")
            attacks
        }
    }

    override fun pickTerritoryAndUnits(
        player: GamePlayer,
        territoryChoices: List<Territory>,
        unitChoices: List<GameUnit>,
        unitsPerPick: Int,
    ): Tuple<Territory?, Set<GameUnit>> =
        askUntilValid(Ask.PickTerritoryAndUnits(player.name, territoryChoices.names(), unitChoices.wireIds(), unitsPerPick)) { reply ->
            val pick = reply.expect<Reply.Pick>()
            val territory = pick.territory?.let(names::territory)?.also { requireOffered(listOf(it), territoryChoices, "territory") }
            val units = names.units(pick.units).toSet()
            requireOffered(units, unitChoices, "units")
            if (units.size > unitsPerPick) invalid("at most $unitsPerPick units")
            Tuple.of(territory, units)
        }
}
```

- [ ] **Step 5: Write `OnlineClient.kt`**

`online/src/main/kotlin/org/triplea/mobile/online/OnlineClient.kt`:

```kotlin
package org.triplea.mobile.online

import games.strategy.engine.data.GameData
import games.strategy.engine.data.GamePlayer
import games.strategy.engine.data.MoveDescription
import games.strategy.engine.data.Unit as GameUnit
import games.strategy.triplea.attachments.PoliticalActionAttachment
import games.strategy.triplea.attachments.UserActionAttachment
import games.strategy.triplea.delegate.battle.IBattle
import games.strategy.triplea.delegate.battle.IBattle.BattleType
import games.strategy.triplea.delegate.data.BattleListing
import games.strategy.triplea.delegate.data.CasualtyList
import games.strategy.triplea.delegate.data.TechResults
import games.strategy.triplea.delegate.data.TechRoll
import games.strategy.triplea.delegate.remote.IPoliticsDelegate
import games.strategy.triplea.delegate.remote.IUserActionDelegate
import java.lang.reflect.Proxy
import java.util.UUID
import org.triplea.java.collections.IntegerMap
import org.triplea.mobile.GameEventListener
import org.triplea.mobile.HumanPlayerUi
import org.triplea.mobile.protocol.Answer
import org.triplea.mobile.protocol.Ask
import org.triplea.mobile.protocol.Event
import org.triplea.mobile.protocol.GameEvent
import org.triplea.mobile.protocol.MoveDto
import org.triplea.mobile.protocol.Note
import org.triplea.mobile.protocol.Notice
import org.triplea.mobile.protocol.Question
import org.triplea.mobile.protocol.Rejected
import org.triplea.mobile.protocol.Reply
import org.triplea.util.Tuple

/**
 * The device side of an online game. It turns a [Question] into a call on [ui] (the UI that plays
 * local games) and the result into an [Answer]. Notices and events go to [ui] and [events].
 * [data] is the device's copy of the game at the message's version; the caller syncs it first.
 * Call [answer] on a background thread: [ui] blocks until the player has answered.
 */
class OnlineClient(private val ui: HumanPlayerUi, private val events: GameEventListener) {

    fun answer(question: Question, data: GameData): Answer = Answer(question.id, reply(question.ask, Names(data), data))

    private fun reply(ask: Ask, names: Names, data: GameData): Reply =
        when (ask) {
            is Ask.Purchase ->
                ui.getPurchase(names.player(ask.player), ask.bid, ask.keepCurrentPurchase).orElse(null)
                    ?.let { Reply.Purchase(it.byName { rule -> rule.name }) } ?: Reply.Done
            is Ask.Repair ->
                ui.getRepair(names.player(ask.player), ask.bid, ask.allowedPlayers.map(names::player)).orElse(null)
                    ?.let { repair -> Reply.Repair(repair.entries.associate { (unit, rules) -> unit.wireId to rules.byName { it.name } }) }
                    ?: Reply.Done
            is Ask.Tech -> {
                val player = names.player(ask.player)
                ui.getTechRolls(player).orElse(null)?.let { techReply(player, it) } ?: Reply.Done
            }
            is Ask.Move -> undoable {
                ui.getMove(names.player(ask.player), ask.nonCombat, ask.step).orElse(null)?.let { Reply.Move(it.toDto()) } ?: Reply.Done
            }
            is Ask.AirCantLand -> Reply.YesNo(ui.okToLetAirDie(names.player(ask.player), names.territories(ask.territories), ask.movePhase))
            is Ask.UnitsCantFight -> Reply.YesNo(ui.okToLetUnitsDie(names.territories(ask.territories)))
            is Ask.Battle ->
                ui.chooseBattle(names.player(ask.player), battleListing(ask.battles, names)).orElse(null)
                    ?.let { Reply.Battle(it.where.name, it.isBombingRaid, it.battleType.name) } ?: Reply.Done
            is Ask.Place -> undoable {
                ui.getPlacement(names.player(ask.player), ask.bid).orElse(null)?.let { Reply.Place(it.units.wireIds(), it.at.name) } ?: Reply.Done
            }
            is Ask.EndTurn -> {
                ui.waitForEndTurn(names.player(ask.player))
                Reply.Done
            }
            is Ask.Politics -> {
                val player = names.player(ask.player)
                val offered = PoliticalActionAttachment.getPoliticalActionAttachments(player).filter { it.nameOrThrow in ask.actions }
                ui.getPoliticalActionChoice(player, ask.firstRun, offering(IPoliticsDelegate::class.java, offered))
                    ?.let { Reply.Choice(it.nameOrThrow) } ?: Reply.Done
            }
            is Ask.UserAction -> {
                val player = names.player(ask.player)
                val offered = UserActionAttachment.getUserActionAttachments(player).filter { it.nameOrThrow in ask.actions }
                ui.getUserActionChoice(player, ask.firstRun, offering(IUserActionDelegate::class.java, offered))
                    ?.let { Reply.Choice(it.nameOrThrow) } ?: Reply.Done
            }
            is Ask.Confirm ->
                Reply.YesNo(ask.territory?.let { ui.confirmInBattle(names.territory(it), ask.title, ask.question) } ?: ui.confirm(ask.title, ask.question))
            is Ask.SelectUnits -> {
                val candidates = names.units(ask.candidates)
                val chosen = ask.territory?.let { ui.selectUnitsInBattle(names.territory(it), candidates, ask.title, ask.message, ask.max) }
                    ?: ui.selectUnits(candidates, ask.title, ask.message, ask.max)
                Reply.Units(chosen.wireIds())
            }
            is Ask.Casualties -> {
                val details = ui.selectCasualties(
                    names.units(ask.selectFrom), emptyMap(), ask.count, ask.message, ask.dice?.toDiceRoll(), names.player(ask.hit),
                    CasualtyList(names.units(ask.defaultKilled), names.units(ask.defaultDamaged)), UUID.fromString(ask.battleId),
                    ask.allowMultipleHitsPerUnit,
                )
                Reply.Casualties(details.killed.wireIds(), details.damaged.wireIds(), details.autoCalculated)
            }
            is Ask.SelectTerritory ->
                ui.selectTerritory(names.territories(ask.candidates), ask.title, ask.message, ask.noneAllowed)
                    ?.let { Reply.TerritoryChoice(it.name) } ?: Reply.Done
            is Ask.Retreat ->
                ui.retreatQuery(UUID.fromString(ask.battleId), ask.submerge, names.territory(ask.battleTerritory), names.territories(ask.possible), ask.message)
                    .orElse(null)?.let { Reply.TerritoryChoice(it.name) } ?: Reply.Done
            is Ask.Scramble -> {
                val possible = ask.options.entries.associate { (from, option) ->
                    names.territory(from) to Tuple.of<Collection<GameUnit>, Collection<GameUnit>>(names.units(option.airbases), names.units(option.units))
                }
                val chosen = ui.scrambleUnitsQuery(names.player(ask.player), names.territory(ask.scrambleTo), possible)
                Reply.Scramble(chosen.entries.associate { (from, units) -> from.name to units.wireIds() })
            }
            is Ask.ConfirmCasualties -> {
                ui.confirmCasualties(UUID.fromString(ask.battleId), ask.message)
                Reply.Done
            }
            is Ask.AcceptAction -> Reply.YesNo(ui.acceptAction(names.player(ask.from), ask.question, ask.politics))
            is Ask.Kamikaze -> {
                val targets = ask.targets.entries.associate { (place, ids) -> names.territory(place) to names.units(ids) }
                val attacks = ui.selectKamikazeSuicideAttacks(names.player(ask.player), targets, data.resourceList.getResourceOrThrow(ask.resource), ask.max)
                Reply.Kamikaze(attacks.entries.associate { (territory, amounts) -> territory.name to amounts.byName { it.wireId } })
            }
            is Ask.PickTerritoryAndUnits -> {
                val pick = ui.pickTerritoryAndUnits(names.player(ask.player), names.territories(ask.territories), names.units(ask.units), ask.unitsPerPick)
                Reply.Pick(pick.first?.name, pick.second.wireIds())
            }
        }

    private inline fun undoable(block: () -> Reply): Reply =
        try {
            block()
        } catch (e: UndoRequest) {
            Reply.Undo(e.index)
        }

    private fun <K> IntegerMap<K>.byName(name: (K) -> String): Map<String, Int> = keySet().associate { name(it) to getInt(it) }

    private fun MoveDescription.toDto() =
        MoveDto(
            units.wireIds(),
            route.allTerritories.names(),
            unitsToSeaTransports.entries.associate { (unit, transport) -> unit.wireId to transport.wireId },
            airTransportsDependents.entries.associate { (transport, units) -> transport.wireId to units.wireIds() },
        )

    private fun techReply(player: GamePlayer, roll: TechRoll): Reply.Tech {
        val tech = roll.tech
        val category = tech?.name?.takeIf { name -> name.isNotEmpty() && player.technologyFrontierList.frontiers.any { it.name == name } }
        val advances = if (category == null) tech?.techs.orEmpty().map { it.name } else emptyList()
        val whoPays = roll.whoPaysHowMuch?.byName { it.name }.orEmpty()
        return Reply.Tech(category, advances, roll.rolls, roll.newTokens, whoPays)
    }

    private fun battleListing(battles: Map<String, List<String>>, names: Names): BattleListing =
        BattleListing(emptySet<IBattle>()).apply {
            battles.forEach { (type, places) -> battlesMap[BattleType.valueOf(type)] = names.territories(places).toMutableSet() }
        }

    /** The delegate argument of the politics and user action questions: the UI only reads the offered actions. */
    private fun <D : Any> offering(type: Class<D>, actions: Collection<*>): D =
        type.cast(
            Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, _ ->
                when (method.name) {
                    "getValidActions" -> actions
                    "toString" -> "online ${type.simpleName}"
                    else -> throw UnsupportedOperationException("${method.name} is not available in online games")
                }
            },
        )

    fun notice(notice: Notice, data: GameData) {
        when (val note = notice.note) {
            is Note.PhaseStarted -> ui.startPhase(Names(data).player(note.player), note.step)
            is Note.Error -> ui.notifyError(note.text)
            is Note.Message -> ui.notifyMessage(note.text, note.title)
            is Note.TechResults ->
                ui.notifyTechResults(note.error?.let { TechResults(it) } ?: TechResults(note.rolls.toIntArray(), note.remainder, note.hits, note.advances))
        }
    }

    fun rejected(rejected: Rejected) = ui.notifyError(rejected.reason)

    fun event(gameEvent: GameEvent, data: GameData) {
        val names = Names(data)
        when (val e = gameEvent.event) {
            is Event.Message -> events.message(e.title, e.text)
            is Event.GameEnded -> events.gameEnded(e.message)
            is Event.StepChanged -> events.gameStepChanged(e.step, e.displayName, e.player?.let(names::player), e.round)
            is Event.BattleStarted ->
                events.battleStarted(
                    UUID.fromString(e.battleId), names.territory(e.territory), e.title, names.knownUnits(e.attackingUnits),
                    names.knownUnits(e.defendingUnits), names.player(e.attacker), names.player(e.defender), BattleType.valueOf(e.battleType),
                )
            is Event.BattleSteps -> events.battleSteps(UUID.fromString(e.battleId), e.steps)
            is Event.BattleStep -> events.battleStep(UUID.fromString(e.battleId), e.step)
            is Event.BattleEnded -> events.battleEnded(UUID.fromString(e.battleId), e.message)
            is Event.DiceRolled -> events.diceRolled(e.dice.toDiceRoll(), e.step)
            is Event.Casualties ->
                events.casualties(
                    UUID.fromString(e.battleId), e.step, e.dice?.toDiceRoll(), names.player(e.player),
                    names.knownUnits(e.killed), names.knownUnits(e.damaged), emptyMap(),
                )
            is Event.UnitsDied -> events.unitsDied(UUID.fromString(e.battleId), names.player(e.player), names.knownUnits(e.dead), emptyMap())
            is Event.UnitsChanged ->
                events.unitsChanged(UUID.fromString(e.battleId), names.player(e.player), names.knownUnits(e.removed), names.knownUnits(e.added))
            is Event.BombingResults -> events.bombingResults(UUID.fromString(e.battleId), e.dice.map { it.toDie() }, e.cost)
            is Event.Retreat -> events.retreat(e.shortMessage, e.message, e.step, e.player?.let(names::player))
            is Event.UnitsRetreated -> events.unitsRetreated(UUID.fromString(e.battleId), names.knownUnits(e.units))
            is Event.UnitsMoved -> events.unitsMoved(names.player(e.player), names.knownUnits(e.units), names.territories(e.route))
            is Event.PlaySound -> events.playSound(e.clip, e.player?.let(names::player))
        }
    }
}
```

- [ ] **Step 6: Write `Placeable.kt`**

```kotlin
package org.triplea.mobile.online

import games.strategy.engine.data.GameData
import games.strategy.triplea.delegate.data.PlaceableUnits
import games.strategy.triplea.delegate.remote.IAbstractPlaceDelegate
import org.triplea.mobile.protocol.PlaceableQuery
import org.triplea.mobile.protocol.PlaceableResult

/**
 * Host side: which of the query's units can be placed in its territory, asked of the active place
 * delegate. Runs on the connection's thread while the placement question is open; read-only
 * delegate queries from another thread are allowed, as for the desktop's network players. The
 * caller checks that [PlaceableQuery.questionId] is the open placement question of that player.
 */
fun answerPlaceable(query: PlaceableQuery, data: GameData, delegates: DelegateAccess): PlaceableResult =
    try {
        val names = Names(data)
        val (units, territory) = data.acquireReadLock().use { names.units(query.units) to names.territory(query.territory) }
        val delegate = delegates.current() as? IAbstractPlaceDelegate ?: invalid("no placement in progress")
        val result = delegate.getPlaceableUnits(units, territory)
        if (result.isError) {
            PlaceableResult(query.questionId, query.territory, emptyList(), 0, result.errorMessage)
        } else {
            PlaceableResult(query.questionId, query.territory, result.units.wireIds(), result.maxUnits)
        }
    } catch (e: InvalidAnswerException) {
        PlaceableResult(query.questionId, query.territory, emptyList(), 0, e.message)
    }

/** Device side: the engine's view of a [PlaceableResult] in the device's copy of the game. */
fun PlaceableResult.toPlaceableUnits(data: GameData): PlaceableUnits =
    error?.let { PlaceableUnits(it) } ?: PlaceableUnits(Names(data).units(units), maxUnits)
```

- [ ] **Step 7: Write `EventEncoder.kt`**

```kotlin
package org.triplea.mobile.online

import games.strategy.engine.data.GamePlayer
import games.strategy.engine.data.Territory
import games.strategy.engine.data.Unit as GameUnit
import games.strategy.triplea.delegate.DiceRoll
import games.strategy.triplea.delegate.Die
import games.strategy.triplea.delegate.battle.IBattle.BattleType
import java.util.UUID
import org.triplea.mobile.GameEventListener
import org.triplea.mobile.protocol.Event

/**
 * Host side: the engine's events as [Event]s for every player. [emit] adds the game id and version
 * and sends them; the device replays them with OnlineClient.event.
 */
class EventEncoder(private val emit: (Event) -> Unit) : GameEventListener {
    override fun message(title: String, message: String) = emit(Event.Message(title, message))

    override fun gameEnded(message: String) = emit(Event.GameEnded(message))

    override fun gameStepChanged(stepName: String, displayName: String, player: GamePlayer?, round: Int) =
        emit(Event.StepChanged(stepName, displayName, player?.name, round))

    override fun battleStarted(
        battleId: UUID,
        location: Territory,
        battleTitle: String,
        attackingUnits: Collection<GameUnit>,
        defendingUnits: Collection<GameUnit>,
        attacker: GamePlayer,
        defender: GamePlayer,
        battleType: BattleType,
    ) = emit(
        Event.BattleStarted(
            battleId.toString(), location.name, battleTitle, attackingUnits.wireIds(), defendingUnits.wireIds(),
            attacker.name, defender.name, battleType.name,
        ),
    )

    override fun battleSteps(battleId: UUID, steps: List<String>) = emit(Event.BattleSteps(battleId.toString(), steps))

    override fun battleStep(battleId: UUID, step: String) = emit(Event.BattleStep(battleId.toString(), step))

    override fun battleEnded(battleId: UUID, message: String) = emit(Event.BattleEnded(battleId.toString(), message))

    override fun diceRolled(dice: DiceRoll, stepName: String) = emit(Event.DiceRolled(dice.toDto(), stepName))

    override fun casualties(
        battleId: UUID,
        step: String,
        dice: DiceRoll?,
        player: GamePlayer,
        killed: Collection<GameUnit>,
        damaged: Collection<GameUnit>,
        dependents: Map<GameUnit, Collection<GameUnit>>,
    ) = emit(Event.Casualties(battleId.toString(), step, dice?.toDto(), player.name, killed.wireIds(), damaged.wireIds()))

    override fun unitsDied(battleId: UUID, player: GamePlayer, dead: Collection<GameUnit>, dependents: Map<GameUnit, Collection<GameUnit>>) =
        emit(Event.UnitsDied(battleId.toString(), player.name, dead.wireIds()))

    override fun unitsChanged(battleId: UUID, player: GamePlayer, removedUnits: Collection<GameUnit>, addedUnits: Collection<GameUnit>) =
        emit(Event.UnitsChanged(battleId.toString(), player.name, removedUnits.wireIds(), addedUnits.wireIds()))

    override fun bombingResults(battleId: UUID, dice: List<Die>, cost: Int) =
        emit(Event.BombingResults(battleId.toString(), dice.map { it.toDto() }, cost))

    override fun retreat(shortMessage: String, message: String, step: String, player: GamePlayer?) =
        emit(Event.Retreat(shortMessage, message, step, player?.name))

    override fun unitsRetreated(battleId: UUID, retreating: Collection<GameUnit>) = emit(Event.UnitsRetreated(battleId.toString(), retreating.wireIds()))

    override fun unitsMoved(player: GamePlayer, units: Collection<GameUnit>, route: List<Territory>) =
        emit(Event.UnitsMoved(player.name, units.wireIds(), route.names()))

    override fun playSound(clipName: String, player: GamePlayer?) = emit(Event.PlaySound(clipName, player?.name))
}
```

- [ ] **Step 8: Write the test helpers `Wire.kt` and `ScriptedPlayer.kt`**

`online/src/test/kotlin/org/triplea/mobile/online/Wire.kt`:

```kotlin
package org.triplea.mobile.online

import games.strategy.engine.data.GameData
import games.strategy.engine.data.Territory
import games.strategy.engine.data.Unit as GameUnit
import games.strategy.engine.framework.GameDataManager
import games.strategy.triplea.delegate.data.PlaceableUnits
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.reflect.KClass
import org.triplea.mobile.protocol.Answer
import org.triplea.mobile.protocol.Ask
import org.triplea.mobile.protocol.ClientMessage
import org.triplea.mobile.protocol.Event
import org.triplea.mobile.protocol.GameEvent
import org.triplea.mobile.protocol.Note
import org.triplea.mobile.protocol.Notice
import org.triplea.mobile.protocol.PlaceableQuery
import org.triplea.mobile.protocol.ProtocolJson
import org.triplea.mobile.protocol.Question
import org.triplea.mobile.protocol.Rejected
import org.triplea.mobile.protocol.Reply
import org.triplea.mobile.protocol.ServerMessage

private const val GAME = "test-game"

/**
 * Host and devices in one process. Every message is encoded to JSON and decoded again. The devices
 * read the game from a copy that is re-loaded from a save whenever the host's game changed, which
 * is what the app does with the server's saves.
 */
internal class Wire(private val host: GameData) : HostLink {
    private var version = 0L
    private var nextQuestionId = 1L
    private var openQuestionId = 0L
    private var mirror: GameData? = null
    private var mirrorVersion = -1L
    private val queuedEvents = mutableListOf<Event>()
    private val clients = mutableMapOf<String, OnlineClient>()
    private val tampered = mutableMapOf<Pair<String, KClass<out Ask>>, ArrayDeque<Reply>>()
    val players = mutableMapOf<String, ScriptedPlayer>()
    val asked = mutableListOf<String>()
    val rejections = mutableListOf<String>()
    lateinit var delegates: DelegateAccess

    init {
        host.addDataChangeListener { version++ }
    }

    fun seat(nation: String, player: ScriptedPlayer) {
        players[nation] = player
        clients[nation] = OnlineClient(player, player)
    }

    /** The next questions of [type] to [seat] get [replies] instead of the player's answer, one each. */
    fun tamper(seat: String, type: KClass<out Ask>, vararg replies: Reply) {
        tampered.getOrPut(seat to type) { ArrayDeque() }.addAll(replies)
    }

    override fun ask(seat: String, ask: Ask): Answer {
        flushEvents()
        val question = viaServerJson(Question(nextQuestionId++, GAME, seat, version, ask))
        openQuestionId = question.id
        asked += ask::class.simpleName!!
        val forged = tampered[seat to ask::class]?.removeFirstOrNull()
        val answer = forged?.let { Answer(question.id, it) } ?: clients.getValue(seat).answer(question, mirror())
        return viaClientJson(answer)
    }

    override fun reject(questionId: Long, reason: String) {
        rejections += viaServerJson(Rejected(questionId, reason)).reason
    }

    override fun notify(seat: String, note: Note) {
        flushEvents()
        clients.getValue(seat).notice(viaServerJson(Notice(GAME, seat, version, note)), mirror())
    }

    /**
     * Events are delivered before the next question or notice, never while the engine emits them:
     * copying the game takes its write lock, and the game thread may hold the read lock while it
     * emits an event.
     */
    fun event(event: Event) {
        queuedEvents += event
    }

    fun flushEvents() {
        val pending = queuedEvents.toList()
        queuedEvents.clear()
        for (event in pending) {
            val message = viaServerJson(GameEvent(GAME, version, event))
            clients.values.forEach { it.event(message, mirror()) }
        }
    }

    /** Sends [event] straight to every device, as a server would for an event of a newer version. */
    fun deliver(event: Event) {
        val message = viaServerJson(GameEvent(GAME, version, event))
        clients.values.forEach { it.event(message, mirror()) }
    }

    /** The placeable-units query of the open placement question, host side and back. */
    fun placeable(territory: Territory, units: List<GameUnit>): PlaceableUnits {
        val query = viaClientJson(PlaceableQuery(openQuestionId, territory.name, units.map { it.id.toString() }))
        return viaServerJson(answerPlaceable(query, host, delegates)).toPlaceableUnits(mirror())
    }

    private fun mirror(): GameData {
        if (mirrorVersion != version) {
            val save = ByteArrayOutputStream()
            GameDataManager.saveGame(save, host)
            mirror = GameDataManager.loadGame(ByteArrayInputStream(save.toByteArray())).orElseThrow()
            mirrorVersion = version
        }
        return mirror!!
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T : ServerMessage> viaServerJson(message: T): T = ProtocolJson.decodeServerMessage(ProtocolJson.encode(message)) as T

    @Suppress("UNCHECKED_CAST")
    private fun <T : ClientMessage> viaClientJson(message: T): T = ProtocolJson.decodeClientMessage(ProtocolJson.encode(message)) as T
}
```

`deliver` and `placeable` are used by the test in Task 3.

`online/src/test/kotlin/org/triplea/mobile/online/ScriptedPlayer.kt`:

```kotlin
package org.triplea.mobile.online

import games.strategy.engine.data.GameData
import games.strategy.engine.data.GamePlayer
import games.strategy.engine.data.MoveDescription
import games.strategy.engine.data.ProductionRule
import games.strategy.engine.data.Route
import games.strategy.engine.data.Territory
import games.strategy.engine.data.Unit as GameUnit
import games.strategy.triplea.delegate.DiceRoll
import games.strategy.triplea.delegate.battle.IBattle.BattleType
import games.strategy.triplea.delegate.data.BattleListing
import games.strategy.triplea.delegate.data.CasualtyDetails
import games.strategy.triplea.delegate.data.CasualtyList
import games.strategy.triplea.delegate.data.FightBattleDetails
import games.strategy.triplea.delegate.data.PlaceableUnits
import games.strategy.triplea.ui.PlaceData
import java.util.Optional
import java.util.UUID
import org.triplea.java.collections.IntegerMap
import org.triplea.mobile.GameEventListener
import org.triplea.mobile.HumanPlayerUiAdapter

/**
 * A scripted player on the device side. It buys one unit per turn, places at its factory and ends
 * its turn. With [attack] = from to to, its first combat move moves one infantry, undoes it and
 * then attacks with every unit in `from`. It records what the engine asked and showed.
 */
internal class ScriptedPlayer(private val attack: Pair<String, String>? = null) : HumanPlayerUiAdapter(), GameEventListener {
    var purchases = 0
    var endTurns = 0
    var casualtyChoices = 0
    var undos = 0
    var infantryBackAfterUndo = -1
    val errors = mutableListOf<String>()
    val events = mutableListOf<String>()
    val placeable = mutableListOf<PlaceableUnits>()

    /** Set by the test: asks the host which units fit into a territory during placement. */
    var askPlaceable: ((Territory, List<GameUnit>) -> PlaceableUnits)? = null
    private var moveScript = if (attack == null) 0 else 1

    override fun getPurchase(player: GamePlayer, bid: Boolean, keepCurrentPurchase: Boolean): Optional<IntegerMap<ProductionRule>> {
        purchases++
        val rule = player.productionFrontier.rules.firstOrNull { rule ->
            rule.results.totalValues() > 0 && rule.costs.entrySet().all { player.resources.getQuantity(it.key) >= it.value }
        } ?: return Optional.empty()
        return Optional.of(IntegerMap<ProductionRule>().apply { put(rule, 1) })
    }

    override fun getMove(player: GamePlayer, nonCombat: Boolean, stepName: String): Optional<MoveDescription> {
        val (from, to) = attack ?: return Optional.empty()
        val data = player.data
        return when (moveScript) {
            1 -> {
                moveScript = 2
                Optional.of(MoveDescription(infantry(data, "red north 1", player).take(1), Route(data.t("red north 1"), data.t("red middle 1"))))
            }
            2 -> {
                moveScript = 3
                undos++
                throw UndoRequest(0)
            }
            3 -> {
                moveScript = 0
                infantryBackAfterUndo = infantry(data, "red north 1", player).size
                val start = data.t(from)
                Optional.of(MoveDescription(start.units.filter { it.owner == player }, Route(start, data.t(to))))
            }
            else -> Optional.empty()
        }
    }

    override fun chooseBattle(player: GamePlayer, battles: BattleListing): Optional<FightBattleDetails> {
        val (type, territory) = battles.battlesMap.entries.flatMap { (type, places) -> places.map { type to it } }.first()
        return Optional.of(FightBattleDetails.builder().where(territory).bombingRaid(type.isBombingRun).battleType(type).build())
    }

    override fun selectCasualties(
        selectFrom: Collection<GameUnit>,
        dependents: Map<GameUnit, Collection<GameUnit>>,
        count: Int,
        message: String,
        dice: DiceRoll?,
        hit: GamePlayer,
        defaultCasualties: CasualtyList,
        battleId: UUID,
        allowMultipleHitsPerUnit: Boolean,
    ): CasualtyDetails {
        casualtyChoices++
        return CasualtyDetails(defaultCasualties, false)
    }

    override fun getPlacement(player: GamePlayer, bid: Boolean): Optional<PlaceData> {
        val toPlace = player.unitCollection.units.toList()
        if (toPlace.isEmpty()) return Optional.empty()
        val factory = player.data.map.territories.firstOrNull { territory ->
            territory.owner == player && territory.units.any { it.unitAttachment.canProduceUnits() }
        } ?: return Optional.empty()
        askPlaceable?.let { placeable += it(factory, toPlace) }
        return Optional.of(PlaceData(toPlace, factory))
    }

    override fun waitForEndTurn(player: GamePlayer) {
        endTurns++
    }

    override fun notifyError(error: String) {
        errors += error
    }

    override fun notifyMessage(message: String, title: String) {}

    override fun confirm(title: String, question: String): Boolean = true

    override fun gameStepChanged(stepName: String, displayName: String, player: GamePlayer?, round: Int) {
        events += "stepChanged"
    }

    override fun battleStarted(
        battleId: UUID,
        location: Territory,
        battleTitle: String,
        attackingUnits: Collection<GameUnit>,
        defendingUnits: Collection<GameUnit>,
        attacker: GamePlayer,
        defender: GamePlayer,
        battleType: BattleType,
    ) {
        events += "battleStarted"
    }

    override fun diceRolled(dice: DiceRoll, stepName: String) {
        events += "diceRolled"
    }

    override fun unitsRetreated(battleId: UUID, retreating: Collection<GameUnit>) {
        events += "unitsRetreated:${retreating.size}"
    }

    private fun infantry(data: GameData, territory: String, player: GamePlayer): List<GameUnit> =
        data.t(territory).units.filter { it.owner == player && it.type.name == "infantry" }

    private fun GameData.t(name: String): Territory = map.getTerritoryOrNull(name)!!
}
```

- [ ] **Step 9: Write the first game test**

`online/src/test/kotlin/org/triplea/mobile/online/OnlineGameTest.kt`:

```kotlin
package org.triplea.mobile.online

import games.strategy.engine.data.GameData
import games.strategy.engine.message.IRemote
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.triplea.mobile.LocalGameSession
import org.triplea.mobile.MobileEngine
import org.triplea.mobile.PlayerKind
import org.triplea.mobile.protocol.Ask
import org.triplea.mobile.protocol.Reply

/**
 * Plays the minimap with both nations as remote seats: the host runs the engine with a
 * [RemoteHumanUi] per seat, the devices answer through [OnlineClient] with a [ScriptedPlayer], and
 * every message goes through JSON ([Wire]). A hang (e.g. copying the game while the game thread
 * holds its read lock) fails through the timeout.
 */
@Timeout(value = 5, unit = TimeUnit.MINUTES)
class OnlineGameTest {
    companion object {
        @JvmStatic
        @BeforeAll
        fun installMinimap() {
            MobileEngine.configure(Files.createTempDirectory("online-game-test"))
            val source = Path.of(OnlineGameTest::class.java.getResource("/maps/minimap")!!.toURI())
            val target = MobileEngine.getMapsFolder().resolve("minimap")
            Files.walk(source).use { paths ->
                for (path in paths.toList()) {
                    val dest = target.resolve(source.relativize(path).toString())
                    if (Files.isDirectory(path)) Files.createDirectories(dest) else Files.copy(path, dest)
                }
            }
        }
    }

    @Test
    fun remoteSeatsPlayTwoRoundsAndBadAnswersAreRejected() {
        val host = parseMinimap()
        val wire = Wire(host)
        wire.seat("Russians", ScriptedPlayer())
        wire.seat("Italians", ScriptedPlayer())
        wire.tamper(
            "Russians",
            Ask.Purchase::class,
            Reply.Purchase(mapOf("no such rule" to 1)),
            Reply.Purchase(mapOf("buyInfantry" to -1)),
            Reply.YesNo(true),
        )
        val session = startGame(wire, host)
        session.setUpForSteps()
        playUntilRound(session, host, 3)

        assertThat(host.sequence.round).isGreaterThanOrEqualTo(3)
        for ((nation, player) in wire.players) {
            assertThat(player.purchases).describedAs("$nation purchases").isGreaterThanOrEqualTo(2)
            assertThat(player.endTurns).describedAs("$nation end turns").isGreaterThanOrEqualTo(2)
            assertThat(player.errors).describedAs("$nation errors").isEmpty()
        }
        assertThat(wire.rejections).containsExactly(
            "unknown production rule no such rule",
            "negative purchase of buyInfantry",
            "unexpected answer YesNo",
        )
        assertThat(wire.asked).contains("Purchase", "Move", "Place", "EndTurn")
    }

    private fun parseMinimap(): GameData = MobileEngine.parseGame(MobileEngine.listInstalledGames()[0].xmlPath).orElseThrow()

    private fun startGame(wire: Wire, host: GameData): LocalGameSession {
        lateinit var session: LocalGameSession
        wire.delegates = DelegateAccess { session.getCurrentRemoteDelegate(IRemote::class.java) }
        session = LocalGameSession.create(
            host,
            wire.players.keys.associateWith { PlayerKind.HUMAN },
            { seat -> RemoteHumanUi(seat, host, wire, wire.delegates) },
            EventEncoder(wire::event),
        )
        return session
    }

    private fun playUntilRound(session: LocalGameSession, host: GameData, round: Int) {
        var steps = 0
        while (host.sequence.round < round && steps < 400 && !session.isGameOver) {
            session.runNextStep()
            steps++
        }
        session.stop()
    }
}
```

- [ ] **Step 10: Run the tests**

Run: `./gradlew :online:test :engine:test --console=plain`
Expected: PASS. `OnlineGameTest.remoteSeatsPlayTwoRoundsAndBadAnswersAreRejected` passes, and so
do LocalGameSmokeTest (3) and HumanPlayerFlowTest (1).

If the test hangs until the timeout, a question was asked while the game thread held the read
lock. Report it as BLOCKED with the stack trace (`jstack`, or the timeout's thread dump), because
milestone 2b's save download has the same problem. Do not work around it in the test.

- [ ] **Step 11: Leave the changes in the working tree**

Suggested commit message: `online: host and device sides of a remote human seat, played over JSON in a game test`

---

### Task 3: The battle path over JSON: undo, casualties, placeable query, events

**Files:**
- Test: modify `online/src/test/kotlin/org/triplea/mobile/online/OnlineGameTest.kt`

**Interfaces:**
- Consumes: Task 2's `Wire`, `ScriptedPlayer`, `EventEncoder`, `answerPlaceable`.
- Produces: the test that pins the battle path, undo and events.

- [ ] **Step 1: Add the attack test to `OnlineGameTest`**

Add these imports:

```kotlin
import games.strategy.engine.data.changefactory.ChangeFactory
import games.strategy.engine.random.IRandomSource
import java.util.UUID
import org.assertj.core.api.Assertions.assertThatCode
import org.triplea.mobile.protocol.Event
```

Add this test method and the dice object to the class:

```kotlin
    @Test
    fun attackWithUndoCasualtiesPlacementQueryAndEvents() {
        val host = parseMinimap()
        val russians = host.playerList.getPlayerId("Russians")!!
        // a mixed stack next to the enemy, so the attack forces a real casualty choice
        val reinforcements = host.unitTypeList.getUnitTypeOrThrow("artillery").create(1, russians) +
            host.unitTypeList.getUnitTypeOrThrow("armour").create(1, russians)
        host.performChange(ChangeFactory.addUnits(host.map.getTerritoryOrNull("red north 2")!!, reinforcements))
        val attacker = ScriptedPlayer(attack = "red north 2" to "blue north 2")
        val wire = Wire(host)
        wire.seat("Russians", attacker)
        wire.seat("Italians", ScriptedPlayer())
        attacker.askPlaceable = wire::placeable
        wire.tamper("Russians", Ask.Move::class, Reply.Undo(-1), Reply.Undo(99))
        wire.tamper("Russians", Ask.Battle::class, Reply.Done)
        wire.tamper("Russians", Ask.Casualties::class, Reply.Casualties(emptyList(), emptyList(), false))
        val session = startGame(wire, host)
        // every die hits: the 2 defenders hit 2 of the 4 mixed attackers, who must choose; the defenders all die
        session.serverGame.setRandomSource(AlwaysHits)
        session.setUpForSteps()
        playUntilRound(session, host, 3)
        wire.flushEvents()

        assertThat(wire.rejections).containsExactly(
            "undo index must not be negative: -1",
            "unexpected answer Done",
            "choose exactly 2 casualties",
        )
        assertThat(attacker.errors).describedAs("engine answer to undoing a move that does not exist").containsExactly("No moves to undo")
        assertThat(wire.players.getValue("Italians").errors).isEmpty()
        assertThat(attacker.undos).isEqualTo(1)
        assertThat(attacker.infantryBackAfterUndo).describedAs("infantry in red north 1 after the undo").isEqualTo(2)
        assertThat(attacker.casualtyChoices).isGreaterThanOrEqualTo(1)
        assertThat(host.map.getTerritoryOrNull("blue north 2")!!.owner).isEqualTo(russians)
        assertThat(attacker.placeable).anySatisfy { assertThat(it.isError).isFalse(); assertThat(it.units).isNotEmpty() }
        assertThat(attacker.events).contains("battleStarted", "diceRolled", "stepChanged")

        // an event naming a unit the device does not know is shown without that unit
        assertThatCode { wire.deliver(Event.UnitsRetreated(UUID.randomUUID().toString(), listOf(UUID.randomUUID().toString()))) }
            .doesNotThrowAnyException()
        assertThat(attacker.events).contains("unitsRetreated:0")
    }

    private object AlwaysHits : IRandomSource {
        override fun getRandom(max: Int, annotation: String): Int = 0

        override fun getRandom(max: Int, count: Int, annotation: String): IntArray = IntArray(count)
    }
```

`wire.deliver` runs after `session.stop()`. It only needs the host's game data, not the running
loop.

- [ ] **Step 2: Run the tests**

Run: `./gradlew :online:test :engine:test --console=plain`
Expected: PASS. Both `OnlineGameTest` tests, LocalGameSmokeTest (3) and HumanPlayerFlowTest (1).

If `casualtyChoices` stays 0, the engine auto-chose the casualties. Print the battle's units: the
reinforcements must be in "red north 2" and the defenders must hit (dice 0). Do not loosen the
assertion.

- [ ] **Step 3: Leave the changes in the working tree**

Suggested commit message: `online: battle events, undo, casualties and the placeable query over JSON`

---

### Task 4: Documentation

**Files:**
- Modify: `docs/DEVELOPMENT.md`

**Interfaces:** none.

- [ ] **Step 1: Project layout**

In the table under `## Project layout`, add two rows between `engine/` and `app/`:

```markdown
| `protocol/` | The JSON messages between the app and the game server (Kotlin, kotlinx.serialization). No engine dependency. |
| `online/` | Human seats played over those messages: `RemoteHumanUi` (host side, one per seat), `OnlineClient` (device side), `EventEncoder` and the placeable-units query. Kotlin on top of `engine/`; the server compiles the same sources against the desktop engine. |
```

- [ ] **Step 2: Resync step**

In the paragraph under `### Engine` that lists the places to update when moving to a newer tag,
change "update the tag here, in `engine/upstream/*.sh` and in the server repo" to "update the tag
here, in `engine/upstream/*.sh`, in `ENGINE_BASE_TAG` (`protocol/.../Protocol.kt`) and in the
server repo".

- [ ] **Step 3: New section "Online play" after `## How a game runs`**

```markdown
## Online play (protocol and online modules)

Milestone 2 of `docs/superpowers/specs/2026-10-12-multiplayer-lobby-design.md`. The server runs
the game; a human seat on a phone is played through JSON messages (`protocol/`):

- The host gives every remote seat a `RemoteHumanUi` (`LocalGameSession.create` with a UI per
  nation). Each `HumanPlayerUi` question becomes an `Ask`, sent through a `HostLink`.
  - The answer (`Reply`) is checked against the game before the engine sees it: ids exist, chosen
    items were offered, amounts are not negative, the casualty count matches.
  - A bad answer gets `Rejected` with a reason, and the question is asked again.
  - "Select fixed dice" (edit mode) is never asked online.
- The device keeps a read-only copy of the game, loaded from the host's save of the question's
  `version`. `OnlineClient` decodes the question against that copy and calls the same
  `HumanPlayerUi` that plays local games.
  - Units travel by UUID, territories and nations by name.
  - The save carries the move and place delegates' "moves made", so the device reads them
    locally.
  - To undo, the device UI throws `UndoRequest(index)` from `getMove`/`getPlacement`; that is
    sent as `Reply.Undo` and the question follows again.
  - "Which units can be placed here" is the only query to the host
    (`PlaceableQuery`/`PlaceableResult`).
- Battle, dice and step events go to every device as `GameEvent`s (`EventEncoder` on the host,
  `OnlineClient.event` on the device). Units the device's copy does not know are left out of an
  event.
- Messages are limited to 64 KB (`MAX_MESSAGE_CHARS`). The app's first message is `Hello` with
  `PROTOCOL_VERSION` and `ENGINE_BASE_TAG`; a mismatch is answered with `UpdateRequired`.
- **Changing a message:**
  1. Bump `PROTOCOL_VERSION`.
  2. Run `.\gradlew :protocol:test`; it fails and writes `protocol/build/golden/protocol-v<N>.json`.
  3. Read that file and copy it to `protocol/src/test/resources/golden/`.

  A golden file that was released is never edited.
- Copying the game for a device takes its write lock. Events are therefore not synced while the
  engine emits them (the game thread may hold the read lock then). `OnlineGameTest` syncs at every
  question under a timeout.
```

- [ ] **Step 4: Building**

In the PowerShell block under `## Building`, add after the `:engine:test` line:

```powershell
.\gradlew :protocol:test :online:test   # message golden file, online seats playing the minimap
```

- [ ] **Step 5: Leave the changes in the working tree**

Suggested commit message: `docs: protocol and online modules, online play`

---

## Self-Review notes (for the executor)

- **Spec coverage, 2a part:**

  | Spec item | Where |
  |---|---|
  | `protocol/` pure Kotlin JSON | Task 1 |
  | ids by UUID and name | `Names` |
  | `PROTOCOL_VERSION` + engine id on connect | `Hello`, `UpdateRequired` |
  | `Question`/`Answer`/`GameEvent`/`GameVersion`, rejected with reason | Tasks 1–3 |
  | 64 KB | `ProtocolJson` |
  | roundtrip + golden tests | Task 1 |
  | `RemoteMobilePlayer` built like `MobilePlayer` | `MobilePlayer` + `RemoteHumanUi`, the server reuses both |
  | the engine validates the content, the app is never trusted | `RemoteHumanUi` checks plus the engine's own checks |

- **Left for 2b:**
  - transport
  - question ids across restarts
  - deadlines
  - `GameVersion` push and the save download (`GET /games/X/save`)
  - which user owns a seat
  - spectators
- **Left for 2c:** the app's online mode.
- **Left for 3:**
  - `Clock`, `Presence`, `Chat`, `LobbyUpdate`
  - standing orders
