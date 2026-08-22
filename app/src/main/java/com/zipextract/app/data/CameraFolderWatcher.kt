package com.zipextract.app.data

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.FileObserver
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import java.io.File

/**
 * Watches DCIM / Camera / Pictures folders at the filesystem level so a new photo
 * appears in the UI as soon as the camera finishes writing — without waiting for
 * MediaStore indexing (often several seconds on OEM devices).
 */
class CameraFolderWatcher(
    context: Context,
    private val onFilesChanged: (List<File>) -> Unit,
) {
    private val appContext = context.applicationContext
    private val thread = HandlerThread("filenest-camera-watch").apply { start() }
    private val handler = Handler(thread.looper)
    private val observers = mutableListOf<FileObserver>()
    private val pending = LinkedHashMap<String, File>()
    private var started = false
    @Volatile
    private var lastFlushElapsedMs: Long = 0L

    private val flushRunnable = Runnable { flushPending() }

    fun start() {
        if (started) return
        started = true
        watchRoots().forEach { dir ->
            runCatching { startObserver(dir) }.getOrNull()?.let { observers += it }
        }
    }

    fun stop() {
        if (!started) return
        started = false
        handler.removeCallbacksAndMessages(null)
        observers.forEach { runCatching { it.stopWatching() } }
        observers.clear()
        pending.clear()
        thread.quitSafely()
    }

    private fun watchRoots(): List<File> {
        val storage = Environment.getExternalStorageDirectory()
        val roots = mutableListOf(
            File(storage, "DCIM"),
            File(storage, "DCIM/Camera"),
            File(storage, "DCIM/100ANDRO"),
            File(storage, "Pictures"),
            File(storage, "Pictures/Camera"),
            File(storage, "Pictures/Screenshots"),
            File(storage, "Screenshots"),
        )
        // Secondary volumes (microSD) when present.
        runCatching {
            FileOperations.listDeviceStorages(appContext)
                .filter { !it.isPrimary && it.canBrowse }
                .mapNotNull { it.root }
                .forEach { vol ->
                    roots += File(vol, "DCIM")
                    roots += File(vol, "DCIM/Camera")
                    roots += File(vol, "Pictures")
                }
        }
        return roots
            .filter { it.isDirectory }
            .distinctBy { runCatching { it.canonicalPath }.getOrDefault(it.absolutePath) }
    }

    private fun startObserver(dir: File): FileObserver {
        val mask = FileObserver.CREATE or
            FileObserver.MOVED_TO or
            FileObserver.CLOSE_WRITE or
            FileObserver.MODIFY
        val observer = createObserver(dir, mask) { path ->
            if (path.isNullOrBlank()) return@createObserver
            if (path.startsWith(".")) return@createObserver
            val lower = path.lowercase()
            if (lower.endsWith(".tmp") || lower.endsWith(".pending") ||
                lower.endsWith(".part") || lower.endsWith(".nomedia")
            ) {
                return@createObserver
            }
            val file = File(dir, path)
            if (!looksLikeMedia(file)) return@createObserver
            handler.post {
                pending[file.absolutePath] = file
                // Trailing debounce: wait until camera finishes writing the file.
                handler.removeCallbacks(flushRunnable)
                handler.postDelayed(flushRunnable, FLUSH_DEBOUNCE_MS)
            }
        }
        observer.startWatching()
        return observer
    }

    private fun flushPending() {
        if (pending.isEmpty()) return
        val now = SystemClock.elapsedRealtime()
        // Avoid flooding the UI when the camera writes dozens of EXIF side-cars.
        if (now - lastFlushElapsedMs < MIN_FLUSH_GAP_MS && pending.size < 4) {
            handler.postDelayed(flushRunnable, MIN_FLUSH_GAP_MS)
            return
        }
        lastFlushElapsedMs = now
        val batch = pending.values.toList()
        pending.clear()
        val ready = batch.mapNotNull { file ->
            val resolved = resolveReadyFile(file) ?: return@mapNotNull null
            resolved
        }
        if (ready.isNotEmpty()) {
            onFilesChanged(ready)
        }
    }

    /**
     * Camera apps often write a temp name then rename, or leave a 0-byte placeholder
     * briefly. Prefer an existing non-empty media file next to the notified path.
     */
    private fun resolveReadyFile(file: File): File? {
        if (file.isFile && file.length() > 0L && looksLikeMedia(file)) return file
        // Still being written — keep a short retry via pending re-queue.
        if (file.exists() && looksLikeMedia(file)) {
            handler.postDelayed({
                if (file.isFile && file.length() > 0L) {
                    pending[file.absolutePath] = file
                    handler.removeCallbacks(flushRunnable)
                    handler.postDelayed(flushRunnable, 120L)
                }
            }, 350L)
        }
        return null
    }

    private fun looksLikeMedia(file: File): Boolean {
        val ext = file.extension.lowercase()
        return ext in IMAGE_EXT || ext in VIDEO_EXT
    }

    private fun createObserver(
        dir: File,
        mask: Int,
        onEvent: (String?) -> Unit,
    ): FileObserver {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            object : FileObserver(dir, mask) {
                override fun onEvent(event: Int, path: String?) = onEvent(path)
            }
        } else {
            @Suppress("DEPRECATION")
            object : FileObserver(dir.absolutePath, mask) {
                override fun onEvent(event: Int, path: String?) = onEvent(path)
            }
        }
    }

    companion object {
        private const val FLUSH_DEBOUNCE_MS = 180L
        private const val MIN_FLUSH_GAP_MS = 120L
        private val IMAGE_EXT = setOf(
            "jpg", "jpeg", "png", "webp", "heic", "heif", "dng", "raw", "bmp", "gif",
        )
        private val VIDEO_EXT = setOf(
            "mp4", "mov", "mkv", "webm", "3gp", "avi",
        )
    }
}
