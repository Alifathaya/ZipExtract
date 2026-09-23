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
    MALAY("ms", R.string.language_malay),
    ARABIC("ar", R.string.language_arabic),
    CHINESE_SIMPLIFIED("zh-CN", R.string.language_chinese_simplified),
    CHINESE_TRADITIONAL("zh-TW", R.string.language_chinese_traditional),
    HINDI("hi", R.string.language_hindi),
    SPANISH("es", R.string.language_spanish),
    PORTUGUESE("pt", R.string.language_portuguese),
    FRENCH("fr", R.string.language_french),
    GERMAN("de", R.string.language_german),
    RUSSIAN("ru", R.string.language_russian),
    JAPANESE("ja", R.string.language_japanese),
    KOREAN("ko", R.string.language_korean),
    TURKISH("tr", R.string.language_turkish),
    THAI("th", R.string.language_thai),
    VIETNAMESE("vi", R.string.language_vietnamese),
    FILIPINO("fil", R.string.language_filipino),
    ;

    companion object {
        fun fromTag(raw: String?): AppLanguage {
            val value = raw?.trim().orEmpty()
            if (value.isEmpty() || value.equals("system", ignoreCase = true)) {
                return SYSTEM
            }
            val normalized = value.replace('_', '-')
            return when (normalized.lowercase()) {
                "in", "id" -> INDONESIAN
                "zh", "zh-cn", "zh-hans" -> CHINESE_SIMPLIFIED
                "zh-tw", "zh-hant", "zh-hk" -> CHINESE_TRADITIONAL
                "tl" -> FILIPINO
                else -> entries.firstOrNull {
                    it.tag.equals(normalized, ignoreCase = true)
                } ?: entries.firstOrNull {
                    it.name.equals(value, ignoreCase = true)
                } ?: SYSTEM
            }
        }
    }
}
