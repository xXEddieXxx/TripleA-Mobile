package org.triplea.mobile.app.push

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import org.slf4j.LoggerFactory
import org.triplea.mobile.app.AppSettings
import org.triplea.mobile.app.online.Notifications
import org.triplea.mobile.app.online.OnlineApi
import org.triplea.mobile.app.online.Social

/**
 * FCM in debug builds. In the background the system shows the server's notification itself (its tap carries the data
 * as extras); this service sees pushes while the app runs and new tokens.
 */
class TripleAMessagingService : FirebaseMessagingService() {
    // on a background thread: the server learns the new token while someone is logged in
    override fun onNewToken(token: String) {
        if (AppSettings.tokens.tokens == null) return
        runCatching { OnlineApi.forUrl(AppSettings.current.serverUrl).registerDevice(token) }
            .onFailure { log.info("Push token not registered: {}", it.message) }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        // in front with the social socket open, the banner and the games list show it; otherwise (home screen, a local game) notify
        if (Notifications.inForeground() && Social.api != null) return
        val gameId = message.data["gameId"] ?: return
        val notification = message.notification ?: return
        val channel = if (message.data["kind"] == "turn") Notifications.TURNS else Notifications.BATTLE
        Notifications.show(this, channel, gameId, notification.title.orEmpty(), notification.body.orEmpty(), gameId)
    }

    private companion object {
        val log = LoggerFactory.getLogger(TripleAMessagingService::class.java)
    }
}
