package com.zipextract.app.data

import androidx.annotation.StringRes
import com.zipextract.app.R

/** Sub-filters for the installed Apps category. */
enum class AppSubFilter(@StringRes val labelRes: Int) {
    ALL(R.string.app_filter_all),
    PLAY_STORE(R.string.app_filter_play_store),
    SYSTEM(R.string.app_filter_system),
    LARGE(R.string.app_filter_large),
    RARELY_USED(R.string.app_filter_rarely_used),
    GAMES(R.string.app_filter_games),
    ;

    fun matches(item: FileItem): Boolean {
        if (!item.isInstalledApp) return false
        return when (this) {
            ALL -> true
            PLAY_STORE -> !item.isSystemApp
            SYSTEM -> item.isSystemApp
            LARGE -> item.sizeBytes >= InstalledApps.LARGE_APP_BYTES
            RARELY_USED -> item.isRarelyUsed
            GAMES -> item.isGameApp
        }
    }
}
