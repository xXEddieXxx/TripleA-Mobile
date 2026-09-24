package org.triplea.mobile.app

import android.content.Context
import android.util.Log
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

/**
 * Copies the zipped maps and engine images from the APK assets into the app's private storage.
 * Each zip is only unpacked once; a marker file records the bundled version.
 */
object MapInstaller {
    private const val TAG = "MapInstaller"
    private const val BUNDLE_VERSION = "2"

    fun installAll(context: Context, mapsFolder: Path, engineAssetsFolder: Path) {
        val assets = context.assets
        assets.list("maps").orEmpty().filter { it.endsWith(".zip") }.forEach { zipName ->
            val mapName = zipName.removeSuffix(".zip")
            install(context, "maps/$zipName", mapsFolder.resolve(mapName))
        }
        assets.list("engine").orEmpty().filter { it.endsWith(".zip") }.forEach { zipName ->
            install(context, "engine/$zipName", engineAssetsFolder)
        }
    }

    /**
     * Extracts [zip] into [target]. GitHub archives wrap everything in a "<repo>-master/" folder;
     * with [stripTopLevelFolder] that folder is dropped when every entry lives below the same one.
     */
    /** Sanity limits against zip bombs: no map needs more than this. */
    private const val MAX_ENTRIES = 60_000
    private const val MAX_TOTAL_BYTES = 3L * 1024 * 1024 * 1024

    fun unzip(zip: ZipFile, target: Path, stripTopLevelFolder: Boolean) {
        val entries = zip.entries().toList()
        if (entries.size > MAX_ENTRIES) throw IOException("The archive has too many entries (${entries.size})")
        val declared = entries.sumOf { it.size.coerceAtLeast(0) }
        if (declared > MAX_TOTAL_BYTES) throw IOException("The archive is too large to unpack (${declared / 1_048_576} MB)")
        val topLevel = if (stripTopLevelFolder) {
            val firstSegments = entries.map { it.name.replace('\\', '/').trimStart('/').substringBefore('/') }.distinct()
            val single = firstSegments.singleOrNull()
            if (single != null && entries.all { it.name.replace('\\', '/').trimStart('/').contains('/') || it.isDirectory }) single else null
        } else null
        Files.createDirectories(target)
        for (entry in entries) {
            var name = entry.name.replace('\\', '/').trimStart('/')
            if (topLevel != null) {
                name = name.removePrefix(topLevel).trimStart('/')
                if (name.isEmpty()) continue
            }
            val out = target.resolve(name).normalize()
            if (!out.startsWith(target)) throw IOException("Illegal zip entry: ${entry.name}")
            if (entry.isDirectory || name.endsWith("/")) {
                Files.createDirectories(out)
            } else {
                Files.createDirectories(out.parent)
                zip.getInputStream(entry).use { input ->
                    Files.newOutputStream(out).use { output -> copyBounded(input, output, MAX_TOTAL_BYTES) }
                }
            }
        }
    }

    /** Copies at most [limit] bytes; a zip entry lying about its size cannot fill the storage. */
    private fun copyBounded(input: java.io.InputStream, output: java.io.OutputStream, limit: Long) {
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > limit) throw IOException("Zip entry exceeds the size limit")
            output.write(buffer, 0, read)
        }
    }

    private fun install(context: Context, assetPath: String, target: Path) {
        val marker = target.resolve(".bundled_version_" + assetPath.substringAfterLast('/').removeSuffix(".zip"))
        if (Files.exists(marker) && runCatching { String(Files.readAllBytes(marker), Charsets.UTF_8).trim() }.getOrNull() == BUNDLE_VERSION) {
            return
        }
        Log.i(TAG, "Unpacking $assetPath to $target")
        try {
            Files.createDirectories(target)
            context.assets.open(assetPath).use { input ->
                ZipInputStream(input.buffered()).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        val name = entry.name.replace('\\', '/')
                        val out = target.resolve(name).normalize()
                        if (!out.startsWith(target)) {
                            throw IOException("Illegal zip entry: $name")
                        }
                        if (entry.isDirectory || name.endsWith("/")) {
                            Files.createDirectories(out)
                        } else {
                            Files.createDirectories(out.parent)
                            Files.newOutputStream(out).use { copyBounded(zip, it, MAX_TOTAL_BYTES) }
                        }
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                }
            }
            Files.write(marker, BUNDLE_VERSION.toByteArray(Charsets.UTF_8))
            // the plain marker keeps bundled maps recognisable in the map browser
            Files.write(target.resolve(".bundled_version"), BUNDLE_VERSION.toByteArray(Charsets.UTF_8))
        } catch (e: IOException) {
            Log.e(TAG, "Failed to unpack $assetPath", e)
        }
    }
}
