package com.zipextract.app.data

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import java.util.Locale

object LocaleHelper {

    fun apply(language: AppLanguage) {
        val locales = if (language == AppLanguage.SYSTEM || language.tag.isBlank()) {
            LocaleListCompat.getEmptyLocaleList()
        } else {
            // Prefer BCP-47 tags AppCompat understands (id for Indonesian).
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
     * Create a context whose [Configuration] matches [language] so Compose
     * `stringResource` / `getString` resolve the correct `values-xx` strings
     * even before/without an Activity recreate.
     */
    fun wrap(context: Context, language: AppLanguage): Context {
        val locale = localeFor(language)
        val config = Configuration(context.resources.configuration)
        config.setLocales(LocaleList(locale))
        return context.createConfigurationContext(config)
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
