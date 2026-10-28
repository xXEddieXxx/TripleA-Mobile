package org.triplea.mobile.app.push

import android.content.Context
import com.google.android.gms.tasks.Tasks
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import java.util.concurrent.TimeUnit

/** Debug builds: this device's FCM token. Null without a Firebase config (no app/google-services.json) or Google services. Blocking. */
object PushPlatform {
    /** For registering: switches auto-init on (off in the manifest, so no token exists before the user plays online). */
    fun token(context: Context): String? {
        if (FirebaseApp.getApps(context).isEmpty()) return null
        return fetch(enable = true)
    }

    /** For logout: the token only when Firebase is up and auto-init is already on; never enables it. */
    fun currentToken(context: Context): String? {
        if (FirebaseApp.getApps(context).isEmpty()) return null
        return fetch(enable = false)
    }

    private fun fetch(enable: Boolean): String? = runCatching {
        val messaging = FirebaseMessaging.getInstance()
        if (enable) messaging.isAutoInitEnabled = true else if (!messaging.isAutoInitEnabled) return null
        Tasks.await(messaging.token, 10, TimeUnit.SECONDS)
    }.getOrNull()
}
