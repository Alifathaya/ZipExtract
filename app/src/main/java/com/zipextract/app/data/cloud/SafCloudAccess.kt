package com.zipextract.app.data.cloud

import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.FileOutputStream

object SafCloudAccess {

    fun takePersistableReadWrite(context: Context, uri: Uri, write: Boolean = true) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
            if (write) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, flags)
        }
    }

    fun releasePersistable(context: Context, uri: Uri) {
        runCatching {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.releasePersistableUriPermission(uri, flags)
        }
    }

    fun persistedTreeUris(context: Context): List<Uri> {
        return context.contentResolver.persistedUriPermissions
            .filter { it.isReadPermission }
            .map { it.uri }
            .filter { DocumentsContract.isTreeUri(it) }
    }

    fun labelForTree(context: Context, treeUri: Uri): String {
        val doc = DocumentFile.fromTreeUri(context, treeUri)
        return doc?.name?.takeIf { it.isNotBlank() }
            ?: treeUri.lastPathSegment?.substringAfterLast(':')
            ?: "Folder cloud"
    }

    fun listChildren(context: Context, treeUri: Uri): List<CloudFileItem> {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return emptyList()
        return root.listFiles().mapNotNull { child ->
            val uri = child.uri
            CloudFileItem(
                id = uri.toString(),
                name = child.name ?: "Tanpa nama",
                mimeType = child.type ?: if (child.isDirectory) DocumentsContract.Document.MIME_TYPE_DIR else "*/*",
                sizeBytes = if (child.isFile) child.length() else null,
                isFolder = child.isDirectory,
            )
        }.sortedWith(compareByDescending<CloudFileItem> { it.isFolder }.thenBy { it.name.lowercase() })
    }

    fun queryDisplayName(context: Context, uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor: Cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) return cursor.getString(idx) ?: "cloud_file"
                }
            }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "cloud_file"
    }

    fun copyUriToCache(context: Context, uri: Uri, preferredName: String? = null): File? {
        return runCatching {
            val name = preferredName ?: queryDisplayName(context, uri)
            val safe = name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
            val dir = File(context.cacheDir, "cloud_imports").apply { mkdirs() }
            val target = File(dir, "${System.currentTimeMillis()}_$safe")
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(target).use { output -> input.copyTo(output) }
            } ?: return null
            target
        }.getOrNull()
    }

    fun exportFileToTree(
        context: Context,
        treeUri: Uri,
        source: File,
        mimeType: String = "application/octet-stream",
    ): Boolean {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return false
        if (!root.canWrite()) return false
        val existing = root.findFile(source.name)
        existing?.delete()
        val created = root.createFile(mimeType, source.name) ?: return false
        return runCatching {
            context.contentResolver.openOutputStream(created.uri)?.use { out ->
                source.inputStream().use { input -> input.copyTo(out) }
            } != null
        }.getOrDefault(false)
    }
}
