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
 * Watches hot “inbox” folders (camera, downloads, WhatsApp, Telegram, …) at the
 * filesystem level so new files appear in the UI as soon as they finish writing —
 * without waiting for MediaStore indexing or a full storage rescan.
 *
 * FileObserver is not recursive, so WhatsApp/Telegram Media subfolders are expanded
 * one level deep when present.
 */
class InboxFolderWatcher(
    context: Context,
    private val onFilesChanged: (List<File>) -> Unit,
) {
    private val appContext = context.applicationContext
    private val thread = HandlerThread("filenest-inbox-watch").apply { start() }
    private val handler = Handler(thread.looper)
    private val observers = mutableListOf<FileObserver>()
    private val watchedPaths = LinkedHashSet<String>()
    private val pending = LinkedHashMap<String, File>()
    private var started = false
    @Volatile
    private var lastFlushElapsedMs: Long = 0L
    @Volatile
    private var pendingIsDownloadHeavy: Boolean = false

    private val flushRunnable = Runnable { flushPending() }

    fun start() {
        if (started) return
        started = true
        watchRoots().forEach { dir ->
            runCatching { startObserver(dir) }
        }
    }

    fun stop() {
        if (!started) return
        started = false
        handler.removeCallbacksAndMessages(null)
        observers.forEach { runCatching { it.stopWatching() } }
        observers.clear()
        watchedPaths.clear()
        pending.clear()
        thread.quitSafely()
    }

    private fun watchRoots(): List<File> {
        val storage = Environment.getExternalStorageDirectory()
        val seeds = mutableListOf<File>()

        fun add(path: String) {
            seeds += File(storage, path)
        }

        // Camera / gallery
        add("DCIM")
        add("DCIM/Camera")
        add("DCIM/100ANDRO")
        add("Pictures")
        add("Pictures/Camera")
        add("Pictures/Screenshots")
        add("Screenshots")
        add("Movies")
        add("Video")
        add("Music")
        add("Podcasts")
        add("Recordings")
        add("Documents")
        add("Bluetooth")

        // Browser downloads
        add("Download")
        add("Downloads")
        seeds += FileOperations.downloadWatchCandidates(storage) // Opera / Chrome / Firefox

        // WhatsApp (legacy + scoped storage)
        add("WhatsApp")
        add("WhatsApp/Media")
        add("Android/media/com.whatsapp")
        add("Android/media/com.whatsapp/WhatsApp")
        add("Android/media/com.whatsapp/WhatsApp/Media")
        add("Android/media/com.whatsapp.w4b")
        add("Android/media/com.whatsapp.w4b/WhatsApp Business")
        add("Android/media/com.whatsapp.w4b/WhatsApp Business/Media")

        // Telegram
        add("Telegram")
        add("Telegram/Telegram Images")
        add("Telegram/Telegram Video")
        add("Telegram/Telegram Documents")
        add("Telegram/Telegram Audio")
        add("Android/media/org.telegram.messenger")
        add("Android/media/org.telegram.messenger/Telegram")

        // Snapchat / misc messengers that land in shared storage
        add("Snapchat")
        add("Android/media/com.instagram.android")
        add("Android/media/com.facebook.orca")

        // Secondary volumes (microSD / USB)
        runCatching {
            FileOperations.listDeviceStorages(appContext)
                .filter { !it.isPrimary && it.canBrowse }
                .mapNotNull { it.root }
                .forEach { vol ->
                    seeds += File(vol, "DCIM")
                    seeds += File(vol, "DCIM/Camera")
                    seeds += File(vol, "Pictures")
                    seeds += File(vol, "Download")
                    seeds += File(vol, "Downloads")
                    seeds += File(vol, "WhatsApp")
                    seeds += File(vol, "WhatsApp/Media")
                    seeds += File(vol, "Telegram")
                    seeds += FileOperations.downloadWatchCandidates(vol)
                }
        }

        val expanded = LinkedHashSet<File>()
        seeds.forEach { seed ->
            expandHotLeaves(seed).forEach { expanded += it }
        }

        return expanded
            .filter { it.isDirectory }
            .distinctBy { runCatching { it.canonicalPath }.getOrDefault(it.absolutePath) }
            .take(MAX_WATCHED_DIRS)
    }

    /**
     * FileObserver is not recursive. Expand Media / messenger trees one level so
     * “WhatsApp Images”, “WhatsApp Video”, etc. are watched directly.
     */
    private fun expandHotLeaves(root: File): List<File> {
        if (!root.isDirectory) return emptyList()
        val out = mutableListOf(root)
        val children = root.listFiles()
            ?.asSequence()
            ?.filter { it.isDirectory && !it.name.startsWith('.') }
            ?.toList()
            .orEmpty()

        val mediaChild = children.firstOrNull { it.name.equals("Media", ignoreCase = true) }
        if (mediaChild != null) {
            out += mediaChild
            mediaChild.listFiles()
                ?.asSequence()
                ?.filter { it.isDirectory && !it.name.startsWith('.') }
                ?.take(24)
                ?.forEach { out += it }
        }

        // Direct leaves that already look like content sinks.
        children.asSequence()
            .filter { child ->
                val n = child.name.lowercase()
                n.contains("image") || n.contains("video") || n.contains("document") ||
                    n.contains("audio") || n.contains("download") || n.contains("camera") ||
                    n.contains("screenshot") || n.contains("whatsapp") || n.contains("telegram")
            }
            .take(16)
            .forEach { out += it }

        return out
    }

    private fun startObserver(dir: File) {
        val key = runCatching { dir.canonicalPath }.getOrDefault(dir.absolutePath)
        if (!watchedPaths.add(key)) return

        val mask = FileObserver.CREATE or
            FileObserver.MOVED_TO or
            FileObserver.CLOSE_WRITE or
            FileObserver.MODIFY
        val observer = createObserver(dir, mask) { path ->
            if (path.isNullOrBlank()) return@createObserver
            if (path.startsWith(".")) return@createObserver
            val lower = path.lowercase()
            if (TEMP_SUFFIXES.any { lower.endsWith(it) }) return@createObserver
            val file = File(dir, path)
            if (file.isDirectory) {
                // New subfolder under a watched tree (e.g. first WhatsApp album) —
                // start watching it too, capped by MAX_WATCHED_DIRS.
                if (watchedPaths.size < MAX_WATCHED_DIRS) {
                    handler.post { runCatching { startObserver(file) } }
                }
                return@createObserver
            }
            handler.post {
                pending[file.absolutePath] = file
                if (isUnderDownloads(file)) pendingIsDownloadHeavy = true
                handler.removeCallbacks(flushRunnable)
                val delay = if (pendingIsDownloadHeavy) DOWNLOAD_FLUSH_DEBOUNCE_MS else FLUSH_DEBOUNCE_MS
                handler.postDelayed(flushRunnable, delay)
            }
        }
        observer.startWatching()
        observers += observer
    }

    private fun flushPending() {
        if (pending.isEmpty()) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastFlushElapsedMs < MIN_FLUSH_GAP_MS && pending.size < 6) {
            handler.postDelayed(flushRunnable, MIN_FLUSH_GAP_MS)
            return
        }
        lastFlushElapsedMs = now
        val batch = pending.values.toList()
        pending.clear()
        val downloadHeavy = pendingIsDownloadHeavy
        pendingIsDownloadHeavy = false
        val ready = batch.mapNotNull { resolveReadyFile(it, allowRetry = true) }
        if (ready.isNotEmpty()) {
            onFilesChanged(ready)
        }
        // Downloads often grow for a while after CLOSE_WRITE; one quick size refresh.
        if (downloadHeavy && ready.isNotEmpty()) {
            handler.postDelayed({
                val refreshed = ready.mapNotNull { resolveReadyFile(it, allowRetry = false) }
                if (refreshed.isNotEmpty()) onFilesChanged(refreshed)
            }, 700L)
        }
    }

    private fun resolveReadyFile(file: File, allowRetry: Boolean): File? {
        if (file.isFile && file.length() > 0L && isInterestingFile(file)) return file
        if (allowRetry && file.exists() && !file.isDirectory) {
            handler.postDelayed({
                if (file.isFile && file.length() > 0L && isInterestingFile(file)) {
                    pending[file.absolutePath] = file
                    handler.removeCallbacks(flushRunnable)
                    handler.postDelayed(flushRunnable, 120L)
                }
            }, 400L)
        }
        return null
    }

    private fun isInterestingFile(file: File): Boolean {
        val name = file.name
        if (name.startsWith('.')) return false
        val lower = name.lowercase()
        if (TEMP_SUFFIXES.any { lower.endsWith(it) }) return false
        // Accept any real file in watched inboxes (images, zips, apk, pdf, unknown downloads).
        return true
    }

    private fun isUnderDownloads(file: File): Boolean {
        val path = file.absolutePath.lowercase()
        return path.contains("/download") || path.contains("/opera")
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
        private const val FLUSH_DEBOUNCE_MS = 160L
        private const val DOWNLOAD_FLUSH_DEBOUNCE_MS = 320L
        private const val MIN_FLUSH_GAP_MS = 100L
        private const val MAX_WATCHED_DIRS = 48
        private val TEMP_SUFFIXES = listOf(
            ".tmp", ".pending", ".part", ".partial", ".crdownload", ".download",
            ".nomedia", ".bak", ".temp",
        )
    }
}
