package org.triplea.mobile.online.client

import games.strategy.engine.GameOverException
import games.strategy.engine.data.GameData
import games.strategy.engine.data.Territory
import games.strategy.engine.data.Unit as GameUnit
import games.strategy.engine.framework.GameDataManager
import games.strategy.triplea.delegate.data.PlaceableUnits
import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPInputStream
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import org.slf4j.LoggerFactory
import org.triplea.mobile.GameEventListener
import org.triplea.mobile.HumanPlayerUi
import org.triplea.mobile.online.OnlineClient
import org.triplea.mobile.online.toPlaceableUnits
import org.triplea.mobile.protocol.ChatError
import org.triplea.mobile.protocol.ChatMessage
import org.triplea.mobile.protocol.ClientMessage
import org.triplea.mobile.protocol.Clock
import org.triplea.mobile.protocol.ENGINE_BASE_TAG
import org.triplea.mobile.protocol.Event
import org.triplea.mobile.protocol.FriendsChanged
import org.triplea.mobile.protocol.GameEvent
import org.triplea.mobile.protocol.GameVersion
import org.triplea.mobile.protocol.Hello
import org.triplea.mobile.protocol.LobbyUpdate
import org.triplea.mobile.protocol.Notice
import org.triplea.mobile.protocol.PROTOCOL_VERSION
import org.triplea.mobile.protocol.PlaceableQuery
import org.triplea.mobile.protocol.PlaceableResult
import org.triplea.mobile.protocol.Presence
import org.triplea.mobile.protocol.ProtocolJson
import org.triplea.mobile.protocol.Question
import org.triplea.mobile.protocol.Rejected
import org.triplea.mobile.protocol.ServerMessage
import org.triplea.mobile.protocol.UpdateRequired
import org.triplea.mobile.protocol.Watch
import org.triplea.mobile.protocol.WatchRefused

/**
 * The device's live link to one server game. It keeps a copy of the game in sync with the server's
 * saves and answers the server's questions through [ui], one at a time on its "question runner"
 * thread. It reconnects by itself until [close]. A [spectate] connection watches the
 * game (Watch): its copy is the spectators' delayed one, and it never answers anything.
 */
class GameConnection(
    private val api: ServerApi,
    private val gameId: String,
    private val ui: HumanPlayerUi,
    private val events: GameEventListener,
    private val listener: Listener,
    private val spectate: Boolean = false,
    /** Seconds to wait after the given number of failures in a row; a seam for the tests. */
    private val backoff: (Int) -> Long = ::backoffSeconds,
) {
    /** Called from background threads only (the runner, OkHttp's and the reconnect thread); marshal to the UI yourself. */
    interface Listener {
        /** A new copy was loaded (bump dataVersion). On the question runner. */
        fun copyChanged(data: GameData, version: Long)

        /**
         * The open question was replaced (drop, reconnect or game end): cancel the pending UI request. On an
         * OkHttp thread or the runner; may come when nothing is pending, and more than once.
         */
        fun questionCancelled()

        /**
         * On any background thread. [detail] says why for RECONNECTING and CLOSED; a save that could
         * not be downloaded or loaded is RECONNECTING with "could not load the game: ...".
         */
        fun connectionState(state: State, detail: String?)

        /**
         * The server answered the open question itself (standing order, clock expiry, end of the hybrid
         * window) and sends the next one. Cancel the pending UI request; no answer is sent for it. On an
         * OkHttp thread or the reconnect thread.
         */
        fun questionExpired() = Unit
    }

    enum class State { CONNECTING, CONNECTED, RECONNECTING, UPDATE_REQUIRED, CLOSED }

    private val online = OnlineClient(ui, events)
    private val runner: ExecutorService = Executors.newSingleThreadExecutor { daemon(it, "online-questions") }
    private val reconnects: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { daemon(it, "online-reconnect") }
    private val socketClient = api.socketClient()

    /** The current copy. */
    @Volatile var data: GameData? = null
        private set

    /** The current copy's save version. */
    @Volatile var version = -1L
        private set

    /** The live socket's number; bumped on every new socket and on every drop, so frames and answers of a dead socket are dropped. */
    @Volatile private var generation = 0

    @Volatile private var socket: WebSocket? = null

    @Volatile private var closed = false

    @Volatile private var openQuestion: Long? = null

    @Volatile private var pendingPlaceable: Pair<Long, CompletableFuture<PlaceableResult>>? = null

    // reset by OkHttp's thread and the runner, counted up by the reconnect thread
    private val failures = AtomicInteger()

    private val placeableLock = Any()

    fun open() {
        listener.connectionState(State.CONNECTING, null)
        // a token refresh is network I/O: never on the caller's (main) thread
        reconnects.execute { connect(null) }
    }

    fun close() {
        closed = true
        generation++ // a runner task in the middle of a download must not reach the ui afterwards
        socket?.close(NORMAL_CLOSURE, null)
        releasePlaceable()
        reconnects.shutdownNow()
        runner.shutdownNow()
        listener.connectionState(State.CLOSED, null)
    }

    /** Blocking query for the open placement question; call off the main thread. */
    fun placeable(territory: Territory, units: List<GameUnit>): PlaceableUnits {
        synchronized(placeableLock) { return queryPlaceable(territory, units) }
    }

    private fun queryPlaceable(territory: Territory, units: List<GameUnit>): PlaceableUnits {
        val questionId = openQuestion ?: return PlaceableUnits("no placement in progress")
        val copy = data ?: return PlaceableUnits("no game loaded")
        val ws = socket ?: return PlaceableUnits(NO_ANSWER)
        val future = CompletableFuture<PlaceableResult>()
        pendingPlaceable = questionId to future
        try {
            if (!send(PlaceableQuery(questionId, territory.name, units.map { it.id.toString() }), ws, generation)) {
                return PlaceableUnits(NO_ANSWER)
            }
            val result = future.get(PLACEABLE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            return copy.acquireReadLock().use { result.toPlaceableUnits(copy) }
        } catch (e: TimeoutException) {
            return PlaceableUnits(NO_ANSWER)
        } catch (e: ExecutionException) {
            return PlaceableUnits(NO_ANSWER) // the connection dropped
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return PlaceableUnits(NO_ANSWER)
        } finally {
            pendingPlaceable = null
        }
    }

    private fun releasePlaceable() {
        pendingPlaceable?.second?.completeExceptionally(IOException("connection lost"))
    }

    @Synchronized
    private fun connect(expiredToken: String?) {
        if (closed) return
        val token = api.socketToken(expiredToken, loggedOut = { reason ->
            closed = true
            listener.connectionState(State.CLOSED, reason)
        }, retry = { retryLater(it, expiredToken) }) ?: return // a failed renewal renews again next time
        socket?.cancel()
        val gen = ++generation
        val request = Request.Builder().url(api.webSocketUrl).header("Authorization", "Bearer $token").build()
        val ws = socketClient.newWebSocket(request, SocketListener(gen, token))
        socket = ws
        if (closed) ws.cancel() // close() ran during the token fetch
    }

    private fun retryLater(reason: String?, expiredToken: String? = null) {
        if (closed) return
        listener.connectionState(State.RECONNECTING, reason)
        try {
            reconnects.schedule({ connect(expiredToken) }, backoff(failures.getAndIncrement()), TimeUnit.SECONDS)
        } catch (e: RejectedExecutionException) {
            // closed meanwhile
        }
    }

    private inner class SocketListener(private val gen: Int, private val token: String) : ServerSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            webSocket.send(ProtocolJson.encode(Hello(PROTOCOL_VERSION, ENGINE_BASE_TAG)))
            if (spectate) webSocket.send(ProtocolJson.encode(Watch(gameId)))
            // a question of the old socket may have reached the UI after the drop's cancel
            if (openQuestion != null) listener.questionCancelled()
            listener.connectionState(State.CONNECTED, null)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (gen != generation) return
            val message = try {
                ProtocolJson.decodeServerMessage(text)
            } catch (e: IllegalArgumentException) {
                log.warn("Unreadable server message dropped", e)
                return
            }
            // shortcut: only a usable sync resets the backoff, so a game without any save yet (version 0)
            // keeps its backoff after short drops; reset on a working link too if that ever hurts
            if (message is Clock) {
                if (message.gameId == gameId) clock(message)
                return
            }
            if (!ours(message)) return
            when (message) {
                is PlaceableResult ->
                    pendingPlaceable?.takeIf { it.first == message.questionId }?.second?.complete(message)
                is WatchRefused -> {
                    closed = true
                    reconnects.shutdownNow()
                    listener.connectionState(State.CLOSED, message.reason)
                    webSocket.close(NORMAL_CLOSURE, null)
                }
                is UpdateRequired -> {
                    closed = true
                    reconnects.shutdownNow()
                    listener.connectionState(State.UPDATE_REQUIRED, null)
                    webSocket.close(NORMAL_CLOSURE, null)
                }
                else -> {
                    // an abort can end the game while the runner waits in a question: cancel it here, or the
                    // GameEnded queued behind it would never run
                    if (message is GameEvent && message.event is Event.GameEnded) listener.questionCancelled()
                    // whether the game had clocks when the question came: without, only the device's time can close it
                    val clockedNow = synchronized(expiryLock) { clocked }
                    onRunner { handle(message, gen, webSocket, this, clockedNow) }
                }
            }
        }

        override fun dropped(webSocket: WebSocket, reason: String, status: Int?) {
            synchronized(this@GameConnection) {
                if (gen != generation || closed) return
                generation++ // the runner drops this socket's queued and open questions from now on
            }
            log.info("Game connection dropped: {}", reason)
            releasePlaceable()
            listener.questionCancelled()
            // a 401 means the access token expired: renew it before the next try
            retryLater(reason, token.takeIf { status == 401 })
        }
    }

    private fun ours(message: ServerMessage): Boolean =
        when (message) {
            is GameVersion -> message.gameId == gameId
            // a spectator is never asked; the server sends it no questions of the game it watches
            is Question -> !spectate && message.gameId == gameId
            is Notice -> message.gameId == gameId
            is GameEvent -> message.gameId == gameId
            is WatchRefused -> message.gameId == gameId
            // the SocialConnection handles these; queued here they would wait behind an open question
            is LobbyUpdate, is Presence, is ChatMessage, is ChatError, is FriendsChanged -> false
            else -> true
        }

    private fun onRunner(task: () -> Unit) {
        try {
            runner.execute {
                try {
                    task()
                } catch (e: Exception) {
                    log.warn("Online message failed", e)
                }
            }
        } catch (e: RejectedExecutionException) {
            // closed meanwhile
        }
    }

    /** On the runner. */
    private fun handle(message: ServerMessage, gen: Int, ws: WebSocket, link: SocketListener, clockedNow: Boolean) {
        if (gen != generation) return // the server re-sends what matters on the new socket
        when (message) {
            is GameVersion -> sync(message.version, ws, link)
            is Question -> {
                val copy = sync(message.version, ws, link) ?: return
                synchronized(expiryLock) {
                    // closed by the server while it waited behind another question: the next one follows
                    if (message.id <= closedUpTo) return
                    openQuestion = message.id
                }
                // without clocks (an untimed hybrid window) only the device's time can tell
                val expiry = message.deadline?.takeIf { !clockedNow }
                    ?.let { schedule(it + DEADLINE_GRACE_MS - System.currentTimeMillis()) { expired(message.id) } }
                try {
                    if (gen != generation) return // dropped during the download
                    val answer = online.answer(message, copy)
                    if (!send(answer, ws, gen)) log.info("Answer to question {} dropped: connection replaced", message.id)
                } catch (e: GameOverException) {
                    // expired or cancelled: no answer; a game that really ended says so with GameEnded
                    log.info("Question {} closed without an answer: {}", message.id, e.message)
                } finally {
                    expiry?.cancel(false)
                    synchronized(expiryLock) {
                        openQuestion = null
                    }
                }
            }
            is GameEvent -> sync(message.version, ws, link)?.takeIf { gen == generation }?.let { online.event(message, it) }
            is Notice -> sync(message.version, ws, link)?.takeIf { gen == generation }?.let { online.notice(message, it) }
            is Rejected -> online.rejected(message)
            else -> log.warn("Unexpected server message {}", message::class.simpleName)
        }
    }

    /**
     * The copy at [atLeast] or newer, downloaded when the current one is older. A failed download
     * or load drops the socket: after the backoff the server re-sends the game version and question.
     */
    private fun sync(atLeast: Long, ws: WebSocket, link: SocketListener): GameData? {
        data?.takeIf { version >= atLeast }?.let {
            failures.set(0) // a usable copy resets the backoff: a save that cannot load backs off up to 30 s
            return it
        }
        return try {
            val (newVersion, bytes) = download(atLeast)
            if (newVersion < atLeast) log.warn("Server sent save {} for version {}", newVersion, atLeast)
            val loaded = loadServerSave(bytes) ?: throw IOException("save $newVersion is not readable")
            data = loaded
            version = newVersion
            listener.copyChanged(loaded, newVersion)
            failures.set(0)
            loaded
        } catch (e: Exception) {
            log.warn("Could not load game {}", gameId, e)
            link.dropped(ws, "could not load the game: ${e.message ?: e.javaClass.simpleName}", null)
            ws.cancel()
            null
        }
    }

    /**
     * The save for [version]: a player gets the latest. A spectator asks for exactly the announced one, so
     * its held events match the copy; when the server no longer keeps it, the newest it may see.
     */
    private fun download(version: Long): Pair<Long, ByteArray> {
        if (!spectate) return api.downloadSave(gameId)
        return try {
            api.downloadSave(gameId, spectate = true, version = version)
        } catch (e: ServerException) {
            if (e.status != 404) throw e
            api.downloadSave(gameId, spectate = true)
        }
    }

    private val expiryLock = Any()

    // under expiryLock: the game has clocks (a Clock came), and every question id up to closedUpTo is closed
    private var clocked = false
    private var closedUpTo = Long.MIN_VALUE

    /**
     * On the OkHttp thread. A Clock names its question (ids only grow): a running clock closes every older
     * question, a stopped one closes its own too. A Clock without a question id (no clock runs, or one read
     * before the question changed) closes nothing, so a vacation, time travel or reconnect Clock arriving out
     * of order never closes the open question. The device's clock is never compared with the server's.
     */
    private fun clock(message: Clock) = synchronized(expiryLock) {
        clocked = true
        val id = message.questionId ?: return@synchronized
        closedUpTo = maxOf(closedUpTo, if (message.running == null) id else id - 1)
        if ((openQuestion ?: Long.MAX_VALUE) <= closedUpTo) listener.questionExpired()
    }

    // shortcut: a close between openQuestion and the UI showing the request cancels nothing; the dialog
    // then stays until the answer is refused ("question already answered") or the connection drops
    private fun expired(questionId: Long) = synchronized(expiryLock) {
        if (openQuestion == questionId) listener.questionExpired()
    }

    private fun schedule(delayMs: Long, task: () -> Unit) =
        try {
            reconnects.schedule(Runnable(task), delayMs, TimeUnit.MILLISECONDS)
        } catch (e: RejectedExecutionException) {
            null // closed meanwhile
        }

    private fun send(message: ClientMessage, ws: WebSocket, gen: Int): Boolean =
        gen == generation && ws.send(ProtocolJson.encode(message))

    private companion object {
        val log = LoggerFactory.getLogger(GameConnection::class.java)
        const val PLACEABLE_TIMEOUT_SECONDS = 15L
        const val NO_ANSWER = "server did not answer"

        /** Device and server clocks differ a little: the dialog closes this late rather than too early. */
        const val DEADLINE_GRACE_MS = 2_000L

        /** Largest unzipped save; a gzip bomb fails here instead of filling the heap. */
        const val MAX_UNZIPPED_SAVE_BYTES = 256L * 1024 * 1024

        fun daemon(runnable: Runnable, name: String) = Thread(runnable, name).apply { isDaemon = true }
    }
}

/** Largest unzipped save; a gzip bomb fails here instead of filling the heap. */
private const val MAX_UNZIPPED_SAVE_BYTES = 256L * 1024 * 1024

/** A save of the server (game copy or replay), read through the engine's SafeObjectInputStream, unzipped with a size cap; null if unreadable. */
fun loadServerSave(bytes: ByteArray): GameData? =
    GZIPInputStream(ByteArrayInputStream(bytes)).use { GameDataManager.loadGameUncompressed(Capped(it, MAX_UNZIPPED_SAVE_BYTES)).orElse(null) }

/** Fails the read once more than [limit] bytes came through. */
private class Capped(input: InputStream, private var limit: Long) : FilterInputStream(input) {
    override fun read(): Int = super.read().also { if (it >= 0) count(1) }

    override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) count(it.toLong()) }

    override fun skip(n: Long): Long = super.skip(n).also { count(it) }

    private fun count(n: Long) {
        limit -= n
        if (limit < 0) throw IOException("save too large")
    }
}
