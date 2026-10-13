package org.triplea.mobile.online.client

import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import org.slf4j.LoggerFactory
import org.triplea.mobile.protocol.ChatError
import org.triplea.mobile.protocol.ChatMessage
import org.triplea.mobile.protocol.ChatSend
import org.triplea.mobile.protocol.Clock
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
 * [ChatMessage], [ChatError], [Presence], [LobbyUpdate], [FriendsChanged] and [Clock] (of every game), on an OkHttp thread. [onStopped] runs
 * once when it gives up by itself: the login is gone (refused refresh) or the app is too old.
 */
class SocialConnection(
    private val api: ServerApi,
    private val onMessage: (ServerMessage) -> Unit,
    private val onStopped: (reason: String) -> Unit = {},
) {
    private val socketClient = api.socketClient()
    private val reconnects = Executors.newSingleThreadScheduledExecutor { Thread(it, "social-reconnect").apply { isDaemon = true } }

    /** Set once Hello went out; chat sent before that would break the server's Hello-first rule. */
    @Volatile private var socket: WebSocket? = null

    @Volatile private var closed = false

    @Volatile private var failures = 0

    fun open() = schedule(0, null)

    fun close() {
        closed = true
        reconnects.shutdownNow()
        socket?.close(NORMAL_CLOSURE, null)
        socket = null
    }

    /** False when the socket is not connected right now. */
    fun send(message: ChatSend): Boolean = socket?.send(ProtocolJson.encode(message)) == true

    private fun schedule(delaySeconds: Long, expiredToken: String?) {
        if (closed) return
        try {
            reconnects.schedule({ connect(expiredToken) }, delaySeconds, TimeUnit.SECONDS)
        } catch (e: RejectedExecutionException) {
            // closed meanwhile
        }
    }

    private fun retryLater(expiredToken: String? = null) = schedule(backoffSeconds(failures++), expiredToken)

    private fun connect(expiredToken: String?) {
        if (closed) return
        val token = api.socketToken(expiredToken, loggedOut = ::stop, retry = { retryLater(expiredToken) }) ?: return
        val request = Request.Builder().url(api.webSocketUrl).header("Authorization", "Bearer $token").build()
        val ws = socketClient.newWebSocket(request, Listener(token))
        if (closed) ws.cancel() // close() ran during the token fetch
    }

    private fun stop(reason: String) {
        if (closed) return
        close()
        onStopped(reason)
    }

    private inner class Listener(private val token: String) : ServerSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            webSocket.send(ProtocolJson.encode(Hello(PROTOCOL_VERSION, ENGINE_BASE_TAG)))
            failures = 0
            socket = webSocket
            if (closed) webSocket.cancel()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val message = try {
                ProtocolJson.decodeServerMessage(text)
            } catch (e: IllegalArgumentException) {
                log.warn("Unreadable server message dropped", e)
                return
            }
            when (message) {
                is ChatMessage, is ChatError, is Presence, is LobbyUpdate, is Clock, is FriendsChanged -> onMessage(message)
                is UpdateRequired -> stop("Please update the app")
                else -> {} // game messages: the game's own socket handles them
            }
        }

        override fun dropped(webSocket: WebSocket, reason: String, status: Int?) {
            if (socket === webSocket) socket = null
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
