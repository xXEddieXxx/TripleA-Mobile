package org.triplea.mobile.app.online

import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.slf4j.LoggerFactory
import org.triplea.mobile.app.Haptics
import org.triplea.mobile.app.MainActivity
import org.triplea.mobile.app.R

/** The online notifications (debug builds): their channels, one notification that opens its game, and whether the app is in front. */
object Notifications {
    const val TURNS = "turns"
    const val BATTLE = "battle"
    // the id FCM gives the notifications it shows itself: with the same tag the app's and the push's replace each other
    private const val NOTIFICATION_ID = 0
    private val log = LoggerFactory.getLogger(Notifications::class.java)

    /** Both channels; the server's pushes name them, so they exist from the app's start. */
    fun channels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(TURNS, "Your turn", NotificationManager.IMPORTANCE_DEFAULT))
        manager.createNotificationChannel(
            NotificationChannel(BATTLE, "Battle decisions", NotificationManager.IMPORTANCE_HIGH).apply { enableVibration(true) },
        )
    }

    /**
     * One notification per [channel] and [tag] (a newer one replaces it; a turn and a battle notification of the same game
     * stay apart); a tap opens the app, with [gameId] that online game.
     */
    fun show(context: Context, channel: String, tag: String, title: String, text: String, gameId: String? = null) {
        channels(context)
        val key = "$channel:$tag"
        val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        gameId?.let { intent.putExtra(MainActivity.EXTRA_GAME_ID, it) }
        // one request code per key: with a shared one every notification would open the newest one's game
        val open = PendingIntent.getActivity(context, key.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(key, NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            log.info("Notification permission withdrawn", e)
        }
    }

    fun inForeground(): Boolean =
        ActivityManager.RunningAppProcessInfo().also { ActivityManager.getMyMemoryState(it) }.importance ==
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND

    /** A defender notice appeared while the app is in front: the notification sound and the battle vibration. */
    fun alert(context: Context) {
        runCatching { RingtoneManager.getRingtone(context, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))?.play() }
        Haptics.battle()
    }
}
