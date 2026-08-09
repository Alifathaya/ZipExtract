package com.zipextract.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zipextract.app.data.ClipboardMode
import com.zipextract.app.data.ClipboardState
import com.zipextract.app.data.FileItem
import com.zipextract.app.data.FileOperations
import com.zipextract.app.data.OperationResult
import com.zipextract.app.data.ProgressState
import com.zipextract.app.data.ZipManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.Deflater

sealed class ViewerContent {
    abstract val file: File
    val title: String get() = file.name

    data class Image(override val file: File) : ViewerContent()
    data class Pdf(override val file: File) : ViewerContent()
}

data class BrowserUiState(
    val currentDir: File = FileOperations.defaultRoot(),
    val items: List<FileItem> = emptyList(),
    val selectedPaths: Set<String> = emptySet(),
    val selectionMode: Boolean = false,
    val clipboard: ClipboardState? = null,
    val progress: ProgressState? = null,
    val canGoUp: Boolean = false,
    val storageGranted: Boolean = false,
    val sortNewestFirst: Boolean = false,
    val viewer: ViewerContent? = null,
)

class FileBrowserViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(BrowserUiState())
    val uiState: StateFlow<BrowserUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val events: SharedFlow<String> = _events.asSharedFlow()

    private val root = FileOperations.defaultRoot()

    fun setStorageGranted(granted: Boolean) {
        _uiState.update { it.copy(storageGranted = granted) }
        if (granted) refresh()
    }

    fun refresh() {
        val dir = _uiState.value.currentDir
        val items = FileOperations.listFiles(dir).let { list ->
            if (_uiState.value.sortNewestFirst) {
                list.sortedWith(
                    compareByDescending<FileItem> { it.isDirectory }
                        .thenByDescending { it.lastModified }
                )
            } else {
                list
            }
        }
        _uiState.update {
            it.copy(
                items = items,
                canGoUp = dir.absolutePath != root.absolutePath && dir.parentFile != null,
                selectedPaths = it.selectedPaths.filter { path ->
                    items.any { item -> item.path == path }
                }.toSet(),
            )
        }
    }

    fun openDirectory(item: FileItem) {
        if (!item.isDirectory) return
        _uiState.update {
            it.copy(
                currentDir = item.file,
                selectionMode = false,
                selectedPaths = emptySet(),
            )
        }
        refresh()
    }

    fun openItem(item: FileItem) {
        when {
            item.isDirectory -> openDirectory(item)
            item.isPdf -> openViewer(ViewerContent.Pdf(item.file))
            item.isImage -> openViewer(ViewerContent.Image(item.file))
            item.isArchive -> toggleSelect(item)
            else -> toggleSelect(item)
        }
    }

    fun openViewer(content: ViewerContent) {
        if (!content.file.exists() || !content.file.isFile) {
            emit("File tidak ditemukan")
            return
        }
        _uiState.update { it.copy(viewer = content) }
    }

    fun openViewerFile(file: File) {
        val item = FileItem(file)
        when {
            item.isPdf -> openViewer(ViewerContent.Pdf(file))
            item.isImage -> openViewer(ViewerContent.Image(file))
            else -> emit("Format file tidak didukung untuk dibuka")
        }
    }

    fun closeViewer() {
        _uiState.update { it.copy(viewer = null) }
    }

    fun goUp() {
        val parent = _uiState.value.currentDir.parentFile ?: return
        _uiState.update {
            it.copy(
                currentDir = parent,
                selectionMode = false,
                selectedPaths = emptySet(),
            )
        }
        refresh()
    }

    fun navigateTo(path: File) {
        if (!path.exists() || !path.isDirectory) return
        _uiState.update {
            it.copy(
                currentDir = path,
                selectionMode = false,
                selectedPaths = emptySet(),
            )
        }
        refresh()
    }

    fun toggleSelectionMode() {
        _uiState.update {
            if (it.selectionMode) {
                it.copy(selectionMode = false, selectedPaths = emptySet())
            } else {
                it.copy(selectionMode = true)
            }
        }
    }

    fun toggleSelect(item: FileItem) {
        _uiState.update { state ->
            val next = state.selectedPaths.toMutableSet()
            if (!next.add(item.path)) next.remove(item.path)
            state.copy(
                selectionMode = true,
                selectedPaths = next,
            )
        }
    }

    fun selectAll() {
        _uiState.update { state ->
            state.copy(
                selectionMode = true,
                selectedPaths = state.items.map { it.path }.toSet(),
            )
        }
    }

    fun clearSelection() {
        _uiState.update { it.copy(selectionMode = false, selectedPaths = emptySet()) }
    }

    fun copySelected() = putClipboard(ClipboardMode.COPY)

    fun cutSelected() = putClipboard(ClipboardMode.CUT)

    private fun putClipboard(mode: ClipboardMode) {
        val files = selectedFiles()
        if (files.isEmpty()) {
            emit("Pilih file terlebih dahulu")
            return
        }
        _uiState.update {
            it.copy(
                clipboard = ClipboardState(mode, files),
                selectionMode = false,
                selectedPaths = emptySet(),
            )
        }
        val label = if (mode == ClipboardMode.COPY) "disalin" else "dipotong"
        emit("${files.size} item $label ke clipboard")
    }

    fun paste() {
        val clipboard = _uiState.value.clipboard
        if (clipboard == null) {
            emit("Clipboard kosong")
            return
        }
        runJob("Menempelkan…", "Menyalin file") {
            val result = FileOperations.paste(clipboard, _uiState.value.currentDir) { progress, name ->
                updateProgress("Menempelkan…", name, progress)
            }
            if (clipboard.mode == ClipboardMode.CUT && result is OperationResult.Success) {
                _uiState.update { it.copy(clipboard = null) }
            }
            handleResult(result)
        }
    }

    fun deleteSelected() {
        val files = selectedFiles()
        if (files.isEmpty()) {
            emit("Pilih file terlebih dahulu")
            return
        }
        runJob("Menghapus…", "Menghapus file") {
            val result = FileOperations.deleteRecursively(files)
            _uiState.update { it.copy(selectionMode = false, selectedPaths = emptySet()) }
            handleResult(result)
        }
    }

    fun createFolder(name: String) {
        val result = FileOperations.createFolder(_uiState.value.currentDir, name)
        handleResult(result)
        if (result is OperationResult.Success) refresh()
    }

    fun renameSelected(newName: String) {
        val file = selectedFiles().singleOrNull()
        if (file == null) {
            emit("Pilih tepat 1 item untuk rename")
            return
        }
        val result = FileOperations.rename(file, newName)
        handleResult(result)
        if (result is OperationResult.Success) {
            clearSelection()
            refresh()
        }
    }

    fun createZip(zipName: String, bestCompression: Boolean) {
        val sources = selectedFiles().ifEmpty {
            emit("Pilih file/folder untuk di-zip")
            return
        }
        val safeName = zipName.trim().let { if (it.endsWith(".zip", true)) it else "$it.zip" }
        val destination = FileOperations.uniqueName(File(_uiState.value.currentDir, safeName))
        val level = if (bestCompression) Deflater.BEST_COMPRESSION else Deflater.DEFAULT_COMPRESSION

        runJob("Membuat ZIP…", destination.name) {
            try {
                ZipManager.createZip(sources, destination, level) { progress, name ->
                    updateProgress("Membuat ZIP…", name, progress)
                }
                _uiState.update { it.copy(selectionMode = false, selectedPaths = emptySet()) }
                emit("ZIP berhasil: ${destination.name}")
            } catch (e: Exception) {
                emit("Gagal membuat ZIP: ${e.message ?: "error"}")
            }
        }
    }

    fun extractSelected(customFolderName: String? = null) {
        val zip = selectedFiles().singleOrNull { it.isFile && FileItem(it).isArchive }
            ?: selectedFiles().singleOrNull()?.takeIf { FileItem(it).isArchive }
        if (zip == null) {
            emit("Pilih 1 file ZIP untuk diextract")
            return
        }
        val folderName = customFolderName?.trim()?.ifEmpty { null } ?: zip.nameWithoutExtension
        val destination = FileOperations.uniqueName(File(_uiState.value.currentDir, folderName))

        runJob("Extract ZIP…", zip.name) {
            try {
                ZipManager.extractZip(zip, destination) { progress, name ->
                    updateProgress("Extract ZIP…", name, progress)
                }
                clearSelection()
                emit("Extract selesai ke ${destination.name}")
            } catch (e: Exception) {
                emit("Gagal extract: ${e.message ?: "error"}")
            }
        }
    }

    fun extractZipFile(zip: File) {
        if (!zip.exists()) {
            emit("File ZIP tidak ditemukan")
            return
        }
        navigateTo(zip.parentFile ?: root)
        _uiState.update {
            it.copy(
                selectionMode = true,
                selectedPaths = setOf(zip.absolutePath),
            )
        }
        extractSelected()
    }

    fun toggleSort() {
        _uiState.update { it.copy(sortNewestFirst = !it.sortNewestFirst) }
        refresh()
    }

    private fun selectedFiles(): List<File> {
        val paths = _uiState.value.selectedPaths
        return _uiState.value.items
            .filter { it.path in paths }
            .map { it.file }
    }

    private fun runJob(title: String, message: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(progress = ProgressState(title, message, indeterminate = true))
            }
            try {
                withContext(Dispatchers.IO) { block() }
            } finally {
                _uiState.update { it.copy(progress = null) }
                refresh()
            }
        }
    }

    private fun updateProgress(title: String, message: String, progress: Float) {
        _uiState.update {
            it.copy(
                progress = ProgressState(
                    title = title,
                    message = message,
                    indeterminate = false,
                    progress = progress.coerceIn(0f, 1f),
                )
            )
        }
    }

    private fun handleResult(result: OperationResult) {
        when (result) {
            is OperationResult.Success -> emit(result.message)
            is OperationResult.Error -> emit(result.message)
        }
    }

    private fun emit(message: String) {
        _events.tryEmit(message)
    }
}
