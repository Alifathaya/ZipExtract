package com.zipextract.app.data

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

object LocaleHelper {

    fun apply(language: AppLanguage) {
        val locales = when (language) {
            AppLanguage.SYSTEM -> LocaleListCompat.getEmptyLocaleList()
            AppLanguage.INDONESIAN -> LocaleListCompat.forLanguageTags("in")
            AppLanguage.ENGLISH -> LocaleListCompat.forLanguageTags("en")
        }
        AppCompatDelegate.setApplicationLocales(locales)
    }

    fun applyFromPreferences(prefs: AppPreferences) {
        if (!prefs.hasLanguageChosen()) return
        apply(prefs.getAppLanguage())
    }
}
