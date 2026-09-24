package org.triplea.mobile.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.triplea.mobile.MobileEngine
import org.triplea.mobile.app.game.GameController

/**
 * Moves save games in and out of the app: sharing a save through the system share sheet (chat,
 * mail, a drive) and importing one from any file the document picker offers. This is how a game
 * travels between phones: play a turn, share the save, the other player imports it and goes on.
 */
object SaveTransfer {
    /** Imports are refused above this size; a save is a few hundred kilobytes, big maps a few megabytes. */
    private const val MAX_IMPORT_BYTES = 200L * 1024 * 1024

    /** Opens the share sheet for a save; the file travels as an attachment. */
    fun share(context: Context, save: Path) {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", save.toFile())
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, save.fileName.toString())
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "Share save").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /**
     * Copies the file behind [uri] into the save folder, checks that the engine can read it, and
     * writes the sidecar with round and nation so the list shows it like a save made here.
     */
    suspend fun import(context: Context, uri: Uri): Result<Path> = withContext(Dispatchers.IO) {
        runCatching {
            val folder = MobileEngine.getSaveGamesFolder()
            Files.createDirectories(folder)
            val target = uniqueTarget(folder, safeName(displayName(context, uri)))
            context.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "The file could not be opened." }
                copyBounded(input, target)
            }
            val data = MobileEngine.loadSaveGame(target).orElse(null)
            if (data == null) {
                Files.deleteIfExists(target)
                throw IllegalArgumentException("This is not a TripleA Mobile save game.")
            }
            val step = data.sequence.step
            val info = listOf(
                "round=${data.sequence.round}",
                "player=${step?.playerId?.name ?: ""}",
                "step=${step?.displayName ?: ""}",
                "game=${data.gameName}",
                "map=${data.mapName}",
            ).joinToString(System.lineSeparator())
            Files.write(GameController.infoFileFor(target), info.toByteArray(Charsets.UTF_8))
            target
        }
    }

    private fun copyBounded(input: java.io.InputStream, target: Path) {
        Files.newOutputStream(target).use { output ->
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > MAX_IMPORT_BYTES) {
                    output.close()
                    Files.deleteIfExists(target)
                    throw IllegalArgumentException("The file is too large for a save game.")
                }
                output.write(buffer, 0, read)
            }
        }
    }

    private fun displayName(context: Context, uri: Uri): String {
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) return cursor.getString(index) ?: ""
                }
            }
        }
        return uri.lastPathSegment ?: ""
    }

    /** A plain file name inside the save folder: no paths, only harmless characters, ".tsvg" at the end. */
    private fun safeName(raw: String): String {
        val base = raw.substringAfterLast('/').substringAfterLast('\\')
            .removeSuffix(".tsvg")
            .replace(Regex("[^A-Za-z0-9 _.()-]"), "")
            .trim()
            .take(80)
            .ifBlank { "imported" }
        return "$base.tsvg"
    }

    private fun uniqueTarget(folder: Path, name: String): Path {
        var candidate = folder.resolve(name)
        var n = 2
        while (Files.exists(candidate)) {
            candidate = folder.resolve(name.removeSuffix(".tsvg") + " ($n).tsvg")
            n++
        }
        return candidate
    }
}
