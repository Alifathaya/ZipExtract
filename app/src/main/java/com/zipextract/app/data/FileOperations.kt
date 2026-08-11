package com.zipextract.app.data

import android.os.Environment
import android.os.StatFs
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

object FileOperations {

    fun defaultRoot(): File {
        return Environment.getExternalStorageDirectory()
    }

    fun samePath(left: File, right: File): Boolean {
        return runCatching {
            left.canonicalFile.absolutePath == right.canonicalFile.absolutePath
        }.getOrDefault(left.absolutePath == right.absolutePath)
    }

    fun getStorageInfo(): StorageInfo {
        val root = defaultRoot()
        return runCatching {
            val stat = StatFs(root.absolutePath)
            val total = stat.blockSizeLong * stat.blockCountLong
            val free = stat.availableBlocksLong * stat.blockSizeLong
            StorageInfo(totalBytes = total, freeBytes = free)
        }.getOrDefault(StorageInfo(totalBytes = 0L, freeBytes = 0L))
    }

    fun getCategorySummaries(imageCountOverride: Int? = null): List<CategorySummary> {
        return FileCategory.entries.map { category ->
            val folder = category.resolveFolder()
            if (!folder.exists()) folder.mkdirs()
            val count = when (category) {
                FileCategory.IMAGES -> imageCountOverride ?: countAllImages()
                else -> countTopLevelItems(folder)
            }
            CategorySummary(
                category = category,
                itemCount = count,
                folder = folder,
            )
        }
    }

    fun countTopLevelItems(directory: File): Int {
        if (!directory.exists() || !directory.isDirectory) return 0
        return directory.listFiles()?.size ?: 0
    }

    fun getRecentImages(limit: Int = 12): List<FileItem> {
        return getAllImages(maxResults = 400).take(limit)
    }

    fun getRecentFiles(limit: Int = 12): List<FileItem> = getRecentImages(limit)

    /**
     * Collect images from common photo locations across the device
     * (DCIM, Pictures, Download, WhatsApp, etc.), not only one folder.
     */
    fun getAllImages(maxResults: Int = 2500): List<FileItem> {
        val out = LinkedHashMap<String, FileItem>()
        imageScanRoots().forEach { root ->
            collectImagesRecursive(
                directory = root,
                depth = 0,
                maxDepth = 8,
                out = out,
                maxResults = maxResults,
            )
            if (out.size >= maxResults) return@forEach
        }
        return out.values
            .sortedByDescending { it.lastModified }
            .take(maxResults)
    }

    fun countAllImages(maxDepth: Int = 8, limit: Int = 9999): Int {
        var count = 0
        val seen = HashSet<String>()
        for (root in imageScanRoots()) {
            count += countImagesRecursive(
                directory = root,
                depth = 0,
                maxDepth = maxDepth,
                seen = seen,
                stopAt = limit,
            )
            if (count >= limit) {
                return limit
            }
        }
        return count
    }

    fun imageScanRoots(): List<File> {
        val storage = Environment.getExternalStorageDirectory()
        val candidates = listOf(
            File(storage, "DCIM"),
            File(storage, "Pictures"),
            File(storage, "Download"),
            File(storage, "Downloads"),
            File(storage, "WhatsApp/Media/WhatsApp Images"),
            File(storage, "WhatsApp/Media/WhatsApp Documents"),
            File(storage, "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images"),
            File(storage, "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Documents"),
            File(storage, "Android/media/com.whatsapp.w4b/WhatsApp Business/Media/WhatsApp Images"),
            File(storage, "Telegram/Telegram Images"),
            File(storage, "Android/media/org.telegram.messenger/Telegram/Telegram Images"),
            File(storage, "Snapchat"),
            File(storage, "Screenshots"),
            File(storage, "Pictures/Screenshots"),
            File(storage, "DCIM/Camera"),
            File(storage, "DCIM/Screenshots"),
            FileCategory.IMAGES.resolveFolder(),
            FileCategory.DOWNLOADS.resolveFolder(),
        )
        return candidates
            .filter { it.exists() && it.isDirectory }
            .distinctBy { runCatching { it.canonicalPath }.getOrDefault(it.absolutePath) }
    }

    private fun collectImagesRecursive(
        directory: File,
        depth: Int,
        maxDepth: Int,
        out: MutableMap<String, FileItem>,
        maxResults: Int,
    ) {
        if (depth > maxDepth || out.size >= maxResults) return
        if (!directory.exists() || !directory.isDirectory) return
        val children = directory.listFiles() ?: return
        children.forEach { child ->
            if (out.size >= maxResults) return
            if (child.isDirectory) {
                val name = child.name
                if (name.startsWith('.') || name.equals("cache", true) || name.equals("thumbnails", true)) {
                    return@forEach
                }
                collectImagesRecursive(child, depth + 1, maxDepth, out, maxResults)
            } else {
                val item = FileItem(child)
                if (item.isImage) {
                    out[item.path] = item
                }
            }
        }
    }

    private fun countImagesRecursive(
        directory: File,
        depth: Int,
        maxDepth: Int,
        seen: MutableSet<String>,
        stopAt: Int,
    ): Int {
        if (depth > maxDepth) return 0
        if (!directory.exists() || !directory.isDirectory) return 0
        val path = runCatching { directory.canonicalPath }.getOrDefault(directory.absolutePath)
        if (!seen.add(path)) return 0
        var count = 0
        val children = directory.listFiles() ?: return 0
        children.forEach { child ->
            if (count >= stopAt) return count
            if (child.isDirectory) {
                val name = child.name
                if (name.startsWith('.') || name.equals("cache", true) || name.equals("thumbnails", true)) {
                    return@forEach
                }
                count += countImagesRecursive(child, depth + 1, maxDepth, seen, stopAt - count)
            } else if (FileItem(child).isImage) {
                count++
            }
        }
        return count
    }

    fun searchFiles(query: String, maxResults: Int = 50): List<FileItem> {
        val trimmed = query.trim()
        if (trimmed.length < 2) return emptyList()
        val needle = trimmed.lowercase()
        val results = mutableListOf<FileItem>()
        categoryRoots().forEach { root ->
            searchRecursive(root, needle, depth = 0, maxDepth = 5, out = results, maxResults = maxResults)
            if (results.size >= maxResults) return@forEach
        }
        return results
            .sortedByDescending { it.lastModified }
            .take(maxResults)
    }

    private fun categoryRoots(): List<File> {
        return FileCategory.entries
            .map { it.resolveFolder() }
            .distinctBy { runCatching { it.canonicalPath }.getOrDefault(it.absolutePath) }
    }

    private fun searchRecursive(
        directory: File,
        needle: String,
        depth: Int,
        maxDepth: Int,
        out: MutableList<FileItem>,
        maxResults: Int,
    ) {
        if (depth > maxDepth || out.size >= maxResults) return
        if (!directory.exists() || !directory.isDirectory) return
        val children = directory.listFiles() ?: return
        children.forEach { child ->
            if (out.size >= maxResults) return
            if (child.name.lowercase().contains(needle)) {
                out += FileItem(child)
            }
            if (child.isDirectory) {
                searchRecursive(child, needle, depth + 1, maxDepth, out, maxResults)
            }
        }
    }

    fun listFiles(directory: File): List<FileItem> {
        if (!directory.exists() || !directory.isDirectory) return emptyList()
        return directory.listFiles()
            ?.map { FileItem(it) }
            ?.sortedWith(
                compareBy<FileItem> { !it.isDirectory }
                    .thenBy { it.name.lowercase() }
            )
            .orEmpty()
    }

    fun createFolder(parent: File, name: String): OperationResult {
        val sanitized = name.trim()
        if (sanitized.isEmpty()) return OperationResult.Error("Nama folder kosong")
        if (sanitized.contains('/') || sanitized.contains('\\')) {
            return OperationResult.Error("Nama folder tidak valid")
        }
        val target = File(parent, sanitized)
        if (target.exists()) return OperationResult.Error("Folder sudah ada")
        return if (target.mkdirs()) {
            OperationResult.Success("Folder \"$sanitized\" dibuat")
        } else {
            OperationResult.Error("Gagal membuat folder")
        }
    }

    fun rename(file: File, newName: String): OperationResult {
        val sanitized = newName.trim()
        if (sanitized.isEmpty()) return OperationResult.Error("Nama baru kosong")
        if (sanitized.contains('/') || sanitized.contains('\\')) {
            return OperationResult.Error("Nama tidak valid")
        }
        val target = File(file.parentFile, sanitized)
        if (target.exists()) return OperationResult.Error("Nama sudah dipakai")
        return if (file.renameTo(target)) {
            OperationResult.Success("Berhasil diganti nama")
        } else {
            OperationResult.Error("Gagal mengganti nama")
        }
    }

    fun deleteRecursively(files: List<File>): OperationResult {
        var failed = 0
        files.forEach { file ->
            if (!deleteDeep(file)) failed++
        }
        return if (failed == 0) {
            OperationResult.Success("${files.size} item dihapus")
        } else {
            OperationResult.Error("$failed item gagal dihapus")
        }
    }

    fun paste(
        clipboard: ClipboardState,
        destinationDir: File,
        onProgress: ((Float, String) -> Unit)? = null,
    ): OperationResult {
        if (!destinationDir.isDirectory) {
            return OperationResult.Error("Folder tujuan tidak valid")
        }

        val items = clipboard.items.filter { it.exists() }
        if (items.isEmpty()) return OperationResult.Error("Clipboard kosong")

        val total = items.size.coerceAtLeast(1)
        items.forEachIndexed { index, source ->
            onProgress?.invoke((index + 1f) / total, source.name)
            val target = uniqueName(File(destinationDir, source.name))

            if (isNestedTarget(source, target)) {
                return OperationResult.Error("Tidak bisa menempel ke dalam folder sumber")
            }

            val ok = when (clipboard.mode) {
                ClipboardMode.COPY -> copyDeep(source, target)
                ClipboardMode.CUT -> moveDeep(source, target)
            }
            if (!ok) {
                return OperationResult.Error("Gagal memproses ${source.name}")
            }
        }

        val action = if (clipboard.mode == ClipboardMode.COPY) "disalin" else "dipindahkan"
        return OperationResult.Success("${items.size} item $action")
    }

    fun uniqueName(file: File): File {
        if (!file.exists()) return file
        val parent = file.parentFile ?: return file
        val name = file.nameWithoutExtension
        val ext = file.extension
        var i = 1
        while (true) {
            val candidate = if (file.isDirectory || ext.isEmpty()) {
                File(parent, "$name ($i)")
            } else {
                File(parent, "$name ($i).$ext")
            }
            if (!candidate.exists()) return candidate
            i++
        }
    }

    private fun deleteDeep(file: File): Boolean {
        if (file.isDirectory) {
            file.listFiles()?.forEach { child ->
                if (!deleteDeep(child)) return false
            }
        }
        return file.delete()
    }

    private fun copyDeep(source: File, target: File): Boolean {
        return try {
            if (source.isDirectory) {
                if (!target.exists() && !target.mkdirs()) return false
                source.listFiles()?.forEach { child ->
                    if (!copyDeep(child, File(target, child.name))) return false
                }
                true
            } else {
                target.parentFile?.mkdirs()
                FileInputStream(source).use { input ->
                    FileOutputStream(target).use { output ->
                        input.copyTo(output, bufferSize = 64 * 1024)
                    }
                }
                target.setLastModified(source.lastModified())
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun moveDeep(source: File, target: File): Boolean {
        if (source.renameTo(target)) return true
        if (!copyDeep(source, target)) return false
        return deleteDeep(source)
    }

    private fun isNestedTarget(source: File, target: File): Boolean {
        if (!source.isDirectory) return false
        val sourcePath = source.canonicalPath
        val targetPath = target.canonicalPath
        return targetPath == sourcePath || targetPath.startsWith(sourcePath + File.separator)
    }
}
