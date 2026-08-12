package com.zipextract.app.data

import android.content.Context
import android.content.SharedPreferences
import java.io.File

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

    fun getCachedCategoryCounts(): Map<FileCategory, Int> {
        if (!prefs.contains(KEY_CATEGORY_COUNTS)) return emptyMap()
        val raw = prefs.getString(KEY_CATEGORY_COUNTS, "").orEmpty()
        if (raw.isBlank()) return emptyMap()
        return raw.split('|')
            .mapNotNull { part ->
                val bits = part.split('=', limit = 2)
                if (bits.size != 2) return@mapNotNull null
                val category = runCatching { FileCategory.valueOf(bits[0]) }.getOrNull()
                    ?: return@mapNotNull null
                val count = bits[1].toIntOrNull() ?: return@mapNotNull null
                category to count
            }
            .toMap()
    }

    fun saveCategoryCounts(summaries: List<CategorySummary>) {
        val encoded = summaries.joinToString("|") { "${it.category.name}=${it.itemCount}" }
        prefs.edit().putString(KEY_CATEGORY_COUNTS, encoded).apply()
    }

    fun getCachedRecentPhotoPaths(): List<String> {
        val raw = prefs.getString(KEY_RECENT_PHOTOS, "").orEmpty()
        if (raw.isBlank()) return emptyList()
        return raw.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
    }

    fun saveRecentPhotoPaths(paths: List<String>) {
        prefs.edit()
            .putString(KEY_RECENT_PHOTOS, paths.take(12).joinToString("\n"))
            .apply()
    }

    fun loadCachedRecentPhotos(): List<FileItem> {
        return getCachedRecentPhotoPaths().mapNotNull { path ->
            val file = File(path)
            if (file.exists() && file.isFile) FileItem(file) else null
        }
    }

    fun loadCachedCategorySummaries(): List<CategorySummary> {
        val counts = getCachedCategoryCounts()
        return FileCategory.entries.map { category ->
            CategorySummary(
                category = category,
                itemCount = counts[category] ?: 0,
                folder = category.resolveFolder(),
            )
        }
    }

    companion object {
        private const val PREFS_NAME = "filenest_prefs"
        private const val KEY_THEME = "theme_mode"
        private const val KEY_FAVORITES = "favorite_paths"
        private const val KEY_CATEGORY_COUNTS = "category_counts_v1"
        private const val KEY_RECENT_PHOTOS = "recent_photo_paths_v1"
    }
}
