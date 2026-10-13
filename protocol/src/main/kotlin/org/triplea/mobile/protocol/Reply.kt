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
