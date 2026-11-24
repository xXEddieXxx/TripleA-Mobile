package org.triplea.mobile.app.update

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import androidx.core.content.edit
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.triplea.mobile.app.AppServices
import org.triplea.mobile.app.AppSettings
import org.triplea.mobile.app.maps.MapDownloadManager

/** A newer build on GitHub: [version] is what the About screen of that build will show. */
data class AppRelease(val version: String, val url: String, val size: Long, val sha256: String?)

/** One line of the in-app change list. */
data class Change(val kind: Kind, val text: String) {
    enum class Kind { NEW, FIXED, CHANGED }
}

sealed class UpdateState {
    object Idle : UpdateState()
    object Checking : UpdateState()
    object UpToDate : UpdateState()
    /** [downloaded]: the checked APK is on the device, the button installs it without a new download. */
    data class Available(val release: AppRelease, val downloaded: Boolean = false) : UpdateState()
    data class Downloading(val release: AppRelease, val bytes: Long) : UpdateState() {
        val fraction: Float get() = (bytes.toDouble() / release.size).toFloat().coerceIn(0f, 1f)
    }
    /** [release] is set when the download failed, so the card can offer it again. */
    data class Failed(val message: String, val release: AppRelease? = null) : UpdateState()
}

/**
 * Looks for a newer build of the app's own channel on GitHub (official releases, or the rolling
 * `dev` pre-release for dev builds; the two are signed with different keys and cannot update each
 * other), downloads it and hands it to the system installer. Local builds are never offered one.
 */
object AppUpdater {
    private const val TAG = "AppUpdater"
    private const val REPO_API = "https://api.github.com/repos/xXEddieXxx/TripleA-Mobile"
    private const val API = "$REPO_API/releases"
    private const val CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L
    private const val RETRY_MS = 60 * 60 * 1000L
    private const val PREFS = "updates"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private val context: Context get() = AppServices.appContext
    private val prefs get() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val updatesDir: Path get() = context.cacheDir.toPath().resolve("updates")

    /** One file per version, so a download of an older version is never installed as a newer one. */
    private fun apkFile(release: AppRelease): Path = updatesDir.resolve("triplea-mobile-${release.version}.apk")

    val installedVersion: String by lazy {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: ""
    }

    /** Builds from a developer's machine have no channel to update from. */
    val canUpdate: Boolean get() = installedVersion.startsWith("dev-") || parseVersion(installedVersion) != null

    /** The version the user chose to skip; it is not advertised again, a later one is. */
    val skippedVersion: String? get() = prefs.getString("skipped", null)

    /** Checks GitHub; without [force] at most once a day and only while the setting is on. */
    fun check(force: Boolean = false) {
        if (!canUpdate || job?.isActive == true) return
        if (!force) {
            if (!AppSettings.current.checkForUpdates) return
            // an offer, a download or its error with Retry stays until the user acts on it
            if (_state.value.let { it is UpdateState.Available || it is UpdateState.Downloading || (it is UpdateState.Failed && it.release != null) }) return
        }
        // preferences and files are read in here, off the UI thread
        job = scope.launch {
            val now = System.currentTimeMillis()
            val sinceLast = now - prefs.getLong("lastCheck", 0)
            // a clock set back makes sinceLast negative: check again then
            if (!force && sinceLast in 0 until CHECK_INTERVAL_MS) {
                // a release found by the last check is still shown after a restart
                val cached = prefs.getString("available", null)?.let { runCatching { fromJson(JSONObject(it)) }.getOrNull() }
                if (cached != null && isNewer(cached.version)) {
                    _state.value = UpdateState.Available(cached, isDownloaded(cached))
                } else if (cached == null || cached.version == skippedVersion || !isNewer(cached.version, installedVersion)) {
                    // just updated through the app, or skipped: its APK is no longer needed
                    deleteDownloads()
                }
                return@launch
            }
            // an offer already on screen survives a check that fails or finds nothing installable
            val previous = _state.value.takeIf { it is UpdateState.Available || (it is UpdateState.Failed && it.release != null) }
            _state.value = UpdateState.Checking
            _state.value = try {
                val release = fetchNewest()
                if (release == null) {
                    // GitHub answered without an APK we can check (asset being replaced, no digest yet):
                    // keep the saved offer and its download, and look again in an hour
                    prefs.edit { putLong("lastCheck", now - CHECK_INTERVAL_MS + RETRY_MS) }
                    _state.value = previous ?: UpdateState.Failed("No installable build found on GitHub")
                    return@launch
                }
                prefs.edit {
                    putLong("lastCheck", now)
                    putString("available", toJson(release).toString())
                    // "Check now" brings a skipped version back, once GitHub has answered
                    if (force) remove("skipped")
                }
                when {
                    isNewer(release.version) -> UpdateState.Available(release, isDownloaded(release))
                    // newer but skipped: nothing to show, and not "newest version" either; its download goes
                    isNewer(release.version, installedVersion) -> UpdateState.Idle.also { deleteDownloads() }
                    else -> {
                        // an installed update leaves its APK behind
                        deleteDownloads()
                        UpdateState.UpToDate
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Update check failed", e)
                // offline or rate limited: the next automatic try in an hour, "Check now" any time
                prefs.edit { putLong("lastCheck", now - CHECK_INTERVAL_MS + RETRY_MS) }
                previous ?: UpdateState.Failed(e.message ?: "Could not reach GitHub")
            }
        }
    }

    fun skip(release: AppRelease) {
        prefs.edit { putString("skipped", release.version) }
        // a running download stops; the job may still be in a blocking read or the checksum, so the
        // state is settled once it ended. Its file goes at the next check, not now: the Android
        // installer may be reading it at this moment
        val running = job
        _state.value = UpdateState.Idle
        scope.launch {
            running?.cancelAndJoin()
            _state.value = UpdateState.Idle
        }
    }

    private fun deleteDownloads() {
        runCatching { Files.list(updatesDir).use { files -> files.forEach { Files.deleteIfExists(it) } } }
    }

    /**
     * Downloads the APK, checks its size and checksum and opens the system installer. A retry after
     * a failed download asks GitHub first: the offer may be out of date (a newer build replaced it).
     */
    fun download(offered: AppRelease) {
        if (job?.isActive == true) return
        val retry = _state.value is UpdateState.Failed
        _state.value = UpdateState.Downloading(offered, 0)
        job = scope.launch {
            // a verified download only needs the installer again; otherwise the offer may be stale
            val release = if (retry && !isDownloaded(offered)) {
                runCatching { fetchNewest() }.getOrNull()?.takeIf { isNewer(it.version) }
                    ?.also { fresh -> prefs.edit { putString("available", toJson(fresh).toString()) } } ?: offered
            } else {
                offered
            }
            _state.value = UpdateState.Downloading(release, 0)
            val apkFile = apkFile(release)
            // downloaded before and the installer closed: install again without a new download
            if (!isDownloaded(release)) {
                // the APK gets its final name only once it is complete and checked
                val part = apkFile.resolveSibling("${apkFile.fileName}.part")
                try {
                    Files.createDirectories(updatesDir)
                    // older downloads are of no use any more
                    deleteDownloads()
                    MapDownloadManager.downloadFile(release.url, part, release.size) { bytes, _ ->
                        if (bytes > release.size) throw IOException("The download is larger than announced")
                        // skipped meanwhile: no progress over the Idle that skip() set
                        ensureActive()
                        _state.value = UpdateState.Downloading(release, bytes)
                    }
                    verify(part, release)
                    ensureActive()
                    Files.move(part, apkFile, StandardCopyOption.REPLACE_EXISTING)
                } catch (e: Exception) {
                    runCatching { Files.deleteIfExists(part) }
                    // Skip cancelled the download: no error card for that
                    if (e is CancellationException) throw e
                    Log.w(TAG, "Update download failed", e)
                    _state.value = UpdateState.Failed(e.message ?: "Download failed", release)
                    return@launch
                }
            }
            // Skip may have cancelled while the checksum was computed (no suspension point there)
            ensureActive()
            _state.value = UpdateState.Available(release, downloaded = true)
            // a failed installer start keeps the checked download for the next try; Android also
            // drops the start silently while the app is in the background, then "Install" opens it
            runCatching { install(apkFile) }.onFailure {
                Log.w(TAG, "Opening the installer failed", it)
                _state.value = UpdateState.Failed("Could not open the Android installer", release)
            }
        }
    }

    private fun isDownloaded(release: AppRelease): Boolean {
        val file = apkFile(release)
        return Files.exists(file) && runCatching { verify(file, release) }.isSuccess
    }

    private fun install(apkFile: Path) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile.toFile())
        // Android asks once whether this app may install apps, then shows its installer;
        // it only accepts an APK signed with the same key as the installed app
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    private fun verify(file: Path, release: AppRelease) {
        if (Files.size(file) != release.size) throw IOException("The download is incomplete")
        val expected = release.sha256 ?: return
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (!actual.equals(expected, ignoreCase = true)) throw IOException("The download is damaged (checksum mismatch)")
    }

    private fun isNewer(version: String): Boolean = version != skippedVersion && isNewer(version, installedVersion)

    private fun fetchNewest(): AppRelease? {
        val dev = installedVersion.startsWith("dev-")
        val json = JSONObject(MapDownloadManager.fetchText(if (dev) "$API/tags/dev" else "$API/latest"))
        return parseRelease(json, dev)
    }

    private fun toJson(release: AppRelease) = JSONObject()
        .put("version", release.version).put("url", release.url)
        .put("size", release.size).put("sha256", release.sha256 ?: "")

    private fun fromJson(json: JSONObject) = AppRelease(
        json.getString("version"), json.getString("url"), json.getLong("size"),
        json.optString("sha256").takeUnless { it.isEmpty() || it == "null" },
    )

    /** What changed between the installed version and [release]: the commit titles in between, newest first. */
    suspend fun changes(release: AppRelease): List<Change> = withContext(Dispatchers.IO) {
        val (base, head) = compareRefs(installedVersion, release.version) ?: throw IOException("No change list for this version")
        parseChanges(JSONObject(MapDownloadManager.fetchText("$REPO_API/compare/$base...$head")))
    }

    // ---- pure parts, unit tested --------------------------------------------------------------

    /** The git refs to compare: release tags (`v0.4.0...v0.4.2`) or the commits of two dev builds. */
    internal fun compareRefs(installed: String, candidate: String): Pair<String, String>? {
        if (installed.startsWith("dev-") && candidate.startsWith("dev-")) {
            val commit = { v: String -> v.substringAfterLast('-').takeIf { Regex("[0-9a-f]{7,40}").matches(it) } }
            return (commit(installed) ?: return null) to (commit(candidate) ?: return null)
        }
        if (parseVersion(installed) == null || parseVersion(candidate) == null) return null
        return "v$installed" to "v$candidate"
    }

    /**
     * The GitHub compare answer as a change list for players: the first line of each commit,
     * newest first, without merges and build/CI/docs housekeeping; `feat:` is new, `fix:` fixed.
     */
    internal fun parseChanges(json: JSONObject): List<Change> {
        val commits = json.optJSONArray("commits") ?: return emptyList()
        // only conventional-commit types: "Battle screen: ..." keeps its first words
        val prefix = Regex("""^(feat|fix|perf|revert|ci|docs|chore|test|build|style|refactor)(\(([^)]*)\))?!?:\s*""", RegexOption.IGNORE_CASE)
        val housekeeping = setOf("ci", "docs", "chore", "test", "build", "style", "refactor")
        return (0 until commits.length()).mapNotNull { i ->
            val title = commits.optJSONObject(i)?.optJSONObject("commit")?.optString("message")
                ?.lineSequence()?.firstOrNull()?.trim().orEmpty()
            if (title.isEmpty() || title.startsWith("Merge ")) return@mapNotNull null
            val match = prefix.find(title)
            val type = match?.groupValues?.get(1)?.lowercase()
            val scope = match?.groupValues?.get(3)?.lowercase()
            if (type in housekeeping || scope in housekeeping) return@mapNotNull null
            val text = title.substring(match?.range?.last?.plus(1) ?: 0).replaceFirstChar { it.uppercase() }
            val kind = when (type) {
                "feat" -> Change.Kind.NEW
                "fix" -> Change.Kind.FIXED
                else -> Change.Kind.CHANGED
            }
            if (text.isBlank()) null else Change(kind, text)
        }.reversed().distinctBy { it.text }
    }

    /** "0.4.2" or "v0.4.2" as numbers; null for anything else ("0.0.0-local", "dev-..."). */
    internal fun parseVersion(version: String): List<Int>? =
        Regex("""v?(\d+)\.(\d+)\.(\d+)""").matchEntire(version.trim())?.groupValues?.drop(1)?.map { it.toInt() }

    /** Release builds compare version numbers; dev builds (`dev-<yyyyMMdd>-<commit>`) the day. */
    internal fun isNewer(candidate: String, installed: String): Boolean {
        if (installed.startsWith("dev-")) {
            val day = { v: String -> v.removePrefix("dev-").substringBefore('-') }
            // a build of the same day may be older (all dev builds share one versionCode): wait for the next day
            return candidate.startsWith("dev-") && day(candidate) > day(installed)
        }
        val a = parseVersion(candidate) ?: return false
        val b = parseVersion(installed) ?: return false
        return a.zip(b).firstOrNull { (x, y) -> x != y }?.let { (x, y) -> x > y } ?: false
    }

    /**
     * The release's APK: `triplea-mobile.apk` of an official release (its version is the tag), or
     * for the dev release the dated asset with the same checksum as `triplea-mobile-dev.apk`
     * (`triplea-mobile-dev-<date>-<commit>.apk`, its name is the version).
     */
    internal fun parseRelease(json: JSONObject, dev: Boolean): AppRelease? {
        val assets = json.optJSONArray("assets") ?: return null
        val all = (0 until assets.length()).mapNotNull { assets.optJSONObject(it) }
        val apk = all.firstOrNull { it.optString("name") == if (dev) "triplea-mobile-dev.apk" else "triplea-mobile.apk" } ?: return null
        // no checksum, no offer: every APK is checked before it reaches the installer
        val digest = digestOf(apk) ?: return null
        // dev: the dated twin, which a later push does not replace (the rolling file would no
        // longer match the checksum of an offer made before that push)
        val file = if (dev) all.firstOrNull { it !== apk && digestOf(it) == digest } ?: return null else apk
        val version = if (dev) {
            file.optString("name").removePrefix("triplea-mobile-").removeSuffix(".apk")
        } else {
            json.optString("tag_name").removePrefix("v")
        }
        val url = file.optString("browser_download_url")
        val size = file.optLong("size")
        if (!url.startsWith("https://") || size <= 0) return null
        return AppRelease(version, url, size, digest)
    }

    /** An asset's SHA-256, or null; Android's org.json turns `"digest": null` into the text "null". */
    private fun digestOf(asset: JSONObject): String? =
        if (asset.isNull("digest")) null else asset.optString("digest").removePrefix("sha256:").ifEmpty { null }
}
