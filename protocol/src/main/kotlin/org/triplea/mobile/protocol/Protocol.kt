package org.triplea.mobile.protocol

import kotlinx.serialization.json.Json

/**
 * Bump on every change to the messages that the other side cannot read. ProtocolTest fails until
 * the golden file of the new version exists.
 */
const val PROTOCOL_VERSION = 3

/** The desktop TripleA tag both engines are based on; saves only travel between equal tags. */
const val ENGINE_BASE_TAG = "2026-10.16.15815"

/** Longest message either side accepts, in characters of JSON text. */
const val MAX_MESSAGE_CHARS = 64 * 1024

/** The one JSON configuration both sides use. */
object ProtocolJson {
    val json =Json { classDiscriminator = "type" }

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
