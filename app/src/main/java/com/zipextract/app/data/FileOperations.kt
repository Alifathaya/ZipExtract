package com.zipextract.app.data

import android.content.ContentResolver
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
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

    fun getCategorySummaries(library: MediaLibrary? = null): List<CategorySummary> {
        val media = library ?: scanMediaLibrary()
        return FileCategory.entries.map { category ->
            val folder = category.resolveFolder()
            if (!folder.exists()) folder.mkdirs()
            CategorySummary(
                category = category,
                itemCount = media.forCategory(category).size,
                folder = folder,
            )
        }
    }

    /** Instant placeholders so the home category grid can render before scanning finishes. */
    fun getEmptyCategorySummaries(): List<CategorySummary> {
        return FileCategory.entries.map { category ->
            CategorySummary(
                category = category,
                itemCount = 0,
                folder = category.resolveFolder(),
            )
        }
    }

    fun countTopLevelItems(directory: File): Int {
        if (!directory.exists() || !directory.isDirectory) return 0
        return directory.listFiles()?.size ?: 0
    }

    fun getRecentImages(limit: Int = 12): List<FileItem> {
        return getFilesForCategory(FileCategory.IMAGES, maxResults = 400).take(limit)
    }

    fun getRecentFiles(limit: Int = 12): List<FileItem> = getRecentImages(limit)

    fun getAllImages(maxResults: Int = 2500): List<FileItem> {
        return getFilesForCategory(FileCategory.IMAGES, maxResults)
    }

    fun getFilesForCategory(category: FileCategory, maxResults: Int = 2500): List<FileItem> {
        return scanMediaLibrary(maxPerCategory = maxResults).forCategory(category).take(maxResults)
    }

    /**
     * One-pass scan of common storage locations, then classify files into categories.
     * When [contentResolver] is provided, MediaStore images/videos are merged in so
     * newly taken camera shots (already indexed by the system) are not missed even if
     * a filesystem walk is slow or skips an OEM-specific folder.
     */
    fun scanMediaLibrary(
        maxPerCategory: Int = 2500,
        maxDepth: Int = 8,
        contentResolver: ContentResolver? = null,
    ): MediaLibrary {
        val downloads = LinkedHashMap<String, FileItem>()
        val images = LinkedHashMap<String, FileItem>()
        val videos = LinkedHashMap<String, FileItem>()
        val documents = LinkedHashMap<String, FileItem>()
        val archives = LinkedHashMap<String, FileItem>()
        val apps = LinkedHashMap<String, FileItem>()
        val others = LinkedHashMap<String, FileItem>()
        val visited = HashSet<String>()
        val downloadRoots = downloadScanRoots()
            .map { runCatching { it.canonicalPath }.getOrDefault(it.absolutePath) }
            .toSet()

        fun underDownload(path: String): Boolean {
            return downloadRoots.any { root ->
                path == root || path.startsWith(root + File.separator)
            }
        }

        fun addItem(map: MutableMap<String, FileItem>, item: FileItem) {
            // Collect everything first; limiting mid-walk drops newer files when older folders
            // are visited earlier (common with large photo libraries).
            map[item.path] = item
        }

        mediaScanRoots().forEach { root ->
            walkFiles(root, depth = 0, maxDepth = maxDepth, visited = visited) { file ->
                val item = FileItem(file)
                val path = runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)
                if (underDownload(path)) {
                    addItem(downloads, item)
                }
                when {
                    item.isImage -> addItem(images, item)
                    item.isVideo -> addItem(videos, item)
                    item.isApp -> addItem(apps, item)
                    item.isArchive -> addItem(archives, item)
                    item.isDocument -> addItem(documents, item)
                    item.isAudio -> addItem(others, item)
                }
            }
        }

        contentResolver?.let { resolver ->
            mergeMediaStoreFiles(
                resolver = resolver,
                collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                into = images,
            )
            mergeMediaStoreFiles(
                resolver = resolver,
                collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                into = videos,
            )
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                mergeMediaStoreFiles(
                    resolver = resolver,
                    collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    into = downloads,
                    alsoClassify = { item ->
                        when {
                            item.isImage -> addItem(images, item)
                            item.isVideo -> addItem(videos, item)
                            item.isApp -> addItem(apps, item)
                            item.isArchive -> addItem(archives, item)
                            item.isDocument -> addItem(documents, item)
                            item.isAudio -> addItem(others, item)
                        }
                    },
                )
            }
        }

        fun newest(map: Map<String, FileItem>): List<FileItem> {
            return map.values
                .sortedByDescending { it.lastModified }
                .take(maxPerCategory)
        }

        return MediaLibrary(
            downloads = newest(downloads),
            images = newest(images),
            videos = newest(videos),
            documents = newest(documents),
            archives = newest(archives),
            apps = newest(apps),
            others = newest(others),
        )
    }

    /**
     * Pull paths from MediaStore so camera / gallery apps' newest files appear even when
     * they live outside the folders we walk first.
     */
    @Suppress("DEPRECATION")
    private fun mergeMediaStoreFiles(
        resolver: ContentResolver,
        collection: Uri,
        into: MutableMap<String, FileItem>,
        alsoClassify: ((FileItem) -> Unit)? = null,
    ) {
        val projection = arrayOf(
            MediaStore.MediaColumns.DATA,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
        )
        runCatching {
            resolver.query(
                collection,
                projection,
                null,
                null,
                "${MediaStore.MediaColumns.DATE_MODIFIED} DESC",
            )?.use { cursor ->
                val dataIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                val sizeIdx = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
                val modIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
                if (dataIdx < 0) return
                var merged = 0
                while (cursor.moveToNext()) {
                    if (merged >= 800) break
                    val path = cursor.getString(dataIdx)?.takeIf { it.isNotBlank() } ?: continue
                    if (into.containsKey(path)) continue
                    val file = File(path)
                    if (!file.isFile) continue
                    val size = if (sizeIdx >= 0) {
                        cursor.getLong(sizeIdx).takeIf { it > 0L } ?: file.length()
                    } else {
                        file.length()
                    }
                    val modified = if (modIdx >= 0) {
                        val raw = cursor.getLong(modIdx)
                        // MediaStore DATE_MODIFIED is seconds; File.lastModified is millis.
                        if (raw in 1 until 10_000_000_000L) raw * 1000L else raw
                    } else {
                        file.lastModified()
                    }
                    val item = FileItem(
                        file = file,
                        name = file.name,
                        path = file.absolutePath,
                        isDirectory = false,
                        sizeBytes = size,
                        lastModified = modified,
                    )
                    into[path] = item
                    alsoClassify?.invoke(item)
                    merged++
                }
            }
        }
    }

    fun isUnderDownloads(dir: File): Boolean {
        val path = runCatching { dir.canonicalPath }.getOrDefault(dir.absolutePath)
        return downloadScanRoots().any { root ->
            val rootPath = runCatching { root.canonicalPath }.getOrDefault(root.absolutePath)
            path == rootPath || path.startsWith(rootPath + File.separator)
        }
    }

    private fun mediaScanRoots(): List<File> {
        val storage = Environment.getExternalStorageDirectory()
        val candidates = listOf(
            File(storage, "DCIM"),
            File(storage, "DCIM/Camera"),
            File(storage, "Pictures"),
            File(storage, "Pictures/Camera"),
            File(storage, "Pictures/Screenshots"),
            File(storage, "Movies"),
            File(storage, "Video"),
            File(storage, "Download"),
            File(storage, "Downloads"),
            File(storage, "Documents"),
            File(storage, "Music"),
            File(storage, "Podcasts"),
            File(storage, "Recordings"),
            File(storage, "Screenshots"),
            File(storage, "WhatsApp"),
            File(storage, "Android/media/com.whatsapp"),
            File(storage, "Android/media/com.whatsapp.w4b"),
            File(storage, "Telegram"),
            File(storage, "Android/media/org.telegram.messenger"),
            File(storage, "Snapchat"),
            File(storage, "Bluetooth"),
            storage,
        ) + FileCategory.entries.map { it.resolveFolder() }

        return candidates
            .filter { it.exists() && it.isDirectory }
            .distinctBy { runCatching { it.canonicalPath }.getOrDefault(it.absolutePath) }
    }

    private fun downloadScanRoots(): List<File> {
        val storage = Environment.getExternalStorageDirectory()
        return listOf(
            File(storage, "Download"),
            File(storage, "Downloads"),
            FileCategory.DOWNLOADS.resolveFolder(),
        ).filter { it.exists() && it.isDirectory }
            .distinctBy { runCatching { it.canonicalPath }.getOrDefault(it.absolutePath) }
    }

    private fun walkFiles(
        directory: File,
        depth: Int,
        maxDepth: Int,
        visited: MutableSet<String>,
        onFile: (File) -> Unit,
    ) {
        if (depth > maxDepth) return
        if (!directory.exists() || !directory.isDirectory) return
        val path = runCatching { directory.canonicalPath }.getOrDefault(directory.absolutePath)
        if (!visited.add(path)) return

        // Avoid scanning huge/system-ish trees from storage root.
        if (depth == 0 && isStorageRoot(directory)) {
            directory.listFiles()
                ?.filter { it.isDirectory && shouldScanTopLevelName(it.name) }
                ?.forEach { child ->
                    walkFiles(child, depth + 1, maxDepth, visited, onFile)
                }
            directory.listFiles()
                ?.filter { it.isFile }
                ?.forEach(onFile)
            return
        }

        val children = directory.listFiles() ?: return
        children.forEach { child ->
            if (child.isDirectory) {
                val name = child.name
                if (shouldSkipDirectoryName(name)) return@forEach
                walkFiles(child, depth + 1, maxDepth, visited, onFile)
            } else {
                onFile(child)
            }
        }
    }

    private fun isStorageRoot(directory: File): Boolean {
        val storage = Environment.getExternalStorageDirectory()
        return samePath(directory, storage)
    }

    private fun shouldScanTopLevelName(name: String): Boolean {
        val lower = name.lowercase()
        if (lower.startsWith('.')) return false
        val blocked = setOf(
            "android", "data", "obb", "lost.dir", "lost+found", "notifications",
            "alarms", "ringtones", "systemui",
        )
        return lower !in blocked
    }

    private fun shouldSkipDirectoryName(name: String): Boolean {
        val lower = name.lowercase()
        return lower.startsWith('.') ||
            lower in setOf("cache", "thumbnails", "tmp", "temp", ".thumbnails", "code_cache")
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
