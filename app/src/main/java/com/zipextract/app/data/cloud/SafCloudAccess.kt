package com.zipextract.app.data.cloud

import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.activity.result.contract.ActivityResultContract
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

object SafCloudAccess {

    /**
     * Soft-take persistable permission. Many cloud providers (esp. Drive) reject this;
     * temporary grants from the picker are still enough for a one-shot import.
     */
    fun tryTakePersistable(context: Context, uri: Uri, allowWrite: Boolean = false): Boolean {
        val readWrite = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        val readOnly = Intent.FLAG_GRANT_READ_URI_PERMISSION
        return runCatching {
            if (allowWrite) {
                context.contentResolver.takePersistableUriPermission(uri, readWrite)
            } else {
                context.contentResolver.takePersistableUriPermission(uri, readOnly)
            }
            true
        }.recoverCatching {
            if (allowWrite) {
                context.contentResolver.takePersistableUriPermission(uri, readOnly)
                true
            } else {
                false
            }
        }.getOrDefault(false)
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
            .filter { DocumentsContract.isTreeUri(it) || isLikelyTree(it) }
    }

    private fun isLikelyTree(uri: Uri): Boolean {
        return uri.toString().contains("/tree/")
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
                mimeType = child.type
                    ?: if (child.isDirectory) DocumentsContract.Document.MIME_TYPE_DIR else "*/*",
                sizeBytes = if (child.isFile) child.length() else null,
                isFolder = child.isDirectory,
            )
        }.sortedWith(
            compareByDescending<CloudFileItem> { it.isFolder }.thenBy { it.name.lowercase() },
        )
    }

    fun queryDisplayName(context: Context, uri: Uri): String {
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor: Cursor ->
                    if (cursor.moveToFirst()) {
                        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) {
                            val name = cursor.getString(idx)
                            if (!name.isNullOrBlank()) return name
                        }
                    }
                }
        }
        return uri.lastPathSegment?.substringAfterLast(':')?.substringAfterLast('/') ?: "cloud_file"
    }

    fun queryMimeType(context: Context, uri: Uri): String {
        return context.contentResolver.getType(uri) ?: "application/octet-stream"
    }

    /**
     * Open a readable stream. Drive/virtual docs often reject openInputStream but allow AFD.
     */
    private fun openReadableStream(context: Context, uri: Uri): InputStream? {
        val cr = context.contentResolver
        runCatching { cr.openInputStream(uri) }.getOrNull()?.let { return it }
        runCatching { cr.openAssetFileDescriptor(uri, "r")?.createInputStream() }.getOrNull()?.let { return it }
        runCatching {
            cr.openTypedAssetFileDescriptor(uri, "*/*", null)?.createInputStream()
        }.getOrNull()?.let { return it }
        return null
    }

    /**
     * Copy using the temporary URI grant from the picker. Returns null + error message on failure.
     * @param tryPersist soft-take persistable permission (often rejected by Google Drive).
     */
    fun copyUriToCache(
        context: Context,
        uri: Uri,
        preferredName: String? = null,
        tryPersist: Boolean = true,
    ): Pair<File?, String?> {
        return try {
            if (tryPersist) {
                // Best-effort; Drive and some providers reject persistable grants by design.
                tryTakePersistable(context, uri, allowWrite = false)
            }

            val name = preferredName ?: queryDisplayName(context, uri)
            val safe = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "cloud_file" }
            val dir = File(context.cacheDir, "cloud_imports").apply { mkdirs() }
            val target = File(dir, "${System.currentTimeMillis()}_$safe")

            val input = openReadableStream(context, uri)
                ?: return null to
                    "Sistem memblokir akses file cloud. Pakai 'Buka file cloud (disarankan)', " +
                    "unduh dulu di app Drive, atau Login Google Drive native."

            input.use { stream ->
                FileOutputStream(target).use { output -> stream.copyTo(output) }
            }
            if (!target.exists() || target.length() <= 0L) {
                target.delete()
                return null to "File cloud kosong atau tidak bisa dibaca."
            }
            target to null
        } catch (security: SecurityException) {
            null to "Ditolak sistem (izin URI dilindungi). Buka ulang lewat Cloud → 'Buka file cloud (disarankan)'."
        } catch (t: Throwable) {
            null to (t.message ?: "Gagal mengimpor file cloud")
        }
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

    /** OPEN_DOCUMENT with persistable + read flags (required by Android for lasting access). */
    class OpenCloudDocument : ActivityResultContract<Array<String>, Uri?>() {
        override fun createIntent(context: Context, input: Array<String>): Intent {
            return Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(Intent.EXTRA_MIME_TYPES, input)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            }
        }

        override fun parseResult(resultCode: Int, intent: Intent?): Uri? {
            if (resultCode != android.app.Activity.RESULT_OK) return null
            return intent?.data
        }
    }

    /**
     * GET_CONTENT often works better with Google Drive / cloud apps than OPEN_DOCUMENT.
     * Temporary grant is enough to copy into app cache.
     */
    class GetCloudContent : ActivityResultContract<String, Uri?>() {
        override fun createIntent(context: Context, input: String): Intent {
            return Intent(Intent.ACTION_GET_CONTENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = input.ifBlank { "*/*" }
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }

        override fun parseResult(resultCode: Int, intent: Intent?): Uri? {
            if (resultCode != android.app.Activity.RESULT_OK) return null
            return intent?.data ?: intent?.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
        }
    }

    /** OPEN_DOCUMENT_TREE with persistable read/write/prefix flags. */
    class OpenCloudTree : ActivityResultContract<Uri?, Uri?>() {
        override fun createIntent(context: Context, input: Uri?): Intent {
            return Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PREFIX_URI_PERMISSION,
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && input != null) {
                    putExtra(DocumentsContract.EXTRA_INITIAL_URI, input)
                }
            }
        }

        override fun parseResult(resultCode: Int, intent: Intent?): Uri? {
            if (resultCode != android.app.Activity.RESULT_OK) return null
            return intent?.data
        }
    }

    class CreateCloudDocument : ActivityResultContract<String, Uri?>() {
        override fun createIntent(context: Context, input: String): Intent {
            return Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(Intent.EXTRA_TITLE, input)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            }
        }

        override fun parseResult(resultCode: Int, intent: Intent?): Uri? {
            if (resultCode != android.app.Activity.RESULT_OK) return null
            return intent?.data
        }
    }
}
