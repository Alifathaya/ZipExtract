package com.zipextract.app.data

import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Launchable apps installed on the device (home-screen entries).
 * Uses MAIN/LAUNCHER query visibility — no QUERY_ALL_PACKAGES needed.
 */
object InstalledApps {
    const val PATH_PREFIX = "app://"
    const val LARGE_APP_BYTES = 100L * 1024L * 1024L
    private const val RARELY_USED_IDLE_MS = 60L * 24L * 60L * 60L * 1000L
    private const val USAGE_WINDOW_MS = 90L * 24L * 60L * 60L * 1000L

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

        val lastUsedByPackage = loadLastUsedTimes(context)
        val now = System.currentTimeMillis()
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
            val size = apkSizeBytes(appInfo, apkFile)
            val updated = pkgInfo?.lastUpdateTime ?: 0L
            val firstInstall = pkgInfo?.firstInstallTime ?: 0L
            val isSystem = (appInfo?.flags?.and(ApplicationInfo.FLAG_SYSTEM) ?: 0) != 0
            val isGame = isGame(appInfo)
            val lastUsed = lastUsedByPackage[pkg]
                ?: updated.takeIf { it > 0L }
                ?: firstInstall
            val rarelyUsed = isRarelyUsed(lastUsed, now, lastUsedByPackage.containsKey(pkg))

            byPackage[pkg] = FileItem(
                file = apkFile,
                name = label,
                path = PATH_PREFIX + pkg,
                isDirectory = false,
                sizeBytes = size,
                lastModified = updated,
                packageName = pkg,
                isSystemApp = isSystem,
                isPlayStoreApp = !isSystem,
                isGameApp = isGame,
                lastUsedMs = lastUsed,
                isRarelyUsed = rarelyUsed,
            )
        }
        return byPackage.values.sortedBy { it.name.lowercase(Locale.ROOT) }
    }

    fun filter(items: List<FileItem>, filter: AppSubFilter): List<FileItem> {
        val matched = items.filter { filter.matches(it) }
        return when (filter) {
            AppSubFilter.LARGE -> matched.sortedByDescending { it.sizeBytes }
            AppSubFilter.RARELY_USED -> matched.sortedBy { it.lastUsedMs }
            else -> matched.sortedBy { it.name.lowercase(Locale.ROOT) }
        }
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

    fun uninstall(context: Context, packageName: String): Boolean {
        if (packageName == context.packageName) return false
        return runCatching {
            val intent = Intent(Intent.ACTION_DELETE).apply {
                data = Uri.parse("package:$packageName")
                putExtra(Intent.EXTRA_RETURN_RESULT, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }

    /**
     * Copy readable APK(s) into cache for sharing via FileProvider.
     * Returns empty list if none could be staged.
     */
    fun stageApksForShare(context: Context, items: List<FileItem>): List<File> {
        val dir = File(context.cacheDir, "shared-apks").apply {
            if (exists()) deleteRecursively()
            mkdirs()
        }
        val staged = ArrayList<File>(items.size)
        for (item in items) {
            val pkg = item.packageName ?: continue
            val source = item.file.takeIf { it.isFile }
                ?: runCatching {
                    context.packageManager.getApplicationInfo(pkg, 0).sourceDir?.let(::File)
                }.getOrNull()?.takeIf { it.isFile }
                ?: continue
            val safeName = (item.name.ifBlank { pkg })
                .replace(Regex("[\\\\/:*?\"<>|]"), "_")
                .take(48)
            val dest = File(dir, "$safeName-$pkg.apk")
            runCatching {
                source.copyTo(dest, overwrite = true)
                staged += dest
            }
        }
        return staged
    }

    /**
     * Compress selected APK files into a ZIP under public Downloads/FileNest/Apps.
     */
    fun compressApks(
        context: Context,
        items: List<FileItem>,
        zipBaseName: String = "apps",
    ): File? {
        val staged = stageApksForShare(context, items)
        if (staged.isEmpty()) return null
        val downloads = android.os.Environment.getExternalStoragePublicDirectory(
            android.os.Environment.DIRECTORY_DOWNLOADS,
        )
        val outDir = File(downloads, "FileNest/Apps").apply { mkdirs() }
        val safeBase = zipBaseName.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "apps" }
        var out = File(outDir, "$safeBase.zip")
        var n = 1
        while (out.exists()) {
            out = File(outDir, "$safeBase-$n.zip")
            n++
        }
        return runCatching {
            ZipOutputStream(FileOutputStream(out)).use { zos ->
                val buffer = ByteArray(64 * 1024)
                for (file in staged) {
                    zos.putNextEntry(ZipEntry(file.name))
                    FileInputStream(file).use { input ->
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            zos.write(buffer, 0, read)
                        }
                    }
                    zos.closeEntry()
                }
            }
            out
        }.getOrNull()
    }

    private fun apkSizeBytes(appInfo: ApplicationInfo?, apkFile: File): Long {
        var total = apkFile.takeIf { it.isFile }?.length() ?: 0L
        val splitDirs = appInfo?.splitSourceDirs
        if (!splitDirs.isNullOrEmpty()) {
            for (path in splitDirs) {
                total += File(path).takeIf { it.isFile }?.length() ?: 0L
            }
        }
        return total
    }

    private fun isGame(appInfo: ApplicationInfo?): Boolean {
        if (appInfo == null) return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            appInfo.category == ApplicationInfo.CATEGORY_GAME
        } else {
            false
        }
    }

    private fun isRarelyUsed(lastUsed: Long, now: Long, hasUsageStat: Boolean): Boolean {
        if (lastUsed <= 0L) return true
        val idleFor = now - lastUsed
        return if (hasUsageStat) {
            idleFor >= RARELY_USED_IDLE_MS
        } else {
            // Without usage access, approximate with last update age.
            idleFor >= RARELY_USED_IDLE_MS
        }
    }

    private fun loadLastUsedTimes(context: Context): Map<String, Long> {
        return runCatching {
            val usm = context.getSystemService(UsageStatsManager::class.java) ?: return emptyMap()
            val end = System.currentTimeMillis()
            val start = end - USAGE_WINDOW_MS
            val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_BEST, start, end)
                ?: return emptyMap()
            val map = HashMap<String, Long>(stats.size)
            for (stat in stats) {
                val pkg = stat.packageName ?: continue
                val used = stat.lastTimeUsed
                if (used <= 0L) continue
                val prev = map[pkg] ?: 0L
                if (used > prev) map[pkg] = used
            }
            map
        }.getOrDefault(emptyMap())
    }
}
