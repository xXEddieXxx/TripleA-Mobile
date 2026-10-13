package org.triplea.mobile.online.client

import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

// The rules GameConnection and SocialConnection share; each keeps its own state handling.

internal const val NORMAL_CLOSURE = 1000
private const val TOKEN_EXPIRED = 4401
private const val MAX_BACKOFF_SECONDS = 30L
private const val PING_SECONDS = 20L

/**
 * The access token for a new socket: the current one, or a renewed one after [expired] was refused. Null
 * when there is none: [loggedOut] runs when the login is gone (401, stop for good), [retry] for anything else.
 */
internal fun ServerApi.socketToken(expired: String?, loggedOut: (reason: String) -> Unit, retry: (reason: String) -> Unit): String? =
    try {
        if (expired == null) accessToken() else refresh(expired).accessToken
    } catch (e: ServerException) {
        if (e.status == 401) loggedOut(e.message ?: "please log in again") else retry(e.message ?: "HTTP ${e.status}")
        null
    } catch (e: Exception) {
        retry(e.message ?: e.javaClass.simpleName)
        null
    }

/** Seconds to wait before the next try after [failures] failed ones: 1, 2, 4 ... up to 30. */
internal fun backoffSeconds(failures: Int): Long = minOf(1L shl minOf(failures, 5), MAX_BACKOFF_SECONDS)

// pings find half-open connections (network switch, NAT timeout) that would never fail by themselves
internal fun ServerApi.socketClient(): OkHttpClient = client.newBuilder().pingInterval(PING_SECONDS, TimeUnit.SECONDS).build()

/** Answers the server's close and reports every end of the socket to [dropped]. */
internal abstract class ServerSocketListener : WebSocketListener() {
    /** The socket ended; [status] 401 means the access token expired: renew it before the next try. */
    abstract fun dropped(webSocket: WebSocket, reason: String, status: Int?)

    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
        webSocket.close(NORMAL_CLOSURE, null)
    }

    // the server accepts the upgrade and closes with 4401 when the token has expired: renew it like an HTTP 401
    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) =
        dropped(webSocket, reason.ifEmpty { "connection closed" }, if (code == TOKEN_EXPIRED) 401 else null)

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) =
        dropped(webSocket, t.message ?: t.javaClass.simpleName, response?.code)
}
