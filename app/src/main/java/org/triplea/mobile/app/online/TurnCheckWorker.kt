package org.triplea.mobile.app.online

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.edit
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import org.slf4j.LoggerFactory
import org.triplea.mobile.app.AppSettings
import org.triplea.mobile.app.game.GameController

/**
 * Polls GET /me/turns every 15 minutes (WorkManager's minimum, only on a network, only while logged in)
 * and shows one notification per game (a tap opens that game) whose waiting question is new since the last check (questions
 * are kept per game in prefs). The game open on the screen is skipped. Unread private messages give one
 * "New message from <name>" per friend whose unread count rose since the last check (every check saves the
 * counts; reading the chat in the app resets that friend's count to 0).
 */
class TurnCheckWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        if (AppSettings.tokens.tokens == null) return Result.success()
        // nothing is remembered while notifications are off, so the waiting games show once they are on
        if (!NotificationManagerCompat.from(applicationContext).areNotificationsEnabled()) return Result.success()
        val api = try {
            OnlineApi.forUrl(AppSettings.current.serverUrl)
        } catch (e: IllegalArgumentException) {
            return Result.success()
        }
        val prefs = applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // before the network calls: a markRead during them must show as a change below, not be overwritten
        val notified = prefs.all
        val checked = try {
            api.turns()
        } catch (e: Exception) {
            // offline or logged out: the next period tries again
            log.info("Turn check failed: {}", e.message)
            return Result.success()
        }
        val turns = checked.turns
        // who the unread private messages are from; null when the list failed (the old counts are kept)
        val senders = if (checked.unreadDirect == 0) emptyList() else runCatching { api.friends().filter { it.unread > 0 } }.getOrNull()
        // a question not notified yet; the game on the screen right now needs none
        val playing = (GameController.session as? OnlineSession)?.gameId?.takeIf { Notifications.inForeground() }
        val fresh = turns.filter { notified[it.gameId] != it.questionId && it.gameId != playing }
        // a sender whose unread count rose since the last check; reading the chat in the app resets the saved count (markRead)
        // shortcut: a chat read on another device keeps the old count until a check sees 0, so a smaller burst before that is missed
        // only waiting games and unread senders are kept; per key, so a markRead during this check is not overwritten
        val kept = synchronized(LOCK) {
            // logged out or deleted during the calls: stop() already cleared the prefs, nothing to write or show
            if (AppSettings.tokens.tokens == null) return Result.success()
            val now = prefs.all
            val kept = senders?.filter { now[DM + it.username] == notified[DM + it.username] }.orEmpty()
            prefs.edit(commit = true) {
                val waiting = turns.map { it.gameId }.toSet()
                now.keys.filter { !it.startsWith(DM) && it !in waiting }.forEach { remove(it) }
                turns.forEach { putLong(it.gameId, it.questionId) }
                if (senders != null) {
                    val unread = senders.map { DM + it.username }.toSet()
                    now.keys.filter { it.startsWith(DM) && it !in unread }.forEach { remove(it) }
                    kept.forEach { putLong(DM + it.username, it.unread.toLong()) }
                }
            }
            kept
        }
        val freshSenders = kept.filter { it.unread.toLong() > (notified[DM + it.username] as? Long ?: 0L) }
        freshSenders.forEach {
            Notifications.show(applicationContext, Notifications.TURNS, DM + it.username, "New message from ${it.username}", "${it.unread} unread")
        }
        if (fresh.isEmpty()) return Result.success()
        val names = runCatching { api.games().associate { it.id to it.gameName } }.getOrDefault(emptyMap())
        fresh.forEach {
            Notifications.show(
                applicationContext,
                Notifications.TURNS,
                it.gameId,
                "Your turn: ${names[it.gameId] ?: "Online game"}",
                "${it.nation} is waiting for you",
                it.gameId,
            )
        }
        return Result.success()
    }

    companion object {
        private val log = LoggerFactory.getLogger(TurnCheckWorker::class.java)
        private const val WORK = "turn-check"
        private const val PREFS = "turn-check"
        /** Prefs key and notification tag prefix of a private-chat sender (game ids never start with it). */
        private const val DM = "dm:"
        /** Guards the check's read-compare-write of the prefs against markRead and stop. */
        private val LOCK = Any()

        /** While logged in: every 15 minutes on a network; keeps a schedule that already exists. */
        fun start(context: Context) {
            val request = PeriodicWorkRequestBuilder<TurnCheckWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        /** On logout: no more polling, and the next login notifies every waiting game again. */
        fun stop(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK)
            synchronized(LOCK) { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit(commit = true) { clear() } }
        }

        /** The user read [username]'s private chat: their next message counts as new again. */
        fun markRead(context: Context, username: String) {
            synchronized(LOCK) { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit(commit = true) { remove(DM + username) } }
        }

        /** Debug menu: one check now. */
        fun checkNow(context: Context) {
            WorkManager.getInstance(context).enqueue(OneTimeWorkRequestBuilder<TurnCheckWorker>().build())
        }
    }
}
