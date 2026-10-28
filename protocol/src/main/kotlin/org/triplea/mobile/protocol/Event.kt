package org.triplea.mobile.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** What the engine shows every player while a game runs. */
@Serializable
sealed interface Event {
    @Serializable @SerialName("message")
    data class Message(val title: String, val text: String) : Event

    /** [aborted]: stopped without a result (abort, server error); since v3, absent before. [winners]: the winning nations, empty for none. */
    @Serializable @SerialName("game_ended")
    data class GameEnded(val message: String, val aborted: Boolean = false, val winners: List<String> = emptyList()) : Event

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
