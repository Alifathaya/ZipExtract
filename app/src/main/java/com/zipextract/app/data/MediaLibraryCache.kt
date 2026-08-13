package com.zipextract.app.data

import android.content.Context
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.FileReader
import java.io.FileWriter

/**
 * Temporary on-disk snapshot of [MediaLibrary] so cold start / reopen can show home
 * and categories instantly without a full storage rescan.
 */
object MediaLibraryCache {
    private const val CACHE_FILE = "media_library_cache_v1.txt"
    private const val META_FILE = "media_library_cache_meta_v1.txt"
    private const val FORMAT_VERSION = 1

    /** Soft refresh threshold: still show cache instantly, rescan in background after this. */
    const val SOFT_REFRESH_AFTER_MS = 30 * 1000L

    /** Hard expiry: ignore disk cache and force rescan. */
    const val HARD_EXPIRE_MS = 7 * 24 * 60 * 60 * 1000L

    /** Minimum gap between automatic background rescans (onResume / open category). */
    const val RESUME_REFRESH_DEBOUNCE_MS = 5 * 1000L

    /** Faster gap when MediaStore reports a new download / photo. */
    const val MEDIA_OBSERVER_DEBOUNCE_MS = 1_200L

    fun cacheFile(context: Context): File =
        File(context.applicationContext.filesDir, CACHE_FILE)

    fun metaFile(context: Context): File =
        File(context.applicationContext.filesDir, META_FILE)

    fun exists(context: Context): Boolean = cacheFile(context).isFile && cacheFile(context).length() > 0L

    fun savedAtMs(context: Context): Long {
        return runCatching {
            metaFile(context).takeIf { it.isFile }?.readText()?.trim()?.toLongOrNull()
        }.getOrNull() ?: cacheFile(context).takeIf { it.isFile }?.lastModified() ?: 0L
    }

    fun ageMs(context: Context): Long {
        val saved = savedAtMs(context)
        if (saved <= 0L) return Long.MAX_VALUE
        return (System.currentTimeMillis() - saved).coerceAtLeast(0L)
    }

    fun isSoftStale(context: Context): Boolean = ageMs(context) >= SOFT_REFRESH_AFTER_MS

    fun isHardExpired(context: Context): Boolean = ageMs(context) >= HARD_EXPIRE_MS

    fun save(context: Context, library: MediaLibrary) {
        val app = context.applicationContext
        val target = cacheFile(app)
        val tmp = File(app.filesDir, "$CACHE_FILE.tmp")
        runCatching {
            BufferedWriter(FileWriter(tmp, false)).use { out ->
                out.append("v=").append(FORMAT_VERSION.toString()).append('\n')
                writeCategory(out, "DOWNLOADS", library.downloads)
                writeCategory(out, "IMAGES", library.images)
                writeCategory(out, "VIDEOS", library.videos)
                writeCategory(out, "DOCUMENTS", library.documents)
                writeCategory(out, "ARCHIVES", library.archives)
                writeCategory(out, "APPS", library.apps)
                writeCategory(out, "OTHERS", library.others)
            }
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
            metaFile(app).writeText(System.currentTimeMillis().toString())
        }.onFailure {
            runCatching { tmp.delete() }
        }
    }

    fun load(context: Context): MediaLibrary? {
        val app = context.applicationContext
        if (!exists(app) || isHardExpired(app)) return null
        return runCatching {
            BufferedReader(FileReader(cacheFile(app))).use { reader ->
                val header = reader.readLine() ?: return null
                if (!header.startsWith("v=$FORMAT_VERSION")) return null

                val buckets = LinkedHashMap<String, MutableList<FileItem>>()
                var current: MutableList<FileItem>? = null

                while (true) {
                    val line = reader.readLine() ?: break
                    when {
                        line.startsWith("#") -> {
                            val name = line.removePrefix("#").trim()
                            current = buckets.getOrPut(name) { mutableListOf() }
                        }
                        line.isBlank() -> Unit
                        else -> {
                            val list = current ?: continue
                            parseItem(line)?.let { list.add(it) }
                        }
                    }
                }

                MediaLibrary(
                    downloads = buckets["DOWNLOADS"].orEmpty(),
                    images = buckets["IMAGES"].orEmpty(),
                    videos = buckets["VIDEOS"].orEmpty(),
                    documents = buckets["DOCUMENTS"].orEmpty(),
                    archives = buckets["ARCHIVES"].orEmpty(),
                    apps = buckets["APPS"].orEmpty(),
                    others = buckets["OTHERS"].orEmpty(),
                )
            }
        }.getOrNull()
    }

    fun clear(context: Context) {
        runCatching { cacheFile(context).delete() }
        runCatching { metaFile(context).delete() }
    }

    private fun writeCategory(out: BufferedWriter, name: String, items: List<FileItem>) {
        out.append('#').append(name).append('\n')
        items.forEach { item ->
            // path may contain '|' rarely; encode newlines only — paths shouldn't have newlines.
            val safePath = item.path.replace('\n', ' ').replace('\r', ' ')
            out.append(safePath)
                .append('|')
                .append(item.sizeBytes.toString())
                .append('|')
                .append(item.lastModified.toString())
                .append('\n')
        }
    }

    private fun parseItem(line: String): FileItem? {
        val parts = line.split('|')
        if (parts.size < 3) return null
        val path = parts[0]
        if (path.isBlank()) return null
        val file = File(path)
        if (!file.exists() || !file.isFile) return null
        val size = parts[1].toLongOrNull() ?: file.length()
        val modified = parts[2].toLongOrNull() ?: file.lastModified()
        return FileItem(
            file = file,
            name = file.name,
            path = file.absolutePath,
            isDirectory = false,
            sizeBytes = size,
            lastModified = modified,
        )
    }
}
