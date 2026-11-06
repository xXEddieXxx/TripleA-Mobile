package org.triplea.mobile.app.forum

import android.content.Context
import androidx.core.content.edit
import java.nio.file.Path
import org.triplea.mobile.app.AppServices
import org.triplea.mobile.forum.Forum
import org.triplea.mobile.forum.NodeBbClient.GameThread

/**
 * Which forum thread a downloaded save came from, so a save opened later from the load screen
 * still posts to its thread.
 */
object ForumSaves {
    private val prefs get() = AppServices.appContext.getSharedPreferences("forum_saves", Context.MODE_PRIVATE)

    // the forum leads, as "@AXIS_AND_ALLIES", so entries written before there were two forums still read as TripleA's
    fun record(save: Path, thread: GameThread) {
        prefs.edit { putString(save.toAbsolutePath().toString(), "@${thread.forum().name}\n${thread.topicId()}\n${thread.slug()}\n${thread.title()}") }
    }

    fun threadFor(save: Path): GameThread? {
        val stored = prefs.getString(save.toAbsolutePath().toString(), null) ?: return null
        val (forum, rest) = if (stored.startsWith("@")) {
            val name = stored.substring(1).substringBefore('\n')
            (Forum.entries.firstOrNull { it.name == name } ?: return null) to stored.substringAfter('\n')
        } else {
            Forum.TRIPLEA to stored
        }
        val parts = rest.split('\n', limit = 3)
        val tid = parts[0].toIntOrNull() ?: return null
        return GameThread.of(forum, tid, parts.getOrElse(1) { "" }, parts.getOrElse(2) { "" })
    }
}
