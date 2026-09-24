package org.triplea.mobile.app.sound

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import games.strategy.triplea.ResourceLoader
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import org.triplea.mobile.app.AppSettings
import org.triplea.sound.SoundPath

/** Which settings switch governs a sound clip. */
enum class SoundCategory { BATTLE, PHASE, PLACEMENT, OTHER }

/**
 * Plays the desktop client's sound clips on Android. Mirrors the desktop {@code ClipPlayer}: a
 * clip name is a folder under {@code sounds/<era>/}, optionally with a player specific variant
 * ({@code required_your_turn_series_Germans}), overridable by the map's {@code sounds.properties},
 * with the {@code generic} folder as the last fallback. One random file of the folder is played.
 */
object SoundPlayer {
    private const val TAG = "SoundPlayer"
    private const val SOUNDS_FOLDER = "sounds"
    private const val GENERIC_FOLDER = "generic"
    private const val DEFAULT_ERA = "ww2"
    private const val MAX_CONCURRENT = 4

    private val executor = Executors.newSingleThreadExecutor { Thread(it, "sound-player").apply { isDaemon = true } }
    private val active: MutableSet<MediaPlayer> = Collections.synchronizedSet(HashSet())

    /** Resolved clip files per (loader identity, clip path). */
    private val cache = ConcurrentHashMap<String, List<Path>>()
    @Volatile private var cachedLoader: ResourceLoader? = null

    fun categoryOf(clipName: String): SoundCategory = when {
        clipName.startsWith("battle_") || clipName.startsWith("bombing_") -> SoundCategory.BATTLE
        clipName.startsWith("phase_") || clipName.startsWith(SoundPath.CLIP_REQUIRED_YOUR_TURN_SERIES) -> SoundCategory.PHASE
        clipName.startsWith("placed_") || clipName.startsWith("territory_capture_") -> SoundCategory.PLACEMENT
        else -> SoundCategory.OTHER
    }

    /** Plays [clipName] for [playerName] (may be null) using the resources of the running game. */
    fun play(clipName: String, playerName: String?, loader: ResourceLoader) {
        val settings = AppSettings.current
        if (!settings.soundEnabled || settings.soundVolume <= 0f) return
        val enabled = when (categoryOf(clipName)) {
            SoundCategory.BATTLE -> settings.soundBattle
            SoundCategory.PHASE -> settings.soundPhase
            SoundCategory.PLACEMENT -> settings.soundPlacement
            SoundCategory.OTHER -> settings.soundOther
        }
        if (!enabled) return
        executor.execute {
            try {
                if (cachedLoader !== loader) {
                    cache.clear()
                    cachedLoader = loader
                }
                val files = (if (playerName != null) resolve(loader, clipName + "_" + playerName) else emptyList())
                    .ifEmpty { resolve(loader, clipName) }
                if (files.isEmpty()) return@execute
                val file = files.random()
                startPlayback(file, settings.soundVolume)
            } catch (e: Exception) {
                Log.w(TAG, "Cannot play $clipName", e)
            }
        }
    }

    fun stopAll() {
        synchronized(active) {
            active.forEach { runCatching { it.stop(); it.release() } }
            active.clear()
        }
    }

    private fun startPlayback(file: Path, volume: Float) {
        synchronized(active) {
            if (active.size >= MAX_CONCURRENT) return
        }
        val player = MediaPlayer()
        player.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        player.setDataSource(file.toString())
        player.setVolume(volume, volume)
        player.setOnCompletionListener {
            active.remove(it)
            it.release()
        }
        player.setOnErrorListener { p, _, _ ->
            active.remove(p)
            p.release()
            true
        }
        player.prepare()
        active.add(player)
        player.start()
    }

    /** The clip files for a sound path, following the desktop resolution rules. */
    private fun resolve(loader: ResourceLoader, pathName: String): List<Path> =
        cache.getOrPut(pathName) {
            val properties = runCatching { loader.loadPropertyFile("sounds.properties") }.getOrNull()
            val themeOverride = AppSettings.current.soundTheme.takeIf { it.isNotBlank() }
            val era = themeOverride ?: properties?.getProperty("Sound.Default.Folder")?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_ERA
            val override = if (themeOverride == null) properties?.getProperty(pathName)?.trim() else null
            val paths = if (!override.isNullOrEmpty()) {
                if (override == "NONE") return@getOrPut emptyList()
                override.replace('\\', '/').split(';').map { it.trim() }.filter { it.isNotEmpty() }
            } else {
                listOf("$era/$pathName")
            }
            val files = ArrayList<Path>()
            for (path in paths) files += clipFiles(loader, "$SOUNDS_FOLDER/$path")
            if (files.isEmpty()) files += clipFiles(loader, "$SOUNDS_FOLDER/$GENERIC_FOLDER/$pathName")
            files
        }

    private fun clipFiles(loader: ResourceLoader, resourcePath: String): List<Path> =
        runCatching {
            loader.listResources(resourcePath).mapNotNull { url ->
                runCatching { Paths.get(url.toURI()) }.getOrNull()
            }.filter { it.toString().endsWith(".mp3", ignoreCase = true) }
        }.getOrDefault(emptyList())
}
