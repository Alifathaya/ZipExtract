package com.zipextract.app.license

import android.content.Context

class LicenseStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var expiresAtEpochMs: Long
        get() = prefs.getLong(EXPIRES, 0L)
        set(value) = prefs.edit().putLong(EXPIRES, value).apply()

    var lastSuccessfulCheckAtEpochMs: Long
        get() = prefs.getLong(LAST_CHECK, 0L)
        set(value) = prefs.edit().putLong(LAST_CHECK, value).apply()

    var registered: Boolean
        get() = prefs.getBoolean(REGISTERED, false)
        set(value) = prefs.edit().putBoolean(REGISTERED, value).apply()

    var blocked: Boolean
        get() = prefs.getBoolean(BLOCKED, false)
        set(value) = prefs.edit().putBoolean(BLOCKED, value).apply()

    var unlimited: Boolean
        get() = prefs.getBoolean(UNLIMITED, false)
        set(value) = prefs.edit().putBoolean(UNLIMITED, value).apply()

    fun clearLicenseFlags() {
        prefs.edit()
            .remove(EXPIRES)
            .remove(LAST_CHECK)
            .remove(REGISTERED)
            .remove(BLOCKED)
            .remove(UNLIMITED)
            .apply()
    }

    companion object {
        private const val PREFS = "filenest_license"
        private const val EXPIRES = "expires_at_ms"
        private const val LAST_CHECK = "last_check_ms"
        private const val REGISTERED = "registered"
        private const val BLOCKED = "blocked"
        private const val UNLIMITED = "unlimited"

        /** Offline grace without a successful server check. */
        const val GRACE_MS: Long = 3L * 24 * 60 * 60 * 1000
    }
}
