package com.zipextract.app.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
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
        /** Original content:// URI from Chrome / Downloads / FileProvider, if any. */
        val sourceUri: Uri?,
        /** True when [file] is a cache copy, not the on-disk original. */
        val isCacheCopy: Boolean,
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
                    isCacheCopy = false,
                )
            }
            "content" -> {
                // Prefer the real Downloads/storage path so "delete original" removes
                // Chrome's file — not only a cache copy under app files.
                val realFile = tryResolveFilesystemFile(context, uri, displayName)
                if (realFile != null) {
                    return ResolvedShare(
                        file = realFile,
                        displayName = displayName ?: realFile.name,
                        mimeType = resolvedMime,
                        sourceUri = uri,
                        isCacheCopy = false,
                    )
                }
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
                    isCacheCopy = true,
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

    /**
     * Map a content:// URI (Chrome, Downloads, MediaStore, SAF) to a real [File]
     * when All Files Access / legacy storage exposes the path.
     */
    fun tryResolveFilesystemFile(
        context: Context,
        uri: Uri,
        displayNameHint: String? = null,
    ): File? {
        queryPathColumn(context, uri)?.let { return it }

        if (DocumentsContract.isDocumentUri(context, uri)) {
            resolveDocumentUri(context, uri)?.let { return it }
        }

        // MediaStore-style: content://media/.../downloads/123
        resolveMediaStoreIdUri(context, uri)?.let { return it }

        val name = displayNameHint?.takeIf { it.isNotBlank() }
            ?: resolveDisplayName(context, uri)
            ?: return null
        return findPublicFileByDisplayName(context, name)
    }

    fun resolveDisplayName(
        context: Context,
        uri: Uri,
        mimeType: String? = null,
    ): String? {
        val fromProvider = queryDisplayName(context, uri)
        if (!fromProvider.isNullOrBlank()) return fromProvider

        val lastSegment = uri.lastPathSegment?.takeIf { it.isNotBlank() }
        if (!lastSegment.isNullOrBlank() && lastSegment.contains('.')) {
            return Uri.decode(lastSegment.substringAfterLast(':').substringAfterLast('/'))
        }

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

    fun isVideo(file: File, mimeType: String? = null): Boolean {
        if (FileItem(file).isVideo) return true
        if (mimeType?.startsWith("video/", ignoreCase = true) == true) return true
        return false
    }

    fun isAppCacheFile(file: File): Boolean {
        val path = file.absolutePath
        return path.contains("/cache/incoming") ||
            path.contains("/code_cache/") ||
            (path.contains("/com.zipextract.app/") && path.contains("/cache/"))
    }

    @Suppress("DEPRECATION")
    private fun queryPathColumn(context: Context, uri: Uri): File? {
        val projection = arrayOf(MediaStore.MediaColumns.DATA)
        return runCatching {
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                if (idx < 0 || !cursor.moveToFirst()) return@use null
                val path = cursor.getString(idx)?.takeIf { it.isNotBlank() } ?: return@use null
                File(path).takeIf { it.isFile }
            }
        }.getOrNull()
    }

    @Suppress("DEPRECATION")
    private fun resolveDocumentUri(context: Context, uri: Uri): File? {
        val docId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull() ?: return null
        when {
            docId.startsWith("raw:", ignoreCase = true) -> {
                val path = Uri.decode(docId.removePrefix("raw:").removePrefix("RAW:"))
                return File(path).takeIf { it.isFile }
            }
            docId.startsWith("msf:", ignoreCase = true) ||
                docId.startsWith("msd:", ignoreCase = true) -> {
                val id = docId.substringAfter(':')
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    runCatching {
                        val contentUri = ContentUris.withAppendedId(
                            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                            id.toLong(),
                        )
                        queryPathColumn(context, contentUri)
                    }.getOrNull()?.let { return it }
                }
                return findByMediaStoreId(context, id)
            }
            docId.contains(':') -> {
                val type = docId.substringBefore(':')
                val relative = docId.substringAfter(':')
                if (type.equals("primary", ignoreCase = true)) {
                    val file = File(Environment.getExternalStorageDirectory(), relative)
                    if (file.isFile) return file
                }
            }
        }
        return null
    }

    @Suppress("DEPRECATION")
    private fun resolveMediaStoreIdUri(context: Context, uri: Uri): File? {
        val authority = uri.authority ?: return null
        if (!authority.contains("media", ignoreCase = true) &&
            !authority.contains("downloads", ignoreCase = true)
        ) {
            return null
        }
        val id = uri.lastPathSegment?.toLongOrNull() ?: return null
        val collections = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(MediaStore.Downloads.EXTERNAL_CONTENT_URI)
            }
            add(MediaStore.Files.getContentUri("external"))
        }
        collections.forEach { collection ->
            runCatching {
                val contentUri = ContentUris.withAppendedId(collection, id)
                queryPathColumn(context, contentUri)?.let { return it }
            }
        }
        return findByMediaStoreId(context, id.toString())
    }

    @Suppress("DEPRECATION")
    private fun findByMediaStoreId(context: Context, id: String): File? {
        val longId = id.toLongOrNull() ?: return null
        val collections = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(MediaStore.Downloads.EXTERNAL_CONTENT_URI)
            }
            add(MediaStore.Files.getContentUri("external"))
        }
        val projection = arrayOf(MediaStore.MediaColumns.DATA)
        collections.forEach { collection ->
            runCatching {
                context.contentResolver.query(
                    collection,
                    projection,
                    MediaStore.MediaColumns._ID + "=?",
                    arrayOf(longId.toString()),
                    null,
                )?.use { cursor ->
                    val idx = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                    if (idx >= 0 && cursor.moveToFirst()) {
                        val path = cursor.getString(idx)
                        if (!path.isNullOrBlank()) {
                            val file = File(path)
                            if (file.isFile) return file
                        }
                    }
                }
            }
        }
        return null
    }

    /**
     * Locate a public file by display name (Chrome downloads often land in Download/).
     */
    @Suppress("DEPRECATION")
    fun findPublicFileByDisplayName(context: Context, displayName: String): File? {
        val name = displayName.trim()
        if (name.isEmpty()) return null

        // MediaStore first — covers Download/, Documents/, etc. indexed by the system.
        val projection = arrayOf(MediaStore.MediaColumns.DATA)
        val collections = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(MediaStore.Downloads.EXTERNAL_CONTENT_URI)
            }
            add(MediaStore.Files.getContentUri("external"))
        }
        collections.forEach { collection ->
            runCatching {
                context.contentResolver.query(
                    collection,
                    projection,
                    MediaStore.MediaColumns.DISPLAY_NAME + "=?",
                    arrayOf(name),
                    MediaStore.MediaColumns.DATE_ADDED + " DESC",
                )?.use { cursor ->
                    val idx = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                    while (idx >= 0 && cursor.moveToNext()) {
                        val path = cursor.getString(idx) ?: continue
                        val file = File(path)
                        if (file.isFile) return file
                    }
                }
            }
        }

        val candidates = listOf(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            File(Environment.getExternalStorageDirectory(), "Download"),
            File(Environment.getExternalStorageDirectory(), "Downloads"),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
        )
        candidates.forEach { dir ->
            if (!dir.isDirectory) return@forEach
            val direct = File(dir, name)
            if (direct.isFile) return direct
            // One-level scan for OEM subfolders (e.g. Download/Chrome).
            dir.listFiles()?.forEach { child ->
                if (child.isFile && child.name == name) return child
                if (child.isDirectory) {
                    val nested = File(child, name)
                    if (nested.isFile) return nested
                }
            }
        }
        return null
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
