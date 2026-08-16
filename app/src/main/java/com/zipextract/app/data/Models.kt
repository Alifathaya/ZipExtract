package com.zipextract.app.data

import java.io.File
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class FileItem(
    val file: File,
    val name: String = file.name,
    val path: String = file.absolutePath,
    val isDirectory: Boolean = file.isDirectory,
    val sizeBytes: Long = if (file.isFile) file.length() else 0L,
    val lastModified: Long = file.lastModified(),
    /** Set for launchable apps installed on the device (Apps category). */
    val packageName: String? = null,
    val isSystemApp: Boolean = false,
    val isPlayStoreApp: Boolean = false,
    val isGameApp: Boolean = false,
    val lastUsedMs: Long = 0L,
    val isRarelyUsed: Boolean = false,
) {
    val isInstalledApp: Boolean
        get() = !packageName.isNullOrBlank()

    val extension: String
        get() = if (isDirectory || isInstalledApp) "" else file.extension.lowercase(Locale.ROOT)

    val isArchive: Boolean
        get() = !isInstalledApp && (extension in ARCHIVE_EXTENSIONS || isZip)

    val isZip: Boolean
        get() = !isInstalledApp && (extension == "zip" || name.lowercase(Locale.ROOT).endsWith(".zip"))

    val isPdf: Boolean
        get() = !isInstalledApp && extension == "pdf"

    val isImage: Boolean
        get() = !isInstalledApp && extension in IMAGE_EXTENSIONS

    val isVideo: Boolean
        get() = !isInstalledApp && extension in VIDEO_EXTENSIONS

    val isAudio: Boolean
        get() = !isInstalledApp && extension in AUDIO_EXTENSIONS

    val isDocument: Boolean
        get() = !isInstalledApp && (isPdf || extension in DOCUMENT_EXTENSIONS)

    val isApp: Boolean
        get() = isInstalledApp || extension in APP_EXTENSIONS

    /** Plain APK that the system package installer can install directly. */
    val isApk: Boolean
        get() = !isInstalledApp && extension == "apk"

    val isViewable: Boolean
        get() = isPdf || isImage || isVideo

    fun matchesFilter(filter: FileFilter): Boolean {
        return when (filter) {
            FileFilter.ALL -> true
            FileFilter.IMAGES -> isDirectory || isImage
            FileFilter.VIDEOS -> isDirectory || isVideo
            FileFilter.DOCUMENTS -> isDirectory || isDocument
            FileFilter.ARCHIVES -> isDirectory || (isArchive && !isApp)
            FileFilter.AUDIO -> isDirectory || isAudio
            FileFilter.APPS -> isDirectory || (isApp && !isInstalledApp)
            FileFilter.OTHERS -> isDirectory || (
                !isImage && !isVideo && !isDocument && !isAudio &&
                    !(isArchive && !isApp) && !isApp
                )
        }
    }

    fun matchesCategory(category: FileCategory): Boolean {
        return when (category) {
            FileCategory.DOWNLOADS -> true
            FileCategory.IMAGES -> isImage
            FileCategory.VIDEOS -> isVideo
            FileCategory.DOCUMENTS -> isDocument
            FileCategory.ARCHIVES -> isArchive && !isApp
            FileCategory.APPS -> isInstalledApp
            FileCategory.AUDIO -> isAudio
            FileCategory.RAW_APK -> isApp && !isInstalledApp
            FileCategory.OTHERS ->
                !isImage && !isVideo && !isDocument && !isAudio &&
                    !(isArchive && !isApp) && !isApp
        }
    }

    val parentFolderName: String
        get() = file.parentFile?.name?.takeIf { it.isNotBlank() } ?: "Storage"

    val formattedSize: String
        get() = if (isDirectory) "Folder" else formatBytes(sizeBytes)

    val formattedDate: String
        get() = DATE_FORMAT.format(Date(lastModified))

    companion object {
        val ARCHIVE_EXTENSIONS = setOf("zip", "rar", "7z", "tar", "gz", "tgz", "bz2", "jar")
        val IMAGE_EXTENSIONS = setOf(
            "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "wbmp",
        )
        val VIDEO_EXTENSIONS = setOf(
            "mp4", "mkv", "avi", "mov", "webm", "3gp", "flv", "wmv", "m4v", "ts",
        )
        val AUDIO_EXTENSIONS = setOf(
            "mp3", "m4a", "aac", "flac", "wav", "ogg", "opus", "wma", "amr",
        )
        val DOCUMENT_EXTENSIONS = setOf(
            "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "rtf", "odt", "csv",
        )
        val APP_EXTENSIONS = setOf("apk", "xapk", "apks", "apkm")
        private val DATE_FORMAT = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault())
        private val SIZE_FORMAT = DecimalFormat("#,##0.#")

        fun formatBytes(bytes: Long): String {
            if (bytes < 1024) return "$bytes B"
            val kb = bytes / 1024.0
            if (kb < 1024) return "${SIZE_FORMAT.format(kb)} KB"
            val mb = kb / 1024.0
            if (mb < 1024) return "${SIZE_FORMAT.format(mb)} MB"
            val gb = mb / 1024.0
            return "${SIZE_FORMAT.format(gb)} GB"
        }
    }
}

enum class ClipboardMode {
    COPY,
    CUT,
}

data class ClipboardState(
    val mode: ClipboardMode,
    val items: List<File>,
)

sealed class OperationResult {
    data class Success(val message: String) : OperationResult()
    data class Error(val message: String) : OperationResult()
}

data class ProgressState(
    val title: String,
    val message: String,
    val indeterminate: Boolean = true,
    val progress: Float = 0f,
)

data class ZipEntryItem(
    val path: String,
    val displayName: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
) {
    val formattedSize: String
        get() = if (isDirectory) "Folder" else FileItem.formatBytes(sizeBytes)
}
