package org.triplea.mobile.app.game

import games.strategy.engine.data.GamePlayer
import games.strategy.engine.data.MoveDescription
import games.strategy.engine.data.ProductionRule
import games.strategy.engine.data.RepairRule
import games.strategy.engine.data.Resource
import games.strategy.engine.data.Territory
import games.strategy.engine.data.Unit
import games.strategy.triplea.attachments.PoliticalActionAttachment
import games.strategy.triplea.attachments.UserActionAttachment
import games.strategy.triplea.delegate.DiceRoll
import games.strategy.triplea.delegate.data.BattleListing
import games.strategy.triplea.delegate.data.CasualtyDetails
import games.strategy.triplea.delegate.data.CasualtyList
import games.strategy.triplea.delegate.data.FightBattleDetails
import games.strategy.triplea.delegate.data.TechRoll
import games.strategy.triplea.ui.PlaceData
import java.util.Optional
import java.util.UUID
import java.util.concurrent.CompletableFuture
import org.triplea.java.collections.IntegerMap
import org.triplea.util.Tuple

/**
 * A question the engine (running on the game thread) asks the user. The UI answers by calling
 * [complete]; the game thread is blocked in the meantime.
 */
sealed class UiRequest<T> {
    val result: CompletableFuture<T> = CompletableFuture()

    fun complete(value: T) {
        result.complete(value)
    }

    fun cancel() {
        result.completeExceptionally(InterruptedException("request cancelled"))
    }
}

class MoveRequest(val player: GamePlayer, val nonCombat: Boolean, val stepName: String) :
    UiRequest<Optional<MoveDescription>>()

class PurchaseRequest(val player: GamePlayer, val bid: Boolean) :
    UiRequest<Optional<IntegerMap<ProductionRule>>>()

class PlaceRequest(val player: GamePlayer, val bid: Boolean) : UiRequest<Optional<PlaceData>>()

class BattleRequest(val player: GamePlayer, val battles: BattleListing) :
    UiRequest<Optional<FightBattleDetails>>()

class EndTurnRequest(val player: GamePlayer) : UiRequest<Boolean>()

class ConfirmRequest(
    val title: String,
    val question: String,
    val okOnly: Boolean = false,
    /** The battle territory this question belongs to; such questions are asked in the battle window. */
    val territory: String? = null,
) : UiRequest<Boolean>()

class SelectTerritoryRequest(
    val candidates: List<Territory>,
    val title: String,
    val message: String,
    val noneAllowed: Boolean,
) : UiRequest<Optional<Territory>>()

class SelectUnitsRequest(
    val candidates: List<Unit>,
    val title: String,
    val message: String,
    val max: Int,
    /** The battle territory this choice belongs to (a bombing target); asked in the battle window. */
    val territory: String? = null,
) : UiRequest<Collection<Unit>>()

class CasualtyRequest(
    val battleId: UUID,
    val selectFrom: List<Unit>,
    val count: Int,
    val message: String,
    val dice: DiceRoll?,
    val hit: GamePlayer,
    val defaults: CasualtyList,
    val allowMultipleHitsPerUnit: Boolean,
) : UiRequest<CasualtyDetails>()

/** The politics phase: pick one of the valid political actions, or none to end the phase. */
class PoliticsRequest(
    val player: GamePlayer,
    val actions: List<PoliticalActionAttachment>,
    val firstRun: Boolean,
) : UiRequest<Optional<PoliticalActionAttachment>>()

/** The user actions phase: pick one of the valid user actions, or none to end the phase. */
class UserActionRequest(
    val player: GamePlayer,
    val actions: List<UserActionAttachment>,
    val firstRun: Boolean,
) : UiRequest<Optional<UserActionAttachment>>()

/** The casualty report of a battle round; the battle waits until the player continues. */
class CasualtyNoticeRequest(val battleId: UUID, val message: String) : UiRequest<Boolean>()

class RetreatRequest(
    val battleId: UUID,
    val battleTerritory: Territory,
    val possibleTerritories: List<Territory>,
    val message: String,
    val submerge: Boolean,
) : UiRequest<Optional<Territory>>()

/**
 * The technology phase: how many dice to roll (or research tokens to buy) and, where the map
 * allows it, which technology or field to research. Empty means no research this turn.
 */
class TechRequest(val player: GamePlayer) : UiRequest<Optional<TechRoll>>()

/** One unit with bombing damage that can be repaired, with the rule that repairs it. */
class RepairItem(
    val unit: Unit,
    val territory: String,
    val rule: RepairRule,
    /** Damage points on the unit. */
    val damage: Int,
    /** Damage points one application of the rule repairs (usually 1). */
    val pointsPerRepair: Int,
)

/** Repairs before the purchase: how much damage to repair on which units. Empty means none. */
class RepairRequest(val player: GamePlayer, val items: List<RepairItem>) :
    UiRequest<Optional<Map<Unit, IntegerMap<RepairRule>>>>()

/** The aircraft that can scramble from one territory, and how many the air bases there allow. */
class ScrambleOption(val from: Territory, val units: List<Unit>, val max: Int)

/** The defender's choice which aircraft scramble into the battle at [scrambleTo]. */
class ScrambleRequest(val player: GamePlayer, val scrambleTo: Territory, val options: List<ScrambleOption>) :
    UiRequest<Map<Territory, Collection<Unit>>>()

/**
 * Kamikaze suicide attacks: how many attacks, paid with [resource], against which enemy units.
 * At most [maxAttacks] in total; each attack hits on a roll of [attackValue] or less.
 */
class KamikazeRequest(
    val player: GamePlayer,
    val targets: Map<Territory, List<Unit>>,
    val resource: Resource,
    val attackValue: Int,
    val maxAttacks: Int,
) : UiRequest<Map<Territory, IntegerMap<Unit>>>()

/**
 * A random start map: pick one of [territories] and [unitsPerPick] of the player's [units] to
 * start there with. Fewer units are only allowed when fewer are left.
 */
class PickTerritoryAndUnitsRequest(
    val player: GamePlayer,
    val territories: List<Territory>,
    val units: List<Unit>,
    val unitsPerPick: Int,
) : UiRequest<Tuple<Territory, Set<Unit>>>()
