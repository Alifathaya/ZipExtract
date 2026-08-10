package com.zipextract.app.data

enum class FileFilter(val label: String) {
    ALL("Semua"),
    IMAGES("Gambar"),
    VIDEOS("Video"),
    DOCUMENTS("Dokumen"),
    ARCHIVES("ZIP"),
    APPS("APK"),
    OTHERS("Lainnya"),
    ;

    companion object {
        fun forCategory(category: FileCategory): FileFilter {
            return when (category) {
                FileCategory.DOWNLOADS -> ALL
                FileCategory.IMAGES -> IMAGES
                FileCategory.VIDEOS -> VIDEOS
                FileCategory.DOCUMENTS -> DOCUMENTS
                FileCategory.APPS -> APPS
                FileCategory.OTHERS -> OTHERS
            }
        }
    }
}
