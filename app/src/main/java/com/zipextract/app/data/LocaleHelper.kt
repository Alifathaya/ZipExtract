package com.zipextract.app.data

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

object LocaleHelper {

    fun apply(language: AppLanguage) {
        val locales = if (language == AppLanguage.SYSTEM || language.tag.isBlank()) {
            LocaleListCompat.getEmptyLocaleList()
        } else {
            // Android resource folders use BCP-47 "id" for Indonesian (not legacy "in").
            val tag = when (language) {
                AppLanguage.INDONESIAN -> "id"
                else -> language.tag
            }
            LocaleListCompat.forLanguageTags(tag)
        }
        AppCompatDelegate.setApplicationLocales(locales)
    }

    fun applyFromPreferences(prefs: AppPreferences) {
        if (!prefs.hasLanguageChosen()) return
        apply(prefs.getAppLanguage())
    }
}
