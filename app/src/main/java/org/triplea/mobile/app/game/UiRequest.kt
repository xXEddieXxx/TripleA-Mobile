package org.triplea.mobile.app.game

import games.strategy.engine.data.GamePlayer
import games.strategy.engine.data.MoveDescription
import games.strategy.engine.data.ProductionRule
import games.strategy.engine.data.Territory
import games.strategy.engine.data.Unit
import games.strategy.triplea.attachments.PoliticalActionAttachment
import games.strategy.triplea.attachments.UserActionAttachment
import games.strategy.triplea.delegate.DiceRoll
import games.strategy.triplea.delegate.data.BattleListing
import games.strategy.triplea.delegate.data.CasualtyDetails
import games.strategy.triplea.delegate.data.CasualtyList
import games.strategy.triplea.delegate.data.FightBattleDetails
import games.strategy.triplea.ui.PlaceData
import java.util.Optional
import java.util.UUID
import java.util.concurrent.CompletableFuture
import org.triplea.java.collections.IntegerMap

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
