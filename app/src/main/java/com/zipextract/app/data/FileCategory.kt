package com.zipextract.app.data

import android.os.Environment
import java.io.File

enum class FileCategory(
    val title: String,
    val subtitle: String,
) {
    DOWNLOADS("Download", "File unduhan"),
    IMAGES("Gambar", "Foto & gambar"),
    VIDEOS("Video", "Film & rekaman"),
    DOCUMENTS("Dokumen", "PDF, Word, Excel"),
    APPS("Aplikasi", "File APK & installer"),
    OTHERS("Lainnya", "Musik & file lain"),
    ;

    fun resolveFolder(): File {
        val storage = Environment.getExternalStorageDirectory()
        return when (this) {
            DOWNLOADS -> publicDir(Environment.DIRECTORY_DOWNLOADS, File(storage, "Download"))
            IMAGES -> firstExisting(
                publicDir(Environment.DIRECTORY_PICTURES),
                publicDir(Environment.DIRECTORY_DCIM),
                File(storage, "Pictures"),
                File(storage, "DCIM"),
            )
            VIDEOS -> firstExisting(
                publicDir(Environment.DIRECTORY_MOVIES),
                File(storage, "Movies"),
                File(storage, "Video"),
            )
            DOCUMENTS -> firstExisting(
                publicDir(Environment.DIRECTORY_DOCUMENTS),
                File(storage, "Documents"),
            )
            APPS -> firstExisting(
                File(publicDir(Environment.DIRECTORY_DOWNLOADS), "APK"),
                publicDir(Environment.DIRECTORY_DOWNLOADS),
            )
            OTHERS -> firstExisting(
                publicDir(Environment.DIRECTORY_MUSIC),
                File(storage, "Music"),
                storage,
            )
        }
    }

    private fun publicDir(type: String, fallback: File? = null): File {
        return runCatching {
            Environment.getExternalStoragePublicDirectory(type)
        }.getOrNull()?.takeIf { it.absolutePath.isNotBlank() }
            ?: fallback
            ?: File(Environment.getExternalStorageDirectory(), type)
    }

    private fun firstExisting(vararg candidates: File): File {
        return candidates.firstOrNull { it.exists() && it.isDirectory }
            ?: candidates.first().also { it.mkdirs() }
    }
}

data class CategorySummary(
    val category: FileCategory,
    val itemCount: Int,
    val folder: File,
) {
    val folderLabel: String
        get() = folder.name.ifBlank { folder.absolutePath }
}

data class StorageInfo(
    val totalBytes: Long,
    val freeBytes: Long,
) {
    val usedBytes: Long
        get() = (totalBytes - freeBytes).coerceAtLeast(0L)

    val usedFraction: Float
        get() = if (totalBytes > 0L) usedBytes.toFloat() / totalBytes.toFloat() else 0f
}
