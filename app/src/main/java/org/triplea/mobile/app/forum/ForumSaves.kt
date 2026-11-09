package org.triplea.mobile.app.forum

import android.content.Context
import androidx.core.content.edit
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.triplea.mobile.app.AppServices
import org.triplea.mobile.app.game.GameController
import org.triplea.mobile.forum.Forum
import org.triplea.mobile.forum.NodeBbClient.GameThread

/**
 * Which forum thread a downloaded save came from, so a save opened later from the load screen
 * still posts to its thread. Also the rule that keeps the load screen short: a forum game keeps
 * only its newest save (a turn put off with "send later" is never removed by a download).
 */
object ForumSaves {
    private val prefs get() = AppServices.appContext.getSharedPreferences("forum_saves", Context.MODE_PRIVATE)

    /** Marks a turn save that was not sent yet ("send later"); a key next to the save's own. */
    private const val UNSENT = "unsent:"

    private fun key(save: Path) = save.toAbsolutePath().toString()

    // the forum leads, as "@AXIS_AND_ALLIES", so entries written before there were two forums still read as TripleA's
    fun record(save: Path, thread: GameThread) {
        prefs.edit {
            putString(key(save), "@${thread.forum().name}\n${thread.topicId()}\n${thread.slug()}\n${thread.title()}")
            remove(UNSENT + key(save))
        }
    }

    /** A turn saved but not sent: kept when newer saves of its thread are downloaded. */
    fun recordUnsent(save: Path, thread: GameThread) {
        record(save, thread)
        prefs.edit { putBoolean(UNSENT + key(save), true) }
    }

    fun threadFor(save: Path): GameThread? {
        val stored = prefs.getString(key(save), null) ?: return null
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

    /** The save is gone (deleted on the load screen): its thread entry goes too. */
    fun forget(save: Path) {
        prefs.edit {
            remove(key(save))
            remove(UNSENT + key(save))
        }
    }

    /** The save was renamed on the load screen: it keeps its thread. */
    fun move(from: Path, to: Path) {
        val thread = threadFor(from) ?: return
        val unsent = prefs.getBoolean(UNSENT + key(from), false)
        forget(from)
        if (unsent) recordUnsent(to, thread) else record(to, thread)
    }

    /**
     * Deletes the other saves of [save]'s forum game, so only the newest stays. [keepUnsent]: a
     * download keeps a turn put off for later; a turn of one's own that is sent replaces it. Call
     * from a background thread.
     */
    fun keepOnly(save: Path, keepUnsent: Boolean) {
        val thread = threadFor(save) ?: return
        recorded().filter { (path, other) -> path != key(save) && sameGame(other, thread) }
            .filterNot { (path, _) -> keepUnsent && prefs.getBoolean(UNSENT + path, false) }
            .forEach { (path, _) -> delete(Paths.get(path)) }
    }

    /**
     * The same rule for saves already on the device (from before it, or written while a forum game
     * was open elsewhere): per forum game the newest save and the unsent turns stay. Entries whose
     * file is gone are dropped. Call from a background thread.
     */
    fun keepNewestOfEachGame() {
        val (present, missing) = recorded().partition { (path, _) -> Files.exists(Paths.get(path)) }
        missing.forEach { (path, _) -> forget(Paths.get(path)) }
        present.groupBy { (_, thread) -> thread.forum() to thread.topicId() }.values.forEach { saves ->
            saves.maxByOrNull { (path, _) -> Files.getLastModifiedTime(Paths.get(path)).toMillis() }
                ?.let { (newest, _) -> keepOnly(Paths.get(newest), keepUnsent = true) }
        }
    }

    private fun sameGame(a: GameThread, b: GameThread) = a.forum() == b.forum() && a.topicId() == b.topicId()

    /** Every recorded save path with its thread. */
    private fun recorded(): List<Pair<String, GameThread>> = prefs.all.keys
        .filterNot { it.startsWith(UNSENT) }
        .mapNotNull { path -> threadFor(Paths.get(path))?.let { path to it } }

    private fun delete(save: Path) {
        runCatching { Files.deleteIfExists(save) }
        runCatching { Files.deleteIfExists(GameController.infoFileFor(save)) }
        forget(save)
    }
}
