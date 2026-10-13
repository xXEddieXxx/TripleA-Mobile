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
