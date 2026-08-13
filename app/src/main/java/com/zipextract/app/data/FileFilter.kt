package com.zipextract.app.data

import androidx.annotation.StringRes
import com.zipextract.app.R

enum class FileFilter(@StringRes val labelRes: Int) {
    ALL(R.string.filter_all),
    IMAGES(R.string.filter_images),
    VIDEOS(R.string.filter_videos),
    DOCUMENTS(R.string.filter_documents),
    ARCHIVES(R.string.filter_archives),
    APPS(R.string.filter_apps),
    OTHERS(R.string.filter_others),
    ;

    companion object {
        fun forCategory(category: FileCategory): FileFilter {
            return when (category) {
                FileCategory.DOWNLOADS -> ALL
                FileCategory.IMAGES -> IMAGES
                FileCategory.VIDEOS -> VIDEOS
                FileCategory.DOCUMENTS -> DOCUMENTS
                FileCategory.ARCHIVES -> ARCHIVES
                FileCategory.APPS -> APPS
                FileCategory.OTHERS -> OTHERS
            }
        }
    }
}
