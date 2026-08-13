package com.zipextract.app.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File
import java.util.Locale

object FileActions {

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
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Bagikan ${file.name}"))
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
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Bagikan ${existing.size} file"))
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
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(Intent.createChooser(intent, "Buka dengan"))
            true
        }.getOrDefault(false)
    }

    /**
     * Launch the system package installer for a plain `.apk` file.
     * Does not show an app chooser — goes straight to the installer UI.
     */
    fun installApk(context: Context, file: File): InstallApkResult {
        if (!file.exists() || !file.isFile) {
            return InstallApkResult.Failed("File APK tidak ditemukan")
        }
        if (!FileItem(file).isApk) {
            return InstallApkResult.Failed("Hanya file .apk yang bisa diinstal langsung")
        }
        if (file.length() <= 0L) {
            return InstallApkResult.Failed("File APK kosong")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            openUnknownSourcesSettings(context)
            return InstallApkResult.NeedInstallPermission
        }

        return runCatching {
            val uri = uriFor(context, file)
            grantInstallUriPermission(context, uri)
            val baseFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP

            val installerPackages = listOf(
                "com.google.android.packageinstaller",
                "com.android.packageinstaller",
                "com.samsung.android.packageinstaller",
                "com.miui.packageinstaller",
            )
            for (pkg in installerPackages) {
                val direct = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    setPackage(pkg)
                    addFlags(baseFlags)
                }
                if (direct.resolveActivity(context.packageManager) != null) {
                    context.startActivity(direct)
                    return@runCatching InstallApkResult.Started
                }
            }

            val view = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(baseFlags)
            }
            if (view.resolveActivity(context.packageManager) != null) {
                context.startActivity(view)
                return@runCatching InstallApkResult.Started
            }

            val install = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(baseFlags)
                putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
                putExtra(Intent.EXTRA_RETURN_RESULT, false)
            }
            context.startActivity(install)
            InstallApkResult.Started
        }.getOrElse { err ->
            InstallApkResult.Failed(err.message ?: "Gagal membuka installer")
        }
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
            "com.google.android.apps.nbu.files",
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
        return runCatching {
            val uri = uriFor(context, file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeTypeFor(file))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(Intent.createChooser(intent, "Buka dengan"))
            true
        }.getOrDefault(false)
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
                FileItem(file).isApp -> "application/vnd.android.package-archive"
                else -> "*/*"
            }
    }
}
