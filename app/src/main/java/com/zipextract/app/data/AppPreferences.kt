package com.zipextract.app.data

import android.content.Context
import android.content.SharedPreferences

enum class ThemeMode(val label: String) {
    SYSTEM("Sistem"),
    LIGHT("Terang"),
    DARK("Gelap"),
}

class AppPreferences(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getThemeMode(): ThemeMode {
        val raw = prefs.getString(KEY_THEME, ThemeMode.SYSTEM.name) ?: ThemeMode.SYSTEM.name
        return runCatching { ThemeMode.valueOf(raw) }.getOrDefault(ThemeMode.SYSTEM)
    }

    fun setThemeMode(mode: ThemeMode) {
        prefs.edit().putString(KEY_THEME, mode.name).apply()
    }

    fun getFavoritePaths(): Set<String> {
        return prefs.getStringSet(KEY_FAVORITES, emptySet())?.toSet().orEmpty()
    }

    fun isFavorite(path: String): Boolean = path in getFavoritePaths()

    fun toggleFavorite(path: String): Boolean {
        val current = getFavoritePaths().toMutableSet()
        val nowFavorite = if (path in current) {
            current.remove(path)
            false
        } else {
            current.add(path)
            true
        }
        prefs.edit().putStringSet(KEY_FAVORITES, current).apply()
        return nowFavorite
    }

    companion object {
        private const val PREFS_NAME = "filenest_prefs"
        private const val KEY_THEME = "theme_mode"
        private const val KEY_FAVORITES = "favorite_paths"
    }
}
