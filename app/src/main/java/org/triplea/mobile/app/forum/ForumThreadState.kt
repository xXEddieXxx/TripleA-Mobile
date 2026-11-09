package org.triplea.mobile.app.forum

import android.content.Context
import androidx.core.content.edit
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.triplea.mobile.app.AppServices
import org.triplea.mobile.forum.Forum
import org.triplea.mobile.forum.NodeBbClient.GameThread

/**
 * What the app remembers per forum thread: the unsent reply, the newest post the player has seen
 * (for "new" marks), the last turn a notification was shown for, and whether the player stopped
 * following the game.
 */
object ForumThreadState {
    private val prefs get() = AppServices.appContext.getSharedPreferences("forum_threads", Context.MODE_PRIVATE)

    /** Topic numbers repeat across forums; the TripleA forum's keys stay as they were before there were two. */
    private fun key(kind: String, thread: GameThread) =
        if (thread.forum() == Forum.TRIPLEA) "$kind.${thread.topicId()}" else "$kind.${thread.forum().name}.${thread.topicId()}"

    fun draft(thread: GameThread): String = prefs.getString(key("draft", thread), "").orEmpty()

    fun saveDraft(thread: GameThread, text: String) = prefs.edit {
        if (text.isBlank()) remove(key("draft", thread)) else putString(key("draft", thread), text)
    }

    fun seen(thread: GameThread): Instant = Instant.ofEpochMilli(prefs.getLong(key("seen", thread), 0))

    fun markSeen(thread: GameThread, newest: Instant) {
        if (newest.isAfter(seen(thread))) prefs.edit { putLong(key("seen", thread), newest.toEpochMilli()) }
    }

    /** Posts the player has not seen; a thread never opened in the app counts as seen, so old games are not all "new". */
    fun hasNew(thread: GameThread): Boolean {
        val seen = prefs.getLong(key("seen", thread), -1)
        return seen >= 0 && thread.lastPost().toEpochMilli() > seen
    }

    /** True once per last post: whether a notification for it is still to be shown. */
    fun firstNotice(thread: GameThread): Boolean {
        val last = thread.lastPost().toEpochMilli()
        if (prefs.getLong(key("notified", thread), 0) >= last) return false
        prefs.edit { putLong(key("notified", thread), last) }
        return true
    }

    /** Bumped when a game is stopped or followed again, so the games list filters anew. */
    private val _stoppedChanges = MutableStateFlow(0)
    val stoppedChanges: StateFlow<Int> = _stoppedChanges.asStateFlow()

    /** A game the player no longer follows (over, or they are out): off My games, never notifies. */
    fun isStopped(thread: GameThread): Boolean = prefs.getBoolean(key("stopped", thread), false)

    fun setStopped(thread: GameThread, stopped: Boolean) {
        prefs.edit { if (stopped) putBoolean(key("stopped", thread), true) else remove(key("stopped", thread)) }
        _stoppedChanges.value++
    }
}
