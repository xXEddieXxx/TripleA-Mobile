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

    override fun gameAborted(message: String) = emit(Event.GameEnded(message, aborted = true))

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

    // the engine passes null for addedUnits (RemoveNonCombatants)
    override fun unitsChanged(battleId: UUID, player: GamePlayer, removedUnits: Collection<GameUnit>, addedUnits: Collection<GameUnit>?) =
        emit(Event.UnitsChanged(battleId.toString(), player.name, removedUnits.wireIds(), addedUnits.orEmpty().wireIds()))

    override fun bombingResults(battleId: UUID, dice: List<Die>, cost: Int) =
        emit(Event.BombingResults(battleId.toString(), dice.map { it.toDto() }, cost))

    override fun retreat(shortMessage: String, message: String, step: String, player: GamePlayer?) =
        emit(Event.Retreat(shortMessage, message, step, player?.name))

    override fun unitsRetreated(battleId: UUID, retreating: Collection<GameUnit>) = emit(Event.UnitsRetreated(battleId.toString(), retreating.wireIds()))

    override fun unitsMoved(player: GamePlayer, units: Collection<GameUnit>, route: List<Territory>) =
        emit(Event.UnitsMoved(player.name, units.wireIds(), route.names()))

    override fun playSound(clipName: String, player: GamePlayer?) = emit(Event.PlaySound(clipName, player?.name))
}
