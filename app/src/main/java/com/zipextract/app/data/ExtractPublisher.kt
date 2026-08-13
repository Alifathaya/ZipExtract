package com.zipextract.app.data

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Copies extracted files into a public, easy-to-find folder:
 * `Download/FileNest/<folderName>/…`
 *
 * Uses direct File I/O when possible, otherwise MediaStore Downloads (Android 10+).
 */
object ExtractPublisher {

    data class PublishResult(
        val destination: File,
        val fileCount: Int,
        val usedMediaStore: Boolean,
    )

    fun publicFileNestFolder(folderName: String): File {
        val safe = sanitizeFolderName(folderName)
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        return File(File(downloads, "FileNest"), safe)
    }

    fun sanitizeFolderName(name: String): String {
        return name.trim()
            .replace(Regex("""[\\/:*?"<>|]"""), "_")
            .ifBlank { "extract" }
            .take(80)
    }

    /**
     * Publish [stagingDir] contents into Download/FileNest/[folderName].
     * Returns the destination users should open.
     */
    fun publishToFileNest(
        context: Context,
        stagingDir: File,
        folderName: String,
    ): PublishResult {
        require(stagingDir.isDirectory) { "Staging extract tidak valid" }
        val stagedFiles = collectFiles(stagingDir)
        if (stagedFiles.isEmpty()) {
            error("Extract tidak menghasilkan file")
        }

        val destRoot = publicFileNestFolder(folderName)
        // Prefer unique folder so repeat extracts don't mix.
        val target = uniqueDir(destRoot)

        // 1) Try classic File copy (works with All Files Access).
        val fileCopyOk = runCatching {
            ensureDir(target)
            if (!ZipManager.probeWritable(target)) error("not writable")
            stagedFiles.forEach { (src, relative) ->
                val out = File(target, relative)
                out.parentFile?.let { ensureDir(it) }
                FileInputStream(src).use { input ->
                    FileOutputStream(out).use { output -> input.copyTo(output) }
                }
                if (!out.isFile || out.length() != src.length()) {
                    error("Gagal menyalin $relative")
                }
            }
            true
        }.getOrDefault(false)

        if (fileCopyOk) {
            return PublishResult(destination = target, fileCount = stagedFiles.size, usedMediaStore = false)
        }

        // 2) MediaStore Downloads (Android 10+) — visible in system Files/Download.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val relativeRoot = "Download/FileNest/${target.name}"
            var written = 0
            stagedFiles.forEach { (src, relative) ->
                val parentRel = relative.substringBeforeLast('/', missingDelimiterValue = "")
                val displayName = relative.substringAfterLast('/')
                val relPath = if (parentRel.isBlank()) {
                    "$relativeRoot/"
                } else {
                    "$relativeRoot/$parentRel/"
                }
                val mime = mimeFor(displayName)
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, displayName)
                    put(MediaStore.Downloads.MIME_TYPE, mime)
                    put(MediaStore.Downloads.RELATIVE_PATH, relPath)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: error("Gagal membuat entri MediaStore untuk $relative")
                resolver.openOutputStream(uri)?.use { output ->
                    FileInputStream(src).use { input -> input.copyTo(output) }
                } ?: error("Gagal menulis MediaStore untuk $relative")
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                written++
            }
            // Prefer opening the public folder when the File API can see it; otherwise keep staging
            // so in-app browsing still shows the extracted files.
            runCatching { ensureDir(target) }
            val visibleCount = target.listFiles()?.count { it.isFile || (it.isDirectory && (it.list()?.isNotEmpty() == true)) } ?: 0
            val openDir = if (visibleCount > 0) target else stagingDir
            return PublishResult(
                destination = openDir,
                fileCount = written,
                usedMediaStore = true,
            )
        }

        // 3) Last resort: keep staging (always readable by the app) and surface that path.
        return PublishResult(destination = stagingDir, fileCount = stagedFiles.size, usedMediaStore = false)
    }

    private fun uniqueDir(preferred: File): File {
        if (!preferred.exists()) return preferred
        var index = 2
        while (true) {
            val candidate = File(preferred.parentFile, "${preferred.name} ($index)")
            if (!candidate.exists()) return candidate
            index++
        }
    }

    private fun collectFiles(root: File): List<Pair<File, String>> {
        val out = mutableListOf<Pair<File, String>>()
        fun walk(dir: File, prefix: String) {
            val children = dir.listFiles() ?: return
            children.forEach { child ->
                val rel = if (prefix.isEmpty()) child.name else "$prefix/${child.name}"
                if (child.isDirectory) {
                    walk(child, rel)
                } else if (child.isFile && child.length() >= 0L) {
                    // Skip write probes.
                    if (child.name.startsWith(".filenest_write_probe_")) return@forEach
                    out += child to rel
                }
            }
        }
        walk(root, "")
        return out
    }

    private fun ensureDir(dir: File) {
        if (dir.isDirectory) return
        if (!dir.mkdirs() && !dir.isDirectory) {
            error("Tidak bisa membuat folder: ${dir.absolutePath}")
        }
    }

    private fun mimeFor(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext.isBlank()) return "application/octet-stream"
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: when (ext) {
                "jpg", "jpeg" -> "image/jpeg"
                "png" -> "image/png"
                "webp" -> "image/webp"
                "gif" -> "image/gif"
                "pdf" -> "application/pdf"
                "mp4" -> "video/mp4"
                "txt" -> "text/plain"
                "zip" -> "application/zip"
                else -> "application/octet-stream"
            }
    }
}
