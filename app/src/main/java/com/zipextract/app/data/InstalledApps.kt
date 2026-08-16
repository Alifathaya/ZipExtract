package com.zipextract.app.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import java.io.File
import java.util.Locale

/**
 * Launchable apps installed on the device (home-screen entries).
 * Uses MAIN/LAUNCHER query visibility — no QUERY_ALL_PACKAGES needed.
 */
object InstalledApps {
    const val PATH_PREFIX = "app://"

    fun isInstalledAppPath(path: String): Boolean = path.startsWith(PATH_PREFIX)

    fun packageFromPath(path: String): String? =
        path.takeIf { isInstalledAppPath(it) }?.removePrefix(PATH_PREFIX)?.takeIf { it.isNotBlank() }

    fun count(context: Context): Int = list(context).size

    fun list(context: Context): List<FileItem> {
        val pm = context.packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val activities = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            pm.queryIntentActivities(main, PackageManager.MATCH_ALL)
        } else {
            pm.queryIntentActivities(main, 0)
        }

        val byPackage = LinkedHashMap<String, FileItem>(activities.size)
        for (ri in activities) {
            val pkg = ri.activityInfo?.packageName ?: continue
            if (byPackage.containsKey(pkg)) continue

            val label = ri.loadLabel(pm)?.toString()?.ifBlank { pkg } ?: pkg
            val appInfo = runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull()
            val pkgInfo = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
                } else {
                    @Suppress("DEPRECATION")
                    pm.getPackageInfo(pkg, 0)
                }
            }.getOrNull()

            val apkPath = appInfo?.sourceDir
            val apkFile = if (!apkPath.isNullOrBlank()) File(apkPath) else File("/system/app/$pkg")
            val size = apkFile.takeIf { it.isFile }?.length() ?: 0L
            val updated = pkgInfo?.lastUpdateTime ?: 0L

            byPackage[pkg] = FileItem(
                file = apkFile,
                name = label,
                path = PATH_PREFIX + pkg,
                isDirectory = false,
                sizeBytes = size,
                lastModified = updated,
                packageName = pkg,
            )
        }
        return byPackage.values.sortedBy { it.name.lowercase(Locale.ROOT) }
    }

    fun launch(context: Context, packageName: String): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } ?: return false
        return runCatching {
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }

    fun openAppInfo(context: Context, packageName: String): Boolean {
        return runCatching {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:$packageName")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
            true
        }.getOrDefault(false)
    }
}
