package com.zipextract.app.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import com.zipextract.app.R
import java.io.File
import java.util.Locale

object FileActions {

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
        return runCatching {
            val uri = uriFor(context, file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeTypeFor(file))
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
                FileItem(file).isApp -> "application/vnd.android.package-archive"
                else -> "*/*"
            }
    }
}
