package com.zipextract.app.data

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.os.Build
import android.widget.Toast
import com.zipextract.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File

/**
 * Continues the in-app update after DownloadManager reports completion.
 *
 * The APK is copied from DownloadManager's content URI into app-private cache,
 * checked for package/version correctness, and passed to the existing installer flow.
 */
class AppUpdateDownloadReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return

        val downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
        if (downloadId <= 0L) return

        val prefs = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        if (prefs.getLong(KEY_DOWNLOAD_ID, -1L) != downloadId) return

        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val appContext = context.applicationContext
                val downloadManager = appContext.getSystemService(DownloadManager::class.java)
                    ?: error(appContext.getString(R.string.app_update_download_failed))

                val query = DownloadManager.Query().setFilterById(downloadId)
                val status = downloadManager.query(query).use { cursor ->
                    if (!cursor.moveToFirst()) error(appContext.getString(R.string.app_update_download_failed))
                    val state = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    if (state != DownloadManager.STATUS_SUCCESSFUL) {
                        val reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                        error(appContext.getString(R.string.app_update_download_failed_reason, reason))
                    }
                    val downloaded = cursor.getLong(
                        cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR),
                    )
                    val total = cursor.getLong(
                        cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES),
                    )
                    if (downloaded <= 0L || (total > 0L && downloaded != total)) {
                        error(appContext.getString(R.string.app_update_download_incomplete))
                    }
                    true
                }
                if (!status) error(appContext.getString(R.string.app_update_download_failed))

                val sourceUri = downloadManager.getUriForDownloadedFile(downloadId)
                    ?: error(appContext.getString(R.string.app_update_download_failed))
                val targetDir = File(appContext.cacheDir, "app-updates").apply {
                    if (!exists() && !mkdirs()) error(appContext.getString(R.string.app_update_stage_failed))
                }
                val apk = File(targetDir, "update-$downloadId.apk")
                appContext.contentResolver.openInputStream(sourceUri)?.use { input ->
                    BufferedInputStream(input).use { bufferedInput ->
                        BufferedOutputStream(apk.outputStream()).use { output ->
                            bufferedInput.copyTo(output)
                        }
                    }
                } ?: error(appContext.getString(R.string.app_update_stage_failed))

                if (!apk.isFile || apk.length() <= 0L) {
                    error(appContext.getString(R.string.app_update_download_incomplete))
                }

                val archiveInfo = appContext.packageManager.getPackageArchiveInfo(apk.absolutePath, 0)
                    ?: error(appContext.getString(R.string.app_update_invalid_apk))
                if (archiveInfo.packageName != appContext.packageName) {
                    error(appContext.getString(R.string.app_update_wrong_package))
                }
                val archiveVersion = archiveInfo.compatVersionCode()
                val installedVersion = appContext.packageManager
                    .getPackageInfo(appContext.packageName, 0)
                    .compatVersionCode()
                if (archiveVersion <= installedVersion) {
                    error(appContext.getString(R.string.app_update_not_newer))
                }

                prefs.edit().remove(KEY_DOWNLOAD_ID).putString(KEY_PENDING_APK, apk.absolutePath).apply()
                withContext(Dispatchers.Main.immediate) {
                    val localized = LocaleHelper.wrap(appContext, AppPreferences(appContext).getAppLanguage())
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                        !appContext.packageManager.canRequestPackageInstalls()
                    ) {
                        Toast.makeText(
                            localized,
                            R.string.app_update_install_permission,
                            Toast.LENGTH_LONG,
                        ).show()
                        FileActions.openUnknownSourcesSettings(appContext)
                        return@withContext
                    }

                    when (val result = FileActions.installApk(appContext, apk)) {
                        FileActions.InstallApkResult.Started -> {
                            prefs.edit().remove(KEY_PENDING_APK).apply()
                        }
                        FileActions.InstallApkResult.NeedInstallPermission -> {
                            // MainActivity retries the staged APK after the user returns from Settings.
                        }
                        is FileActions.InstallApkResult.Failed -> {
                            Toast.makeText(
                                localized,
                                localized.getString(R.string.apk_install_failed, result.reason),
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    }
                }
            } catch (error: Exception) {
                val message = error.message ?: context.getString(R.string.app_update_download_failed)
                withContext(Dispatchers.Main.immediate) {
                    val localized = LocaleHelper.wrap(context.applicationContext, AppPreferences(context).getAppLanguage())
                    Toast.makeText(localized, message, Toast.LENGTH_LONG).show()
                }
                prefs.edit().remove(KEY_DOWNLOAD_ID).apply()
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun PackageInfo.compatVersionCode(): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) longVersionCode else @Suppress("DEPRECATION") versionCode.toLong()

    companion object {
        const val PREFERENCES = "app_update_download"
        const val KEY_DOWNLOAD_ID = "download_id"
        const val KEY_PENDING_APK = "pending_apk_path"
    }
}
