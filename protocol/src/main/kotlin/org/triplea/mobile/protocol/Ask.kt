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
        /** The battle's territory, for the defender's notification; null when the battle is not pending (AA fire during a move). */
        val territory: String? = null,
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

    /** Not sent since v3 milestone 3d: the host confirms casualties itself (RemoteHumanUi); kept so older peers still decode. */
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
