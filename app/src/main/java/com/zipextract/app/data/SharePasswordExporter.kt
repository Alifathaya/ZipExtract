package com.zipextract.app.data

import android.content.Context
import com.zipextract.app.R
import java.io.File

/**
 * Prepare a single file for "share with password":
 * - PDF → native encrypted PDF
 * - anything else → AES-encrypted ZIP containing the file
 */
object SharePasswordExporter {
    fun exportLocked(context: Context, source: File, password: String): File {
        val pass = password.trim()
        require(pass.isNotEmpty()) { context.getString(R.string.share_password_required) }
        require(source.exists() && source.isFile) {
            context.getString(R.string.share_password_failed)
        }
        return if (FileItem(source).isPdf) {
            PdfPasswordHelper.encryptToCache(context, source, pass)
        } else {
            val zipDir = File(context.cacheDir, "share_zip").also { it.mkdirs() }
            val base = source.nameWithoutExtension.ifBlank { "shared" }
            val destination = FileOperations.uniqueName(File(zipDir, "$base.zip"))
            ZipManager.createZip(
                context = context,
                sources = listOf(source),
                destinationZip = destination,
                password = pass,
            )
            destination
        }
    }
}
