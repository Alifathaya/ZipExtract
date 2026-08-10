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
) {
    val extension: String
        get() = if (isDirectory) "" else file.extension.lowercase(Locale.ROOT)

    val isArchive: Boolean
        get() = extension in ARCHIVE_EXTENSIONS

    val isPdf: Boolean
        get() = extension == "pdf"

    val isImage: Boolean
        get() = extension in IMAGE_EXTENSIONS

    val isViewable: Boolean
        get() = isPdf || isImage

    val formattedSize: String
        get() = if (isDirectory) "Folder" else formatBytes(sizeBytes)

    val formattedDate: String
        get() = DATE_FORMAT.format(Date(lastModified))

    companion object {
        val ARCHIVE_EXTENSIONS = setOf("zip", "jar", "apk")
        val IMAGE_EXTENSIONS = setOf(
            "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "wbmp",
        )
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
