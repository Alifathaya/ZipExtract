package com.zipextract.app.data

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.provider.MediaStore

data class MediaStoreChange(
    val source: String,
    val uri: Uri?,
)

/**
 * Watches MediaStore for newly indexed downloads / photos / videos so the UI can
 * insert the changed row without rescanning storage.
 */
class MediaChangeWatcher(
    context: Context,
    private val onChanged: (MediaStoreChange) -> Unit,
) {
    private val appContext = context.applicationContext
    private val thread = HandlerThread("filenest-media-watch").apply { start() }
    private val handler = Handler(thread.looper)
    private var started = false
    @Volatile
    private var lastEmitElapsedMs: Long = 0L

    private val observer = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            onChange(selfChange, null)
        }

        override fun onChange(selfChange: Boolean, uri: Uri?) {
            // Debounce bursty MediaStore notifications (download progress, scanners).
            val now = SystemClock.elapsedRealtime()
            if (now - lastEmitElapsedMs < EMIT_DEBOUNCE_MS) return
            lastEmitElapsedMs = now
            val source = when {
                uri == null -> "media"
                uri.toString().contains("images", ignoreCase = true) -> "images"
                uri.toString().contains("video", ignoreCase = true) -> "video"
                uri.toString().contains("download", ignoreCase = true) -> "downloads"
                else -> "files"
            }
            handler.post { onChanged(MediaStoreChange(source, uri)) }
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
        thread.quitSafely()
    }

    companion object {
        private const val EMIT_DEBOUNCE_MS = 700L
    }
}
