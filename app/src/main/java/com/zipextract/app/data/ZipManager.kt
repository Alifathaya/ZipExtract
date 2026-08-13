package com.zipextract.app.data

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
        require(zipFile.exists() && zipFile.isFile) { "File zip tidak ditemukan" }
        destinationDir.mkdirs()

        val totalEntries = ZipFile(zipFile).use { it.size().coerceAtLeast(1) }
        var processed = 0

        ZipInputStream(BufferedInputStream(FileInputStream(zipFile))).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val outFile = safeResolve(destinationDir, entry.name)
                onProgress?.invoke(++processed / totalEntries.toFloat(), entry.name)

                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { fos ->
                        BufferedOutputStream(fos).use { bos ->
                            zis.copyTo(bos, bufferSize = DEFAULT_BUFFER)
                        }
                    }
                    if (entry.time > 0) {
                        outFile.setLastModified(entry.time)
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    fun listZipEntries(zipFile: File): List<String> {
        return listZipEntryDetails(zipFile).map { it.path }
    }

    fun listZipEntryDetails(zipFile: File): List<ZipEntryItem> {
        return ZipFile(zipFile).use { zip ->
            zip.entries().asSequence().map { entry ->
                val normalized = entry.name.replace('\\', '/')
                val displayName = normalized.trimEnd('/').substringAfterLast('/')
                ZipEntryItem(
                    path = normalized,
                    displayName = displayName.ifEmpty { normalized },
                    isDirectory = entry.isDirectory,
                    sizeBytes = entry.size,
                )
            }.sortedBy { it.path.lowercase() }.toList()
        }
    }

    fun extractZipEntries(
        zipFile: File,
        destinationDir: File,
        selectedPaths: Set<String>,
        onProgress: ((Float, String) -> Unit)? = null,
    ): Int {
        require(zipFile.exists() && zipFile.isFile) { "File zip tidak ditemukan" }
        require(selectedPaths.isNotEmpty()) { "Pilih minimal 1 file untuk diextract" }
        if (!destinationDir.exists() && !destinationDir.mkdirs()) {
            error("Tidak bisa membuat folder tujuan: ${destinationDir.absolutePath}")
        }
        require(destinationDir.isDirectory) {
            "Folder tujuan tidak valid: ${destinationDir.absolutePath}"
        }

        return ZipFile(zipFile).use { zip ->
            val allNames = zip.entries().asSequence().map { it.name.replace('\\', '/') }.toList()
            val toExtract = expandSelectedPaths(allNames, selectedPaths)
            if (toExtract.isEmpty()) {
                error("Tidak ada file yang cocok untuk diextract")
            }

            val totalEntries = toExtract.size.coerceAtLeast(1)
            var processed = 0
            var written = 0

            toExtract.forEach { entryPath ->
                val entry = findEntry(zip, entryPath) ?: return@forEach
                val normalized = entry.name.replace('\\', '/')
                val outFile = safeResolve(destinationDir, normalized)
                onProgress?.invoke(++processed / totalEntries.toFloat(), normalized)

                if (entry.isDirectory || normalized.endsWith('/')) {
                    if (!outFile.exists() && !outFile.mkdirs()) {
                        error("Gagal membuat folder: ${outFile.absolutePath}")
                    }
                } else {
                    outFile.parentFile?.mkdirs()
                    zip.getInputStream(entry).use { input ->
                        BufferedInputStream(input).use { bis ->
                            FileOutputStream(outFile).use { fos ->
                                BufferedOutputStream(fos).use { bos ->
                                    bis.copyTo(bos, bufferSize = DEFAULT_BUFFER)
                                }
                            }
                        }
                    }
                    if (entry.time > 0) {
                        outFile.setLastModified(entry.time)
                    }
                    if (!outFile.isFile) {
                        error("Gagal menulis file: ${outFile.absolutePath}")
                    }
                    written++
                }
            }
            written
        }
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
     * Also matches entries whether or not they use a trailing slash.
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
            // Directory selected as "foo" or "foo/" should pull "foo/bar.txt".
            selected.forEach { sel ->
                val prefix = when {
                    sel.endsWith('/') -> sel
                    else -> "$sel/"
                }
                if (normalized.startsWith(prefix)) {
                    out += normalized
                }
            }
        }
        return out.toList()
    }

    /** Default extract folder: sibling folder named after the ZIP (unique if needed). */
    fun defaultExtractDirectory(zipFile: File): File {
        val parent = zipFile.parentFile ?: File(".")
        val base = zipFile.nameWithoutExtension.trim().ifBlank { "extract" }
            .replace(Regex("""[\\/:*?"<>|]"""), "_")
        var candidate = File(parent, base)
        var index = 2
        while (candidate.exists() && !candidate.isDirectory) {
            candidate = File(parent, "$base ($index)")
            index++
        }
        // If a same-named folder already exists, still use it (extract into it).
        return candidate
    }

    private fun collectFiles(dir: File, basePath: String, out: MutableList<Pair<File, String>>) {
        val children = dir.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            ?: return
        if (children.isEmpty()) {
            // Keep empty folder representation in zip
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

    private fun safeResolve(baseDir: File, entryName: String): File {
        val target = File(baseDir, entryName).canonicalFile
        val base = baseDir.canonicalFile
        if (!target.path.startsWith(base.path + File.separator) && target != base) {
            throw SecurityException("Zip entry tidak valid (path traversal): $entryName")
        }
        return target
    }

    private const val DEFAULT_BUFFER = 64 * 1024
}
