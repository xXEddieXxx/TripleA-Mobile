package org.triplea.mobile.online

import games.strategy.engine.data.GameData
import games.strategy.engine.data.Territory
import games.strategy.engine.data.Unit as GameUnit
import games.strategy.engine.framework.GameDataManager
import games.strategy.triplea.delegate.data.PlaceableUnits
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.reflect.KClass
import org.triplea.mobile.protocol.Answer
import org.triplea.mobile.protocol.Ask
import org.triplea.mobile.protocol.ClientMessage
import org.triplea.mobile.protocol.Event
import org.triplea.mobile.protocol.GameEvent
import org.triplea.mobile.protocol.Note
import org.triplea.mobile.protocol.Notice
import org.triplea.mobile.protocol.PlaceableQuery
import org.triplea.mobile.protocol.ProtocolJson
import org.triplea.mobile.protocol.Question
import org.triplea.mobile.protocol.Rejected
import org.triplea.mobile.protocol.Reply
import org.triplea.mobile.protocol.ServerMessage

private const val GAME = "test-game"

/**
 * Host and devices in one process. Every message is encoded to JSON and decoded again. The devices
 * read the game from a copy that is re-loaded from a save whenever the host's game changed, which
 * is what the app does with the server's saves.
 */
internal class Wire(private val host: GameData) : HostLink {
    private var version = 0L
    private var nextQuestionId = 1L
    private var openQuestionId = 0L
    private var mirror: GameData? = null
    private var mirrorVersion = -1L
    private val queuedEvents = mutableListOf<Event>()
    private val clients = mutableMapOf<String, OnlineClient>()
    private val tampered = mutableMapOf<Pair<String, KClass<out Ask>>, ArrayDeque<(Ask) -> Reply>>()
    val players = mutableMapOf<String, ScriptedPlayer>()
    val asked = mutableListOf<String>()
    val rejections = mutableListOf<String>()
    lateinit var delegates: DelegateAccess

    init {
        host.addDataChangeListener { version++ }
    }

    fun seat(nation: String, player: ScriptedPlayer) {
        players[nation] = player
        clients[nation] = OnlineClient(player, player)
    }

    /** The next questions of [type] to [seat] get [replies] instead of the player's answer, one each. */
    fun tamper(seat: String, type: KClass<out Ask>, vararg replies: Reply) {
        tampered.getOrPut(seat to type) { ArrayDeque() }.addAll(replies.map { reply -> { _: Ask -> reply } })
    }

    /** Like [tamper], but each reply is built from the question asked (e.g. to name an offered unit). */
    fun tamper(seat: String, type: KClass<out Ask>, vararg replies: (Ask) -> Reply) {
        tampered.getOrPut(seat to type) { ArrayDeque() }.addAll(replies)
    }

    override fun ask(seat: String, ask: Ask): Answer {
        flushEvents()
        val question = viaServerJson(Question(nextQuestionId++, GAME, seat, version, ask))
        openQuestionId = question.id
        asked += ask::class.simpleName!!
        val forged = tampered[seat to ask::class]?.removeFirstOrNull()
        val answer = forged?.let { Answer(question.id, it(question.ask)) } ?: clients.getValue(seat).answer(question, mirror())
        return viaClientJson(answer)
    }

    override fun reject(questionId: Long, reason: String) {
        rejections += viaServerJson(Rejected(questionId, reason)).reason
    }

    override fun notify(seat: String, note: Note) {
        flushEvents()
        clients.getValue(seat).notice(viaServerJson(Notice(GAME, seat, version, note)), mirror())
    }

    /**
     * Events are delivered before the next question or notice, never while the engine emits them:
     * copying the game takes its write lock, and the game thread may hold the read lock while it
     * emits an event.
     */
    fun event(event: Event) {
        queuedEvents += event
    }

    fun flushEvents() {
        val pending = queuedEvents.toList()
        queuedEvents.clear()
        for (event in pending) {
            val message = viaServerJson(GameEvent(GAME, version, event))
            clients.values.forEach { it.event(message, mirror()) }
        }
    }

    /** Sends [event] straight to every device, as a server would for an event of a newer version. */
    fun deliver(event: Event) {
        val message = viaServerJson(GameEvent(GAME, version, event))
        clients.values.forEach { it.event(message, mirror()) }
    }

    /** The placeable-units query of the open placement question, host side and back. */
    fun placeable(territory: Territory, units: List<GameUnit>): PlaceableUnits {
        val query = viaClientJson(PlaceableQuery(openQuestionId, territory.name, units.map { it.id.toString() }))
        return viaServerJson(answerPlaceable(query, host, delegates)).toPlaceableUnits(mirror())
    }

    private fun mirror(): GameData {
        if (mirrorVersion != version) {
            val save = ByteArrayOutputStream()
            GameDataManager.saveGame(save, host)
            mirror = GameDataManager.loadGame(ByteArrayInputStream(save.toByteArray())).orElseThrow()
            mirrorVersion = version
        }
        return mirror!!
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T : ServerMessage> viaServerJson(message: T): T = ProtocolJson.decodeServerMessage(ProtocolJson.encode(message)) as T

    @Suppress("UNCHECKED_CAST")
    private fun <T : ClientMessage> viaClientJson(message: T): T = ProtocolJson.decodeClientMessage(ProtocolJson.encode(message)) as T
}
