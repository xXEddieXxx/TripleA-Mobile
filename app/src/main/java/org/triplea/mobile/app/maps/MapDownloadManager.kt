package org.triplea.mobile.app.maps

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.util.LruCache
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipFile
import kotlin.io.path.deleteRecursively
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import org.triplea.mobile.MobileEngine
import org.triplea.mobile.app.AppServices
import org.triplea.mobile.app.MapInstaller

/** One entry of the desktop client's map list (triplea_maps.yaml). */
data class MapEntry(
    val name: String,
    val category: String,
    val url: String,
    val imageUrl: String?,
    val version: Int,
    val description: String,
) {
    /** Folder below the maps folder, following the repository naming rule of the listing. */
    val folderName: String = name.lowercase(Locale.ROOT).trim().replace(Regex("\\s+"), "_")

    /** Name used to match listing entries against installed folders regardless of spelling. */
    val normalizedName: String = normalizeName(name)

    companion object {
        fun normalizeName(name: String): String = name.lowercase(Locale.ROOT).replace(Regex("[_ -]"), "")
    }
}

/** What is known about a map on this device. */
data class InstalledMap(
    val folder: Path,
    val normalizedName: String,
    /** The listing version that was downloaded, null for maps not installed by the browser. */
    val downloadVersion: Int?,
    val bundled: Boolean,
)

sealed class ListingState {
    object Idle : ListingState()
    object Loading : ListingState()
    data class Loaded(val maps: List<MapEntry>, val fromCache: Boolean) : ListingState()
    data class Error(val message: String) : ListingState()
}

sealed class DownloadState {
    /** [fraction] is null while the size is unknown. */
    data class Downloading(val bytes: Long, val total: Long?) : DownloadState() {
        val fraction: Float? get() = total?.takeIf { it > 0 }?.let { (bytes.toDouble() / it).toFloat().coerceIn(0f, 1f) }
    }
    object Installing : DownloadState()
    data class Failed(val message: String) : DownloadState()
}

/**
 * Downloads the map listing, installs map zips into the maps folder and keeps track of what is
 * installed. Lives for the whole process so downloads survive navigation.
 */
object MapDownloadManager {
    private const val TAG = "MapDownloads"
    private const val LISTING_URL = "https://raw.githubusercontent.com/triplea-game/triplea/master/triplea_maps.yaml"
    private const val VERSION_FILE = ".download_version"
    private const val BUNDLED_FILE = ".bundled_version"
    private const val LISTING_CACHE_MAX_AGE_MS = 6 * 60 * 60 * 1000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = HashMap<String, Job>()
    private val installLock = Mutex()

    private val _listing = MutableStateFlow<ListingState>(ListingState.Idle)
    val listing: StateFlow<ListingState> = _listing.asStateFlow()

    private val _installed = MutableStateFlow<Map<String, InstalledMap>>(emptyMap())
    /** Installed maps keyed by normalized name. */
    val installed: StateFlow<Map<String, InstalledMap>> = _installed.asStateFlow()

    private val _downloads = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    /** Running or failed downloads keyed by normalized map name. */
    val downloads: StateFlow<Map<String, DownloadState>> = _downloads.asStateFlow()

    private val thumbnails = LruCache<String, Bitmap>(60)

    private val cacheDir: Path get() = AppServices.appContext.cacheDir.toPath().resolve("mapdownloads")
    private val listingCache: Path get() = cacheDir.resolve("triplea_maps.yaml")

    // ---------------------------------------------------------------- listing

    fun refreshListing(force: Boolean = false) {
        if (_listing.value is ListingState.Loading) return
        _listing.value = ListingState.Loading
        scope.launch {
            val cached = runCatching {
                if (Files.exists(listingCache)) {
                    val age = System.currentTimeMillis() - Files.getLastModifiedTime(listingCache).toMillis()
                    if (!force && age < LISTING_CACHE_MAX_AGE_MS) String(Files.readAllBytes(listingCache), Charsets.UTF_8) else null
                } else null
            }.getOrNull()
            if (cached != null) {
                val maps = runCatching { parseListing(cached) }.getOrNull()
                if (!maps.isNullOrEmpty()) {
                    _listing.value = ListingState.Loaded(maps, fromCache = true)
                    return@launch
                }
            }
            try {
                val text = fetchText(LISTING_URL)
                val maps = parseListing(text)
                if (maps.isEmpty()) throw IOException("The map list is empty")
                runCatching {
                    Files.createDirectories(cacheDir)
                    Files.write(listingCache, text.toByteArray(Charsets.UTF_8))
                }
                _listing.value = ListingState.Loaded(maps, fromCache = false)
            } catch (e: Exception) {
                Log.w(TAG, "Loading the map list failed", e)
                // fall back to a stale cache rather than showing nothing
                val stale = runCatching { String(Files.readAllBytes(listingCache), Charsets.UTF_8) }.getOrNull()
                val maps = stale?.let { runCatching { parseListing(it) }.getOrNull() }
                _listing.value = if (!maps.isNullOrEmpty()) ListingState.Loaded(maps, fromCache = true)
                else ListingState.Error(e.message ?: "Could not load the map list")
            }
        }
    }

    private fun parseListing(text: String): List<MapEntry> {
        val loaded = Load(LoadSettings.builder().build()).loadFromString(text) as? List<*> ?: return emptyList()
        return loaded.mapNotNull { item ->
            val map = item as? Map<*, *> ?: return@mapNotNull null
            val name = map["mapName"]?.toString()?.trim().orEmpty()
            val url = map["url"]?.toString()?.trim().orEmpty()
            if (name.isEmpty() || url.isEmpty()) return@mapNotNull null
            MapEntry(
                name = name,
                category = map["mapCategory"]?.toString()?.trim().orEmpty().ifEmpty { "OTHER" },
                url = url,
                imageUrl = map["img"]?.toString()?.trim()?.takeIf { it.isNotEmpty() },
                version = map["version"]?.toString()?.trim()?.toIntOrNull() ?: 0,
                description = htmlToText(map["description"]?.toString().orEmpty()),
            )
        }.sortedWith(compareBy({ categoryRank(it.category) }, { it.name.lowercase(Locale.ROOT) }))
    }

    fun categoryRank(category: String): Int = when (category.uppercase(Locale.ROOT)) {
        "BEST" -> 0
        "GOOD" -> 1
        "DEVELOPMENT" -> 2
        "EXPERIMENTAL" -> 3
        else -> 4
    }

    private fun htmlToText(html: String): String =
        html.replace(Regex("(?i)<br\\s*/?>"), "\n")
            .replace(Regex("(?i)</p>|</li>"), "\n")
            .replace(Regex("<[^>]+>"), "")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")

    // ---------------------------------------------------------------- installed maps

    fun refreshInstalled() {
        scope.launch { scanInstalled() }
    }

    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    private fun scanInstalled() {
        val folder = MobileEngine.getMapsFolder()
        val result = HashMap<String, InstalledMap>()
        runCatching {
            Files.list(folder).use { stream ->
                stream.filter { Files.isDirectory(it) }.forEach { dir ->
                    val name = dir.fileName.toString()
                    if (name.endsWith(".tmp")) {
                        // leftover of an older build that staged inside the maps folder
                        runCatching { dir.deleteRecursively() }
                        return@forEach
                    }
                    val version = runCatching {
                        String(Files.readAllBytes(dir.resolve(VERSION_FILE)), Charsets.UTF_8).trim().toIntOrNull()
                    }.getOrNull()
                    val bundled = Files.exists(dir.resolve(BUNDLED_FILE))
                    val installed = InstalledMap(dir, MapEntry.normalizeName(name), version, bundled)
                    result[installed.normalizedName] = installed
                }
            }
        }.onFailure { Log.w(TAG, "Scanning the maps folder failed", it) }
        _installed.value = result
    }

    fun installedFor(entry: MapEntry): InstalledMap? = _installed.value[entry.normalizedName]

    /** Imports are refused above this size; the largest maps in the listing are a few hundred MB. */
    private const val MAX_IMPORT_BYTES = 2L * 1024 * 1024 * 1024

    /**
     * Installs a map from a zip file on the device (a map copied from the desktop folder, or from
     * another source than the listing). Same unpacking and checks as a download; the folder is
     * named after the file. Returns the folder name of the installed map.
     */
    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    suspend fun importZip(context: android.content.Context, uri: android.net.Uri): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val rawName = org.triplea.mobile.app.SaveTransfer.displayName(context, uri)
            val folderName = rawName.substringAfterLast('/').substringAfterLast('\\')
                .removeSuffix(".zip").removeSuffix(".ZIP")
                .lowercase(Locale.ROOT).trim()
                .replace(Regex("\\s+"), "_")
                .replace(Regex("[^a-z0-9_.-]"), "")
                .trim('.', '_', '-')
                .take(80)
                .ifBlank { "imported_map" }
            Files.createDirectories(cacheDir)
            val zip = cacheDir.resolve("$folderName.import.zip")
            try {
                context.contentResolver.openInputStream(uri).use { input ->
                    requireNotNull(input) { "The file could not be opened." }
                    Files.newOutputStream(zip).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            total += read
                            if (total > MAX_IMPORT_BYTES) throw IOException("The file is too large for a map.")
                            output.write(buffer, 0, read)
                        }
                    }
                }
                val mapsFolder = MobileEngine.getMapsFolder()
                val target = mapsFolder.resolve(folderName)
                val staging = cacheDir.resolve("staging").resolve(folderName)
                runCatching { staging.deleteRecursively() }
                Files.createDirectories(staging)
                try {
                    ZipFile(zip.toFile()).use { file -> MapInstaller.unzip(file, staging, stripTopLevelFolder = true) }
                    if (!Files.exists(staging.resolve("map.yml")) && !Files.exists(staging.resolve("map"))) {
                        throw IOException("This zip is not a TripleA map (no map.yml inside).")
                    }
                    Files.createDirectories(mapsFolder)
                    if (Files.exists(target)) target.deleteRecursively()
                    try {
                        Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE)
                    } catch (e: java.nio.file.AtomicMoveNotSupportedException) {
                        Files.move(staging, target)
                    }
                } finally {
                    runCatching { staging.deleteRecursively() }
                }
            } finally {
                runCatching { Files.deleteIfExists(zip) }
            }
            scanInstalled()
            folderName
        }
    }

    // ---------------------------------------------------------------- download / delete

    fun download(entry: MapEntry) {
        val key = entry.normalizedName
        synchronized(jobs) {
            if (jobs[key]?.isActive == true) return
            _downloads.update { it + (key to DownloadState.Downloading(0, null)) }
            jobs[key] = scope.launch {
                var zip: Path? = null
                try {
                    Files.createDirectories(cacheDir)
                    zip = cacheDir.resolve("${entry.folderName}.zip.part")
                    downloadFile(entry.url, zip) { bytes, total ->
                        _downloads.update { it + (key to DownloadState.Downloading(bytes, total)) }
                    }
                    _downloads.update { it + (key to DownloadState.Installing) }
                    installLock.withLock { installZip(zip, entry) }
                    scanInstalled()
                    _downloads.update { it - key }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) {
                        _downloads.update { it - key }
                    } else {
                        Log.w(TAG, "Download of ${entry.name} failed", e)
                        _downloads.update { it + (key to DownloadState.Failed(e.message ?: "Download failed")) }
                    }
                } finally {
                    zip?.let { runCatching { Files.deleteIfExists(it) } }
                }
            }
        }
    }

    fun cancel(entry: MapEntry) {
        synchronized(jobs) { jobs.remove(entry.normalizedName)?.cancel() }
        _downloads.update { it - entry.normalizedName }
    }

    fun dismissError(entry: MapEntry) {
        _downloads.update { it - entry.normalizedName }
    }

    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    fun delete(entry: MapEntry) {
        val installed = installedFor(entry) ?: return
        if (installed.bundled) return
        scope.launch {
            installLock.withLock {
                runCatching { installed.folder.deleteRecursively() }
                    .onFailure { Log.w(TAG, "Deleting ${installed.folder} failed", it) }
            }
            scanInstalled()
        }
    }

    /**
     * Unpacks into a staging folder in the cache directory, outside the maps folder, so the engine
     * never sees a half extracted map, then moves the finished folder into place in one step.
     */
    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    private fun installZip(zip: Path, entry: MapEntry) {
        val mapsFolder = MobileEngine.getMapsFolder()
        val existing = installedFor(entry)?.folder
        val target = existing ?: mapsFolder.resolve(entry.folderName)
        val staging = cacheDir.resolve("staging").resolve(target.fileName.toString())
        runCatching { staging.deleteRecursively() }
        Files.createDirectories(staging)
        try {
            ZipFile(zip.toFile()).use { file ->
                MapInstaller.unzip(file, staging, stripTopLevelFolder = true)
            }
            if (!Files.exists(staging.resolve("map.yml")) && !Files.exists(staging.resolve("map"))) {
                throw IOException("The archive does not look like a TripleA map")
            }
            Files.write(staging.resolve(VERSION_FILE), entry.version.toString().toByteArray(Charsets.UTF_8))
            Files.createDirectories(mapsFolder)
            if (Files.exists(target)) target.deleteRecursively()
            try {
                Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE)
            } catch (e: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(staging, target)
            }
        } finally {
            runCatching { staging.deleteRecursively() }
        }
    }

    private suspend fun downloadFile(url: String, target: Path, onProgress: (Long, Long?) -> Unit) {
        val connection = openConnection(url)
        try {
            val total = connection.contentLengthLong.takeIf { it > 0 }
            connection.inputStream.use { input ->
                Files.newOutputStream(target).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var bytes = 0L
                    var lastReport = 0L
                    while (true) {
                        currentCoroutineContextEnsureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        bytes += read
                        if (bytes - lastReport > 128 * 1024) {
                            lastReport = bytes
                            onProgress(bytes, total)
                        }
                    }
                    onProgress(bytes, total)
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun currentCoroutineContextEnsureActive() {
        kotlin.coroutines.coroutineContext.ensureActive()
    }

    /** Opens a GET connection and follows redirects, also across hosts (GitHub archive downloads). */
    private fun openConnection(url: String): HttpURLConnection {
        var current = url
        repeat(6) {
            // only encrypted connections, also after redirects
            if (!current.startsWith("https://", ignoreCase = true)) throw IOException("Refusing insecure URL: $current")
            val connection = URL(current).openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = true
            connection.connectTimeout = 20_000
            connection.readTimeout = 60_000
            connection.setRequestProperty("User-Agent", "TripleA-Mobile")
            val code = connection.responseCode
            if (code in 300..399) {
                val location = connection.getHeaderField("Location") ?: throw IOException("Redirect without location")
                connection.disconnect()
                current = URL(URL(current), location).toString()
                return@repeat
            }
            if (code !in 200..299) {
                connection.disconnect()
                throw IOException("HTTP $code for $current")
            }
            return connection
        }
        throw IOException("Too many redirects for $url")
    }

    private fun fetchText(url: String): String {
        val connection = openConnection(url)
        try {
            return connection.inputStream.use { String(it.readBytes(), Charsets.UTF_8) }
        } finally {
            connection.disconnect()
        }
    }

    // ---------------------------------------------------------------- thumbnails

    /** Loads a preview image, from memory, the disk cache or the network; null if unavailable. */
    suspend fun thumbnail(url: String): Bitmap? {
        thumbnails.get(url)?.let { return it }
        return withContext(Dispatchers.IO) {
            val file = cacheDir.resolve("thumbs").resolve(sha1(url) + ".img")
            var bitmap = runCatching { if (Files.exists(file)) decode(Files.readAllBytes(file)) else null }.getOrNull()
            if (bitmap == null) {
                bitmap = runCatching {
                    val connection = openConnection(url)
                    val bytes = try {
                        connection.inputStream.use { it.readBytes() }
                    } finally {
                        connection.disconnect()
                    }
                    val decoded = decode(bytes)
                    if (decoded != null) {
                        Files.createDirectories(file.parent)
                        Files.write(file, bytes)
                    }
                    decoded
                }.getOrNull()
            }
            bitmap?.also { thumbnails.put(url, it) }
        }
    }

    private fun decode(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / sample > 640) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private fun sha1(text: String): String =
        MessageDigest.getInstance("SHA-1").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
