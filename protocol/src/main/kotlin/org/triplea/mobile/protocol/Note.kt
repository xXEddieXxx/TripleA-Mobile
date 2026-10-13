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
