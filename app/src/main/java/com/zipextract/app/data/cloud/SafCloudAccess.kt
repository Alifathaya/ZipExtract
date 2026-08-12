package com.zipextract.app.data.cloud

import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.result.contract.ActivityResultContract
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

object SafCloudAccess {

    /**
     * Soft-take persistable permission. Many cloud providers reject this;
     * temporary grants from the picker are still enough for a one-shot import/export.
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

    /**
     * Open a readable stream. Virtual docs often reject openInputStream but allow AFD.
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
     */
    fun copyUriToCache(
        context: Context,
        uri: Uri,
        preferredName: String? = null,
        tryPersist: Boolean = false,
    ): Pair<File?, String?> {
        return try {
            if (tryPersist) {
                tryTakePersistable(context, uri, allowWrite = false)
            }

            val name = preferredName ?: queryDisplayName(context, uri)
            val safe = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "cloud_file" }
            val dir = File(context.cacheDir, "cloud_imports").apply { mkdirs() }
            val target = File(dir, "${System.currentTimeMillis()}_$safe")

            val input = openReadableStream(context, uri)
                ?: return null to
                    "Sistem memblokir akses file. Unduh dulu di Files/Drive lalu buka lagi."

            input.use { stream ->
                FileOutputStream(target).use { output -> stream.copyTo(output) }
            }
            if (!target.exists() || target.length() <= 0L) {
                target.delete()
                return null to "File cloud kosong atau tidak bisa dibaca."
            }
            // Ensure bytes are fully flushed before the viewer opens the file.
            target to null
        } catch (security: SecurityException) {
            null to "Ditolak sistem (izin URI). Buka ulang lewat Cloud → Buka file cloud."
        } catch (t: Throwable) {
            null to (t.message ?: "Gagal mengimpor file cloud")
        }
    }

    /**
     * GET_CONTENT works well with Google Drive / cloud apps via the system picker.
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
