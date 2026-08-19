package com.zipextract.app.data

import android.content.Context
import android.content.SharedPreferences
import com.zipextract.app.R
import java.io.File

enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
    ;

    @get:androidx.annotation.StringRes
    val labelRes: Int
        get() = when (this) {
            SYSTEM -> R.string.theme_system
            LIGHT -> R.string.theme_light
            DARK -> R.string.theme_dark
        }
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

    fun getAppLanguage(): AppLanguage {
        val raw = prefs.getString(KEY_LANGUAGE, AppLanguage.SYSTEM.tag)
        return AppLanguage.fromTag(raw)
    }

    fun setAppLanguage(language: AppLanguage) {
        prefs.edit().putString(KEY_LANGUAGE, language.tag.ifBlank { "system" }).apply()
    }

    fun hasLanguageChosen(): Boolean = prefs.getBoolean(KEY_LANGUAGE_CHOSEN, false)

    fun setLanguageChosen(chosen: Boolean) {
        prefs.edit().putBoolean(KEY_LANGUAGE_CHOSEN, chosen).apply()
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
        return getCachedCategoryStats().mapValues { it.value.first }
    }

    /** category → (itemCount, totalBytes) */
    fun getCachedCategoryStats(): Map<FileCategory, Pair<Int, Long>> {
        if (!prefs.contains(KEY_CATEGORY_COUNTS)) return emptyMap()
        val raw = prefs.getString(KEY_CATEGORY_COUNTS, "").orEmpty()
        if (raw.isBlank()) return emptyMap()
        return raw.split('|')
            .mapNotNull { part ->
                val bits = part.split('=', limit = 2)
                if (bits.size != 2) return@mapNotNull null
                val category = runCatching { FileCategory.valueOf(bits[0]) }.getOrNull()
                    ?: return@mapNotNull null
                val value = bits[1]
                val countBytes = value.split(':', limit = 2)
                val count = countBytes[0].toIntOrNull() ?: return@mapNotNull null
                val bytes = countBytes.getOrNull(1)?.toLongOrNull() ?: 0L
                category to (count to bytes)
            }
            .toMap()
    }

    fun saveCategoryCounts(summaries: List<CategorySummary>) {
        val encoded = summaries.joinToString("|") {
            "${it.category.name}=${it.itemCount}:${it.totalBytes}"
        }
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
        val stats = getCachedCategoryStats()
        return FileCategory.entries.map { category ->
            val (count, bytes) = stats[category] ?: (0 to 0L)
            CategorySummary(
                category = category,
                itemCount = count,
                folder = category.resolveFolder(),
                totalBytes = bytes,
            )
        }
    }

    fun hasHomeUiCache(): Boolean {
        return prefs.contains(KEY_CATEGORY_COUNTS) ||
            getCachedRecentPhotoPaths().isNotEmpty() ||
            prefs.contains(KEY_STORAGE_INFO)
    }

    fun saveStorageInfo(info: StorageInfo) {
        prefs.edit()
            .putString(KEY_STORAGE_INFO, "${info.totalBytes}|${info.freeBytes}")
            .apply()
    }

    fun loadCachedStorageInfo(): StorageInfo? {
        val raw = prefs.getString(KEY_STORAGE_INFO, null) ?: return null
        val parts = raw.split('|')
        if (parts.size != 2) return null
        val total = parts[0].toLongOrNull() ?: return null
        val free = parts[1].toLongOrNull() ?: return null
        if (total <= 0L) return null
        return StorageInfo(totalBytes = total, freeBytes = free.coerceIn(0L, total))
    }

    fun getSafBookmarks(): List<com.zipextract.app.data.cloud.SafBookmark> {
        val raw = prefs.getString(KEY_SAF_BOOKMARKS, "").orEmpty()
        if (raw.isBlank()) return emptyList()
        return raw.split('\n').mapNotNull { line ->
            val parts = line.split('\t', limit = 2)
            if (parts.size != 2) return@mapNotNull null
            com.zipextract.app.data.cloud.SafBookmark(uri = parts[0], label = parts[1])
        }
    }

    fun saveSafBookmarks(bookmarks: List<com.zipextract.app.data.cloud.SafBookmark>) {
        val encoded = bookmarks.joinToString("\n") { "${it.uri}\t${it.label}" }
        prefs.edit().putString(KEY_SAF_BOOKMARKS, encoded).apply()
    }

    companion object {
        private const val PREFS_NAME = "filenest_prefs"
        private const val KEY_THEME = "theme_mode"
        private const val KEY_LANGUAGE = "app_language_v1"
        private const val KEY_LANGUAGE_CHOSEN = "app_language_chosen_v1"
        private const val KEY_FAVORITES = "favorite_paths"
        private const val KEY_CATEGORY_COUNTS = "category_counts_v1"
        private const val KEY_RECENT_PHOTOS = "recent_photo_paths_v1"
        private const val KEY_STORAGE_INFO = "storage_info_v1"
        private const val KEY_SAF_BOOKMARKS = "saf_bookmarks_v1"
    }
}
