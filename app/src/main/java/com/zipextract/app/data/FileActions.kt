package com.zipextract.app.data

import android.app.PendingIntent
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import com.zipextract.app.R
import java.io.File
import java.util.Locale

object FileActions {

    private const val APK_MIME = "application/vnd.android.package-archive"

    sealed class InstallApkResult {
        data object Started : InstallApkResult()
        data object NeedInstallPermission : InstallApkResult()
        data class Failed(val reason: String) : InstallApkResult()
    }

    fun shareFile(context: Context, file: File): Boolean {
        if (!file.exists() || !file.isFile) return false
        return runCatching {
            val uri = uriFor(context, file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = mimeTypeFor(file)
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, file.name)
                clipData = ClipData.newUri(context.contentResolver, file.name, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(
                Intent.createChooser(
                    intent,
                    context.getString(R.string.share_file_title, file.name),
                ),
            )
            true
        }.getOrDefault(false)
    }

    fun shareFiles(context: Context, files: List<File>): Boolean {
        val existing = files.filter { it.exists() && it.isFile }
        if (existing.isEmpty()) return false
        if (existing.size == 1) return shareFile(context, existing.first())
        return runCatching {
            val uris = ArrayList<Uri>(existing.map { uriFor(context, it) })
            val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "*/*"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                if (uris.isNotEmpty()) {
                    clipData = ClipData.newUri(context.contentResolver, "files", uris.first()).also { clip ->
                        uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
                    }
                }
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(
                Intent.createChooser(
                    intent,
                    context.getString(R.string.share_files_title, existing.size),
                ),
            )
            true
        }.getOrDefault(false)
    }

    fun openWith(context: Context, file: File): Boolean {
        if (!file.exists() || !file.isFile) return false
        if (FileItem(file).isApk) {
            return when (installApk(context, file)) {
                is InstallApkResult.Started,
                is InstallApkResult.NeedInstallPermission,
                -> true
                is InstallApkResult.Failed -> false
            }
        }
        return runCatching {
            val uri = uriFor(context, file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeTypeFor(file))
                clipData = ClipData.newUri(context.contentResolver, file.name, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(
                Intent.createChooser(
                    intent,
                    context.getString(R.string.open_with_title),
                ),
            )
            true
        }.getOrDefault(false)
    }

    /**
     * Install a plain `.apk` via the system [PackageInstaller] session API
     * (shows the confirmation UI). Falls back to ACTION_VIEW if needed.
     *
     * Safe to call from a background thread (staging / session I/O).
     */
    fun installApk(context: Context, file: File): InstallApkResult {
        if (!file.exists() || !file.isFile) {
            return InstallApkResult.Failed(context.getString(R.string.apk_not_found))
        }
        if (!FileItem(file).isApk) {
            return InstallApkResult.Failed(context.getString(R.string.apk_only_apk))
        }
        if (file.length() <= 0L) {
            return InstallApkResult.Failed(context.getString(R.string.apk_empty))
        }
        if (!file.canRead()) {
            return InstallApkResult.Failed(context.getString(R.string.apk_unreadable))
        }

        val appContext = context.applicationContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !appContext.packageManager.canRequestPackageInstalls()
        ) {
            openUnknownSourcesSettings(appContext)
            return InstallApkResult.NeedInstallPermission
        }

        val sessionResult = runCatching { installViaPackageInstaller(appContext, file) }
        if (sessionResult.isSuccess) {
            return InstallApkResult.Started
        }

        val viewResult = runCatching { installViaViewIntent(appContext, file) }
        if (viewResult.isSuccess) {
            return InstallApkResult.Started
        }

        val sessionErr = sessionResult.exceptionOrNull()?.message
        val viewErr = viewResult.exceptionOrNull()?.message
        return InstallApkResult.Failed(
            listOfNotNull(sessionErr, viewErr).firstOrNull()
                ?: context.getString(R.string.apk_open_installer_failed),
        )
    }

    private fun installViaPackageInstaller(context: Context, file: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                setInstallReason(PackageManager.INSTALL_REASON_USER)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
            }
            setSize(file.length())
        }

        val sessionId = installer.createSession(params)
        val session = installer.openSession(sessionId)
        try {
            file.inputStream().use { input ->
                session.openWrite("base.apk", 0, file.length()).use { out ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                    }
                    session.fsync(out)
                }
            }

            val statusIntent = Intent(context, ApkInstallReceiver::class.java).apply {
                action = ApkInstallReceiver.ACTION_INSTALL_STATUS
                setPackage(context.packageName)
                putExtra(ApkInstallReceiver.EXTRA_FILE_NAME, file.name)
            }
            // MUTABLE is required so PackageInstaller can fill STATUS / confirmation Intent.
            val piFlags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    PendingIntent.FLAG_MUTABLE
                } else {
                    0
                }
            val pending = PendingIntent.getBroadcast(context, sessionId, statusIntent, piFlags)
            session.commit(pending.intentSender)
        } catch (err: Exception) {
            runCatching { session.abandon() }
            throw err
        } finally {
            runCatching { session.close() }
        }
    }

    private fun installViaViewIntent(context: Context, file: File) {
        val staged = stageApkForInstall(context, file)
        val uri = uriFor(context, staged)
        grantInstallUriPermission(context, uri)

        val baseFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
            Intent.FLAG_ACTIVITY_NEW_TASK or
            Intent.FLAG_ACTIVITY_CLEAR_TOP

        fun Intent.withApkUri(): Intent = apply {
            setDataAndType(uri, APK_MIME)
            clipData = ClipData.newUri(context.contentResolver, staged.name, uri)
            addFlags(baseFlags)
        }

        val installerPackages = listOf(
            "com.google.android.packageinstaller",
            "com.android.packageinstaller",
            "com.samsung.android.packageinstaller",
            "com.miui.packageinstaller",
            "com.huawei.packageinstaller",
            "com.oplus.packageinstaller",
            "com.vivo.packageinstaller",
        )
        for (pkg in installerPackages) {
            val direct = Intent(Intent.ACTION_VIEW).withApkUri().apply { setPackage(pkg) }
            if (direct.resolveActivity(context.packageManager) != null) {
                context.startActivity(direct)
                return
            }
        }

        val view = Intent(Intent.ACTION_VIEW).withApkUri()
        if (view.resolveActivity(context.packageManager) != null) {
            context.startActivity(view)
            return
        }

        @Suppress("DEPRECATION")
        val install = Intent(Intent.ACTION_INSTALL_PACKAGE).withApkUri().apply {
            putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
            putExtra(Intent.EXTRA_RETURN_RESULT, false)
        }
        context.startActivity(install)
    }

    /** Copy APK into app cache so FileProvider + installer grants are reliable. */
    private fun stageApkForInstall(context: Context, file: File): File {
        val dir = File(context.cacheDir, "apk-install").apply { mkdirs() }
        val target = File(dir, file.name.ifBlank { "app.apk" })
        if (target.absolutePath == file.absolutePath) return file
        if (target.exists() && target.length() == file.length() && target.lastModified() >= file.lastModified()) {
            return target
        }
        file.inputStream().use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
        return target
    }

    fun openUnknownSourcesSettings(context: Context) {
        runCatching {
            val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}"),
                )
            } else {
                Intent(Settings.ACTION_SECURITY_SETTINGS)
            }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }

    private fun grantInstallUriPermission(context: Context, uri: Uri) {
        val candidates = listOf(
            "com.google.android.packageinstaller",
            "com.android.packageinstaller",
            "com.samsung.android.packageinstaller",
            "com.miui.packageinstaller",
            "com.huawei.packageinstaller",
            "com.oplus.packageinstaller",
            "com.vivo.packageinstaller",
        )
        candidates.forEach { pkg ->
            runCatching {
                context.grantUriPermission(pkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
    }

    fun playMedia(context: Context, file: File): Boolean {
        if (!file.exists() || !file.isFile) return false
        val item = FileItem(file)
        if (!item.isVideo && !item.isAudio) return false
        return openWith(context, file)
    }

    fun uriFor(context: Context, file: File): Uri {
        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
    }

    fun mimeTypeFor(file: File): String {
        val ext = file.extension.lowercase(Locale.ROOT)
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: when {
                FileItem(file).isImage -> "image/*"
                FileItem(file).isVideo -> "video/*"
                FileItem(file).isAudio -> "audio/*"
                FileItem(file).isPdf -> "application/pdf"
                FileItem(file).isZip -> "application/zip"
                FileItem(file).isApp -> APK_MIME
                else -> "*/*"
            }
    }
}
