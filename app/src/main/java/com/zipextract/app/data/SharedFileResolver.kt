package com.zipextract.app.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import java.io.File
import java.util.Locale

object SharedFileResolver {

    fun resolveToLocalFile(
        context: Context,
        uri: Uri,
        mimeType: String? = null,
    ): File? {
        return when (uri.scheme?.lowercase(Locale.ROOT)) {
            "file" -> uri.path?.let(::File)?.takeIf { it.exists() && it.isFile }
            "content" -> copyContentUriToCache(context, uri, mimeType)
            else -> null
        }
    }

    private fun copyContentUriToCache(
        context: Context,
        uri: Uri,
        mimeType: String?,
    ): File? {
        val displayName = queryDisplayName(context, uri)
        val extension = extensionFromName(displayName)
            ?: extensionFromMime(mimeType ?: context.contentResolver.getType(uri))
            ?: "bin"
        val safeBase = displayName
            ?.substringBeforeLast('.')
            ?.replace(Regex("[^A-Za-z0-9._-]"), "_")
            ?.takeIf { it.isNotBlank() }
            ?: "shared_${System.currentTimeMillis()}"

        val cacheDir = File(context.cacheDir, "incoming").apply { mkdirs() }
        val target = File(cacheDir, "$safeBase.$extension")

        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: return null
            target.takeIf { it.exists() && it.length() > 0L }
        } catch (_: Exception) {
            target.delete()
            null
        }
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) {
                    cursor.getString(index)
                } else {
                    null
                }
            }
        } catch (_: Exception) {
            null
        } ?: uri.lastPathSegment
    }

    private fun extensionFromName(name: String?): String? {
        if (name.isNullOrBlank()) return null
        val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return ext.takeIf { it.isNotBlank() && ext != name.lowercase(Locale.ROOT) }
    }

    private fun extensionFromMime(mimeType: String?): String? {
        if (mimeType.isNullOrBlank()) return null
        return when (mimeType.lowercase(Locale.ROOT)) {
            "application/pdf" -> "pdf"
            "application/zip", "application/x-zip-compressed" -> "zip"
            "image/jpeg" -> "jpg"
            "image/png" -> "png"
            "image/webp" -> "webp"
            "image/gif" -> "gif"
            else -> MimeTypeMap.getSingleton()
                .getExtensionFromMimeType(mimeType)
                ?.lowercase(Locale.ROOT)
        }
    }
}
