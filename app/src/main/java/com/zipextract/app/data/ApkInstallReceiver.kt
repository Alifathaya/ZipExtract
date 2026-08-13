package com.zipextract.app.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.widget.Toast

/**
 * Receives [PackageInstaller] session status and launches the system
 * confirmation UI when the user must approve an install.
 */
class ApkInstallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_INSTALL_STATUS) return

        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        val fileName = intent.getStringExtra(EXTRA_FILE_NAME).orEmpty()

        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = extractConfirmIntent(intent) ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching {
                    context.startActivity(confirm)
                }.onFailure {
                    Toast.makeText(
                        context,
                        "Tidak bisa membuka installer. Izinkan instal dari FileNest di Pengaturan.",
                        Toast.LENGTH_LONG,
                    ).show()
                    FileActions.openUnknownSourcesSettings(context)
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                val label = fileName.ifBlank { "APK" }
                Toast.makeText(context, "$label berhasil diinstal", Toast.LENGTH_SHORT).show()
            }
            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                // User cancelled the confirmation sheet — stay quiet.
            }
            else -> {
                val detail = message?.takeIf { it.isNotBlank() } ?: "Status $status"
                Toast.makeText(
                    context,
                    "Instal gagal: $detail",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    private fun extractConfirmIntent(intent: Intent): Intent? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_INTENT)
        }
    }

    companion object {
        const val ACTION_INSTALL_STATUS = "com.zipextract.app.action.APK_INSTALL_STATUS"
        const val EXTRA_FILE_NAME = "extra_apk_file_name"
    }
}
