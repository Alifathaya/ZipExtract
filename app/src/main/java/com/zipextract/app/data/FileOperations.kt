package com.zipextract.app.data

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import android.provider.MediaStore
import com.zipextract.app.R
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

    /**
     * Lists all mounted storage volumes: internal, microSD, and USB Type-C/OTG
     * when the system exposes a path. Unmounted volumes are omitted.
     */
    fun listDeviceStorages(context: Context): List<DeviceStorageVolume> {
        val manager = context.getSystemService(StorageManager::class.java) ?: return emptyList()
        val volumes = manager.storageVolumes.mapNotNull { volume ->
            toDeviceStorageVolume(context, volume)
        }.toMutableList()

        // Fallback: discover remounted volumes via app-specific external dirs
        // when StorageVolume.directory is null (some OEM / USB cases).
        val knownRoots = volumes.mapNotNull { it.root?.let { root ->
            runCatching { root.canonicalPath }.getOrDefault(root.absolutePath)
        } }.toHashSet()
        discoverExtraVolumeRoots(context).forEach { root ->
            val key = runCatching { root.canonicalPath }.getOrDefault(root.absolutePath)
            if (key in knownRoots) return@forEach
            if (!root.exists() || !root.isDirectory) return@forEach
            val stats = statPath(root) ?: return@forEach
            knownRoots += key
            volumes += DeviceStorageVolume(
                id = "extra:$key",
                label = guessVolumeLabel(root),
                kind = guessVolumeKind(label = null, path = key, isPrimary = false, isRemovable = true),
                root = root,
                totalBytes = stats.first,
                freeBytes = stats.second,
                isPrimary = false,
                isRemovable = true,
                isMounted = true,
            )
        }

        return volumes.sortedWith(
            compareByDescending<DeviceStorageVolume> { it.isPrimary }
                .thenBy { it.kind.ordinal }
                .thenBy { it.label.lowercase() },
        )
    }

    private fun toDeviceStorageVolume(
        context: Context,
        volume: StorageVolume,
    ): DeviceStorageVolume? {
        val state = volume.state
        val mounted = state == null ||
            state == Environment.MEDIA_MOUNTED ||
            state == Environment.MEDIA_MOUNTED_READ_ONLY
        if (!mounted) return null

        val root = volumeDirectory(volume) ?: return if (volume.isPrimary) {
            // Always expose primary via the legacy shared-storage root.
            val primary = defaultRoot()
            val stats = statPath(primary) ?: (0L to 0L)
            DeviceStorageVolume(
                id = volumeUuid(volume) ?: "primary",
                label = volume.getDescription(context).ifBlank {
                    context.getString(R.string.storage_internal)
                },
                kind = StorageKind.INTERNAL,
                root = primary,
                totalBytes = stats.first,
                freeBytes = stats.second,
                isPrimary = true,
                isRemovable = volume.isRemovable,
                isMounted = true,
            )
        } else {
            null
        }

        val stats = statPath(root) ?: (0L to 0L)
        val label = volume.getDescription(context).ifBlank {
            when {
                volume.isPrimary -> context.getString(R.string.storage_internal)
                else -> root.name.ifBlank { context.getString(R.string.storage_external) }
            }
        }
        val path = runCatching { root.canonicalPath }.getOrDefault(root.absolutePath)
        return DeviceStorageVolume(
            id = volumeUuid(volume) ?: path,
            label = label,
            kind = guessVolumeKind(
                label = label,
                path = path,
                isPrimary = volume.isPrimary,
                isRemovable = volume.isRemovable,
            ),
            root = root,
            totalBytes = stats.first,
            freeBytes = stats.second,
            isPrimary = volume.isPrimary,
            isRemovable = volume.isRemovable,
            isMounted = true,
        )
    }

    private fun volumeUuid(volume: StorageVolume): String? {
        return volume.uuid?.takeIf { it.isNotBlank() }
            ?: if (volume.isPrimary) "primary" else null
    }

    private fun volumeDirectory(volume: StorageVolume): File? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            volume.directory?.let { return it }
        }
        // Pre-R public API: StorageVolume.getPath()
        return runCatching {
            val method = volume.javaClass.getMethod("getPath")
            val path = method.invoke(volume) as? String
            path?.takeIf { it.isNotBlank() }?.let { File(it) }
        }.getOrNull()
    }

    private fun discoverExtraVolumeRoots(context: Context): List<File> {
        val dirs = context.getExternalFilesDirs(null)?.filterNotNull().orEmpty()
        return dirs.mapNotNull { appDir ->
            // …/Android/data/<pkg>/files → climb to volume root
            var cursor: File? = appDir
            repeat(4) { cursor = cursor?.parentFile }
            cursor?.takeIf { it.exists() && it.isDirectory }
        }.distinctBy {
            runCatching { it.canonicalPath }.getOrDefault(it.absolutePath)
        }
    }

    private fun guessVolumeLabel(root: File): String {
        val name = root.name
        return when {
            name.equals("0", ignoreCase = true) ||
                root.absolutePath.contains("emulated", ignoreCase = true) -> "Internal"
            name.matches(Regex("[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}")) -> "SD card"
            else -> name.ifBlank { "Storage" }
        }
    }

    private fun guessVolumeKind(
        label: String?,
        path: String,
        isPrimary: Boolean,
        isRemovable: Boolean,
    ): StorageKind {
        if (isPrimary || path.contains("emulated", ignoreCase = true)) {
            return StorageKind.INTERNAL
        }
        val haystack = "${label.orEmpty()} $path".lowercase()
        return when {
            "usb" in haystack || "otg" in haystack || "type-c" in haystack ||
                "typec" in haystack -> StorageKind.USB
            isRemovable || "sd" in haystack || "card" in haystack ||
                Regex("(?i)/[0-9A-F]{4}-[0-9A-F]{4}(/|$)").containsMatchIn(path) -> StorageKind.SD_CARD
            else -> StorageKind.OTHER
        }
    }

    private fun statPath(root: File): Pair<Long, Long>? {
        return runCatching {
            val stat = StatFs(root.absolutePath)
            val total = stat.blockSizeLong * stat.blockCountLong
            val free = stat.availableBlocksLong * stat.blockSizeLong
            if (total <= 0L) null else total to free.coerceIn(0L, total)
        }.getOrNull()
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
        context: Context? = null,
    ): MediaLibrary {
        val downloads = LinkedHashMap<String, FileItem>()
        val images = LinkedHashMap<String, FileItem>()
        val videos = LinkedHashMap<String, FileItem>()
        val documents = LinkedHashMap<String, FileItem>()
        val archives = LinkedHashMap<String, FileItem>()
        val apps = LinkedHashMap<String, FileItem>()
        val others = LinkedHashMap<String, FileItem>()
        val visited = HashSet<String>()
        val downloadRoots = downloadScanRoots(context)
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

        mediaScanRoots(context).forEach { root ->
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

    /**
     * Queries only MediaStore rows changed since [sinceEpochSeconds]. This is used by
     * [MediaChangeWatcher] so a new photo/download can be inserted into the UI without
     * walking storage again. At most 64 rows are returned for a burst.
     */
    @Suppress("DEPRECATION")
    fun queryRecentMediaStoreChanges(
        resolver: ContentResolver,
        source: String,
        changedUri: Uri?,
        sinceEpochSeconds: Long,
    ): List<FileItem> {
        val collection = when (source) {
            "images" -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            "video" -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            "downloads" -> if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                MediaStore.Downloads.EXTERNAL_CONTENT_URI
            } else {
                MediaStore.Files.getContentUri("external")
            }
            else -> MediaStore.Files.getContentUri("external")
        }
        // A row URI ends in a numeric MediaStore ID. Query it directly when available.
        val rowUri = changedUri?.takeIf {
            it.lastPathSegment?.toLongOrNull() != null
        }
        val target = rowUri ?: collection
        val projection = arrayOf(
            MediaStore.MediaColumns.DATA,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.DATE_ADDED,
        )
        val selection = if (rowUri == null && sinceEpochSeconds > 0L) {
            "${MediaStore.MediaColumns.DATE_MODIFIED} >= ? OR " +
                "${MediaStore.MediaColumns.DATE_ADDED} >= ?"
        } else {
            null
        }
        val selectionArgs = if (selection != null) {
            arrayOf(sinceEpochSeconds.toString(), sinceEpochSeconds.toString())
        } else {
            null
        }
        return runCatching {
            resolver.query(
                target,
                projection,
                selection,
                selectionArgs,
                "${MediaStore.MediaColumns.DATE_MODIFIED} DESC",
            )?.use { cursor ->
                val dataIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                val sizeIdx = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
                val modifiedIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
                if (dataIdx < 0) return@use emptyList()
                buildList {
                    while (cursor.moveToNext() && size < 64) {
                        val path = cursor.getString(dataIdx)?.takeIf { it.isNotBlank() } ?: continue
                        val file = File(path)
                        if (!file.isFile) continue
                        val mediaSize = if (sizeIdx >= 0) cursor.getLong(sizeIdx) else 0L
                        val rawModified = if (modifiedIdx >= 0) cursor.getLong(modifiedIdx) else 0L
                        val modified = when {
                            rawModified in 1 until 10_000_000_000L -> rawModified * 1000L
                            rawModified > 0L -> rawModified
                            else -> file.lastModified()
                        }
                        add(
                            FileItem(
                                file = file,
                                name = file.name,
                                path = file.absolutePath,
                                isDirectory = false,
                                sizeBytes = mediaSize.takeIf { it > 0L } ?: file.length(),
                                lastModified = modified,
                            ),
                        )
                    }
                }
            }.orEmpty()
        }.getOrDefault(emptyList())
    }

    /**
     * Upserts a small set of changed files into all relevant cached categories.
     * A file may belong to Downloads and a media category at the same time.
     */
    fun mergeIncremental(base: MediaLibrary, changed: List<FileItem>): MediaLibrary {
        if (changed.isEmpty()) return base

        fun merge(current: List<FileItem>, accepts: (FileItem) -> Boolean): List<FileItem> {
            val byPath = LinkedHashMap<String, FileItem>(current.size + changed.size)
            current.asSequence()
                .filter { it.file.isFile }
                .forEach { byPath[it.path] = it }
            changed.asSequence().filter(accepts).forEach { byPath[it.path] = it }
            return byPath.values.sortedByDescending { it.lastModified }.take(2500)
        }

        return MediaLibrary(
            downloads = merge(base.downloads) { item ->
                item.file.parentFile?.let(::isUnderDownloads) == true
            },
            images = merge(base.images) { it.isImage },
            videos = merge(base.videos) { it.isVideo },
            documents = merge(base.documents) { it.isDocument },
            archives = merge(base.archives) { it.isArchive && !it.isApp },
            apps = merge(base.apps) { it.isApp },
            others = merge(base.others) { item ->
                item.isAudio ||
                    (!item.isImage && !item.isVideo && !item.isDocument &&
                        !(item.isArchive && !item.isApp) && !item.isApp)
            },
        )
    }

    fun isUnderDownloads(dir: File): Boolean {
        val path = runCatching { dir.canonicalPath }.getOrDefault(dir.absolutePath)
        return downloadScanRoots().any { root ->
            val rootPath = runCatching { root.canonicalPath }.getOrDefault(root.absolutePath)
            path == rootPath || path.startsWith(rootPath + File.separator)
        }
    }


    /** Common Opera / Opera Mini / Opera GX download locations on Android. */
    private fun operaDownloadCandidates(storageRoot: File): List<File> {
        val packages = listOf(
            "com.opera.browser",
            "com.opera.browser.beta",
            "com.opera.mini.native",
            "com.opera.gx",
            "com.opera.touch",
        )
        val out = mutableListOf(
            File(storageRoot, "Opera"),
            File(storageRoot, "Opera Downloads"),
            File(storageRoot, "Download/Opera"),
            File(storageRoot, "Downloads/Opera"),
        )
        packages.forEach { pkg ->
            out += File(storageRoot, "Android/media/$pkg")
            out += File(storageRoot, "Android/data/$pkg/files")
            out += File(storageRoot, "Android/data/$pkg/files/Download")
            out += File(storageRoot, "Android/data/$pkg/files/Downloads")
            out += File(storageRoot, "Android/data/$pkg/cache/downloads")
        }
        return out
    }

    private fun mediaScanRoots(context: Context? = null): List<File> {
        val storage = Environment.getExternalStorageDirectory()
        val candidates = mutableListOf(
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
        )
        candidates += operaDownloadCandidates(storage)
        candidates += FileCategory.entries.map { it.resolveFolder() }

        // Also walk common folders on microSD / USB volumes when present.
        context?.let { ctx ->
            listDeviceStorages(ctx)
                .filter { !it.isPrimary && it.canBrowse }
                .mapNotNull { it.root }
                .forEach { volRoot ->
                    candidates += listOf(
                        volRoot,
                        File(volRoot, "DCIM"),
                        File(volRoot, "Pictures"),
                        File(volRoot, "Movies"),
                        File(volRoot, "Download"),
                        File(volRoot, "Downloads"),
                        File(volRoot, "Documents"),
                        File(volRoot, "Music"),
                        File(volRoot, "WhatsApp"),
                    )
                    candidates += operaDownloadCandidates(volRoot)
                }
        }

        return candidates
            .filter { it.exists() && it.isDirectory }
            .distinctBy { runCatching { it.canonicalPath }.getOrDefault(it.absolutePath) }
    }

    private fun downloadScanRoots(context: Context? = null): List<File> {
        val storage = Environment.getExternalStorageDirectory()
        val roots = mutableListOf(
            File(storage, "Download"),
            File(storage, "Downloads"),
            FileCategory.DOWNLOADS.resolveFolder(),
        )
        roots += operaDownloadCandidates(storage)
        context?.let { ctx ->
            listDeviceStorages(ctx)
                .filter { !it.isPrimary && it.canBrowse }
                .mapNotNull { it.root }
                .forEach { volRoot ->
                    roots += File(volRoot, "Download")
                    roots += File(volRoot, "Downloads")
                    roots += operaDownloadCandidates(volRoot)
                }
        }
        return roots.filter { it.exists() && it.isDirectory }
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

    fun createFolder(context: Context, parent: File, name: String): OperationResult {
        val sanitized = name.trim()
        if (sanitized.isEmpty()) return OperationResult.Error(context.getString(R.string.folder_name_empty))
        if (sanitized.contains('/') || sanitized.contains('\\')) {
            return OperationResult.Error(context.getString(R.string.folder_name_invalid))
        }
        val target = File(parent, sanitized)
        if (target.exists()) return OperationResult.Error(context.getString(R.string.folder_exists))
        return if (target.mkdirs()) {
            OperationResult.Success(context.getString(R.string.folder_created, sanitized))
        } else {
            OperationResult.Error(context.getString(R.string.folder_create_failed))
        }
    }

    fun rename(context: Context, file: File, newName: String): OperationResult {
        val sanitized = newName.trim()
        if (sanitized.isEmpty()) return OperationResult.Error(context.getString(R.string.name_empty))
        if (sanitized.contains('/') || sanitized.contains('\\')) {
            return OperationResult.Error(context.getString(R.string.name_invalid))
        }
        val target = File(file.parentFile, sanitized)
        if (target.exists()) return OperationResult.Error(context.getString(R.string.name_taken))
        return if (file.renameTo(target)) {
            OperationResult.Success(context.getString(R.string.rename_success))
        } else {
            OperationResult.Error(context.getString(R.string.rename_failed))
        }
    }

    fun deleteRecursively(context: Context, files: List<File>): OperationResult {
        var failed = 0
        files.forEach { file ->
            if (!deleteDeep(file)) failed++
        }
        return if (failed == 0) {
            OperationResult.Success(context.getString(R.string.items_deleted, files.size))
        } else {
            OperationResult.Error(context.getString(R.string.items_delete_partial, failed))
        }
    }

    fun paste(
        context: Context,
        clipboard: ClipboardState,
        destinationDir: File,
        onProgress: ((Float, String) -> Unit)? = null,
    ): OperationResult {
        if (!destinationDir.isDirectory) {
            return OperationResult.Error(context.getString(R.string.dest_folder_invalid))
        }

        val items = clipboard.items.filter { it.exists() }
        if (items.isEmpty()) return OperationResult.Error(context.getString(R.string.clipboard_empty))

        val total = items.size.coerceAtLeast(1)
        items.forEachIndexed { index, source ->
            onProgress?.invoke((index + 1f) / total, source.name)
            val target = uniqueName(File(destinationDir, source.name))

            if (isNestedTarget(source, target)) {
                return OperationResult.Error(context.getString(R.string.paste_into_source))
            }

            val ok = when (clipboard.mode) {
                ClipboardMode.COPY -> copyDeep(source, target)
                ClipboardMode.CUT -> moveDeep(source, target)
            }
            if (!ok) {
                return OperationResult.Error(context.getString(R.string.process_failed_item, source.name))
            }
        }

        val action = context.getString(
            if (clipboard.mode == ClipboardMode.COPY) R.string.action_copied else R.string.action_moved,
        )
        return OperationResult.Success(context.getString(R.string.items_pasted, items.size, action))
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
