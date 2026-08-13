package com.zipextract.app.data

import android.content.Context
import android.os.Environment
import com.zipextract.app.R
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

    fun msg(context: Context, id: Int, vararg args: Any): String {
        return if (args.isEmpty()) context.getString(id) else context.getString(id, *args)
    }

    private fun msgOr(context: Context?, id: Int, fallback: String, vararg args: Any): String {
        return if (context != null) msg(context, id, *args) else fallback
    }

    fun createZip(
        sources: List<File>,
        destinationZip: File,
        compressionLevel: Int = Deflater.DEFAULT_COMPRESSION,
        password: String? = null,
        context: Context? = null,
        onProgress: ((Float, String) -> Unit)? = null,
    ) {
        require(sources.isNotEmpty()) {
            msgOr(context, R.string.zip_no_sources, "No files selected")
        }
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
        context: Context? = null,
        onProgress: ((Float, String) -> Unit)? = null,
    ) {
        val entries = listZipEntryDetails(zipFile, context).map { it.path }.toSet()
        extractZipEntries(zipFile, destinationDir, entries, context, onProgress)
    }

    fun listZipEntries(zipFile: File, context: Context? = null): List<String> {
        return listZipEntryDetails(zipFile, context).map { it.path }
    }

    fun listZipEntryDetails(zipFile: File, context: Context? = null): List<ZipEntryItem> {
        require(zipFile.exists() && zipFile.isFile) {
            msgOr(context, R.string.zip_not_found, "ZIP file not found")
        }
        val fromZip4j = runCatching { listWithZip4j(zipFile, context) }.getOrNull()
        if (!fromZip4j.isNullOrEmpty()) return fromZip4j
        return runCatching { listWithJavaZip(zipFile) }.getOrElse { err ->
            throw IllegalStateException(
                msgOr(
                    context,
                    R.string.extract_zip_read_fail,
                    "Failed to read ZIP: ${err.message ?: "unsupported format"}",
                    err.message ?: msgOr(context, R.string.zip_unsupported_format, "unsupported format"),
                ),
                err,
            )
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
        context: Context? = null,
        onProgress: ((Float, String) -> Unit)? = null,
    ): Int {
        require(zipFile.exists() && zipFile.isFile) {
            msgOr(context, R.string.zip_not_found, "ZIP file not found")
        }
        require(selectedPaths.isNotEmpty()) {
            msgOr(context, R.string.select_min_one_extract, "Select at least 1 file to extract")
        }
        require(zipFile.length() > 0L) {
            msgOr(context, R.string.zip_empty_file, "ZIP file is empty (0 bytes)")
        }

        ensureDirectory(destinationDir, context)
        require(probeWritable(destinationDir)) {
            msgOr(
                context,
                R.string.zip_dest_not_writable,
                "Destination folder is not writable: ${destinationDir.absolutePath}",
                destinationDir.absolutePath,
            )
        }

        val errors = mutableListOf<String>()

        runCatching {
            return extractWithZipInputStream(zipFile, destinationDir, selectedPaths, context, onProgress)
        }.onFailure { errors += "stream: ${it.message ?: it.javaClass.simpleName}" }

        runCatching {
            return extractWithZip4j(zipFile, destinationDir, selectedPaths, context, onProgress)
        }.onFailure { errors += "zip4j: ${it.message ?: it.javaClass.simpleName}" }

        runCatching {
            return extractWithJavaZip(zipFile, destinationDir, selectedPaths, context, onProgress)
        }.onFailure { errors += "java: ${it.message ?: it.javaClass.simpleName}" }

        val detail = errors.joinToString(" | ").ifBlank {
            msgOr(context, R.string.zip_unsupported_format, "unsupported format")
        }
        error(
            msgOr(
                context,
                R.string.zip_extract_failed,
                "Failed to extract ZIP ($detail)",
                detail,
            ),
        )
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
                ensureDirectory(dir, context)
                if (probeWritable(dir)) return dir
            }
        }
        error(msg(context, R.string.zip_no_writable_folder))
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
                File(fallback, zipFile.nameWithoutExtension.ifBlank { "extract" }).also {
                    ensureDirectory(it, context)
                }
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

    private fun listWithZip4j(zipFile: File, context: Context? = null): List<ZipEntryItem> {
        val zip = net.lingala.zip4j.ZipFile(zipFile)
        @Suppress("UNCHECKED_CAST")
        val headers = zip.fileHeaders as List<FileHeader>
        if (headers.isEmpty() && zip.isEncrypted) {
            error(
                msgOr(
                    context,
                    R.string.zip_password_unsupported,
                    "Password-protected ZIP. Encrypted extract is not supported yet.",
                ),
            )
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
        context: Context? = null,
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
        if (toExtract.isEmpty()) {
            error(
                msgOr(
                    context,
                    R.string.zip_no_matching_files,
                    "No matching files to extract",
                ),
            )
        }

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
                    val safeName = sanitizeEntryName(originalName, context)
                    onProgress?.invoke(++processed / total.toFloat(), safeName.ifBlank { originalName })
                    if (entry.isDirectory || originalName.endsWith('/') || safeName.isEmpty()) {
                        if (safeName.isNotEmpty()) ensureDirectory(File(destinationDir, safeName), context)
                    } else {
                        val outFile = safeResolve(destinationDir, safeName, context)
                        outFile.parentFile?.let { ensureDirectory(it, context) }
                        FileOutputStream(outFile).use { fos ->
                            BufferedOutputStream(fos).use { bos ->
                                zis.copyTo(bos, bufferSize = DEFAULT_BUFFER)
                            }
                        }
                        if (entry.time > 0) outFile.setLastModified(entry.time)
                        if (!outFile.isFile) {
                            error(
                                msgOr(
                                    context,
                                    R.string.zip_write_file_failed,
                                    "Failed to write file: ${outFile.absolutePath}",
                                    outFile.absolutePath,
                                ),
                            )
                        }
                        written++
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        if (written <= 0) {
            error(
                msgOr(
                    context,
                    R.string.zip_stream_wrote_none,
                    "Stream extract wrote no files (maybe special/encrypted ZIP)",
                ),
            )
        }
        return written
    }

    private fun extractWithZip4j(
        zipFile: File,
        destinationDir: File,
        selectedPaths: Set<String>,
        context: Context? = null,
        onProgress: ((Float, String) -> Unit)?,
    ): Int {
        val zip = net.lingala.zip4j.ZipFile(zipFile)
        if (zip.isEncrypted) {
            error(
                msgOr(
                    context,
                    R.string.zip_password_unsupported,
                    "Password-protected ZIP. Encrypted extract is not supported yet.",
                ),
            )
        }
        @Suppress("UNCHECKED_CAST")
        val headers = zip.fileHeaders as List<FileHeader>
        val allNames = headers.map { it.fileName.replace('\\', '/') }
        val toExtract = expandSelectedPaths(allNames, selectedPaths).toSet()
        if (toExtract.isEmpty()) {
            error(
                msgOr(
                    context,
                    R.string.zip_no_matching_files,
                    "No matching files to extract",
                ),
            )
        }

        val total = toExtract.size.coerceAtLeast(1)
        var processed = 0
        var written = 0

        headers.forEach { header ->
            val originalName = header.fileName.replace('\\', '/')
            if (originalName !in toExtract) return@forEach
            val safeName = sanitizeEntryName(originalName, context)
            onProgress?.invoke(++processed / total.toFloat(), safeName.ifBlank { originalName })

            if (header.isDirectory || originalName.endsWith('/') || safeName.isEmpty()) {
                if (safeName.isNotEmpty()) {
                    ensureDirectory(File(destinationDir, safeName), context)
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
                    context = context,
                )
            }
            val out = File(destinationDir, safeName)
            if (!out.isFile) {
                error(
                    msgOr(
                        context,
                        R.string.zip_write_file_failed,
                        "Failed to write file: ${out.absolutePath}",
                        out.absolutePath,
                    ),
                )
            }
            written++
        }
        return written
    }

    private fun extractWithJavaZip(
        zipFile: File,
        destinationDir: File,
        selectedPaths: Set<String>,
        context: Context? = null,
        onProgress: ((Float, String) -> Unit)?,
    ): Int {
        return ZipFile(zipFile).use { zip ->
            val allNames = zip.entries().asSequence().map { it.name.replace('\\', '/') }.toList()
            val toExtract = expandSelectedPaths(allNames, selectedPaths)
            if (toExtract.isEmpty()) {
                error(
                    msgOr(
                        context,
                        R.string.zip_no_matching_files,
                        "No matching files to extract",
                    ),
                )
            }

            val total = toExtract.size.coerceAtLeast(1)
            var processed = 0
            var written = 0

            toExtract.forEach { entryPath ->
                val entry = findEntry(zip, entryPath) ?: return@forEach
                val safeName = sanitizeEntryName(entry.name, context)
                onProgress?.invoke(++processed / total.toFloat(), safeName.ifBlank { entryPath })

                if (entry.isDirectory || entry.name.replace('\\', '/').endsWith('/') || safeName.isEmpty()) {
                    if (safeName.isNotEmpty()) ensureDirectory(File(destinationDir, safeName), context)
                    return@forEach
                }

                val outFile = safeResolve(destinationDir, safeName, context)
                outFile.parentFile?.let { ensureDirectory(it, context) }
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
                if (!outFile.isFile) {
                    error(
                        msgOr(
                            context,
                            R.string.zip_write_file_failed,
                            "Failed to write file: ${outFile.absolutePath}",
                            outFile.absolutePath,
                        ),
                    )
                }
                written++
            }
            written
        }
    }

    private fun writeEntryBytes(
        destinationDir: File,
        safeName: String,
        bytes: ByteArray,
        context: Context? = null,
    ) {
        val outFile = safeResolve(destinationDir, safeName, context)
        outFile.parentFile?.let { ensureDirectory(it, context) }
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
    fun sanitizeEntryName(entryName: String, context: Context? = null): String {
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
            throw SecurityException(
                msgOr(
                    context,
                    R.string.zip_path_traversal,
                    "Invalid zip entry (path traversal): $entryName",
                    entryName,
                ),
            )
        }
        return parts.joinToString("/")
    }

    private fun safeResolve(baseDir: File, entryName: String, context: Context? = null): File {
        val safe = sanitizeEntryName(entryName, context)
        if (safe.isEmpty()) return baseDir.absoluteFile.normalize()
        val base = baseDir.absoluteFile.normalize()
        val target = File(base, safe).normalize()
        val basePath = base.path
        val prefix = if (basePath.endsWith(File.separator)) basePath else basePath + File.separator
        if (target.path != basePath && !target.path.startsWith(prefix)) {
            throw SecurityException(
                msgOr(
                    context,
                    R.string.zip_path_traversal,
                    "Invalid zip entry (path traversal): $entryName",
                    entryName,
                ),
            )
        }
        return target
    }

    private fun ensureDirectory(dir: File, context: Context? = null) {
        if (dir.isDirectory) return
        if (dir.exists() && dir.isFile) {
            error(
                msgOr(
                    context,
                    R.string.zip_mkdir_conflict,
                    "Cannot create folder (a file with the same name exists): ${dir.absolutePath}",
                    dir.absolutePath,
                ),
            )
        }
        if (!dir.mkdirs() && !dir.isDirectory) {
            error(
                msgOr(
                    context,
                    R.string.zip_mkdir_failed,
                    "Cannot create destination folder: ${dir.absolutePath}",
                    dir.absolutePath,
                ),
            )
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
