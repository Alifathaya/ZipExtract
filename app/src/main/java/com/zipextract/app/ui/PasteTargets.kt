package com.zipextract.app.ui

import com.zipextract.app.data.FileCategory
import com.zipextract.app.data.MediaAlbum
import java.io.File

/**
 * Shared paste destination rules for the browser UI and ViewModel.
 * Keep one implementation so banner enablement and paste() never drift.
 */
fun BrowserUiState.resolvePasteTargetDir(): File? {
    if (showHome ||
        showExplorerRoots ||
        showFavoritesOnly ||
        showLargestFiles ||
        showDuplicates ||
        showCloud
    ) {
        return null
    }
    if (libraryMode) {
        val category = activeCategory
        if (category == FileCategory.IMAGES || category == FileCategory.VIDEOS) {
            return MediaAlbum.resolvePasteDirectory(mediaAlbumId, items)
        }
        return currentDir.takeIf { it.isDirectory }
    }
    return currentDir.takeIf { it.isDirectory }
}

fun BrowserUiState.canPasteHere(): Boolean = resolvePasteTargetDir() != null

/** Library gallery on "Semua" — paste needs a concrete album (Camera, Screenshot, …). */
fun BrowserUiState.needsAlbumPickForPaste(): Boolean {
    return libraryMode &&
        (activeCategory == FileCategory.IMAGES || activeCategory == FileCategory.VIDEOS) &&
        (mediaAlbumId == MediaAlbum.ALL || mediaAlbumId.isBlank())
}
