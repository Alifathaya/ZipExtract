package com.zipextract.app.data

import android.os.Environment
import androidx.annotation.StringRes
import com.zipextract.app.R
import java.io.File

enum class FileCategory(
    @StringRes val titleRes: Int,
    @StringRes val subtitleRes: Int,
) {
    DOWNLOADS(R.string.category_downloads, R.string.category_downloads_sub),
    IMAGES(R.string.category_images, R.string.category_images_sub),
    VIDEOS(R.string.category_videos, R.string.category_videos_sub),
    DOCUMENTS(R.string.category_documents, R.string.category_documents_sub),
    ARCHIVES(R.string.category_archives, R.string.category_archives_sub),
    APPS(R.string.category_apps, R.string.category_apps_sub),
    OTHERS(R.string.category_others, R.string.category_others_sub),
    ;

    /** Fallback Indonesian labels for non-Compose call sites. */
    val title: String
        get() = when (this) {
            DOWNLOADS -> "Download"
            IMAGES -> "Gambar"
            VIDEOS -> "Video"
            DOCUMENTS -> "Dokumen"
            ARCHIVES -> "ZIP"
            APPS -> "Aplikasi"
            OTHERS -> "Lainnya"
        }

    val subtitle: String
        get() = when (this) {
            DOWNLOADS -> "File unduhan"
            IMAGES -> "Foto & gambar"
            VIDEOS -> "Film & rekaman"
            DOCUMENTS -> "PDF, Word, Excel"
            ARCHIVES -> "File ZIP & arsip"
            APPS -> "File APK & installer"
            OTHERS -> "Musik & file lain"
        }

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
            ARCHIVES -> firstExisting(
                publicDir(Environment.DIRECTORY_DOWNLOADS, File(storage, "Download")),
                File(storage, "Download"),
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

    val libraryNoun: String
        get() = when (this) {
            DOWNLOADS -> "file unduhan"
            IMAGES -> "foto"
            VIDEOS -> "video"
            DOCUMENTS -> "dokumen"
            ARCHIVES -> "file ZIP"
            APPS -> "APK"
            OTHERS -> "file"
        }

    @get:StringRes
    val nounRes: Int
        get() = when (this) {
            DOWNLOADS -> R.string.noun_downloads
            IMAGES -> R.string.noun_images
            VIDEOS -> R.string.noun_videos
            DOCUMENTS -> R.string.noun_documents
            ARCHIVES -> R.string.noun_archives
            APPS -> R.string.noun_apps
            OTHERS -> R.string.noun_others
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

data class MediaLibrary(
    val downloads: List<FileItem> = emptyList(),
    val images: List<FileItem> = emptyList(),
    val videos: List<FileItem> = emptyList(),
    val documents: List<FileItem> = emptyList(),
    val archives: List<FileItem> = emptyList(),
    val apps: List<FileItem> = emptyList(),
    val others: List<FileItem> = emptyList(),
) {
    fun forCategory(category: FileCategory): List<FileItem> {
        return when (category) {
            FileCategory.DOWNLOADS -> downloads
            FileCategory.IMAGES -> images
            FileCategory.VIDEOS -> videos
            FileCategory.DOCUMENTS -> documents
            FileCategory.ARCHIVES -> archives
            FileCategory.APPS -> apps
            FileCategory.OTHERS -> others
        }
    }
}
