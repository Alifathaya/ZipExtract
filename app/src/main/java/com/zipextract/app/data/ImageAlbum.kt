package com.zipextract.app.data

import java.io.File

/**
 * Album chips inside the Images gallery (All / Camera / Screenshots / WhatsApp / other folders).
 */
data class ImageAlbumChip(
    val id: String,
    val label: String,
    val count: Int,
)

object ImageAlbum {
    const val ALL = "all"
    const val CAMERA = "camera"
    const val SCREENSHOTS = "screenshots"
    const val WHATSAPP = "whatsapp"

    private const val FOLDER_PREFIX = "folder:"

    fun folderId(parentPath: String): String = FOLDER_PREFIX + parentPath

    fun isFolderId(id: String): Boolean = id.startsWith(FOLDER_PREFIX)

    fun folderPathFromId(id: String): String? =
        id.takeIf { isFolderId(it) }?.removePrefix(FOLDER_PREFIX)

    /**
     * Build chip list: fixed albums first (only if they have items), then other parent folders
     * by descending count.
     */
    fun buildChips(images: List<FileItem>): List<ImageAlbumChip> {
        if (images.isEmpty()) {
            return listOf(ImageAlbumChip(ALL, "Semua", 0))
        }

        var camera = 0
        var screenshots = 0
        var whatsapp = 0
        val folders = LinkedHashMap<String, Int>()

        images.forEach { item ->
            when (val kind = classify(item)) {
                Kind.CAMERA -> camera++
                Kind.SCREENSHOTS -> screenshots++
                Kind.WHATSAPP -> whatsapp++
                is Kind.Folder -> {
                    folders[kind.parentPath] = (folders[kind.parentPath] ?: 0) + 1
                }
            }
        }

        val chips = mutableListOf(ImageAlbumChip(ALL, "Semua", images.size))
        if (camera > 0) chips += ImageAlbumChip(CAMERA, "Kamera", camera)
        if (screenshots > 0) chips += ImageAlbumChip(SCREENSHOTS, "Screenshot", screenshots)
        if (whatsapp > 0) chips += ImageAlbumChip(WHATSAPP, "WhatsApp", whatsapp)

        folders.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { folderLabel(it.key) })
            .forEach { (path, count) ->
                chips += ImageAlbumChip(
                    id = folderId(path),
                    label = folderLabel(path),
                    count = count,
                )
            }

        return chips
    }

    fun filter(images: List<FileItem>, albumId: String): List<FileItem> {
        if (albumId == ALL || albumId.isBlank()) return images
        val folderPath = folderPathFromId(albumId)
        return images.filter { item ->
            when (val kind = classify(item)) {
                Kind.CAMERA -> albumId == CAMERA
                Kind.SCREENSHOTS -> albumId == SCREENSHOTS
                Kind.WHATSAPP -> albumId == WHATSAPP
                is Kind.Folder -> folderPath != null && kind.parentPath == folderPath
            }
        }
    }

    fun sanitizeSelection(albumId: String, chips: List<ImageAlbumChip>): String {
        if (chips.any { it.id == albumId }) return albumId
        return ALL
    }

    private sealed class Kind {
        data object CAMERA : Kind()
        data object SCREENSHOTS : Kind()
        data object WHATSAPP : Kind()
        data class Folder(val parentPath: String) : Kind()
    }

    private fun classify(item: FileItem): Kind {
        val path = normalize(item.path)
        val parent = item.file.parentFile
        val parentName = parent?.name.orEmpty()
        val parentPath = parent?.let { normalize(it.absolutePath) }.orEmpty()

        // WhatsApp before generic folder names (paths often contain Media/...).
        if (isWhatsApp(path, parentName)) return Kind.WHATSAPP
        if (isScreenshot(path, parentName)) return Kind.SCREENSHOTS
        if (isCamera(path, parentName)) return Kind.CAMERA

        return Kind.Folder(parentPath.ifBlank { parentName.ifBlank { "Lainnya" } })
    }

    private fun isWhatsApp(path: String, parentName: String): Boolean {
        return path.contains("/whatsapp") ||
            path.contains("/com.whatsapp") ||
            path.contains("/com.whatsapp.w4b") ||
            parentName.contains("whatsapp", ignoreCase = true)
    }

    private fun isScreenshot(path: String, parentName: String): Boolean {
        val lowerParent = parentName.lowercase()
        return lowerParent.contains("screenshot") ||
            path.contains("/screenshots/") ||
            path.contains("/screenshot/")
    }

    private fun isCamera(path: String, parentName: String): Boolean {
        val lowerParent = parentName.lowercase()
        if (lowerParent == "camera" || lowerParent == "100andro" || lowerParent == "100media") {
            return true
        }
        return path.contains("/dcim/camera/") ||
            path.contains("/pictures/camera/") ||
            path.contains("/dcim/100andro/") ||
            path.contains("/dcim/100media/")
    }

    private fun normalize(path: String): String {
        return path.replace('\\', '/').lowercase()
    }

    private fun folderLabel(parentPath: String): String {
        if (parentPath.isBlank()) return "Lainnya"
        val name = File(parentPath).name
        return name.ifBlank { "Lainnya" }
    }
}
