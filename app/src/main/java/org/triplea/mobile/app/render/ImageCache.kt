package org.triplea.mobile.app.render

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import games.strategy.triplea.ResourceLoader
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Decodes map tiles and unit icons from the map folder (with the engine assets as fallback) and
 * keeps them in a memory bounded cache. Missing images are decoded on a background thread; the
 * [version] flow ticks whenever a new image is available so the map can redraw.
 */
class ImageCache(private val loader: ResourceLoader) {
    // a quarter of the heap, so large maps zoomed out do not evict tiles as fast as they load
    private val maxBytes = (Runtime.getRuntime().maxMemory() / 4).coerceIn(64L * 1024 * 1024, 512L * 1024 * 1024).toInt()
    private val cache = object : LruCache<String, Bitmap>(maxBytes) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val missing: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val loading: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val executor = Executors.newFixedThreadPool(2) { runnable ->
        Thread(runnable, "image-loader").apply { isDaemon = true }
    }
    private val notifier = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "image-notify").apply { isDaemon = true }
    }
    private val dirty = AtomicBoolean(false)

    private val _version = MutableStateFlow(0)
    val version = _version.asStateFlow()

    /** Redraw requests from the decoder threads are coalesced so a burst of tiles gives one redraw. */
    private fun markDirty() {
        if (dirty.compareAndSet(false, true)) {
            notifier.schedule({
                dirty.set(false)
                _version.value = _version.value + 1
            }, 40, TimeUnit.MILLISECONDS)
        }
    }

    private fun cacheKey(key: String, sampleSize: Int) = if (sampleSize <= 1) key else "$key@$sampleSize"

    /** True once a decode attempt found no file for [key]; such tiles are simply not drawn. */
    fun isMissing(key: String): Boolean = missing.contains(key)

    /**
     * Returns the image if it is already decoded, otherwise starts decoding and returns null.
     * [sampleSize] (1, 2, 4, ...) requests a downscaled decode for zoomed out views; while that
     * is loading, an already decoded version at another sample size is returned instead so
     * zooming never blanks tiles.
     */
    fun get(key: String, candidates: List<String> = listOf(key), sampleSize: Int = 1): Bitmap? =
        getDecoded(key, candidates, sampleSize)?.bitmap

    /** A decoded image and the sample size it was decoded at (its pixels are that many map pixels each). */
    class Decoded(val bitmap: Bitmap, val sampleSize: Int)

    /** Like [get], but also tells at which sample size the returned bitmap was decoded. */
    fun getDecoded(key: String, candidates: List<String> = listOf(key), sampleSize: Int = 1): Decoded? {
        val wanted = cacheKey(key, sampleSize)
        cache.get(wanted)?.let { return Decoded(it, sampleSize) }
        if (missing.contains(key)) return null
        if (loading.add(wanted)) {
            executor.execute {
                try {
                    val bitmap = decode(candidates, sampleSize)
                    if (bitmap == null) {
                        missing.add(key)
                    } else {
                        cache.put(wanted, bitmap)
                        markDirty()
                    }
                } finally {
                    loading.remove(wanted)
                }
            }
        }
        for (other in SAMPLE_SIZES) {
            if (other == sampleSize) continue
            cache.get(cacheKey(key, other))?.let { return Decoded(it, other) }
        }
        return null
    }

    /** Decodes synchronously; use for small icons shown in dialogs. */
    fun getNow(key: String, candidates: List<String> = listOf(key)): Bitmap? {
        cache.get(key)?.let { return it }
        if (missing.contains(key)) return null
        val bitmap = decode(candidates)
        if (bitmap == null) missing.add(key) else cache.put(key, bitmap)
        return bitmap
    }

    private fun decode(candidates: List<String>, sampleSize: Int = 1): Bitmap? {
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize.coerceAtLeast(1) }
        for (candidate in candidates) {
            val path = loader.optionalResource(candidate).orElse(null) ?: continue
            val bitmap = BitmapFactory.decodeFile(path.toString(), options) ?: continue
            return bitmap
        }
        return null
    }

    fun close() {
        executor.shutdownNow()
        notifier.shutdownNow()
        cache.evictAll()
    }

    companion object {
        val SAMPLE_SIZES = intArrayOf(1, 2, 4, 8)

        /** The decode sample size that keeps tiles at roughly screen resolution for a map scale. */
        fun sampleSizeFor(scale: Float): Int = when {
            scale <= 0.14f -> 8
            scale <= 0.27f -> 4
            scale <= 0.55f -> 2
            else -> 1
        }
    }
}
