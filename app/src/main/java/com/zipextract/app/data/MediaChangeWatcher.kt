package com.zipextract.app.data

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore

data class MediaStoreChange(
    val source: String,
    val uri: Uri?,
)

/**
 * Watches MediaStore for newly indexed downloads / photos / videos so the UI can
 * insert the changed row without rescanning storage.
 *
 * Uses a short *trailing* debounce so camera save bursts (temp → final JPG) are not
 * dropped after the first noisy notification.
 */
class MediaChangeWatcher(
    context: Context,
    private val onChanged: (MediaStoreChange) -> Unit,
) {
    private val appContext = context.applicationContext
    private val thread = HandlerThread("filenest-media-watch").apply { start() }
    private val handler = Handler(thread.looper)
    private var started = false
    private var pending: MediaStoreChange? = null

    private val emitRunnable = Runnable {
        val change = pending ?: return@Runnable
        pending = null
        onChanged(change)
    }

    private val observer = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            onChange(selfChange, null)
        }

        override fun onChange(selfChange: Boolean, uri: Uri?) {
            val source = when {
                uri == null -> "media"
                uri.toString().contains("images", ignoreCase = true) -> "images"
                uri.toString().contains("video", ignoreCase = true) -> "video"
                uri.toString().contains("download", ignoreCase = true) -> "downloads"
                else -> "files"
            }
            // Prefer a concrete row URI over a broad collection notify when coalescing.
            val existing = pending
            pending = when {
                existing == null -> MediaStoreChange(source, uri)
                uri != null && uri.lastPathSegment?.toLongOrNull() != null ->
                    MediaStoreChange(source, uri)
                existing.uri != null -> existing.copy(source = preferSource(existing.source, source))
                else -> MediaStoreChange(preferSource(existing.source, source), uri)
            }
            handler.removeCallbacks(emitRunnable)
            val delay = if (source == "images" || source == "video") {
                IMAGE_DEBOUNCE_MS
            } else {
                EMIT_DEBOUNCE_MS
            }
            handler.postDelayed(emitRunnable, delay)
        }
    }

    fun start() {
        if (started) return
        started = true
        val resolver = appContext.contentResolver
        runCatching {
            resolver.registerContentObserver(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                true,
                observer,
            )
        }
        runCatching {
            resolver.registerContentObserver(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                true,
                observer,
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                resolver.registerContentObserver(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    true,
                    observer,
                )
            }
        }
        runCatching {
            resolver.registerContentObserver(
                MediaStore.Files.getContentUri("external"),
                true,
                observer,
            )
        }
    }

    fun stop() {
        if (!started) return
        started = false
        runCatching { appContext.contentResolver.unregisterContentObserver(observer) }
        handler.removeCallbacksAndMessages(null)
        pending = null
        thread.quitSafely()
    }

    private fun preferSource(a: String, b: String): String {
        val rank = mapOf("images" to 3, "video" to 3, "downloads" to 2, "files" to 1, "media" to 0)
        return if ((rank[b] ?: 0) >= (rank[a] ?: 0)) b else a
    }

    companion object {
        /** Trailing quiet-period before emitting a MediaStore burst. */
        private const val EMIT_DEBOUNCE_MS = 350L
        /** Camera / gallery writes settle faster when we react sooner. */
        private const val IMAGE_DEBOUNCE_MS = 160L
    }
}
