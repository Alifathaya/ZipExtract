package com.zipextract.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zipextract.app.data.ClipboardMode
import com.zipextract.app.data.ClipboardState
import com.zipextract.app.data.CategorySummary
import com.zipextract.app.data.FileCategory
import com.zipextract.app.data.FileFilter
import com.zipextract.app.data.FileItem
import com.zipextract.app.data.FileOperations
import com.zipextract.app.data.OperationResult
import com.zipextract.app.data.ProgressState
import com.zipextract.app.data.StorageInfo
import com.zipextract.app.data.ZipManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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

data class ExtractZipState(
    val zipFile: File,
    val entries: List<com.zipextract.app.data.ZipEntryItem> = emptyList(),
    val selectedPaths: Set<String> = emptySet(),
    val deleteOriginal: Boolean = false,
    val isLoading: Boolean = true,
    val error: String? = null,
)

data class BrowserUiState(
    val showHome: Boolean = true,
    val activeCategory: FileCategory? = null,
    val categoryRoot: File? = null,
    val storageInfo: StorageInfo? = null,
    val categorySummaries: List<CategorySummary> = emptyList(),
    val recentFiles: List<FileItem> = emptyList(),
    val searchQuery: String = "",
    val searchResults: List<FileItem> = emptyList(),
    val searchLoading: Boolean = false,
    val homeLoading: Boolean = false,
    val fileFilter: FileFilter = FileFilter.ALL,
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
    val extractDialog: ExtractZipState? = null,
)

class FileBrowserViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(BrowserUiState())
    val uiState: StateFlow<BrowserUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val events: SharedFlow<String> = _events.asSharedFlow()

    private val root = FileOperations.defaultRoot()
    private var searchJob: Job? = null

    fun setStorageGranted(granted: Boolean) {
        _uiState.update { it.copy(storageGranted = granted) }
        if (granted) {
            if (_uiState.value.showHome) {
                loadHomeData()
            } else {
                refresh()
            }
        }
    }

    fun loadHomeData() {
        viewModelScope.launch {
            _uiState.update { it.copy(homeLoading = true) }
            val data = withContext(Dispatchers.IO) {
                HomeDashboardData(
                    storageInfo = FileOperations.getStorageInfo(),
                    categories = FileOperations.getCategorySummaries(),
                    recentFiles = FileOperations.getRecentImages(),
                )
            }
            _uiState.update {
                it.copy(
                    homeLoading = false,
                    storageInfo = data.storageInfo,
                    categorySummaries = data.categories,
                    recentFiles = data.recentFiles,
                )
            }
        }
    }

    fun openCategory(category: FileCategory) {
        val folder = category.resolveFolder()
        if (!folder.exists()) folder.mkdirs()
        _uiState.update {
            it.copy(
                showHome = false,
                activeCategory = category,
                categoryRoot = folder,
                currentDir = folder,
                fileFilter = FileFilter.forCategory(category),
                selectionMode = false,
                selectedPaths = emptySet(),
                searchQuery = "",
                searchResults = emptyList(),
            )
        }
        refresh()
    }

    fun updateSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
        searchJob?.cancel()
        val trimmed = query.trim()
        if (trimmed.length < 2) {
            _uiState.update { it.copy(searchResults = emptyList(), searchLoading = false) }
            return
        }
        searchJob = viewModelScope.launch {
            delay(300)
            _uiState.update { it.copy(searchLoading = true) }
            val results = withContext(Dispatchers.IO) {
                FileOperations.searchFiles(trimmed)
            }
            _uiState.update {
                it.copy(searchResults = results, searchLoading = false)
            }
        }
    }

    fun clearSearch() {
        searchJob?.cancel()
        _uiState.update {
            it.copy(searchQuery = "", searchResults = emptyList(), searchLoading = false)
        }
    }

    fun setFileFilter(filter: FileFilter) {
        _uiState.update { it.copy(fileFilter = filter) }
        refresh()
    }

    fun openFileFromAnywhere(item: FileItem) {
        if (item.isDirectory) {
            _uiState.update {
                it.copy(
                    showHome = false,
                    activeCategory = null,
                    categoryRoot = null,
                    currentDir = item.file,
                    fileFilter = FileFilter.ALL,
                    searchQuery = "",
                    searchResults = emptyList(),
                )
            }
            refresh()
            return
        }
        val parent = item.file.parentFile
        if (parent != null) {
            _uiState.update {
                it.copy(
                    showHome = false,
                    activeCategory = null,
                    categoryRoot = null,
                    currentDir = parent,
                    fileFilter = FileFilter.ALL,
                    searchQuery = "",
                    searchResults = emptyList(),
                )
            }
            refresh()
        }
        openItem(item)
    }

    fun browseAllFiles() {
        _uiState.update {
            it.copy(
                showHome = false,
                activeCategory = null,
                categoryRoot = null,
                currentDir = root,
                fileFilter = FileFilter.ALL,
                selectionMode = false,
                selectedPaths = emptySet(),
                searchQuery = "",
                searchResults = emptyList(),
            )
        }
        refresh()
    }

    fun goHome() {
        searchJob?.cancel()
        _uiState.update {
            it.copy(
                showHome = true,
                activeCategory = null,
                categoryRoot = null,
                fileFilter = FileFilter.ALL,
                selectionMode = false,
                selectedPaths = emptySet(),
                items = emptyList(),
                canGoUp = false,
                searchQuery = "",
                searchResults = emptyList(),
                searchLoading = false,
            )
        }
        loadHomeData()
    }

    fun refresh() {
        if (_uiState.value.showHome) {
            loadHomeData()
            return
        }
        val dir = _uiState.value.currentDir
        val filter = _uiState.value.fileFilter
        val items = FileOperations.listFiles(dir)
            .filter { it.matchesFilter(filter) }
            .let { list ->
            if (_uiState.value.sortNewestFirst) {
                list.sortedWith(
                    compareByDescending<FileItem> { it.isDirectory }
                        .thenByDescending { it.lastModified }
                )
            } else {
                list
            }
        }
        val canGoUp = !_uiState.value.showHome
        _uiState.update {
            it.copy(
                items = items,
                canGoUp = canGoUp,
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
                showHome = false,
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
            item.isArchive -> openExtractDialog(item.file.canonicalFile)
            item.isPdf -> openViewer(ViewerContent.Pdf(item.file))
            item.isImage -> openViewer(ViewerContent.Image(item.file))
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

    fun openExtractDialogForItem(item: FileItem) {
        openExtractDialog(item.file)
    }

    fun openExtractDialog(zipFile: File) {
        val file = runCatching { zipFile.canonicalFile }.getOrDefault(zipFile)
        if (!file.exists() || !file.isFile) {
            emit("File ZIP tidak ditemukan")
            return
        }
        _uiState.update {
            it.copy(
                extractDialog = ExtractZipState(zipFile = file, isLoading = true),
                selectionMode = false,
                selectedPaths = emptySet(),
            )
        }
        viewModelScope.launch {
            try {
                val entries = withContext(Dispatchers.IO) {
                    ZipManager.listZipEntryDetails(file)
                }
                if (entries.isEmpty()) {
                    _uiState.update {
                        it.copy(
                            extractDialog = ExtractZipState(
                                zipFile = file,
                                isLoading = false,
                                error = "File ZIP kosong",
                            ),
                        )
                    }
                    return@launch
                }
                _uiState.update {
                    it.copy(
                        extractDialog = ExtractZipState(
                            zipFile = file,
                            entries = entries,
                            selectedPaths = entries.map { entry -> entry.path }.toSet(),
                            isLoading = false,
                        ),
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        extractDialog = ExtractZipState(
                            zipFile = file,
                            isLoading = false,
                            error = "Gagal membaca ZIP: ${e.message ?: "error"}",
                        ),
                    )
                }
            }
        }
    }

    fun closeExtractDialog() {
        _uiState.update { it.copy(extractDialog = null) }
    }

    fun toggleExtractEntry(path: String) {
        _uiState.update { state ->
            val dialog = state.extractDialog ?: return@update state
            val next = dialog.selectedPaths.toMutableSet()
            if (!next.add(path)) next.remove(path)
            state.copy(extractDialog = dialog.copy(selectedPaths = next))
        }
    }

    fun selectAllExtractEntries() {
        _uiState.update { state ->
            val dialog = state.extractDialog ?: return@update state
            state.copy(
                extractDialog = dialog.copy(
                    selectedPaths = dialog.entries.map { it.path }.toSet(),
                ),
            )
        }
    }

    fun deselectAllExtractEntries() {
        _uiState.update { state ->
            val dialog = state.extractDialog ?: return@update state
            state.copy(extractDialog = dialog.copy(selectedPaths = emptySet()))
        }
    }

    fun setDeleteOriginalZip(delete: Boolean) {
        _uiState.update { state ->
            val dialog = state.extractDialog ?: return@update state
            state.copy(extractDialog = dialog.copy(deleteOriginal = delete))
        }
    }

    fun confirmExtract() {
        val dialog = _uiState.value.extractDialog ?: return
        if (dialog.selectedPaths.isEmpty()) {
            emit("Pilih minimal 1 file untuk diextract")
            return
        }
        val zip = dialog.zipFile
        val destination = zip.parentFile
        if (destination == null || !destination.exists()) {
            emit("Folder tujuan tidak ditemukan")
            return
        }

        val deleteOriginal = dialog.deleteOriginal
        val selectedPaths = dialog.selectedPaths
        closeExtractDialog()

        runJob("Extract ZIP…", zip.name) {
            try {
                ZipManager.extractZipEntries(zip, destination, selectedPaths) { progress, name ->
                    updateProgress("Extract ZIP…", name, progress)
                }
                if (deleteOriginal) {
                    if (!zip.delete()) {
                        emit("Extract selesai, tetapi gagal menghapus ZIP asli")
                        return@runJob
                    }
                }
                val suffix = if (deleteOriginal) " (ZIP asli dihapus)" else ""
                emit("Extract selesai ke ${destination.name}$suffix")
            } catch (e: Exception) {
                emit("Gagal extract: ${e.message ?: "error"}")
            }
        }
    }

    fun goUp() {
        val state = _uiState.value
        val dir = state.currentDir
        val categoryRoot = state.categoryRoot

        val atCategoryRoot = categoryRoot != null && FileOperations.samePath(dir, categoryRoot)
        val atStorageRoot = FileOperations.samePath(dir, root)

        if (atCategoryRoot || atStorageRoot) {
            goHome()
            return
        }

        val parent = dir.parentFile
        if (parent == null || !parent.exists()) {
            goHome()
            return
        }

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
                showHome = false,
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

    fun extractSelected() {
        val zip = selectedFiles().singleOrNull { it.isFile && FileItem(it).isArchive }
            ?: selectedFiles().singleOrNull()?.takeIf { FileItem(it).isArchive }
        if (zip == null) {
            emit("Pilih 1 file ZIP untuk diextract")
            return
        }
        openExtractDialog(zip)
    }

    fun extractZipFile(zip: File) {
        if (!zip.exists()) {
            emit("File ZIP tidak ditemukan")
            return
        }
        navigateTo(zip.parentFile ?: root)
        openExtractDialog(zip)
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

    private data class HomeDashboardData(
        val storageInfo: StorageInfo,
        val categories: List<CategorySummary>,
        val recentFiles: List<FileItem>,
    )
}
