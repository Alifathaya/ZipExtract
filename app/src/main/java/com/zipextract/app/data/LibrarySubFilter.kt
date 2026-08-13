package com.zipextract.app.data

import androidx.annotation.StringRes
import com.zipextract.app.R

enum class LibrarySubFilter(@StringRes val labelRes: Int) {
    ALL(R.string.subfilter_all),
    PDF(R.string.subfilter_pdf),
    OFFICE(R.string.subfilter_office),
    TEXT(R.string.subfilter_text),
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
