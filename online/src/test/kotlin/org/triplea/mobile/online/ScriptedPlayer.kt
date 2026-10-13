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
 * then attacks with every unit in `from`; when several battles are pending it fights `to` first. It records what the engine asked and showed.
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
        val all = battles.battlesMap.entries.flatMap { (type, places) -> places.map { type to it } }
        // fights the attack's target first; the engine fights the last remaining battle by itself
        val (type, territory) = all.firstOrNull { it.second.name == attack?.second } ?: all.first()
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
