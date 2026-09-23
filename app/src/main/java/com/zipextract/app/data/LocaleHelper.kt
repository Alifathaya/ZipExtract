package com.zipextract.app.data

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.os.LocaleListCompat
import com.zipextract.app.R
import java.util.Locale

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

    /**
     * Context for [getString] that follows [language], while keeping the app theme
     * (required so Compose/Material does not crash if used for inflation).
     */
    fun wrap(context: Context, language: AppLanguage): Context {
        val locale = localeFor(language)
        Locale.setDefault(locale)
        val config = Configuration(context.resources.configuration)
        config.setLocales(LocaleList(locale))
        val localized = context.createConfigurationContext(config)
        return ContextThemeWrapper(localized, R.style.Theme_ZipExtract)
    }

    fun localeFor(language: AppLanguage): Locale {
        return when (language) {
            AppLanguage.SYSTEM -> LocaleList.getDefault().get(0) ?: Locale.getDefault()
            AppLanguage.INDONESIAN -> Locale.forLanguageTag("id")
            AppLanguage.CHINESE_SIMPLIFIED -> Locale.forLanguageTag("zh-CN")
            AppLanguage.CHINESE_TRADITIONAL -> Locale.forLanguageTag("zh-TW")
            else -> Locale.forLanguageTag(language.tag).takeIf {
                it.language.isNotBlank()
            } ?: Locale.getDefault()
        }
    }
}
