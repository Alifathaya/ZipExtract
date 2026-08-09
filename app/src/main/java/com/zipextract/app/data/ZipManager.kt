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
        return ZipFile(zipFile).use { zip ->
            zip.entries().asSequence().map { it.name }.sorted().toList()
        }
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
