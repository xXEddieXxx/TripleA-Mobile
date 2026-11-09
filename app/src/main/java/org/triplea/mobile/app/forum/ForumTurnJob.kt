package org.triplea.mobile.app.forum

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.triplea.mobile.app.MainActivity
import org.triplea.mobile.app.R
import org.triplea.mobile.forum.Forum
import org.triplea.mobile.forum.GameSearch
import org.triplea.mobile.forum.NodeBbClient.GameThread

/**
 * Every 15 minutes (Android's shortest period; Doze may delay it) while signed in to the forum, and
 * once each time the app comes to the front: a notification for each of the player's games that
 * waits for them since a post they have not seen, once per post.
 */
class ForumTurnJob : JobService() {
    @Volatile
    private var worker: Thread? = null

    override fun onStartJob(params: JobParameters): Boolean {
        worker = thread(name = "forum-turns", isDaemon = true) {
            runCatching { check(applicationContext) }.onFailure { Log.w(TAG, "Forum turn check failed", it) }
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        worker?.interrupt()
        return true
    }

    companion object {
        private const val TAG = "ForumTurnJob"
        private const val JOB_ID = 4501
        private const val CHANNEL = "forum_turns"
        const val ACTION_OPEN_THREAD = "org.triplea.mobile.OPEN_FORUM_THREAD"
        const val EXTRA_FORUM = "forum.site"
        const val EXTRA_TOPIC = "forum.topic"
        const val EXTRA_SLUG = "forum.slug"
        const val EXTRA_TITLE = "forum.title"

        /** Android's shortest period for a periodic job. */
        private val PERIOD = TimeUnit.MINUTES.toMillis(15)

        /** Runs at app start: a refused job only costs the notifications, never the start. */
        fun schedule(context: Context) {
            try {
                val scheduler = context.getSystemService(JobScheduler::class.java)
                // a job of an older build (every 30 minutes) is planned anew with the current period
                if (scheduler.getPendingJob(JOB_ID)?.intervalMillis == PERIOD) return
                scheduler.schedule(
                    JobInfo.Builder(JOB_ID, ComponentName(context, ForumTurnJob::class.java))
                        .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                        .setPeriodic(PERIOD)
                        .setPersisted(true)
                        .build(),
                )
            } catch (e: RuntimeException) {
                Log.w(TAG, "Forum turn check not scheduled", e)
            }
        }

        fun cancel(context: Context) = context.getSystemService(JobScheduler::class.java).cancel(JOB_ID)

        @Volatile
        private var lastCheckNow = 0L

        /** The same check at once, off the main thread: the app came to the front (a rotation within a minute does not count). */
        fun checkNow(context: Context) {
            val now = System.currentTimeMillis()
            if (now - lastCheckNow < TimeUnit.MINUTES.toMillis(1)) return
            lastCheckNow = now
            val app = context.applicationContext
            thread(name = "forum-turns-now", isDaemon = true) {
                runCatching { check(app) }.onFailure { Log.w(TAG, "Forum turn check failed", it) }
            }
        }

        private fun check(context: Context) {
            if (!canNotify(context)) return
            Forum.entries.forEach { forum ->
                // one forum being down does not keep the other's turns back
                runCatching { check(context, forum) }.onFailure { Log.w(TAG, "Turn check of $forum failed", it) }
            }
        }

        private fun check(context: Context, forum: Forum) {
            val client = ForumAccount.client(forum) ?: return
            val me = client.signedInPlayer().orElse(null) ?: return
            // old abandoned games never notify, nor posts already read in the app
            val recent = Instant.now().minus(GameSearch.INACTIVE_AFTER)
            client.threadsOf(me)
                .filter { it.waitsFor(me) && it.lastPost().isAfter(recent) && it.lastPost().isAfter(ForumThreadState.seen(it)) }
                // a game the player stopped following never notifies
                .filterNot { ForumThreadState.isStopped(it) }
                .filter { ForumThreadState.firstNotice(it) }
                .forEach { notify(context, it) }
        }

        private fun canNotify(context: Context): Boolean =
            NotificationManagerCompat.from(context).areNotificationsEnabled() &&
                // the permission exists from Android 13; before, notifications need none
                (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)

        private fun notify(context: Context, game: GameThread) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "Your turn in forum games", NotificationManager.IMPORTANCE_DEFAULT),
            )
            val open = Intent(context, MainActivity::class.java)
                .setAction(ACTION_OPEN_THREAD)
                .putExtra(EXTRA_FORUM, game.forum().name)
                .putExtra(EXTRA_TOPIC, game.topicId())
                .putExtra(EXTRA_SLUG, game.slug())
                .putExtra(EXTRA_TITLE, game.title())
            // topic numbers repeat across forums: one pending intent and notification per forum and topic
            val id = "${game.forum().name}.${game.topicId()}".hashCode()
            val tap = PendingIntent.getActivity(
                context, id, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val text = listOfNotNull(
                game.lastTurn().ifEmpty { null },
                game.lastPosterSlug().ifEmpty { null }?.let { "posted by $it" },
            ).joinToString(" · ").ifEmpty { "New post" }
            val notification = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notify_turn)
                .setContentTitle("Your turn: ${game.title()}")
                .setSubText(game.forum().label)
                .setContentText(text)
                .setContentIntent(tap)
                .setAutoCancel(true)
                .build()
            try {
                NotificationManagerCompat.from(context).notify(id, notification)
            } catch (e: SecurityException) {
                Log.w(TAG, "Notification permission withdrawn", e)
            }
        }
    }
}
