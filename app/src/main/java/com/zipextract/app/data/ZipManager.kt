package com.zipextract.app.data

import android.os.Environment
import net.lingala.zip4j.exception.ZipException
import net.lingala.zip4j.model.FileHeader
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object ZipManager {

    fun createZip(
        sources: List<File>,
        destinationZip: File,
        compressionLevel: Int = Deflater.DEFAULT_COMPRESSION,
        password: String? = null,
        onProgress: ((Float, String) -> Unit)? = null,
    ) {
        require(sources.isNotEmpty()) { "Tidak ada file yang dipilih" }
        destinationZip.parentFile?.mkdirs()

        val allFiles = mutableListOf<Pair<File, String>>()
        sources.forEach { source ->
            if (source.isDirectory) {
                collectFiles(source, source.name, allFiles)
            } else {
                allFiles += source to source.name
            }
        }

        if (!password.isNullOrBlank()) {
            createEncryptedZip(allFiles, destinationZip, password, onProgress)
            return
        }

        val total = allFiles.size.coerceAtLeast(1)
        ZipOutputStream(BufferedOutputStream(FileOutputStream(destinationZip))).use { zos ->
            zos.setLevel(compressionLevel.coerceIn(Deflater.NO_COMPRESSION, Deflater.BEST_COMPRESSION))
            allFiles.forEachIndexed { index, (file, entryName) ->
                onProgress?.invoke((index + 1f) / total, entryName)
                FileInputStream(file).use { fis ->
                    BufferedInputStream(fis).use { bis ->
                        val entry = ZipEntry(entryName.replace('\\', '/'))
                        entry.time = file.lastModified()
                        zos.putNextEntry(entry)
                        bis.copyTo(zos, bufferSize = DEFAULT_BUFFER)
                        zos.closeEntry()
                    }
                }
            }
        }
    }

    private fun createEncryptedZip(
        allFiles: List<Pair<File, String>>,
        destinationZip: File,
        password: String,
        onProgress: ((Float, String) -> Unit)?,
    ) {
        if (destinationZip.exists()) destinationZip.delete()
        val zipFile = net.lingala.zip4j.ZipFile(destinationZip, password.toCharArray())
        val total = allFiles.size.coerceAtLeast(1)
        allFiles.forEachIndexed { index, (file, entryName) ->
            onProgress?.invoke((index + 1f) / total, entryName)
            val params = net.lingala.zip4j.model.ZipParameters().apply {
                compressionMethod = net.lingala.zip4j.model.enums.CompressionMethod.DEFLATE
                compressionLevel = net.lingala.zip4j.model.enums.CompressionLevel.NORMAL
                isEncryptFiles = true
                encryptionMethod = net.lingala.zip4j.model.enums.EncryptionMethod.AES
                aesKeyStrength = net.lingala.zip4j.model.enums.AesKeyStrength.KEY_STRENGTH_256
                fileNameInZip = entryName.replace('\\', '/')
            }
            zipFile.addFile(file, params)
        }
    }

    fun extractZip(
        zipFile: File,
        destinationDir: File,
        onProgress: ((Float, String) -> Unit)? = null,
    ) {
        val entries = listZipEntryDetails(zipFile).map { it.path }.toSet()
        extractZipEntries(zipFile, destinationDir, entries, onProgress)
    }

    fun listZipEntries(zipFile: File): List<String> {
        return listZipEntryDetails(zipFile).map { it.path }
    }

    fun listZipEntryDetails(zipFile: File): List<ZipEntryItem> {
        require(zipFile.exists() && zipFile.isFile) { "File zip tidak ditemukan" }
        val fromZip4j = runCatching { listWithZip4j(zipFile) }.getOrNull()
        if (!fromZip4j.isNullOrEmpty()) return fromZip4j
        return runCatching { listWithJavaZip(zipFile) }.getOrElse { err ->
            throw IllegalStateException("Gagal membaca ZIP: ${err.message ?: "format tidak didukung"}", err)
        }
    }

    /**
     * Extract selected entries. Returns number of files written (directories not counted).
     * Tries ZipInputStream first (most compatible), then zip4j, then Java ZipFile.
     */
    fun extractZipEntries(
        zipFile: File,
        destinationDir: File,
        selectedPaths: Set<String>,
        onProgress: ((Float, String) -> Unit)? = null,
    ): Int {
        require(zipFile.exists() && zipFile.isFile) { "File zip tidak ditemukan" }
        require(selectedPaths.isNotEmpty()) { "Pilih minimal 1 file untuk diextract" }
        require(zipFile.length() > 0L) { "File ZIP kosong (0 byte)" }

        ensureDirectory(destinationDir)
        require(probeWritable(destinationDir)) {
            "Folder tujuan tidak bisa ditulis: ${destinationDir.absolutePath}"
        }

        val errors = mutableListOf<String>()

        runCatching {
            return extractWithZipInputStream(zipFile, destinationDir, selectedPaths, onProgress)
        }.onFailure { errors += "stream: ${it.message ?: it.javaClass.simpleName}" }

        runCatching {
            return extractWithZip4j(zipFile, destinationDir, selectedPaths, onProgress)
        }.onFailure { errors += "zip4j: ${it.message ?: it.javaClass.simpleName}" }

        runCatching {
            return extractWithJavaZip(zipFile, destinationDir, selectedPaths, onProgress)
        }.onFailure { errors += "java: ${it.message ?: it.javaClass.simpleName}" }

        error("Gagal extract ZIP (${errors.joinToString(" | ").ifBlank { "format tidak didukung" }})")
    }

    fun isSupportedZipFile(file: File): Boolean {
        val ext = file.extension.lowercase()
        return ext in setOf("zip", "jar", "apk", "xapk", "apks", "apkm")
    }

    /**
     * Pick a destination that is actually writable.
     * Prefers [preferred], then Download/FileNest, then app-specific external storage
     * (always writable even without All Files Access).
     */
    fun resolveWritableExtractDir(context: android.content.Context, zipFile: File, preferred: File?): File {
        val baseName = zipFile.nameWithoutExtension.trim().ifBlank { "extract" }
            .replace(Regex("""[\\/:*?"<>|]"""), "_")
        val stamp = System.currentTimeMillis() % 100000
        val candidates = buildList {
            preferred?.let { add(it) }
            add(File(publicFileNestDir(), baseName))
            add(File(publicFileNestDir(), "${baseName}_$stamp"))
            context.getExternalFilesDir("Extract")?.let { add(File(it, baseName)) }
            context.getExternalFilesDir("Extract")?.let { add(File(it, "${baseName}_$stamp")) }
            add(File(File(context.filesDir, "Extract").also { it.mkdirs() }, baseName))
        }
        candidates.forEach { dir ->
            runCatching {
                ensureDirectory(dir)
                if (probeWritable(dir)) return dir
            }
        }
        error("Tidak ada folder yang bisa ditulis. Berikan izin penyimpanan, lalu coba lagi.")
    }

    /** Default extract folder suggestion for the UI. */
    fun defaultExtractDirectory(context: android.content.Context, zipFile: File): File {
        val sibling = zipFile.parentFile
            ?.takeUnless { isAppPrivatePath(it) }
            ?.let { parent ->
                val base = zipFile.nameWithoutExtension.trim().ifBlank { "extract" }
                    .replace(Regex("""[\\/:*?"<>|]"""), "_")
                File(parent, base)
            }
        return runCatching { resolveWritableExtractDir(context, zipFile, sibling) }
            .getOrElse {
                val fallback = context.getExternalFilesDir("Extract") ?: File(context.filesDir, "Extract")
                File(fallback, zipFile.nameWithoutExtension.ifBlank { "extract" }).also { ensureDirectory(it) }
            }
    }

    /** @deprecated Use [defaultExtractDirectory] with Context. */
    fun defaultExtractDirectory(zipFile: File): File {
        val parent = zipFile.parentFile
        val baseParent = when {
            parent == null -> publicFileNestDir()
            isAppPrivatePath(parent) -> publicFileNestDir()
            !parent.exists() -> publicFileNestDir()
            else -> parent
        }
        baseParent.mkdirs()
        val base = zipFile.nameWithoutExtension.trim().ifBlank { "extract" }
            .replace(Regex("""[\\/:*?"<>|]"""), "_")
        var candidate = File(baseParent, base)
        var index = 2
        while (candidate.exists() && !candidate.isDirectory) {
            candidate = File(baseParent, "$base ($index)")
            index++
        }
        return candidate
    }

    fun publicFileNestDir(): File {
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        return File(downloads, "FileNest").also { it.mkdirs() }
    }

    fun probeWritable(dir: File): Boolean {
        return try {
            if (!dir.isDirectory) return false
            val probe = File(dir, ".filenest_write_probe_${System.nanoTime()}")
            probe.writeText("ok")
            probe.delete()
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun isAppPrivatePath(dir: File): Boolean {
        val path = dir.absolutePath
        return path.contains("/cache/") ||
            path.contains("/code_cache/") ||
            path.contains("/files/incoming") ||
            path.contains("/data/data/com.zipextract.app/") ||
            (path.contains("/data/user/") && path.contains("/com.zipextract.app/"))
    }

    private fun listWithZip4j(zipFile: File): List<ZipEntryItem> {
        val zip = net.lingala.zip4j.ZipFile(zipFile)
        @Suppress("UNCHECKED_CAST")
        val headers = zip.fileHeaders as List<FileHeader>
        if (headers.isEmpty() && zip.isEncrypted) {
            error("ZIP berpassword. Extract ZIP terenkripsi belum didukung.")
        }
        return headers.map { header ->
            val normalized = header.fileName.replace('\\', '/')
            val displayName = normalized.trimEnd('/').substringAfterLast('/')
            ZipEntryItem(
                path = normalized,
                displayName = displayName.ifEmpty { normalized },
                isDirectory = header.isDirectory || normalized.endsWith('/'),
                sizeBytes = header.uncompressedSize,
            )
        }.sortedBy { it.path.lowercase() }
    }

    private fun listWithJavaZip(zipFile: File): List<ZipEntryItem> {
        return ZipFile(zipFile).use { zip ->
            zip.entries().asSequence().map { entry ->
                val normalized = entry.name.replace('\\', '/')
                val displayName = normalized.trimEnd('/').substringAfterLast('/')
                ZipEntryItem(
                    path = normalized,
                    displayName = displayName.ifEmpty { normalized },
                    isDirectory = entry.isDirectory || normalized.endsWith('/'),
                    sizeBytes = entry.size.coerceAtLeast(0L),
                )
            }.sortedBy { it.path.lowercase() }.toList()
        }
    }

    private fun extractWithZipInputStream(
        zipFile: File,
        destinationDir: File,
        selectedPaths: Set<String>,
        onProgress: ((Float, String) -> Unit)?,
    ): Int {
        // Precompute selection using ZipFile central directory when possible.
        val allNames = runCatching {
            ZipFile(zipFile).use { zip ->
                zip.entries().asSequence().map { it.name.replace('\\', '/') }.toList()
            }
        }.getOrDefault(emptyList())
        val toExtract = if (allNames.isNotEmpty()) {
            expandSelectedPaths(allNames, selectedPaths).toSet()
        } else {
            selectedPaths.map { it.replace('\\', '/') }.toSet()
        }
        if (toExtract.isEmpty()) error("Tidak ada file yang cocok untuk diextract")

        val total = toExtract.size.coerceAtLeast(1)
        var processed = 0
        var written = 0

        ZipInputStream(BufferedInputStream(FileInputStream(zipFile))).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val originalName = entry.name.replace('\\', '/')
                val selected = originalName in toExtract ||
                    toExtract.any { sel ->
                        val prefix = if (sel.endsWith('/')) sel else "$sel/"
                        originalName.startsWith(prefix)
                    }
                if (selected) {
                    val safeName = sanitizeEntryName(originalName)
                    onProgress?.invoke(++processed / total.toFloat(), safeName.ifBlank { originalName })
                    if (entry.isDirectory || originalName.endsWith('/') || safeName.isEmpty()) {
                        if (safeName.isNotEmpty()) ensureDirectory(File(destinationDir, safeName))
                    } else {
                        val outFile = safeResolve(destinationDir, safeName)
                        outFile.parentFile?.let { ensureDirectory(it) }
                        FileOutputStream(outFile).use { fos ->
                            BufferedOutputStream(fos).use { bos ->
                                zis.copyTo(bos, bufferSize = DEFAULT_BUFFER)
                            }
                        }
                        if (entry.time > 0) outFile.setLastModified(entry.time)
                        if (!outFile.isFile) error("Gagal menulis file: ${outFile.absolutePath}")
                        written++
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        if (written <= 0) {
            error("Stream extract tidak menulis file (mungkin ZIP special/encrypted)")
        }
        return written
    }

    private fun extractWithZip4j(
        zipFile: File,
        destinationDir: File,
        selectedPaths: Set<String>,
        onProgress: ((Float, String) -> Unit)?,
    ): Int {
        val zip = net.lingala.zip4j.ZipFile(zipFile)
        if (zip.isEncrypted) {
            error("ZIP berpassword. Extract ZIP terenkripsi belum didukung.")
        }
        @Suppress("UNCHECKED_CAST")
        val headers = zip.fileHeaders as List<FileHeader>
        val allNames = headers.map { it.fileName.replace('\\', '/') }
        val toExtract = expandSelectedPaths(allNames, selectedPaths).toSet()
        if (toExtract.isEmpty()) error("Tidak ada file yang cocok untuk diextract")

        val total = toExtract.size.coerceAtLeast(1)
        var processed = 0
        var written = 0

        headers.forEach { header ->
            val originalName = header.fileName.replace('\\', '/')
            if (originalName !in toExtract) return@forEach
            val safeName = sanitizeEntryName(originalName)
            onProgress?.invoke(++processed / total.toFloat(), safeName.ifBlank { originalName })

            if (header.isDirectory || originalName.endsWith('/') || safeName.isEmpty()) {
                if (safeName.isNotEmpty()) {
                    ensureDirectory(File(destinationDir, safeName))
                }
                return@forEach
            }

            try {
                zip.extractFile(header, destinationDir.absolutePath, safeName)
            } catch (e: ZipException) {
                // Fallback: stream manually if extractFile fails for this entry.
                writeEntryBytes(
                    destinationDir = destinationDir,
                    safeName = safeName,
                    bytes = zip.getInputStream(header).use { it.readBytes() },
                )
            }
            val out = File(destinationDir, safeName)
            if (!out.isFile) {
                error("Gagal menulis file: ${out.absolutePath}")
            }
            written++
        }
        return written
    }

    private fun extractWithJavaZip(
        zipFile: File,
        destinationDir: File,
        selectedPaths: Set<String>,
        onProgress: ((Float, String) -> Unit)?,
    ): Int {
        return ZipFile(zipFile).use { zip ->
            val allNames = zip.entries().asSequence().map { it.name.replace('\\', '/') }.toList()
            val toExtract = expandSelectedPaths(allNames, selectedPaths)
            if (toExtract.isEmpty()) error("Tidak ada file yang cocok untuk diextract")

            val total = toExtract.size.coerceAtLeast(1)
            var processed = 0
            var written = 0

            toExtract.forEach { entryPath ->
                val entry = findEntry(zip, entryPath) ?: return@forEach
                val safeName = sanitizeEntryName(entry.name)
                onProgress?.invoke(++processed / total.toFloat(), safeName.ifBlank { entryPath })

                if (entry.isDirectory || entry.name.replace('\\', '/').endsWith('/') || safeName.isEmpty()) {
                    if (safeName.isNotEmpty()) ensureDirectory(File(destinationDir, safeName))
                    return@forEach
                }

                val outFile = safeResolve(destinationDir, safeName)
                outFile.parentFile?.let { ensureDirectory(it) }
                zip.getInputStream(entry).use { input ->
                    BufferedInputStream(input).use { bis ->
                        FileOutputStream(outFile).use { fos ->
                            BufferedOutputStream(fos).use { bos ->
                                bis.copyTo(bos, bufferSize = DEFAULT_BUFFER)
                            }
                        }
                    }
                }
                if (entry.time > 0) outFile.setLastModified(entry.time)
                if (!outFile.isFile) error("Gagal menulis file: ${outFile.absolutePath}")
                written++
            }
            written
        }
    }

    private fun writeEntryBytes(destinationDir: File, safeName: String, bytes: ByteArray) {
        val outFile = safeResolve(destinationDir, safeName)
        outFile.parentFile?.let { ensureDirectory(it) }
        FileOutputStream(outFile).use { it.write(bytes) }
    }

    private fun findEntry(zip: ZipFile, entryPath: String): ZipEntry? {
        zip.getEntry(entryPath)?.let { return it }
        val normalized = entryPath.replace('\\', '/')
        return zip.entries().asSequence().firstOrNull {
            it.name.replace('\\', '/') == normalized
        }
    }

    /**
     * If a folder entry is selected, include all nested files under that prefix.
     */
    private fun expandSelectedPaths(
        allNames: List<String>,
        selectedPaths: Set<String>,
    ): List<String> {
        val selected = selectedPaths.map { it.replace('\\', '/') }.toSet()
        val out = LinkedHashSet<String>()
        allNames.forEach { name ->
            val normalized = name.replace('\\', '/')
            if (normalized in selected) {
                out += normalized
                return@forEach
            }
            selected.forEach { sel ->
                val prefix = if (sel.endsWith('/')) sel else "$sel/"
                if (normalized.startsWith(prefix)) {
                    out += normalized
                }
            }
        }
        return out.toList()
    }

    /** Strip absolute/Windows prefixes and block path traversal. */
    fun sanitizeEntryName(entryName: String): String {
        var name = entryName.replace('\\', '/')
        // Windows drive prefix: C:/foo or C:foo
        if (name.length >= 2 && name[1] == ':') {
            name = name.substring(2)
        }
        while (name.startsWith("/")) name = name.drop(1)
        val parts = name.split('/').filter { part ->
            part.isNotEmpty() && part != "."
        }
        if (parts.any { it == ".." }) {
            throw SecurityException("Zip entry tidak valid (path traversal): $entryName")
        }
        return parts.joinToString("/")
    }

    private fun safeResolve(baseDir: File, entryName: String): File {
        val safe = sanitizeEntryName(entryName)
        if (safe.isEmpty()) return baseDir.absoluteFile.normalize()
        val base = baseDir.absoluteFile.normalize()
        val target = File(base, safe).normalize()
        val basePath = base.path
        val prefix = if (basePath.endsWith(File.separator)) basePath else basePath + File.separator
        if (target.path != basePath && !target.path.startsWith(prefix)) {
            throw SecurityException("Zip entry tidak valid (path traversal): $entryName")
        }
        return target
    }

    private fun ensureDirectory(dir: File) {
        if (dir.isDirectory) return
        if (dir.exists() && dir.isFile) {
            error("Tidak bisa membuat folder (ada file dengan nama sama): ${dir.absolutePath}")
        }
        if (!dir.mkdirs() && !dir.isDirectory) {
            error("Tidak bisa membuat folder tujuan: ${dir.absolutePath}")
        }
    }

    private fun collectFiles(dir: File, basePath: String, out: MutableList<Pair<File, String>>) {
        val children = dir.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            ?: return
        if (children.isEmpty()) {
            return
        }
        children.forEach { child ->
            val relative = "$basePath/${child.name}"
            if (child.isDirectory) {
                collectFiles(child, relative, out)
            } else {
                out += child to relative
            }
        }
    }

    private const val DEFAULT_BUFFER = 64 * 1024
}
