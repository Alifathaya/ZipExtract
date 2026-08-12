package com.zipextract.app.data

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.util.LruCache
import java.io.File
import kotlin.math.max

/**
 * Extracts a still frame from local video files for gallery thumbnails.
 * Uses [MediaMetadataRetriever] (more reliable than Coil for many devices/codecs)
 * with an in-memory LRU cache keyed by path + mtime + size.
 */
object VideoThumbnailLoader {

    private const val MAX_EDGE_PX = 512

    private val memoryCache: LruCache<String, Bitmap> = object : LruCache<String, Bitmap>(
        // ~8 MB of bitmaps (ARGB approx 4 bytes/px; entry sizes vary).
        ((Runtime.getRuntime().maxMemory() / 1024L) / 16L).toInt().coerceIn(4_096, 16_384),
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int {
            return (value.byteCount / 1024).coerceAtLeast(1)
        }
    }

    fun cacheKey(file: File): String {
        return "${file.absolutePath}|${file.lastModified()}|${file.length()}"
    }

    fun peek(file: File): Bitmap? {
        if (!file.exists() || !file.isFile || file.length() <= 0L) return null
        return synchronized(memoryCache) { memoryCache.get(cacheKey(file)) }
    }

    fun load(file: File): Bitmap? {
        if (!file.exists() || !file.isFile || file.length() <= 0L) return null
        val key = cacheKey(file)
        synchronized(memoryCache) {
            memoryCache.get(key)?.let { return it }
        }

        val extracted = extractFrame(file) ?: return null
        val scaled = scaleDown(extracted, MAX_EDGE_PX)
        if (scaled !== extracted) {
            runCatching { extracted.recycle() }
        }

        synchronized(memoryCache) {
            memoryCache.put(key, scaled)
        }
        return scaled
    }

    private fun extractFrame(file: File): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            // Prefer ~1s (more representative than a black first frame), then fall back.
            retriever.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.frameAtTime
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun scaleDown(source: Bitmap, maxEdge: Int): Bitmap {
        val largest = max(source.width, source.height)
        if (largest <= maxEdge || largest <= 0) return source
        val scale = maxEdge.toFloat() / largest.toFloat()
        val w = (source.width * scale).toInt().coerceAtLeast(1)
        val h = (source.height * scale).toInt().coerceAtLeast(1)
        return runCatching {
            Bitmap.createScaledBitmap(source, w, h, true)
        }.getOrDefault(source)
    }
}
