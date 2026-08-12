package com.zipextract.app.data

import androidx.annotation.StringRes
import com.zipextract.app.R

enum class AppLanguage(
    val tag: String,
    @StringRes val labelRes: Int,
) {
    SYSTEM("", R.string.language_system),
    INDONESIAN("in", R.string.language_indonesian),
    ENGLISH("en", R.string.language_english),
    ;

    companion object {
        fun fromTag(raw: String?): AppLanguage {
            return when (raw?.lowercase()) {
                "in", "id" -> INDONESIAN
                "en" -> ENGLISH
                "system", "" -> SYSTEM
                else -> entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: SYSTEM
            }
        }
    }
}
