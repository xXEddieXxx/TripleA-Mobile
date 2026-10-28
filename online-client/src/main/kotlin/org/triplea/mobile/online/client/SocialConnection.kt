package org.triplea.mobile.online.client

import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import org.slf4j.LoggerFactory
import org.triplea.mobile.protocol.ChatError
import org.triplea.mobile.protocol.ChatMessage
import org.triplea.mobile.protocol.ChatSend
import org.triplea.mobile.protocol.Clock
import org.triplea.mobile.protocol.DefenderNotice
import org.triplea.mobile.protocol.DefenderNoticeClosed
import org.triplea.mobile.protocol.ENGINE_BASE_TAG
import org.triplea.mobile.protocol.FriendsChanged
import org.triplea.mobile.protocol.Hello
import org.triplea.mobile.protocol.LobbyUpdate
import org.triplea.mobile.protocol.PROTOCOL_VERSION
import org.triplea.mobile.protocol.Presence
import org.triplea.mobile.protocol.ProtocolJson
import org.triplea.mobile.protocol.ServerMessage
import org.triplea.mobile.protocol.UpdateRequired

/**
 * The user's social WebSocket: lobby updates, friends' presence and chat. It is a second socket next to
 * a game's [GameConnection] (the server sends every message to all of a user's sockets), so game
 * messages arriving here are ignored. It reconnects by itself until [close]. [onMessage] gets only
 * [ChatMessage], [ChatError], [Presence], [LobbyUpdate], [FriendsChanged], [DefenderNotice], [DefenderNoticeClosed] and [Clock] (of every game), on an OkHttp thread. [onStopped] runs
 * once when it gives up by itself: the login is gone (refused refresh) or the app is too old.
 */
class SocialConnection(
    private val api: ServerApi,
    private val onMessage: (ServerMessage) -> Unit,
    private val onStopped: (reason: String) -> Unit = {},
    /** Each connect, before the server's first message on it: what the server re-sends on connect (open notices) starts empty. */
    private val onConnected: () -> Unit = {},
) {
    private val socketClient = api.socketClient()
    private val reconnects = Executors.newSingleThreadScheduledExecutor { Thread(it, "social-reconnect").apply { isDaemon = true } }

    @Volatile private var closed = false

    @Volatile private var failures = 0

    /** The newest socket's listener, open or opening; an open or a drop of an older (paused, replaced) one is ignored. */
    @Volatile private var current: Listener? = null

    // between pause and resume: no socket, no reconnect
    @Volatile private var paused = false

    @Volatile private var retry: ScheduledFuture<*>? = null

    fun open() = schedule(0, null)

    fun close() {
        closed = true
        reconnects.shutdownNow()
        current?.ws?.close(NORMAL_CLOSURE, null)
    }

    /**
     * The app went to the background: the socket closes until [resume], so the server counts the user as offline (push).
     * Nothing is interrupted, unlike [close]: a token refresh in flight still stores the rotated refresh token.
     */
    fun pause() {
        paused = true
        // on the reconnect thread, after a connect in flight (as GameConnection does): no connect revives the socket afterwards
        onReconnectThread {
            retry?.cancel(false)
            val gone = current
            current = null
            gone?.ws?.cancel()
        }
    }

    fun resume() {
        if (!paused) return
        paused = false
        onReconnectThread {
            // a drop that raced with pause() may have scheduled one more try: it would replace this socket
            retry?.cancel(false)
            // the failures before the pause say nothing about the network now
            failures = 0
            connect(null)
        }
    }

    private fun onReconnectThread(task: () -> Unit) {
        try {
            reconnects.execute(task)
        } catch (e: RejectedExecutionException) {
            // closed meanwhile
        }
    }

    /** False when the socket is not connected right now. */
    fun send(message: ChatSend): Boolean = current?.takeIf { it.ready }?.ws?.send(ProtocolJson.encode(message)) == true

    private fun schedule(delaySeconds: Long, expiredToken: String?) {
        if (closed || paused) return
        try {
            retry = reconnects.schedule({ connect(expiredToken) }, delaySeconds, TimeUnit.SECONDS)
        } catch (e: RejectedExecutionException) {
            // closed meanwhile
        }
    }

    private fun retryLater(expiredToken: String? = null) = schedule(backoffSeconds(failures++), expiredToken)

    private fun connect(expiredToken: String?) {
        // a healthy socket stays: a stray retry (one that raced with pause/resume) must not replace it
        if (closed || paused || current?.ready == true) return
        val token = api.socketToken(expiredToken, loggedOut = ::stop, retry = { retryLater(expiredToken) }) ?: return
        val request = Request.Builder().url(api.webSocketUrl).header("Authorization", "Bearer $token").build()
        val listener = Listener(token)
        val old = current
        current = listener
        // a connect that raced with pause/resume replaces the older socket instead of keeping two open; its drop is ignored
        old?.ws?.cancel()
        listener.ws = socketClient.newWebSocket(request, listener)
        if (closed || paused) listener.ws?.cancel() // close() or pause() ran during the token fetch
    }

    private fun stop(reason: String) {
        if (closed) return
        close()
        onStopped(reason)
    }

    private inner class Listener(private val token: String) : ServerSocketListener() {
        @Volatile var ws: WebSocket? = null

        /** Hello went out: chat may be sent now (the server's Hello-first rule); false again after a drop. */
        @Volatile var ready = false

        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (closed || paused || current !== this) {
                webSocket.cancel()
                return
            }
            // before the first message of the connection; a throwing callback must not skip Hello
            try {
                onConnected()
            } catch (e: RuntimeException) {
                log.warn("onConnected callback failed", e)
            }
            webSocket.send(ProtocolJson.encode(Hello(PROTOCOL_VERSION, ENGINE_BASE_TAG)))
            failures = 0
            ready = true
            if (closed) webSocket.cancel()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (current !== this) return // a frame still in flight on a paused or replaced socket
            val message = try {
                ProtocolJson.decodeServerMessage(text)
            } catch (e: IllegalArgumentException) {
                log.warn("Unreadable server message dropped", e)
                return
            }
            when (message) {
                is ChatMessage, is ChatError, is Presence, is LobbyUpdate, is Clock, is FriendsChanged, is DefenderNotice, is DefenderNoticeClosed -> onMessage(message)
                is UpdateRequired -> stop("Please update the app")
                else -> {} // game messages: the game's own socket handles them
            }
        }

        override fun dropped(webSocket: WebSocket, reason: String, status: Int?) {
            ready = false
            // paused or replaced: this drop must not open a second socket
            if (current !== this) return
            // a 401 means the access token expired: renew it before the next try
            retryLater(token.takeIf { status == 401 })
        }
    }

    private companion object {
        val log = LoggerFactory.getLogger(SocialConnection::class.java)
    }
}

/** [old] plus [new] without duplicates, sorted by id (the server may deliver ids out of order), the newest [max]. */
fun mergeChat(old: List<ChatMessage>, new: List<ChatMessage>, max: Int): List<ChatMessage> =
    (old + new).distinctBy { it.id }.sortedBy { it.id }.takeLast(max)
