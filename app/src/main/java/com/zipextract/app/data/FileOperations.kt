package com.zipextract.app.data

import android.os.Environment
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

object FileOperations {

    fun defaultRoot(): File {
        return Environment.getExternalStorageDirectory()
    }

    fun listFiles(directory: File): List<FileItem> {
        if (!directory.exists() || !directory.isDirectory) return emptyList()
        return directory.listFiles()
            ?.map { FileItem(it) }
            ?.sortedWith(
                compareBy<FileItem> { !it.isDirectory }
                    .thenBy { it.name.lowercase() }
            )
            .orEmpty()
    }

    fun createFolder(parent: File, name: String): OperationResult {
        val sanitized = name.trim()
        if (sanitized.isEmpty()) return OperationResult.Error("Nama folder kosong")
        if (sanitized.contains('/') || sanitized.contains('\\')) {
            return OperationResult.Error("Nama folder tidak valid")
        }
        val target = File(parent, sanitized)
        if (target.exists()) return OperationResult.Error("Folder sudah ada")
        return if (target.mkdirs()) {
            OperationResult.Success("Folder \"$sanitized\" dibuat")
        } else {
            OperationResult.Error("Gagal membuat folder")
        }
    }

    fun rename(file: File, newName: String): OperationResult {
        val sanitized = newName.trim()
        if (sanitized.isEmpty()) return OperationResult.Error("Nama baru kosong")
        if (sanitized.contains('/') || sanitized.contains('\\')) {
            return OperationResult.Error("Nama tidak valid")
        }
        val target = File(file.parentFile, sanitized)
        if (target.exists()) return OperationResult.Error("Nama sudah dipakai")
        return if (file.renameTo(target)) {
            OperationResult.Success("Berhasil diganti nama")
        } else {
            OperationResult.Error("Gagal mengganti nama")
        }
    }

    fun deleteRecursively(files: List<File>): OperationResult {
        var failed = 0
        files.forEach { file ->
            if (!deleteDeep(file)) failed++
        }
        return if (failed == 0) {
            OperationResult.Success("${files.size} item dihapus")
        } else {
            OperationResult.Error("$failed item gagal dihapus")
        }
    }

    fun paste(
        clipboard: ClipboardState,
        destinationDir: File,
        onProgress: ((Float, String) -> Unit)? = null,
    ): OperationResult {
        if (!destinationDir.isDirectory) {
            return OperationResult.Error("Folder tujuan tidak valid")
        }

        val items = clipboard.items.filter { it.exists() }
        if (items.isEmpty()) return OperationResult.Error("Clipboard kosong")

        val total = items.size.coerceAtLeast(1)
        items.forEachIndexed { index, source ->
            onProgress?.invoke((index + 1f) / total, source.name)
            val target = uniqueName(File(destinationDir, source.name))

            if (isNestedTarget(source, target)) {
                return OperationResult.Error("Tidak bisa menempel ke dalam folder sumber")
            }

            val ok = when (clipboard.mode) {
                ClipboardMode.COPY -> copyDeep(source, target)
                ClipboardMode.CUT -> moveDeep(source, target)
            }
            if (!ok) {
                return OperationResult.Error("Gagal memproses ${source.name}")
            }
        }

        val action = if (clipboard.mode == ClipboardMode.COPY) "disalin" else "dipindahkan"
        return OperationResult.Success("${items.size} item $action")
    }

    fun uniqueName(file: File): File {
        if (!file.exists()) return file
        val parent = file.parentFile ?: return file
        val name = file.nameWithoutExtension
        val ext = file.extension
        var i = 1
        while (true) {
            val candidate = if (file.isDirectory || ext.isEmpty()) {
                File(parent, "$name ($i)")
            } else {
                File(parent, "$name ($i).$ext")
            }
            if (!candidate.exists()) return candidate
            i++
        }
    }

    private fun deleteDeep(file: File): Boolean {
        if (file.isDirectory) {
            file.listFiles()?.forEach { child ->
                if (!deleteDeep(child)) return false
            }
        }
        return file.delete()
    }

    private fun copyDeep(source: File, target: File): Boolean {
        return try {
            if (source.isDirectory) {
                if (!target.exists() && !target.mkdirs()) return false
                source.listFiles()?.forEach { child ->
                    if (!copyDeep(child, File(target, child.name))) return false
                }
                true
            } else {
                target.parentFile?.mkdirs()
                FileInputStream(source).use { input ->
                    FileOutputStream(target).use { output ->
                        input.copyTo(output, bufferSize = 64 * 1024)
                    }
                }
                target.setLastModified(source.lastModified())
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun moveDeep(source: File, target: File): Boolean {
        if (source.renameTo(target)) return true
        if (!copyDeep(source, target)) return false
        return deleteDeep(source)
    }

    private fun isNestedTarget(source: File, target: File): Boolean {
        if (!source.isDirectory) return false
        val sourcePath = source.canonicalPath
        val targetPath = target.canonicalPath
        return targetPath == sourcePath || targetPath.startsWith(sourcePath + File.separator)
    }
}
