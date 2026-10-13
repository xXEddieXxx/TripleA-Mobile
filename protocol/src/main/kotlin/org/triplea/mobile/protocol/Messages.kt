package org.triplea.mobile.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Everything the server sends to the app. */
@Serializable
sealed interface ServerMessage

/** Everything the app sends to the server. */
@Serializable
sealed interface ClientMessage

/** The app's first message on every connection. */
@Serializable
@SerialName("hello")
data class Hello(val protocolVersion: Int, val engineBaseTag: String) : ClientMessage {
    fun isCompatible(): Boolean = protocolVersion == PROTOCOL_VERSION && engineBaseTag == ENGINE_BASE_TAG
}

/** The answer to an incompatible [Hello]: "please update the app". The server then closes. */
@Serializable
@SerialName("update_required")
data class UpdateRequired(val protocolVersion: Int, val engineBaseTag: String) : ServerMessage

/** Game [gameId] changed. The app fetches the save of [version] before it handles anything newer. */
@Serializable
@SerialName("game_version")
data class GameVersion(val gameId: String, val version: Long) : ServerMessage

/**
 * The engine asks the player of [seat] (a nation name). Ids and names refer to the game at
 * [version]; [deadline] is epoch milliseconds, null without a clock.
 */
@Serializable
@SerialName("question")
data class Question(
    val id: Long,
    val gameId: String,
    val seat: String,
    val version: Long,
    val ask: Ask,
    val deadline: Long? = null,
) : ServerMessage

/** The answer to [questionId] did not fit; the same question follows again. */
@Serializable
@SerialName("rejected")
data class Rejected(val questionId: Long, val reason: String) : ServerMessage

/** Information for the player of [seat] that needs no answer. */
@Serializable
@SerialName("notice")
data class Notice(val gameId: String, val seat: String, val version: Long, val note: Note) : ServerMessage

/** Something every player of the game sees, e.g. a battle step or dice. */
@Serializable
@SerialName("game_event")
data class GameEvent(val gameId: String, val version: Long, val event: Event) : ServerMessage

/** Which of [units] the player can place in [territory] while placement question [questionId] is open. */
@Serializable
@SerialName("placeable_query")
data class PlaceableQuery(val questionId: Long, val territory: String, val units: List<String>) : ClientMessage

/** The answer to a [PlaceableQuery]; [error] says why nothing can be placed there. */
@Serializable
@SerialName("placeable_result")
data class PlaceableResult(
    val questionId: Long,
    val territory: String,
    val units: List<String>,
    val maxUnits: Int,
    val error: String? = null,
) : ServerMessage

/** The player's answer to question [questionId]. */
@Serializable
@SerialName("answer")
data class Answer(val questionId: Long, val reply: Reply) : ClientMessage

/** Accepted friend [username] came online (first connection) or went offline (last one closed). */
@Serializable
@SerialName("presence")
data class Presence(val username: String, val online: Boolean) : ServerMessage

/** Lobby game [game] was created, changed, started or [deleted]; sent to everyone who can see it in the lobby. */
@Serializable
@SerialName("lobby_update")
data class LobbyUpdate(val game: GameInfoDto, val deleted: Boolean = false) : ServerMessage

/**
 * The app sends [text] (1 to 500 characters, trimmed) to [channel]: `lobby`, `game:<id>` (players), `spectate:<id>`
 * (spectators) or (v3) `dm:<a>:<b>`, the private chat of users a < b (ids), which only accepted friends may send to.
 */
@Serializable
@SerialName("chat_send")
data class ChatSend(val channel: String, val text: String) : ClientMessage

/**
 * Chat message [id] in [channel] from [username] ("deleted user" for a deleted account), sent at
 * [sentAt] (epoch milliseconds). Also the body of `GET /chat/{channel}`.
 */
@Serializable
@SerialName("chat_message")
data class ChatMessage(val id: Long, val channel: String, val username: String, val text: String, val sentAt: Long) : ServerMessage

/**
 * The clocks of game [gameId]: [clocks] maps each human player's username to their remaining
 * milliseconds; [running] is the player whose clock runs, until [deadline] (epoch milliseconds).
 * Sent when a clock starts or stops, and when time travel or a vacation moves a deadline.
 * [questionId] names the question this state is about (ids only grow): with [running] set, its clock
 * runs, so every older question is closed; without, its clock stopped, so it and every older one are
 * closed. Null when no question's clock runs: it closes nothing.
 */
@Serializable
@SerialName("clock")
data class Clock(
    val gameId: String,
    val clocks: Map<String, Long>,
    val running: String? = null,
    val deadline: Long? = null,
    val questionId: Long? = null,
) : ServerMessage

/**
 * This connection watches game [gameId] as a spectator: it gets the game's [GameVersion]s and
 * [GameEvent]s a few versions late (the game's spectator delay) and never a [Question]. Its saves come
 * from `GET /games/{id}/save?spectate=true`. Ends with the connection.
 */
@Serializable
@SerialName("watch")
data class Watch(val gameId: String) : ClientMessage

/** A [Watch] of [gameId] was refused: [reason] (not running, spectators not allowed, a seat of yours). */
@Serializable
@SerialName("watch_refused")
data class WatchRefused(val gameId: String, val reason: String) : ServerMessage

/** A [ChatSend] to [channel] was dropped: [reason] (too long, too many, not a member). */
@Serializable
@SerialName("chat_error")
data class ChatError(val channel: String, val reason: String) : ServerMessage

/** v3: a friendship or request with [username] was asked, accepted, declined, removed or blocked: reload `GET /friends`. Sent to both users. */
@Serializable
@SerialName("friends_changed")
data class FriendsChanged(val username: String) : ServerMessage
