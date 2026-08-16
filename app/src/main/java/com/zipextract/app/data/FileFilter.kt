package com.zipextract.app.data

import androidx.annotation.StringRes
import com.zipextract.app.R

enum class FileFilter(@StringRes val labelRes: Int) {
    ALL(R.string.filter_all),
    IMAGES(R.string.filter_images),
    VIDEOS(R.string.filter_videos),
    DOCUMENTS(R.string.filter_documents),
    ARCHIVES(R.string.filter_archives),
    AUDIO(R.string.filter_audio),
    APPS(R.string.filter_apps),
    OTHERS(R.string.filter_others),
    ;

    /** Fallback Indonesian label for non-Compose call sites. */
    val label: String
        get() = when (this) {
            ALL -> "Semua"
            IMAGES -> "Gambar"
            VIDEOS -> "Video"
            DOCUMENTS -> "Dokumen"
            ARCHIVES -> "ZIP"
            AUDIO -> "Audio"
            APPS -> "APK"
            OTHERS -> "Lainnya"
        }

    companion object {
        fun forCategory(category: FileCategory): FileFilter {
            return when (category) {
                FileCategory.DOWNLOADS -> ALL
                FileCategory.IMAGES -> IMAGES
                FileCategory.VIDEOS -> VIDEOS
                FileCategory.DOCUMENTS -> DOCUMENTS
                FileCategory.ARCHIVES -> ARCHIVES
                FileCategory.APPS -> ALL
                FileCategory.AUDIO -> AUDIO
                FileCategory.RAW_APK -> APPS
                FileCategory.OTHERS -> OTHERS
            }
        }
    }
}
