package org.triplea.mobile.app.online

import okhttp3.OkHttpClient
import org.triplea.mobile.app.AppSettings
import org.triplea.mobile.online.client.ServerApi

/**
 * The process's one [ServerApi] per server URL, shared by the screens, the sockets and TurnCheckWorker.
 * Refresh tokens are single-use: two instances refreshing at once would log the user out, and only
 * one instance's synchronized refresh prevents that.
 */
object OnlineApi {
    /** One client for every server call: REST and the WebSockets share its connection pool. */
    private val client = OkHttpClient()
    private var url: String? = null
    private var api: ServerApi? = null

    /** Throws for an invalid [serverUrl]. */
    @Synchronized
    fun forUrl(serverUrl: String): ServerApi =
        api?.takeIf { url == serverUrl } ?: ServerApi(serverUrl, AppSettings.tokens, client).also {
            url = serverUrl
            api = it
        }
}
