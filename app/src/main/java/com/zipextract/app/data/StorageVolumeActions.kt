package com.zipextract.app.data

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import com.zipextract.app.R

/**
 * Opens the system Storage settings so the user can safely eject or format a
 * removable volume (USB/OTG / microSD). Direct unmount/format APIs are restricted
 * to system apps on modern Android.
 */
object StorageVolumeActions {

    enum class Purpose { EJECT, FORMAT }

    fun supportsManageActions(volume: DeviceStorageVolume): Boolean {
        return volume.isRemovable && !volume.isPrimary && volume.isMounted
    }

    /** @return true if a settings screen was started. */
    fun openSystemStorageSettings(context: Context, purpose: Purpose): Boolean {
        val appContext = context.applicationContext
        val candidates = buildList {
            add(Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                add(Intent("android.settings.STORAGE_MANAGER_SETTINGS"))
            }
            @Suppress("DEPRECATION")
            add(Intent(Settings.ACTION_MEMORY_CARD_SETTINGS))
            add(Intent("android.settings.STORAGE_SETTINGS"))
            add(Intent(Settings.ACTION_SETTINGS))
        }

        val started = candidates.any { raw ->
            val intent = Intent(raw).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching {
                if (intent.resolveActivity(appContext.packageManager) != null) {
                    appContext.startActivity(intent)
                    true
                } else {
                    false
                }
            }.getOrDefault(false)
        }

        if (started) {
            val hint = when (purpose) {
                Purpose.EJECT -> appContext.getString(R.string.storage_eject_system_hint)
                Purpose.FORMAT -> appContext.getString(R.string.storage_format_system_hint)
            }
            Toast.makeText(appContext, hint, Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(
                appContext,
                appContext.getString(R.string.storage_settings_open_failed),
                Toast.LENGTH_SHORT,
            ).show()
        }
        return started
    }
}
