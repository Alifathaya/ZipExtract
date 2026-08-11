package com.zipextract.app.data

enum class LibrarySubFilter(val label: String) {
    ALL("Semua"),
    PDF("PDF"),
    OFFICE("Office"),
    TEXT("Teks"),
    ;

    fun matches(item: FileItem): Boolean {
        if (this == ALL) return true
        val ext = item.extension
        return when (this) {
            ALL -> true
            PDF -> item.isPdf
            OFFICE -> ext in setOf("doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "csv")
            TEXT -> ext in setOf("txt", "rtf", "md", "log")
        }
    }
}
