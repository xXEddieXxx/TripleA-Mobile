package org.triplea.mobile.online

import games.strategy.engine.message.IRemote
import org.triplea.mobile.protocol.Answer
import org.triplea.mobile.protocol.Ask
import org.triplea.mobile.protocol.Note

/**
 * How the host reaches the players. The server implements it on its connections; tests in one
 * process. Called on the game thread.
 */
interface HostLink {
    /**
     * Sends [ask] to the player of [seat] and blocks until they answer. The link adds the question
     * id, game id, version and deadline, and returns only the answer to this question.
     */
    fun ask(seat: String, ask: Ask): Answer

    /** Tells the player why their answer to [questionId] was not accepted; the question follows again. */
    fun reject(questionId: Long, reason: String)

    /** Information for the player of [seat] that needs no answer. */
    fun notify(seat: String, note: Note)
}

/** The game's active delegate, for undo and queries while a question is open. */
fun interface DelegateAccess {
    fun current(): IRemote
}

/**
 * Thrown by a device-side HumanPlayerUi from getMove or getPlacement to undo the move or placement
 * at [index] instead of answering; OnlineClient turns it into Reply.Undo.
 */
class UndoRequest(val index: Int) : RuntimeException("undo $index")
