package com.zipextract.app.data

import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

data class DuplicateGroup(
    val key: String,
    val files: List<FileItem>,
) {
    val wastedBytes: Long
        get() = files.drop(1).sumOf { it.sizeBytes }
}

object DuplicateFinder {

    fun findDuplicates(
        files: List<FileItem>,
        checkingMessage: String = "Checking duplicates…",
        onProgress: ((Float, String) -> Unit)? = null,
    ): List<DuplicateGroup> {
        val candidates = files.filter { it.file.isFile && it.sizeBytes > 0L }
        if (candidates.isEmpty()) return emptyList()

        val bySize = candidates.groupBy { it.sizeBytes }.filterValues { it.size > 1 }
        val groups = mutableListOf<DuplicateGroup>()
        val sizeBuckets = bySize.entries.toList()
        sizeBuckets.forEachIndexed { index, (_, sameSize) ->
            onProgress?.invoke(
                (index + 1f) / sizeBuckets.size.coerceAtLeast(1),
                checkingMessage,
            )
            val byHash = linkedMapOf<String, MutableList<FileItem>>()
            sameSize.forEach { item ->
                val hash = contentFingerprint(item.file) ?: return@forEach
                byHash.getOrPut(hash) { mutableListOf() }.add(item)
            }
            byHash.values.filter { it.size > 1 }.forEach { dupes ->
                groups += DuplicateGroup(
                    key = "${dupes.first().sizeBytes}:${dupes.first().name}",
                    files = dupes.sortedBy { it.path },
                )
            }
        }
        return groups.sortedByDescending { it.wastedBytes }
    }

    private fun contentFingerprint(file: File): String? {
        return runCatching {
            val digest = MessageDigest.getInstance("MD5")
            val length = file.length()
            FileInputStream(file).use { input ->
                val buffer = ByteArray(8192)
                var read = input.read(buffer)
                if (read > 0) digest.update(buffer, 0, read)
                if (length > buffer.size * 2L) {
                    input.skip((length - buffer.size).coerceAtLeast(0L))
                    read = input.read(buffer)
                    if (read > 0) digest.update(buffer, 0, read)
                }
            }
            digest.update(length.toString().toByteArray())
            digest.digest().joinToString("") { "%02x".format(it) }
        }.getOrNull()
    }
}
