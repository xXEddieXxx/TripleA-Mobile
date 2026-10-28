package org.triplea.mobile.app.push

import android.content.Context

/** Release builds have no online play and no Firebase: never a push token. */
object PushPlatform {
    @Suppress("UNUSED_PARAMETER")
    fun token(context: Context): String? = null

    @Suppress("UNUSED_PARAMETER")
    fun currentToken(context: Context): String? = null
}
