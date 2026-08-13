package com.zipextract.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import com.zipextract.app.data.AppLanguage
import com.zipextract.app.data.LocaleHelper
import java.util.Locale

/**
 * Forces Compose string lookups to use the user-selected [language], so Download /
 * Documents / action rails (Delete, Copy, …) match the language picker — not only the home dashboard.
 */
@Composable
fun ProvideAppLocale(
    language: AppLanguage,
    content: @Composable () -> Unit,
) {
    val baseContext = LocalContext.current
    val localizedContext = remember(language, baseContext) {
        LocaleHelper.wrap(baseContext, language)
    }
    val localizedConfig = remember(language) {
        val config = android.content.res.Configuration(localizedContext.resources.configuration)
        config.setLocale(LocaleHelper.localeFor(language))
        config
    }
    // Keep Java default locale in sync for DateFormat / lowercase helpers.
    remember(language) {
        Locale.setDefault(LocaleHelper.localeFor(language))
        true
    }
    CompositionLocalProvider(
        LocalContext provides localizedContext,
        LocalConfiguration provides localizedConfig,
    ) {
        content()
    }
}
