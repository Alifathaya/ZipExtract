package com.zipextract.app.data

import android.content.Context
import com.github.junrar.Archive
import com.github.junrar.exception.CrcErrorException
import com.github.junrar.exception.InitDeciphererFailedException
import com.github.junrar.exception.UnsupportedRarV5Exception
import com.zipextract.app.R
import org.apache.commons.compress.PasswordRequiredException
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream

/**
 * Multi-format archive extraction: RAR (4.x), 7z, tar, tar.gz/tgz, tar.bz2,
 * plus single-file gz/bz2. ZIP-family archives are delegated to [ZipManager].
 */
object ArchiveManager {

    enum class Kind { ZIP, RAR, SEVEN_Z, TAR, TAR_GZ, TAR_BZ2, GZ, BZ2, UNSUPPORTED }

    fun kindOf(file: File): Kind {
        val name = file.name.lowercase()
        return when {
            ZipManager.isSupportedZipFile(file) -> Kind.ZIP
            name.endsWith(".rar") -> Kind.RAR
            name.endsWith(".7z") -> Kind.SEVEN_Z
            name.endsWith(".tar.gz") || name.endsWith(".tgz") -> Kind.TAR_GZ
            name.endsWith(".tar.bz2") || name.endsWith(".tbz2") -> Kind.TAR_BZ2
            name.endsWith(".tar") -> Kind.TAR
            name.endsWith(".gz") -> Kind.GZ
            name.endsWith(".bz2") -> Kind.BZ2
            else -> Kind.UNSUPPORTED
        }
    }

    fun isSupportedArchive(file: File): Boolean = kindOf(file) != Kind.UNSUPPORTED

    /** Detect password protection without extracting. Safe to call from IO dispatcher. */
    fun isPasswordProtected(file: File): Boolean {
        return when (kindOf(file)) {
            Kind.ZIP -> ZipManager.isEncryptedZip(file)
            Kind.RAR -> runCatching {
                Archive(file).use { archive ->
                    archive.isEncrypted || archive.fileHeaders.any { it.isEncrypted }
                }
            }.getOrDefault(false)
            Kind.SEVEN_Z -> runCatching {
                SevenZFile.builder().setFile(file).get().use { sz ->
                    var entry = sz.nextEntry
                    while (entry != null && entry.isDirectory) entry = sz.nextEntry
                    if (entry != null && entry.size > 0) {
                        sz.read(ByteArray(1))
                    }
                }
                false
            }.getOrElse { it is PasswordRequiredException }
            else -> false
        }
    }

    /**
     * Extract every entry; returns the number of files written.
     * Throws [ZipManager.ZipPasswordException] for missing/wrong passwords.
     */
    fun extractArchive(
        context: Context,
        file: File,
        destinationDir: File,
        password: String? = null,
        onProgress: ((Float, String) -> Unit)? = null,
    ): Int {
        require(file.exists() && file.isFile) { context.getString(R.string.zip_not_found) }
        require(file.length() > 0L) { context.getString(R.string.zip_empty_file) }
        if (!destinationDir.isDirectory) destinationDir.mkdirs()

        return when (kindOf(file)) {
            Kind.ZIP -> {
                val paths = ZipManager.listZipEntryDetails(context, file).map { it.path }.toSet()
                if (paths.isEmpty()) error(context.getString(R.string.zip_empty_unreadable))
                ZipManager.extractZipEntries(context, file, destinationDir, paths, password, onProgress)
            }
            Kind.RAR -> extractRar(context, file, destinationDir, password, onProgress)
            Kind.SEVEN_Z -> extract7z(context, file, destinationDir, password, onProgress)
            Kind.TAR -> countingStream(file) { input, progressOf ->
                extractTar(context, input, destinationDir, progressOf, onProgress)
            }
            Kind.TAR_GZ -> countingStream(file) { input, progressOf ->
                extractTar(context, GzipCompressorInputStream(input), destinationDir, progressOf, onProgress)
            }
            Kind.TAR_BZ2 -> countingStream(file) { input, progressOf ->
                extractTar(context, BZip2CompressorInputStream(input), destinationDir, progressOf, onProgress)
            }
            Kind.GZ -> countingStream(file) { input, progressOf ->
                extractSingle(
                    GzipCompressorInputStream(input),
                    destinationDir,
                    file.name.removeSuffix(".gz").removeSuffix(".GZ").ifBlank { "extracted" },
                    progressOf,
                    onProgress,
                )
            }
            Kind.BZ2 -> countingStream(file) { input, progressOf ->
                extractSingle(
                    BZip2CompressorInputStream(input),
                    destinationDir,
                    file.name.removeSuffix(".bz2").removeSuffix(".BZ2").ifBlank { "extracted" },
                    progressOf,
                    onProgress,
                )
            }
            Kind.UNSUPPORTED -> error(
                context.getString(R.string.format_unsupported_ext, file.extension),
            )
        }
    }

    private fun extractRar(
        context: Context,
        file: File,
        destinationDir: File,
        password: String?,
        onProgress: ((Float, String) -> Unit)?,
    ): Int {
        try {
            val archive = if (password.isNullOrBlank()) Archive(file) else Archive(file, password)
            archive.use { rar ->
                val headers = rar.fileHeaders
                val locked = rar.isEncrypted || headers.any { it.isEncrypted }
                if (locked && password.isNullOrBlank()) {
                    throw ZipManager.ZipPasswordException(context.getString(R.string.zip_password_required))
                }
                val total = headers.size.coerceAtLeast(1)
                var written = 0
                headers.forEachIndexed { index, header ->
                    val name = header.fileName.replace('\\', '/')
                    val safe = ZipManager.sanitizeEntryName(name)
                    onProgress?.invoke((index + 1f) / total, safe.ifBlank { name })
                    if (header.isDirectory || safe.isEmpty()) {
                        if (safe.isNotEmpty()) File(destinationDir, safe).mkdirs()
                        return@forEachIndexed
                    }
                    val out = safeResolve(destinationDir, safe)
                    out.parentFile?.mkdirs()
                    FileOutputStream(out).use { fos ->
                        BufferedOutputStream(fos).use { bos ->
                            rar.extractFile(header, bos)
                        }
                    }
                    written++
                }
                return written
            }
        } catch (e: UnsupportedRarV5Exception) {
            error(context.getString(R.string.rar5_unsupported))
        } catch (e: CrcErrorException) {
            if (!password.isNullOrBlank()) {
                throw ZipManager.ZipPasswordException(context.getString(R.string.zip_wrong_password))
            }
            throw e
        } catch (e: InitDeciphererFailedException) {
            throw ZipManager.ZipPasswordException(context.getString(R.string.zip_wrong_password))
        }
    }

    private fun extract7z(
        context: Context,
        file: File,
        destinationDir: File,
        password: String?,
        onProgress: ((Float, String) -> Unit)?,
    ): Int {
        try {
            val builder = SevenZFile.builder().setFile(file)
            if (!password.isNullOrBlank()) builder.setPassword(password.toCharArray())
            builder.get().use { sz ->
                val total = sz.entries.count().coerceAtLeast(1)
                var processed = 0
                var written = 0
                val buffer = ByteArray(DEFAULT_BUFFER)
                var entry = sz.nextEntry
                while (entry != null) {
                    val safe = ZipManager.sanitizeEntryName(entry.name.replace('\\', '/'))
                    onProgress?.invoke(++processed / total.toFloat(), safe.ifBlank { entry.name })
                    if (entry.isDirectory || safe.isEmpty()) {
                        if (safe.isNotEmpty()) File(destinationDir, safe).mkdirs()
                    } else {
                        val out = safeResolve(destinationDir, safe)
                        out.parentFile?.mkdirs()
                        FileOutputStream(out).use { fos ->
                            BufferedOutputStream(fos).use { bos ->
                                var read = sz.read(buffer)
                                while (read > 0) {
                                    bos.write(buffer, 0, read)
                                    read = sz.read(buffer)
                                }
                            }
                        }
                        written++
                    }
                    entry = sz.nextEntry
                }
                return written
            }
        } catch (e: PasswordRequiredException) {
            throw ZipManager.ZipPasswordException(context.getString(R.string.zip_password_required))
        } catch (e: IOException) {
            val text = e.message.orEmpty()
            if (!password.isNullOrBlank() &&
                (text.contains("checksum", ignoreCase = true) || text.contains("crc", ignoreCase = true))
            ) {
                throw ZipManager.ZipPasswordException(context.getString(R.string.zip_wrong_password))
            }
            throw e
        }
    }

    private fun extractTar(
        context: Context,
        input: InputStream,
        destinationDir: File,
        progressOf: () -> Float,
        onProgress: ((Float, String) -> Unit)?,
    ): Int {
        TarArchiveInputStream(BufferedInputStream(input)).use { tar ->
            var written = 0
            var entry = tar.nextTarEntry
            while (entry != null) {
                val safe = ZipManager.sanitizeEntryName(entry.name.replace('\\', '/'))
                onProgress?.invoke(progressOf(), safe.ifBlank { entry.name })
                if (entry.isDirectory || safe.isEmpty()) {
                    if (safe.isNotEmpty()) File(destinationDir, safe).mkdirs()
                } else {
                    val out = safeResolve(destinationDir, safe)
                    out.parentFile?.mkdirs()
                    FileOutputStream(out).use { fos ->
                        BufferedOutputStream(fos).use { bos ->
                            tar.copyTo(bos, bufferSize = DEFAULT_BUFFER)
                        }
                    }
                    written++
                }
                entry = tar.nextTarEntry
            }
            if (written <= 0) error(context.getString(R.string.zip_no_matching_files))
            return written
        }
    }

    private fun extractSingle(
        input: InputStream,
        destinationDir: File,
        outputName: String,
        progressOf: () -> Float,
        onProgress: ((Float, String) -> Unit)?,
    ): Int {
        val safe = ZipManager.sanitizeEntryName(outputName).ifBlank { "extracted" }
        val out = safeResolve(destinationDir, safe)
        out.parentFile?.mkdirs()
        onProgress?.invoke(0f, safe)
        input.use { stream ->
            FileOutputStream(out).use { fos ->
                BufferedOutputStream(fos).use { bos ->
                    val buffer = ByteArray(DEFAULT_BUFFER)
                    var read = stream.read(buffer)
                    while (read > 0) {
                        bos.write(buffer, 0, read)
                        onProgress?.invoke(progressOf(), safe)
                        read = stream.read(buffer)
                    }
                }
            }
        }
        return 1
    }

    /** Wraps the file stream so tar/gz progress can be derived from compressed bytes read. */
    private fun <T> countingStream(file: File, block: (InputStream, () -> Float) -> T): T {
        val totalBytes = file.length().coerceAtLeast(1L)
        var readBytes = 0L
        val counting = object : FilterInputStream(FileInputStream(file)) {
            override fun read(): Int {
                val value = super.read()
                if (value >= 0) readBytes++
                return value
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                val n = super.read(b, off, len)
                if (n > 0) readBytes += n
                return n
            }
        }
        return counting.use { block(it, { (readBytes.toFloat() / totalBytes).coerceIn(0f, 1f) }) }
    }

    private fun safeResolve(baseDir: File, safeName: String): File {
        val base = baseDir.absoluteFile.normalize()
        val target = File(base, safeName).normalize()
        val prefix = if (base.path.endsWith(File.separator)) base.path else base.path + File.separator
        if (target.path != base.path && !target.path.startsWith(prefix)) {
            throw SecurityException("Invalid entry path: $safeName")
        }
        return target
    }

    private const val DEFAULT_BUFFER = 64 * 1024
}
