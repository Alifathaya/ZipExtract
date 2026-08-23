package com.zipextract.app.license

import android.content.Context
import android.provider.Settings
import java.security.MessageDigest
import java.util.UUID

object DeviceIdProvider {
    fun get(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY, null)?.takeIf { it.isNotBlank() }?.let { return it }

        val androidId = runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull().orEmpty()

        val raw = if (androidId.isNotBlank() && androidId != "9774d56d682e549c") {
            "filenest|$androidId"
        } else {
            "filenest|${UUID.randomUUID()}"
        }
        val id = "fn_" + sha256(raw).take(32)
        prefs.edit().putString(KEY, id).apply()
        return id
    }

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private const val PREFS = "filenest_device"
    private const val KEY = "device_id"
}
