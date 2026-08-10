package com.zipextract.app.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileInputStream
import java.util.Locale

object SharedFileResolver {

    data class ResolvedShare(
        val file: File,
        val displayName: String,
        val mimeType: String?,
        val sourceUri: Uri?,
    )

    fun resolveShare(
        context: Context,
        uri: Uri,
        mimeType: String? = null,
    ): ResolvedShare? {
        val resolvedMime = mimeType?.takeIf { it.isNotBlank() }
            ?: context.contentResolver.getType(uri)
        val displayName = resolveDisplayName(context, uri, resolvedMime)
        return when (uri.scheme?.lowercase(Locale.ROOT)) {
            "file" -> {
                val file = uri.path?.let(::File)?.takeIf { it.exists() && it.isFile } ?: return null
                ResolvedShare(
                    file = file,
                    displayName = displayName ?: file.name,
                    mimeType = resolvedMime,
                    sourceUri = null,
                )
            }
            "content" -> {
                val extension = extensionFromName(displayName)
                    ?: extensionFromMime(resolvedMime)
                    ?: "bin"
                val localFile = copyContentUriToCache(
                    context = context,
                    uri = uri,
                    displayName = displayName,
                    extension = extension,
                ) ?: return null
                ResolvedShare(
                    file = localFile,
                    displayName = displayName ?: localFile.name,
                    mimeType = resolvedMime,
                    sourceUri = uri,
                )
            }
            else -> null
        }
    }

    fun resolveToLocalFile(
        context: Context,
        uri: Uri,
        mimeType: String? = null,
    ): File? = resolveShare(context, uri, mimeType)?.file

    fun resolveDisplayName(
        context: Context,
        uri: Uri,
        mimeType: String? = null,
    ): String? {
        val fromProvider = queryDisplayName(context, uri)
        if (!fromProvider.isNullOrBlank()) return fromProvider

        val lastSegment = uri.lastPathSegment?.takeIf { it.isNotBlank() }
        if (!lastSegment.isNullOrBlank() && lastSegment.contains('.')) return lastSegment

        return when (extensionFromMime(mimeType ?: context.contentResolver.getType(uri))) {
            "pdf" -> "document.pdf"
            "jpg" -> "image.jpg"
            "png" -> "image.png"
            "zip" -> "archive.zip"
            else -> null
        }
    }

    fun isPdf(file: File, mimeType: String? = null): Boolean {
        if (FileItem(file).isPdf) return true
        if (mimeType?.contains("pdf", ignoreCase = true) == true) return true
        return hasPdfHeader(file)
    }

    fun isImage(file: File, mimeType: String? = null): Boolean {
        if (FileItem(file).isImage) return true
        if (mimeType?.startsWith("image/", ignoreCase = true) == true) return true
        return false
    }

    private fun copyContentUriToCache(
        context: Context,
        uri: Uri,
        displayName: String?,
        extension: String,
    ): File? {
        val safeBase = displayName
            ?.substringBeforeLast('.')
            ?.replace(Regex("[^A-Za-z0-9._-]"), "_")
            ?.takeIf { it.isNotBlank() }
            ?: "shared_${System.currentTimeMillis()}"

        val cacheDir = File(context.cacheDir, "incoming").apply { mkdirs() }
        val target = File(cacheDir, "${safeBase}_${System.currentTimeMillis()}.$extension")

        return try {
            val copied = copyUriBytes(context, uri, target)
            if (!copied) {
                target.delete()
                return null
            }
            target.takeIf { it.exists() && it.length() > 0L }
        } catch (_: Exception) {
            target.delete()
            null
        }
    }

    private fun copyUriBytes(context: Context, uri: Uri, target: File): Boolean {
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output ->
                input.copyTo(output)
            }
            return true
        }

        context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            FileInputStream(pfd.fileDescriptor).use { input ->
                target.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            return true
        }

        return false
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
        }
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

    private fun hasPdfHeader(file: File): Boolean {
        if (!file.exists() || !file.isFile || file.length() < 5L) return false
        return runCatching {
            file.inputStream().use { stream ->
                val header = ByteArray(5)
                stream.read(header) == 5 && header.decodeToString().startsWith("%PDF")
            }
        }.getOrDefault(false)
    }
}
