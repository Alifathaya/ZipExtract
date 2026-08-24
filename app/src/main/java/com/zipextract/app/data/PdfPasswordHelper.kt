package com.zipextract.app.data

import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.zipextract.app.R
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

/**
 * Detects password-protected PDFs and unlocks them to a temp file for [android.graphics.pdf.PdfRenderer].
 * Android's PdfRenderer cannot open encrypted PDFs directly.
 */
object PdfPasswordHelper {
    @Volatile
    private var initialized = false

    sealed class ProbeResult {
        data object Openable : ProbeResult()
        data object NeedsPassword : ProbeResult()
        data class Failed(val message: String) : ProbeResult()
    }

    fun ensureInit(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            PDFBoxResourceLoader.init(context.applicationContext)
            initialized = true
        }
    }

    /** Copy content:// (or empty path) into a readable cache file when needed. */
    fun materializeLocalFile(context: Context, file: File, sourceUri: Uri?): File {
        if (file.exists() && file.isFile && file.length() > 0L) return file
        val uri = sourceUri ?: throw IOException(context.getString(R.string.pdf_open_failed))
        val dir = File(context.cacheDir, "pdf_source").also { it.mkdirs() }
        val name = file.name.ifBlank { "document.pdf" }.let { raw ->
            if (raw.endsWith(".pdf", ignoreCase = true)) raw else "$raw.pdf"
        }
        val out = File(dir, "${uri.hashCode()}_$name")
        if (out.exists() && out.length() > 0L) return out
        context.contentResolver.openInputStream(uri)?.use { input ->
            out.outputStream().use { output -> input.copyTo(output) }
        } ?: throw IOException(context.getString(R.string.pdf_open_failed))
        if (!out.exists() || out.length() <= 0L) {
            throw IOException(context.getString(R.string.pdf_open_failed))
        }
        return out
    }

    fun probe(context: Context, file: File): ProbeResult {
        ensureInit(context)
        return try {
            PDDocument.load(file).use { /* readable without password */ }
            ProbeResult.Openable
        } catch (e: InvalidPasswordException) {
            ProbeResult.NeedsPassword
        } catch (e: IOException) {
            if (isPasswordRelated(e)) ProbeResult.NeedsPassword
            else ProbeResult.Failed(e.message ?: context.getString(R.string.pdf_open_failed))
        } catch (e: Exception) {
            if (isPasswordRelated(e)) ProbeResult.NeedsPassword
            else ProbeResult.Failed(e.message ?: context.getString(R.string.pdf_open_failed))
        }
    }

    /**
     * Decrypt [file] with [password] and write an unencrypted copy under cacheDir.
     * @throws InvalidPasswordException when the password is wrong
     */
    fun unlockToCache(context: Context, file: File, password: String): File {
        ensureInit(context)
        val pass = password.trim()
        if (pass.isEmpty()) {
            throw IOException(context.getString(R.string.pdf_password_required))
        }
        val dir = File(context.cacheDir, "pdf_unlocked").also { it.mkdirs() }
        val out = File(
            dir,
            "${file.nameWithoutExtension}_${file.length()}_${pass.hashCode()}.pdf",
        )
        if (out.exists() && out.length() > 0L) {
            // Verify cached unlock still matches this password.
            try {
                PDDocument.load(out).use { return out }
            } catch (_: Exception) {
                out.delete()
            }
        }
        PDDocument.load(file, pass).use { doc ->
            doc.setAllSecurityToBeRemoved(true)
            doc.save(out)
        }
        return out
    }

    /**
     * Encrypt [file] with a user password and write a locked copy under cacheDir for sharing.
     * Recipients open it in any PDF reader that supports passwords (including FileNest).
     */
    fun encryptToCache(context: Context, file: File, password: String): File {
        ensureInit(context)
        val pass = password.trim()
        if (pass.isEmpty()) {
            throw IOException(context.getString(R.string.pdf_password_required))
        }
        if (!file.exists() || !file.isFile) {
            throw IOException(context.getString(R.string.pdf_open_failed))
        }
        val dir = File(context.cacheDir, "pdf_share_locked").also { it.mkdirs() }
        val baseName = file.nameWithoutExtension.ifBlank { "document" }
        val out = File(dir, "${baseName}_locked_${ENCRYPT_SEQ.incrementAndGet()}.pdf")
        try {
            PDDocument.load(file).use { doc ->
                if (doc.isEncrypted) {
                    doc.setAllSecurityToBeRemoved(true)
                }
                val permissions = AccessPermission()
                val policy = StandardProtectionPolicy(pass, pass, permissions).apply {
                    encryptionKeyLength = 128
                }
                doc.protect(policy)
                doc.save(out)
            }
        } catch (e: InvalidPasswordException) {
            throw IOException(context.getString(R.string.share_password_pdf_already_locked), e)
        } catch (e: IOException) {
            if (isPasswordRelated(e)) {
                throw IOException(context.getString(R.string.share_password_pdf_already_locked), e)
            }
            throw e
        }
        if (!out.exists() || out.length() <= 0L) {
            throw IOException(context.getString(R.string.share_password_failed))
        }
        return out
    }

    fun isPasswordRelated(error: Throwable): Boolean {
        var current: Throwable? = error
        while (current != null) {
            if (current is InvalidPasswordException) return true
            val message = current.message.orEmpty().lowercase()
            if (message.contains("password") ||
                message.contains("encrypted") ||
                message.contains("security") ||
                message.contains("crypt")
            ) {
                return true
            }
            current = current.cause
        }
        return false
    }

    private val ENCRYPT_SEQ = AtomicLong(0L)
}
