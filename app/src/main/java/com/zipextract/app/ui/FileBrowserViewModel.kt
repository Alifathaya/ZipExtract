package com.zipextract.app.ui

import android.app.Application
import androidx.annotation.StringRes
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zipextract.app.data.AppLanguage
import com.zipextract.app.data.AppPreferences
import com.zipextract.app.R
import com.zipextract.app.data.ClipboardMode
import com.zipextract.app.data.ClipboardState
import com.zipextract.app.data.CategorySummary
import com.zipextract.app.data.DuplicateFinder
import com.zipextract.app.data.DuplicateGroup
import com.zipextract.app.data.FileActions
import com.zipextract.app.data.FileCategory
import com.zipextract.app.data.FileFilter
import com.zipextract.app.data.FileItem
import com.zipextract.app.data.FileOperations
import com.zipextract.app.data.MediaAlbum
import com.zipextract.app.data.MediaAlbumChip
import com.zipextract.app.data.MediaChangeWatcher
import com.zipextract.app.data.LibrarySubFilter
import com.zipextract.app.data.LocaleHelper
import com.zipextract.app.data.MediaLibrary
import com.zipextract.app.data.MediaLibraryCache
import com.zipextract.app.data.OperationResult
import com.zipextract.app.data.ProgressState
import com.zipextract.app.data.SharedFileResolver
import com.zipextract.app.data.StorageInfo
import com.zipextract.app.data.ThemeMode
import com.zipextract.app.data.ArchiveManager
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
import kotlin.jvm.Volatile

sealed class ViewerContent {
    abstract val file: File
    val title: String get() = file.name

    data class Image(override val file: File) : ViewerContent()
    data class Pdf(
        override val file: File,
        val sourceUri: Uri? = null,
    ) : ViewerContent()
    data class Video(
        override val file: File,
        val sourceUri: Uri? = null,
    ) : ViewerContent()
}

/** Where to land after closing the in-app viewer. */
enum class ViewerReturnTarget {
    /** Stay on current browser / library screen. */
    STAY,
    /** Back to home dashboard (recent photos, search, etc.). */
    HOME,
    /** Back to Cloud hub. */
    CLOUD,
}

data class ExtractZipState(
    val zipFile: File,
    val entries: List<com.zipextract.app.data.ZipEntryItem> = emptyList(),
    val selectedPaths: Set<String> = emptySet(),
    val deleteOriginal: Boolean = false,
    val destinationDir: File = zipFile.parentFile ?: zipFile,
    val isLoading: Boolean = false,
    val isEncrypted: Boolean = false,
    val password: String = "",
    val error: String? = null,
)

data class ExtractResultState(
    val success: Boolean,
    val title: String,
    val message: String,
    val destination: File? = null,
    val fileCount: Int = 0,
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
    val showLargestFiles: Boolean = false,
    val libraryMode: Boolean = false,
    val librarySubFilter: LibrarySubFilter = LibrarySubFilter.ALL,
    val mediaAlbumId: String = MediaAlbum.ALL,
    val mediaAlbums: List<MediaAlbumChip> = emptyList(),
    val favoritePaths: Set<String> = emptySet(),
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val appLanguage: AppLanguage = AppLanguage.SYSTEM,
    val languageChosen: Boolean = false,
    val showFavoritesOnly: Boolean = false,
    val fileDetails: FileItem? = null,
    val duplicateGroups: List<DuplicateGroup> = emptyList(),
    val showDuplicates: Boolean = false,
    val showCloud: Boolean = false,
    val safBookmarks: List<com.zipextract.app.data.cloud.SafBookmark> = emptyList(),
    val cloudExportFile: File? = null,
    val viewer: ViewerContent? = null,
    val viewerReturnTarget: ViewerReturnTarget = ViewerReturnTarget.STAY,
    val launchedFromExternalIntent: Boolean = false,
    val extractDialog: ExtractZipState? = null,
    val extractResult: ExtractResultState? = null,
)

class FileBrowserViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = AppPreferences(application)
    private val appContext = application.applicationContext
    private val initialCachedPhotos = prefs.loadCachedRecentPhotos()
    private val initialCachedCategories = prefs.loadCachedCategorySummaries()
        .ifEmpty { FileOperations.getEmptyCategorySummaries() }
    private val hasStartupCache =
        prefs.hasHomeUiCache() || MediaLibraryCache.exists(application)

    private val _uiState = MutableStateFlow(
        BrowserUiState(
            favoritePaths = prefs.getFavoritePaths(),
            themeMode = prefs.getThemeMode(),
            appLanguage = prefs.getAppLanguage(),
            languageChosen = prefs.hasLanguageChosen(),
            categorySummaries = initialCachedCategories,
            recentFiles = initialCachedPhotos,
            storageInfo = prefs.loadCachedStorageInfo(),
            safBookmarks = prefs.getSafBookmarks(),
            // Skip loading spinner when we already have a previous session snapshot.
            homeLoading = !hasStartupCache,
        )
    )
    val uiState: StateFlow<BrowserUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val events: SharedFlow<String> = _events.asSharedFlow()

    private val root = FileOperations.defaultRoot()
    private var searchJob: Job? = null
    private var libraryJob: Job? = null
    private var homeJob: Job? = null
    private var softRefreshJob: Job? = null
    private var cloudImportJob: Job? = null
    private var activeJob: Job? = null
    private var mediaWatcher: MediaChangeWatcher? = null
    @Volatile
    private var mediaLibraryCache: MediaLibrary? = null
    @Volatile
    private var homeLoadedOnce: Boolean = false
    @Volatile
    private var lastSoftRefreshAtMs: Long = 0L
    @Volatile
    private var pendingSoftRefresh: Boolean = false
    @Volatile
    private var pendingSoftRefreshReason: String = "soft"

    init {
        // Live MediaStore updates: new downloads / photos appear without manual refresh.
        mediaWatcher = MediaChangeWatcher(application) { source ->
            requestSoftRefresh(reason = "media-observer:$source", fromObserver = true)
            // MediaStore often indexes before the file size is final — follow up once.
            viewModelScope.launch {
                delay(2_000)
                requestSoftRefresh(reason = "media-observer-followup:$source", fromObserver = true)
            }
        }.also { it.start() }
    }

    fun setStorageGranted(granted: Boolean) {
        val wasGranted = _uiState.value.storageGranted
        _uiState.update { it.copy(storageGranted = granted) }
        if (!granted) return
        if (_uiState.value.showHome) {
            // First open / first grant: load (cache ok). Later resumes: always soft-rescan
            // so newly taken photos appear without waiting 15+ minutes.
            if (!wasGranted || !homeLoadedOnce || mediaLibraryCache == null) {
                loadHomeData(forceRefresh = false)
            } else {
                softRefreshMediaInBackground(reason = "resume-home")
            }
        } else if (_uiState.value.libraryMode) {
            softRefreshMediaInBackground(reason = "resume-library")
        } else if (!wasGranted) {
            refresh()
        }
    }

    fun loadHomeData(forceRefresh: Boolean = false) {
        homeJob?.cancel()
        homeJob = viewModelScope.launch {
            val hasMemory = mediaLibraryCache != null && !forceRefresh
            if (!hasMemory) {
                val cachedCategories = prefs.loadCachedCategorySummaries()
                    .ifEmpty { FileOperations.getEmptyCategorySummaries() }
                val cachedPhotos = prefs.loadCachedRecentPhotos()
                val cachedStorage = prefs.loadCachedStorageInfo()
                val hasUiCache = prefs.hasHomeUiCache() ||
                    cachedPhotos.isNotEmpty() ||
                    MediaLibraryCache.exists(appContext)
                _uiState.update { state ->
                    state.copy(
                        homeLoading = !hasUiCache,
                        storageInfo = state.storageInfo ?: cachedStorage,
                        categorySummaries = if (state.categorySummaries.any { it.itemCount > 0 }) {
                            state.categorySummaries
                        } else {
                            cachedCategories
                        },
                        recentFiles = state.recentFiles.ifEmpty { cachedPhotos },
                    )
                }
            } else {
                _uiState.update { it.copy(homeLoading = false) }
            }

            val data = withContext(Dispatchers.IO) {
                val library = obtainMediaLibrary(forceRefresh)
                val categories = FileOperations.getCategorySummaries(library)
                val recentFiles = library.images.take(12)
                val storage = FileOperations.getStorageInfo()
                persistHomeSnapshot(library, categories, recentFiles, storage)
                HomeDashboardData(
                    storageInfo = storage,
                    categories = categories,
                    recentFiles = recentFiles,
                )
            }
            homeLoadedOnce = true
            _uiState.update {
                it.copy(
                    homeLoading = false,
                    storageInfo = data.storageInfo,
                    categorySummaries = data.categories,
                    recentFiles = data.recentFiles,
                )
            }

            // Quietly rescan so brand-new camera shots show up soon after.
            if (!forceRefresh) {
                softRefreshMediaInBackground(reason = "after-home-load")
            }
        }
    }

    /** Flush home/media cache to disk so the next cold start opens instantly. */
    fun persistHomeCache() {
        val library = mediaLibraryCache ?: return
        val state = _uiState.value
        viewModelScope.launch(Dispatchers.IO) {
            persistHomeSnapshot(
                library = library,
                categories = state.categorySummaries.ifEmpty {
                    FileOperations.getCategorySummaries(library)
                },
                recentFiles = state.recentFiles.ifEmpty { library.images.take(12) },
                storage = state.storageInfo ?: FileOperations.getStorageInfo(),
            )
        }
    }

    private fun softRefreshMediaInBackground(reason: String = "soft") {
        requestSoftRefresh(reason = reason, fromObserver = false)
    }

    private fun requestSoftRefresh(reason: String, fromObserver: Boolean) {
        val debounce = if (fromObserver) {
            MediaLibraryCache.MEDIA_OBSERVER_DEBOUNCE_MS
        } else {
            MediaLibraryCache.RESUME_REFRESH_DEBOUNCE_MS
        }
        val now = System.currentTimeMillis()
        if (softRefreshJob?.isActive == true) {
            pendingSoftRefresh = true
            pendingSoftRefreshReason = reason
            return
        }
        if (now - lastSoftRefreshAtMs < debounce) {
            // Coalesce near-term observer bursts into one follow-up scan.
            if (fromObserver) {
                pendingSoftRefresh = true
                pendingSoftRefreshReason = reason
                viewModelScope.launch {
                    delay(debounce)
                    if (pendingSoftRefresh && softRefreshJob?.isActive != true) {
                        runSoftRefreshLoop(pendingSoftRefreshReason)
                    }
                }
            }
            return
        }
        runSoftRefreshLoop(reason)
    }

    private fun runSoftRefreshLoop(initialReason: String) {
        lastSoftRefreshAtMs = System.currentTimeMillis()
        softRefreshJob = viewModelScope.launch(Dispatchers.IO) {
            var reason = initialReason
            do {
                pendingSoftRefresh = false
                runCatching {
                    @Suppress("UNUSED_EXPRESSION")
                    reason
                    val library = FileOperations.scanMediaLibrary(
                        contentResolver = appContext.contentResolver,
                    ).also { mediaLibraryCache = it }
                    val categories = FileOperations.getCategorySummaries(library)
                    val recentFiles = library.images.take(12)
                    val storage = FileOperations.getStorageInfo()
                    persistHomeSnapshot(library, categories, recentFiles, storage)
                    _uiState.update { state ->
                        val base = state.copy(
                            homeLoading = false,
                            storageInfo = storage,
                            categorySummaries = categories,
                            recentFiles = recentFiles,
                        )
                        when {
                            state.libraryMode && state.activeCategory != null -> {
                                val category = state.activeCategory
                                val files = library.forCategory(category)
                                when (category) {
                                    FileCategory.IMAGES, FileCategory.VIDEOS -> {
                                        val albums = MediaAlbum.buildChips(files)
                                        val albumId = MediaAlbum.sanitizeSelection(state.mediaAlbumId, albums)
                                        val sorted = sortLibraryFiles(MediaAlbum.filter(files, albumId))
                                        base.copy(
                                            mediaAlbums = albums,
                                            mediaAlbumId = albumId,
                                            items = sorted,
                                            selectedPaths = state.selectedPaths.filter { path ->
                                                sorted.any { item -> item.path == path }
                                            }.toSet(),
                                            progress = null,
                                        )
                                    }
                                    FileCategory.DOCUMENTS -> {
                                        val filtered = files.filter { state.librarySubFilter.matches(it) }
                                        val sorted = sortLibraryFiles(filtered)
                                        base.copy(
                                            items = sorted,
                                            selectedPaths = state.selectedPaths.filter { path ->
                                                sorted.any { item -> item.path == path }
                                            }.toSet(),
                                            progress = null,
                                        )
                                    }
                                    else -> {
                                        // DOWNLOADS / ARCHIVES / APPS / OTHERS
                                        val sorted = sortLibraryFiles(files)
                                        base.copy(
                                            items = sorted,
                                            selectedPaths = state.selectedPaths.filter { path ->
                                                sorted.any { item -> item.path == path }
                                            }.toSet(),
                                            progress = null,
                                        )
                                    }
                                }
                            }
                            !state.showHome &&
                                !state.showCloud &&
                                !state.libraryMode &&
                                FileOperations.isUnderDownloads(state.currentDir) -> {
                                val filter = state.fileFilter
                                val items = FileOperations.listFiles(state.currentDir)
                                    .filter { it.matchesFilter(filter) }
                                    .let { list ->
                                        if (state.sortNewestFirst) {
                                            list.sortedWith(
                                                compareByDescending<FileItem> { it.isDirectory }
                                                    .thenByDescending { it.lastModified },
                                            )
                                        } else {
                                            list
                                        }
                                    }
                                base.copy(
                                    items = items,
                                    selectedPaths = state.selectedPaths.filter { path ->
                                        items.any { item -> item.path == path }
                                    }.toSet(),
                                )
                            }
                            else -> base
                        }
                    }
                }
                reason = pendingSoftRefreshReason
            } while (pendingSoftRefresh)
        }
    }

    private fun softRefreshHomeInBackground() {
        softRefreshMediaInBackground(reason = "legacy-home")
    }

    private fun persistHomeSnapshot(
        library: MediaLibrary,
        categories: List<CategorySummary>,
        recentFiles: List<FileItem>,
        storage: StorageInfo,
    ) {
        MediaLibraryCache.save(appContext, library)
        prefs.saveCategoryCounts(categories)
        prefs.saveRecentPhotoPaths(recentFiles.map { it.path })
        prefs.saveStorageInfo(storage)
    }

    fun openCategory(category: FileCategory) {
        openCategoryLibrary(category, forceRefresh = false)
    }

    fun openCategoryLibrary(category: FileCategory, forceRefresh: Boolean = false) {
        val folder = category.resolveFolder()
        if (!folder.exists()) folder.mkdirs()

        // Hydrate memory from disk cache first so category open stays instant after reopen.
        if (!forceRefresh && mediaLibraryCache == null) {
            mediaLibraryCache = MediaLibraryCache.load(appContext)
        }

        val cachedFiles = if (!forceRefresh) {
            mediaLibraryCache?.forCategory(category)
        } else {
            null
        }

        if (cachedFiles != null) {
            val subFilter = if (category == FileCategory.DOCUMENTS) {
                _uiState.value.librarySubFilter
            } else {
                LibrarySubFilter.ALL
            }
            val usesMediaAlbums = category == FileCategory.IMAGES || category == FileCategory.VIDEOS
            val mediaAlbums = if (usesMediaAlbums) {
                MediaAlbum.buildChips(cachedFiles)
            } else {
                emptyList()
            }
            val mediaAlbumId = MediaAlbum.ALL
            val filtered = when (category) {
                FileCategory.DOCUMENTS -> cachedFiles.filter { subFilter.matches(it) }
                FileCategory.IMAGES, FileCategory.VIDEOS -> MediaAlbum.filter(cachedFiles, mediaAlbumId)
                else -> cachedFiles
            }
            val sorted = sortLibraryFiles(filtered)
            _uiState.update {
                it.copy(
                    showHome = false,
                    activeCategory = category,
                    categoryRoot = folder,
                    currentDir = folder,
                    fileFilter = FileFilter.forCategory(category),
                    libraryMode = true,
                    librarySubFilter = subFilter,
                    mediaAlbums = mediaAlbums,
                    mediaAlbumId = mediaAlbumId,
                    showFavoritesOnly = false,
                showLargestFiles = false,
                    showDuplicates = false,
                    selectionMode = false,
                    selectedPaths = emptySet(),
                    searchQuery = "",
                    searchResults = emptyList(),
                    items = sorted,
                    canGoUp = true,
                    progress = null,
                )
            }
            // Show cache instantly, then rescan so newly added photos/videos appear.
            softRefreshMediaInBackground(reason = "open-category")
            return
        }

        _uiState.update {
            it.copy(
                showHome = false,
                activeCategory = category,
                categoryRoot = folder,
                currentDir = folder,
                fileFilter = FileFilter.forCategory(category),
                libraryMode = true,
                mediaAlbums = emptyList(),
                mediaAlbumId = MediaAlbum.ALL,
                selectionMode = false,
                selectedPaths = emptySet(),
                searchQuery = "",
                searchResults = emptyList(),
                items = emptyList(),
                canGoUp = true,
                progress = ProgressState(
                    title = str(R.string.loading_category, str(category.titleRes)),
                    message = str(R.string.searching_all_category, str(category.nounRes)),
                    indeterminate = true,
                ),
            )
        }

        libraryJob?.cancel()
        libraryJob = viewModelScope.launch {
            val library = withContext(Dispatchers.IO) {
                obtainMediaLibrary(forceRefresh = forceRefresh)
            }
            if (_uiState.value.activeCategory != category || !_uiState.value.libraryMode) {
                return@launch
            }
            val subFilter = _uiState.value.librarySubFilter
            val base = library.forCategory(category)
            val usesMediaAlbums = category == FileCategory.IMAGES || category == FileCategory.VIDEOS
            val mediaAlbums = if (usesMediaAlbums) {
                MediaAlbum.buildChips(base)
            } else {
                emptyList()
            }
            val mediaAlbumId = MediaAlbum.ALL
            val filtered = when (category) {
                FileCategory.DOCUMENTS -> base.filter { subFilter.matches(it) }
                FileCategory.IMAGES, FileCategory.VIDEOS -> MediaAlbum.filter(base, mediaAlbumId)
                else -> base
            }
            val sorted = sortLibraryFiles(filtered)
            _uiState.update {
                it.copy(
                    items = sorted,
                    progress = null,
                    canGoUp = true,
                    librarySubFilter = if (category == FileCategory.DOCUMENTS) subFilter else LibrarySubFilter.ALL,
                    mediaAlbums = mediaAlbums,
                    mediaAlbumId = mediaAlbumId,
                    selectedPaths = it.selectedPaths.filter { path ->
                        sorted.any { item -> item.path == path }
                    }.toSet(),
                )
            }
            withContext(Dispatchers.IO) {
                persistHomeSnapshot(
                    library = library,
                    categories = FileOperations.getCategorySummaries(library),
                    recentFiles = library.images.take(12),
                    storage = _uiState.value.storageInfo ?: FileOperations.getStorageInfo(),
                )
            }
        }
    }

    private fun sortLibraryFiles(files: List<FileItem>): List<FileItem> {
        return files.sortedByDescending { it.lastModified }
    }

    private fun obtainMediaLibrary(forceRefresh: Boolean): MediaLibrary {
        if (!forceRefresh) {
            mediaLibraryCache?.let { return it }
            MediaLibraryCache.load(appContext)?.let { disk ->
                mediaLibraryCache = disk
                return disk
            }
        }
        return FileOperations.scanMediaLibrary(
            contentResolver = appContext.contentResolver,
        ).also { mediaLibraryCache = it }
    }

    private fun invalidateMediaLibraryCache() {
        mediaLibraryCache = null
    }

    override fun onCleared() {
        mediaWatcher?.stop()
        mediaWatcher = null
        // Last chance flush before process teardown.
        mediaLibraryCache?.let { library ->
            runCatching {
                MediaLibraryCache.save(appContext, library)
                prefs.saveCategoryCounts(FileOperations.getCategorySummaries(library))
                prefs.saveRecentPhotoPaths(library.images.take(12).map { it.path })
                _uiState.value.storageInfo?.let { prefs.saveStorageInfo(it) }
            }
        }
        super.onCleared()
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
                    libraryMode = false,
                    // Keep search so Back from this folder can still feel contextual;
                    // goHome clears it explicitly.
                    searchQuery = "",
                    searchResults = emptyList(),
                )
            }
            refresh()
            return
        }

        val fromHomeOrCloud = _uiState.value.showHome || _uiState.value.showCloud
        // From home search / cloud: never jump into an empty parent folder list.
        if (fromHomeOrCloud) {
            when {
                item.isPdf || item.isImage || item.isVideo -> openItem(item)
                item.isApk -> installApkFile(item.file)
                item.isArchive -> openExtractDialog(item.file.canonicalFile)
                // In-app ExoPlayer handles m4a/opus/flac even when no external player exists.
                item.isAudio -> openViewer(ViewerContent.Video(item.file), ViewerReturnTarget.HOME)
                else -> showFileDetails(item)
            }
            return
        }

        // Browser / library: open media in-place; other files may reveal parent folder.
        when {
            item.isPdf || item.isImage -> openItem(item)
            item.isApk -> installApkFile(item.file)
            else -> {
                val parent = item.file.parentFile
                if (parent != null) {
                    _uiState.update {
                        it.copy(
                            showHome = false,
                            activeCategory = null,
                            categoryRoot = null,
                            currentDir = parent,
                            fileFilter = FileFilter.ALL,
                            libraryMode = false,
                            searchQuery = "",
                            searchResults = emptyList(),
                        )
                    }
                    refresh()
                }
                openItem(item)
            }
        }
    }

    fun browseAllFiles() {
        _uiState.update {
            it.copy(
                showHome = false,
                activeCategory = null,
                categoryRoot = null,
                currentDir = root,
                fileFilter = FileFilter.ALL,
                libraryMode = false,
                selectionMode = false,
                selectedPaths = emptySet(),
                searchQuery = "",
                searchResults = emptyList(),
            )
        }
        refresh()
    }

    fun openCloud(exportFile: File? = null) {
        _uiState.update {
            it.copy(
                showCloud = true,
                cloudExportFile = exportFile,
                safBookmarks = prefs.getSafBookmarks(),
            )
        }
    }

    fun closeCloud() {
        _uiState.update {
            it.copy(
                showCloud = false,
                cloudExportFile = null,
            )
        }
    }

    fun updateSafBookmarks(bookmarks: List<com.zipextract.app.data.cloud.SafBookmark>) {
        prefs.saveSafBookmarks(bookmarks)
        _uiState.update { it.copy(safBookmarks = bookmarks) }
    }

    fun openImportedCloudFile(file: File) {
        // Serialize hand-off so rapid cloud picks don't stack viewer opens / freeze UI.
        cloudImportJob?.cancel()
        cloudImportJob = viewModelScope.launch {
            if (!file.exists() || file.length() <= 0L) {
                _events.tryEmit(str(R.string.cloud_file_empty))
                return@launch
            }
            val item = FileItem(file)
            // Keep Cloud as return target even though we close the hub while viewing.
            closeCloud()
            kotlinx.coroutines.yield()
            when {
                item.isImage -> openViewer(ViewerContent.Image(file), ViewerReturnTarget.HOME)
                item.isPdf -> openViewer(ViewerContent.Pdf(file), ViewerReturnTarget.HOME)
                item.isVideo -> openViewer(ViewerContent.Video(file), ViewerReturnTarget.HOME)
                item.isApk -> installApkFile(file)
                item.isArchive -> {
                    // Archives open extract UI; return home/cloud via normal flow after.
                    openExtractDialog(file)
                }
                else -> openFileFromAnywhere(item)
            }
        }
    }

    fun goHome() {
        searchJob?.cancel()
        libraryJob?.cancel()
        cloudImportJob?.cancel()
        _uiState.update {
            it.copy(
                showHome = true,
                showCloud = false,
                cloudExportFile = null,
                activeCategory = null,
                categoryRoot = null,
                fileFilter = FileFilter.ALL,
                libraryMode = false,
                showFavoritesOnly = false,
                showLargestFiles = false,
                showDuplicates = false,
                duplicateGroups = emptyList(),
                librarySubFilter = LibrarySubFilter.ALL,
                mediaAlbumId = MediaAlbum.ALL,
                mediaAlbums = emptyList(),
                selectionMode = false,
                selectedPaths = emptySet(),
                items = emptyList(),
                canGoUp = false,
                searchQuery = "",
                searchResults = emptyList(),
                searchLoading = false,
                progress = null,
                viewer = null,
                viewerReturnTarget = ViewerReturnTarget.STAY,
                extractDialog = null,
            )
        }
        loadHomeData(forceRefresh = false)
    }

    fun refresh() {
        if (_uiState.value.showLargestFiles) {
            invalidateMediaLibraryCache()
            openLargestFiles()
            return
        }
        if (_uiState.value.showHome) {
            invalidateMediaLibraryCache()
            loadHomeData(forceRefresh = true)
            return
        }
        if (_uiState.value.libraryMode) {
            val category = _uiState.value.activeCategory ?: return
            invalidateMediaLibraryCache()
            openCategoryLibrary(category, forceRefresh = true)
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
            item.isApk -> installApkFile(item.file)
            item.isApp -> {
                // xapk / apks / apkm are zip-based bundles — extract first.
                openExtractDialog(item.file.canonicalFile)
            }
            item.isArchive -> openExtractDialog(item.file.canonicalFile)
            item.isPdf -> openViewer(ViewerContent.Pdf(item.file))
            item.isImage -> openViewer(ViewerContent.Image(item.file))
            item.isVideo -> openViewer(ViewerContent.Video(item.file))
            // In-app ExoPlayer handles m4a/opus/flac even when no external player exists.
            item.isAudio -> openViewer(ViewerContent.Video(item.file))
            else -> toggleSelect(item)
        }
    }

    fun installApkFile(file: File) {
        // PackageInstaller session I/O must not run on the main thread (large APKs ANR).
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    progress = ProgressState(str(R.string.apk_preparing_installer), file.name),
                )
            }
            val result = withContext(Dispatchers.IO) {
                FileActions.installApk(getApplication(), file)
            }
            _uiState.update { it.copy(progress = null) }
            when (result) {
                is FileActions.InstallApkResult.Started -> {
                    emit(str(R.string.apk_opening_installer))
                }
                is FileActions.InstallApkResult.NeedInstallPermission -> {
                    emit(str(R.string.apk_need_install_permission))
                }
                is FileActions.InstallApkResult.Failed -> {
                    // Split packages (xapk/apks) or non-apk apps: fall back to extract / open-with.
                    val item = FileItem(file)
                    when {
                        item.isApp && !item.isApk -> {
                            emit(str(R.string.apk_needs_extract, item.extension.uppercase()))
                            openExtractDialog(file)
                        }
                        else -> emit(result.reason)
                    }
                }
            }
        }
    }

    fun openViewer(
        content: ViewerContent,
        returnTarget: ViewerReturnTarget? = null,
    ) {
        val canOpen = when (content) {
            is ViewerContent.Pdf -> {
                content.sourceUri != null ||
                    (content.file.exists() && content.file.isFile)
            }
            is ViewerContent.Image -> content.file.exists() && content.file.isFile
            is ViewerContent.Video -> {
                content.sourceUri != null ||
                    (content.file.exists() && content.file.isFile && content.file.length() > 0L)
            }
        }
        if (!canOpen) {
            emit(str(R.string.file_not_found))
            return
        }
        val state = _uiState.value
        val resolvedReturn = returnTarget ?: when {
            state.showCloud -> ViewerReturnTarget.CLOUD
            state.showHome -> ViewerReturnTarget.HOME
            else -> ViewerReturnTarget.STAY
        }
        _uiState.update {
            it.copy(
                showHome = false,
                showCloud = false,
                viewer = content,
                viewerReturnTarget = resolvedReturn,
                extractDialog = null,
                selectionMode = false,
                selectedPaths = emptySet(),
                // Preserve search so Back from viewer restores the previous results page.
                searchQuery = if (resolvedReturn == ViewerReturnTarget.HOME) it.searchQuery else "",
                searchResults = if (resolvedReturn == ViewerReturnTarget.HOME) it.searchResults else emptyList(),
                searchLoading = if (resolvedReturn == ViewerReturnTarget.HOME) it.searchLoading else false,
            )
        }
    }

    fun openSharedUri(context: Context, uri: Uri, mimeType: String?) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    launchedFromExternalIntent = true,
                    progress = ProgressState(str(R.string.progress_opening_file), uri.lastPathSegment.orEmpty()),
                )
            }
            val resolved = withContext(Dispatchers.IO) {
                SharedFileResolver.resolveShare(context, uri, mimeType)
            }
            _uiState.update { it.copy(progress = null) }
            if (resolved == null) {
                _uiState.update { it.copy(launchedFromExternalIntent = false, showHome = true) }
                emit(str(R.string.file_cannot_open))
                return@launch
            }

            val item = FileItem(resolved.file)
            when {
                item.isApk -> {
                    _uiState.update { it.copy(launchedFromExternalIntent = false) }
                    installApkFile(resolved.file)
                }
                item.isArchive -> extractZipFile(resolved.file)
                SharedFileResolver.isPdf(resolved.file, resolved.mimeType) -> {
                    openViewer(
                        ViewerContent.Pdf(
                            file = resolved.file,
                            sourceUri = resolved.sourceUri,
                        )
                    )
                }
                SharedFileResolver.isImage(resolved.file, resolved.mimeType) -> {
                    openViewer(ViewerContent.Image(resolved.file))
                }
                SharedFileResolver.isVideo(resolved.file, resolved.mimeType) -> {
                    openViewer(
                        ViewerContent.Video(
                            file = resolved.file,
                            sourceUri = resolved.sourceUri,
                        )
                    )
                }
                item.isAudio -> {
                    openViewer(
                        ViewerContent.Video(
                            file = resolved.file,
                            sourceUri = resolved.sourceUri,
                        )
                    )
                }
                else -> {
                    _uiState.update { it.copy(launchedFromExternalIntent = false, showHome = true) }
                    emit(str(R.string.format_unsupported))
                }
            }
        }
    }

    fun openViewerFile(file: File) {
        val item = FileItem(file)
        when {
            item.isPdf -> openViewer(ViewerContent.Pdf(file))
            item.isImage -> openViewer(ViewerContent.Image(file))
            item.isVideo || item.isAudio -> openViewer(ViewerContent.Video(file))
            else -> emit(str(R.string.format_unsupported))
        }
    }

    fun closeViewer(): Boolean {
        val state = _uiState.value
        val shouldFinish = state.launchedFromExternalIntent
        val returnTarget = state.viewerReturnTarget
        _uiState.update {
            it.copy(
                viewer = null,
                viewerReturnTarget = ViewerReturnTarget.STAY,
                launchedFromExternalIntent = false,
            )
        }
        if (shouldFinish) {
            goHome()
            return true
        }
        when (returnTarget) {
            ViewerReturnTarget.HOME,
            ViewerReturnTarget.CLOUD,
            -> restoreHomeAfterViewer()
            ViewerReturnTarget.STAY -> Unit
        }
        return false
    }

    /** Return to home dashboard without wiping cached home content / search. */
    private fun restoreHomeAfterViewer() {
        searchJob?.cancel()
        libraryJob?.cancel()
        _uiState.update {
            it.copy(
                showHome = true,
                showCloud = false,
                cloudExportFile = null,
                activeCategory = null,
                categoryRoot = null,
                fileFilter = FileFilter.ALL,
                libraryMode = false,
                showFavoritesOnly = false,
                showLargestFiles = false,
                showDuplicates = false,
                duplicateGroups = emptyList(),
                librarySubFilter = LibrarySubFilter.ALL,
                mediaAlbumId = MediaAlbum.ALL,
                mediaAlbums = emptyList(),
                selectionMode = false,
                selectedPaths = emptySet(),
                items = emptyList(),
                canGoUp = false,
                // Keep searchQuery / searchResults so Back returns to the search page.
                searchLoading = false,
                progress = null,
                extractDialog = null,
                viewer = null,
                viewerReturnTarget = ViewerReturnTarget.STAY,
            )
        }
    }

    fun openExtractDialogForItem(item: FileItem) {
        openExtractDialog(item.file)
    }

    fun openExtractDialog(zipFile: File) {
        val file = runCatching { zipFile.canonicalFile }.getOrDefault(zipFile)
        if (!file.exists() || !file.isFile) {
            emit(str(R.string.zip_not_found))
            return
        }
        if (!ArchiveManager.isSupportedArchive(file)) {
            emit(str(R.string.format_unsupported_ext, file.extension))
            return
        }
        // Always land in Download/FileNest/<zipName>/ so results show on Download page.
        val destination = ZipManager.downloadExtractDirectory(file)
        _uiState.update {
            it.copy(
                extractDialog = ExtractZipState(
                    zipFile = file,
                    destinationDir = destination,
                    deleteOriginal = false,
                    isLoading = false,
                ),
                extractResult = null,
                selectionMode = false,
                selectedPaths = emptySet(),
            )
        }
        // Detect password protection off the main thread, then show the password field.
        viewModelScope.launch {
            val encrypted = withContext(Dispatchers.IO) { ArchiveManager.isPasswordProtected(file) }
            if (!encrypted) return@launch
            _uiState.update { state ->
                val dialog = state.extractDialog
                if (dialog?.zipFile == file) {
                    state.copy(extractDialog = dialog.copy(isEncrypted = true))
                } else {
                    state
                }
            }
        }
    }

    fun setExtractPassword(password: String) {
        _uiState.update { state ->
            val dialog = state.extractDialog ?: return@update state
            state.copy(extractDialog = dialog.copy(password = password))
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
        val zip = dialog.zipFile
        val deleteOriginal = dialog.deleteOriginal
        val password = dialog.password.takeIf { it.isNotBlank() }
        // Close the small dialog immediately and return to the Download page.
        closeExtractDialog()
        openDownloadCategoryPage()

        runJob(str(R.string.progress_extract_zip), zip.name, refreshAfter = false) {
            try {
                val preferred = ZipManager.downloadExtractDirectory(zip)
                val destination = ZipManager.resolveWritableExtractDir(
                    context = localizedContext(),
                    zipFile = zip,
                    preferred = preferred,
                )
                val written = ArchiveManager.extractArchive(
                    context = localizedContext(),
                    file = zip,
                    destinationDir = destination,
                    password = password,
                ) { progress, name ->
                    updateProgress(str(R.string.progress_extract_zip), name, progress)
                }
                if (deleteOriginal && zip.exists()) {
                    runCatching { zip.delete() }
                }

                // Bump timestamps so extracted files sort to the top of Download.
                val extractedItems = collectExtractedItems(destination, touchNewest = true)
                scanExtractedFiles(destination)
                invalidateMediaLibraryCache()
                showDownloadListWithExtractedOnTop(extractedItems)

                emit(
                    if (written > 0) {
                        str(R.string.extract_success_top_toast, written)
                    } else {
                        str(R.string.extract_empty_toast)
                    },
                )
            } catch (e: ZipManager.ZipPasswordException) {
                // Missing/wrong password: reopen the dialog so the user can retry.
                emit(e.message ?: str(R.string.zip_wrong_password))
                _uiState.update {
                    it.copy(
                        extractDialog = ExtractZipState(
                            zipFile = zip,
                            destinationDir = ZipManager.downloadExtractDirectory(zip),
                            deleteOriginal = deleteOriginal,
                            isEncrypted = true,
                        ),
                        progress = null,
                    )
                }
            } catch (e: Exception) {
                val err = e.message ?: e.javaClass.simpleName
                emit(str(R.string.extract_fail_toast, err))
            }
        }
    }

    fun dismissExtractResult() {
        _uiState.update { it.copy(extractResult = null) }
    }

    fun openExtractResultFolder() {
        val dest = _uiState.value.extractResult?.destination ?: return
        _uiState.update { it.copy(extractResult = null) }
        openDownloadCategoryPage()
        refresh()
    }

    private fun scanExtractedFiles(root: File) {
        val paths = mutableListOf<String>()
        fun walk(dir: File, depth: Int) {
            if (depth > 8) return
            val children = dir.listFiles() ?: return
            children.forEach { child ->
                if (child.isDirectory) walk(child, depth + 1) else paths += child.absolutePath
            }
        }
        if (root.isDirectory) walk(root, 0) else paths += root.absolutePath
        if (paths.isEmpty()) {
            MediaScannerConnection.scanFile(appContext, arrayOf(root.absolutePath), null, null)
        } else {
            MediaScannerConnection.scanFile(appContext, paths.toTypedArray(), null, null)
        }
    }

    /** Open the main Download category page (same as tapping Download on home). */
    private fun openDownloadCategoryPage() {
        val downloadsRoot = FileCategory.DOWNLOADS.resolveFolder()
        if (!downloadsRoot.exists()) downloadsRoot.mkdirs()
        val cached = mediaLibraryCache?.forCategory(FileCategory.DOWNLOADS).orEmpty()
        _uiState.update {
            it.copy(
                showHome = false,
                showCloud = false,
                libraryMode = true,
                showFavoritesOnly = false,
                showLargestFiles = false,
                showDuplicates = false,
                duplicateGroups = emptyList(),
                activeCategory = FileCategory.DOWNLOADS,
                categoryRoot = downloadsRoot,
                currentDir = downloadsRoot,
                fileFilter = FileFilter.forCategory(FileCategory.DOWNLOADS),
                selectionMode = false,
                selectedPaths = emptySet(),
                searchQuery = "",
                searchResults = emptyList(),
                searchLoading = false,
                canGoUp = true,
                extractDialog = null,
                extractResult = null,
                items = sortLibraryFiles(cached),
                mediaAlbums = emptyList(),
                mediaAlbumId = MediaAlbum.ALL,
            )
        }
    }

    private fun collectExtractedItems(root: File, touchNewest: Boolean): List<FileItem> {
        val now = System.currentTimeMillis()
        val items = mutableListOf<FileItem>()
        fun walk(dir: File, depth: Int) {
            if (depth > 8) return
            val children = dir.listFiles() ?: return
            children.forEach { child ->
                when {
                    child.isDirectory -> walk(child, depth + 1)
                    child.isFile -> {
                        if (touchNewest) {
                            runCatching { child.setLastModified(now) }
                        }
                        items += FileItem(child)
                    }
                }
            }
        }
        if (root.isFile) {
            if (touchNewest) runCatching { root.setLastModified(now) }
            items += FileItem(root)
        } else {
            walk(root, 0)
        }
        return items.sortedByDescending { it.lastModified }
    }

    /**
     * Refresh Download library list and pin just-extracted files at the top.
     */
    private fun showDownloadListWithExtractedOnTop(extracted: List<FileItem>) {
        val downloadsRoot = FileCategory.DOWNLOADS.resolveFolder()
        val library = FileOperations.scanMediaLibrary(
            contentResolver = appContext.contentResolver,
        ).also { mediaLibraryCache = it }
        val downloads = library.forCategory(FileCategory.DOWNLOADS)
        val extractedPaths = extracted.map { it.path }.toHashSet()
        // Prefer freshly built FileItem instances (touched timestamps) for the pin.
        val pinned = extracted
            .filter { it.file.exists() && it.file.isFile }
            .sortedByDescending { it.lastModified }
        val rest = downloads.filter { it.path !in extractedPaths }
        val items = pinned + rest

        _uiState.update {
            it.copy(
                showHome = false,
                showCloud = false,
                libraryMode = true,
                showFavoritesOnly = false,
                showLargestFiles = false,
                showDuplicates = false,
                activeCategory = FileCategory.DOWNLOADS,
                categoryRoot = downloadsRoot,
                currentDir = downloadsRoot,
                fileFilter = FileFilter.forCategory(FileCategory.DOWNLOADS),
                items = items,
                progress = null,
                canGoUp = true,
                extractDialog = null,
                extractResult = null,
                selectionMode = false,
                selectedPaths = emptySet(),
                mediaAlbums = emptyList(),
                mediaAlbumId = MediaAlbum.ALL,
            )
        }
        persistHomeSnapshot(
            library = library,
            categories = FileOperations.getCategorySummaries(library),
            recentFiles = library.images.take(12),
            storage = FileOperations.getStorageInfo(),
        )
    }

    /** Kept for call sites that previously opened the extract folder. */
    @Suppress("UNUSED_PARAMETER")
    private fun openFolderAfterExtract(dir: File) {
        openDownloadCategoryPage()
    }

    fun goUp() {
        val state = _uiState.value
        if (state.showDuplicates) {
            closeDuplicates()
            return
        }
        if (state.showFavoritesOnly || state.showLargestFiles || state.libraryMode) {
            goHome()
            return
        }
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
            emit(str(R.string.select_files_first))
            return
        }
        _uiState.update {
            it.copy(
                clipboard = ClipboardState(mode, files),
                selectionMode = false,
                selectedPaths = emptySet(),
            )
        }
        emit(
            if (mode == ClipboardMode.COPY) str(R.string.clipboard_copied, files.size)
            else str(R.string.clipboard_cut, files.size)
        )
    }

    fun paste() {
        val clipboard = _uiState.value.clipboard
        if (clipboard == null) {
            emit(str(R.string.clipboard_empty))
            return
        }
        runJob(str(R.string.progress_pasting), str(R.string.progress_copying)) {
            val result = FileOperations.paste(
                localizedContext(),
                clipboard,
                _uiState.value.currentDir,
            ) { progress, name ->
                updateProgress(str(R.string.progress_pasting), name, progress)
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
            emit(str(R.string.select_files_first))
            return
        }
        val removedPaths = files.mapTo(LinkedHashSet()) { file ->
            runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)
        }
        // Instant UI: drop rows immediately — never runJob→refresh (that shows
        // "Memuat Download…" / full-library search overlay).
        applyLocalRemovals(removedPaths)

        activeJob?.cancel()
        activeJob = viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    FileOperations.deleteRecursively(localizedContext(), files)
                }
                when (result) {
                    is OperationResult.Success -> emit(result.message)
                    is OperationResult.Error -> emit(result.message)
                }
                // Quiet background resync (no searching overlay).
                lastSoftRefreshAtMs = 0L
                softRefreshMediaInBackground(reason = "after-delete")
            } catch (e: kotlinx.coroutines.CancellationException) {
                emit(str(R.string.cancelled))
                throw e
            } finally {
                _uiState.update { it.copy(progress = null) }
                if (activeJob === this) activeJob = null
            }
        }
    }

    /**
     * Instantly drop deleted paths from the visible list and in-memory library cache
     * so the UI updates without a full device scan.
     */
    private fun applyLocalRemovals(removedPaths: Set<String>) {
        if (removedPaths.isEmpty()) return
        fun gone(path: String): Boolean {
            val canonical = runCatching { File(path).canonicalPath }.getOrDefault(path)
            return path in removedPaths || canonical in removedPaths ||
                removedPaths.any { removed ->
                    path == removed ||
                        canonical == removed ||
                        path.startsWith(removed + File.separator) ||
                        canonical.startsWith(removed + File.separator)
                }
        }
        mediaLibraryCache = mediaLibraryCache?.let { library ->
            MediaLibrary(
                downloads = library.downloads.filterNot { gone(it.path) },
                images = library.images.filterNot { gone(it.path) },
                videos = library.videos.filterNot { gone(it.path) },
                documents = library.documents.filterNot { gone(it.path) },
                archives = library.archives.filterNot { gone(it.path) },
                apps = library.apps.filterNot { gone(it.path) },
                others = library.others.filterNot { gone(it.path) },
            )
        }
        val patchedLibrary = mediaLibraryCache
        _uiState.update { state ->
            val nextItems = state.items.filterNot { gone(it.path) }
            val nextRecent = state.recentFiles.filterNot { gone(it.path) }
            val nextCategories = if (patchedLibrary != null) {
                FileOperations.getCategorySummaries(patchedLibrary)
            } else {
                state.categorySummaries
            }
            state.copy(
                items = nextItems,
                recentFiles = nextRecent,
                categorySummaries = nextCategories,
                selectedPaths = emptySet(),
                selectionMode = false,
                progress = null,
            )
        }
    }

    fun createFolder(name: String) {
        val result = FileOperations.createFolder(localizedContext(), _uiState.value.currentDir, name)
        handleResult(result)
        if (result is OperationResult.Success) refresh()
    }

    fun renameSelected(newName: String) {
        val file = selectedFiles().singleOrNull()
        if (file == null) {
            emit(str(R.string.select_one_rename))
            return
        }
        val result = FileOperations.rename(localizedContext(), file, newName)
        handleResult(result)
        if (result is OperationResult.Success) {
            clearSelection()
            refresh()
        }
    }

    fun createZip(zipName: String, bestCompression: Boolean, password: String? = null) {
        val sources = selectedFiles().ifEmpty {
            emit(str(R.string.select_for_zip))
            return
        }
        val safeName = zipName.trim().let { if (it.endsWith(".zip", true)) it else "$it.zip" }
        val destination = FileOperations.uniqueName(File(_uiState.value.currentDir, safeName))
        val level = if (bestCompression) Deflater.BEST_COMPRESSION else Deflater.DEFAULT_COMPRESSION
        val pass = password?.takeIf { it.isNotBlank() }

        runJob(str(R.string.progress_creating_zip), destination.name) {
            try {
                ZipManager.createZip(localizedContext(), sources, destination, level, password = pass) { progress, name ->
                    updateProgress(str(R.string.progress_creating_zip), name, progress)
                }
                _uiState.update { it.copy(selectionMode = false, selectedPaths = emptySet()) }
                invalidateMediaLibraryCache()
                emit(
                    if (pass != null) str(R.string.zip_created_encrypted, destination.name)
                    else str(R.string.zip_created, destination.name)
                )
                refresh()
            } catch (e: Exception) {
                emit(str(R.string.zip_create_failed, e.message ?: str(R.string.error_generic)))
            }
        }
    }

    fun extractSelected() {
        val zip = selectedFiles().singleOrNull { it.isFile && FileItem(it).isArchive }
            ?: selectedFiles().singleOrNull()?.takeIf { FileItem(it).isArchive }
        if (zip == null) {
            emit(str(R.string.select_one_zip_extract))
            return
        }
        openExtractDialog(zip)
    }

    fun extractZipFile(zip: File) {
        if (!zip.exists()) {
            emit(str(R.string.zip_not_found))
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

    private fun runJob(
        title: String,
        message: String,
        refreshAfter: Boolean = true,
        block: suspend () -> Unit,
    ) {
        activeJob?.cancel()
        activeJob = viewModelScope.launch {
            _uiState.update {
                it.copy(progress = ProgressState(title, message, indeterminate = true))
            }
            try {
                withContext(Dispatchers.IO) { block() }
            } catch (e: kotlinx.coroutines.CancellationException) {
                emit(str(R.string.cancelled))
                throw e
            } finally {
                _uiState.update { it.copy(progress = null) }
                if (activeJob === this) {
                    activeJob = null
                }
                if (refreshAfter) {
                    refresh()
                }
            }
        }
    }

    fun cancelActiveJob() {
        activeJob?.cancel()
        activeJob = null
        _uiState.update { it.copy(progress = null) }
        emit(str(R.string.operation_cancelled))
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


    fun setExtractDestination(dir: File) {
        val current = _uiState.value.extractDialog ?: return
        if (!dir.exists()) dir.mkdirs()
        _uiState.update {
            it.copy(extractDialog = current.copy(destinationDir = dir))
        }
    }

    fun shareSelected(context: Context) {
        val files = selectedFiles()
        if (files.isEmpty()) {
            emit(str(R.string.select_one_share))
            return
        }
        if (!FileActions.shareFiles(context, files)) {
            emit(str(R.string.share_failed))
        }
    }

    fun openWithSelected(context: Context) {
        val file = selectedFiles().singleOrNull()
        if (file == null) {
            emit(str(R.string.select_one_open))
            return
        }
        if (FileItem(file).isApk) {
            installApkFile(file)
            return
        }
        if (!FileActions.openWith(context, file)) {
            emit(str(R.string.open_with_failed))
        }
    }

    fun showFileDetails(item: FileItem) {
        _uiState.update { it.copy(fileDetails = item) }
    }

    fun showSelectedDetails() {
        val item = _uiState.value.items.firstOrNull { it.path in _uiState.value.selectedPaths }
        if (item == null) {
            emit(str(R.string.select_one_details))
            return
        }
        showFileDetails(item)
    }

    fun closeFileDetails() {
        _uiState.update { it.copy(fileDetails = null) }
    }

    fun openParentOfDetails() {
        val item = _uiState.value.fileDetails ?: return
        val parent = item.file.parentFile ?: return
        closeFileDetails()
        _uiState.update {
            it.copy(
                showHome = false,
                libraryMode = false,
                showFavoritesOnly = false,
                showLargestFiles = false,
                activeCategory = null,
                categoryRoot = null,
                currentDir = parent,
                fileFilter = FileFilter.ALL,
            )
        }
        refresh()
    }

    fun toggleFavorite(path: String) {
        val nowFavorite = prefs.toggleFavorite(path)
        _uiState.update { it.copy(favoritePaths = prefs.getFavoritePaths()) }
        emit(if (nowFavorite) str(R.string.favorite_added) else str(R.string.favorite_removed))
    }

    fun toggleFavoriteSelected() {
        val path = _uiState.value.selectedPaths.singleOrNull()
        if (path == null) {
            emit(str(R.string.select_one_favorite))
            return
        }
        toggleFavorite(path)
    }

    fun openFavorites() {
        val favorites = prefs.getFavoritePaths()
        val items = favorites.mapNotNull { path ->
            val file = File(path)
            if (file.exists()) FileItem(file) else null
        }.sortedByDescending { it.lastModified }
        _uiState.update {
            it.copy(
                showHome = false,
                libraryMode = false,
                showFavoritesOnly = true,
                showLargestFiles = false,
                activeCategory = null,
                categoryRoot = null,
                currentDir = root,
                fileFilter = FileFilter.ALL,
                items = items,
                canGoUp = true,
                selectionMode = false,
                selectedPaths = emptySet(),
                progress = null,
            )
        }
    }

    /** Storage cleanup tool: show the biggest files on the device so the user can delete them. */
    fun openLargestFiles() {
        activeJob?.cancel()
        activeJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    progress = ProgressState(str(R.string.largest_files), str(R.string.progress_scanning), indeterminate = true),
                )
            }
            try {
                val items = withContext(Dispatchers.IO) {
                    val library = obtainMediaLibrary(forceRefresh = false)
                    (
                        library.downloads + library.images + library.videos +
                            library.documents + library.archives + library.apps + library.others
                        )
                        .distinctBy { it.path }
                        .filter { !it.isDirectory && it.sizeBytes > 0L }
                        .sortedByDescending { it.sizeBytes }
                        .take(150)
                }
                _uiState.update {
                    it.copy(
                        showHome = false,
                        showCloud = false,
                        libraryMode = false,
                        showFavoritesOnly = false,
                        showDuplicates = false,
                        showLargestFiles = true,
                        activeCategory = null,
                        categoryRoot = null,
                        fileFilter = FileFilter.ALL,
                        items = items,
                        canGoUp = true,
                        selectionMode = false,
                        selectedPaths = emptySet(),
                        mediaAlbums = emptyList(),
                        mediaAlbumId = MediaAlbum.ALL,
                        searchQuery = "",
                        searchResults = emptyList(),
                        searchLoading = false,
                        progress = null,
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                emit(e.message ?: str(R.string.error_generic))
            } finally {
                _uiState.update { it.copy(progress = null) }
                if (activeJob === this) activeJob = null
            }
        }
    }

    fun setThemeMode(mode: ThemeMode) {
        prefs.setThemeMode(mode)
        _uiState.update { it.copy(themeMode = mode) }
    }

    fun setAppLanguage(language: AppLanguage) {
        prefs.setAppLanguage(language)
        prefs.setLanguageChosen(true)
        _uiState.update {
            it.copy(
                appLanguage = language,
                languageChosen = true,
            )
        }
        LocaleHelper.apply(language)
    }

    fun setLibrarySubFilter(filter: LibrarySubFilter) {
        _uiState.update { it.copy(librarySubFilter = filter) }
        val category = _uiState.value.activeCategory
        val cached = mediaLibraryCache
        if (category != null && cached != null && _uiState.value.libraryMode) {
            val base = cached.forCategory(category)
            val filtered = if (category == FileCategory.DOCUMENTS) {
                base.filter { filter.matches(it) }
            } else {
                base
            }
            _uiState.update { it.copy(items = sortLibraryFiles(filtered)) }
        }
    }

    fun setMediaAlbum(albumId: String) {
        val state = _uiState.value
        val category = state.activeCategory
        if (!state.libraryMode || (category != FileCategory.IMAGES && category != FileCategory.VIDEOS)) {
            return
        }
        val source = when (category) {
            FileCategory.IMAGES -> mediaLibraryCache?.images ?: state.items.filter { it.isImage }
            FileCategory.VIDEOS -> mediaLibraryCache?.videos ?: state.items.filter { it.isVideo }
            else -> return
        }
        val albums = MediaAlbum.buildChips(source).ifEmpty { state.mediaAlbums }
        val selected = MediaAlbum.sanitizeSelection(albumId, albums)
        val filtered = MediaAlbum.filter(source, selected)
        _uiState.update {
            it.copy(
                mediaAlbumId = selected,
                mediaAlbums = albums,
                items = sortLibraryFiles(filtered),
                selectedPaths = emptySet(),
                selectionMode = false,
            )
        }
    }

    fun findDuplicates() {
        activeJob?.cancel()
        activeJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    progress = ProgressState(str(R.string.progress_duplicates), str(R.string.progress_scanning), indeterminate = true),
                )
            }
            try {
                val groups = withContext(Dispatchers.IO) {
                    val library = obtainMediaLibrary(forceRefresh = false)
                    val pool = library.images + library.videos + library.documents +
                        library.archives + library.apps
                    DuplicateFinder.findDuplicates(pool) { progress, message ->
                        updateProgress(str(R.string.progress_duplicates), message, progress)
                    }
                }
                _uiState.update {
                    it.copy(
                        showHome = false,
                        showDuplicates = true,
                        duplicateGroups = groups,
                        libraryMode = false,
                        showFavoritesOnly = false,
                showLargestFiles = false,
                        progress = null,
                    )
                }
                emit(
                    if (groups.isEmpty()) str(R.string.duplicates_none)
                    else str(R.string.duplicates_found, groups.size),
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                emit(str(R.string.cancelled))
                throw e
            } catch (e: Exception) {
                emit(str(R.string.duplicates_search_failed, e.message ?: str(R.string.error_generic)))
            } finally {
                _uiState.update { it.copy(progress = null) }
                if (activeJob === this) activeJob = null
            }
        }
    }

    fun closeDuplicates() {
        _uiState.update { it.copy(showDuplicates = false, duplicateGroups = emptyList()) }
        goHome()
    }

    fun deleteDuplicateExtras() {
        val extras = _uiState.value.duplicateGroups.flatMap { group ->
            group.files.drop(1).map { it.file }
        }
        if (extras.isEmpty()) {
            emit(str(R.string.duplicates_delete_none))
            return
        }
        activeJob?.cancel()
        activeJob = viewModelScope.launch {
            _uiState.update {
                it.copy(progress = ProgressState(str(R.string.progress_deleting_duplicates), str(R.string.files_count, extras.size)))
            }
            try {
                val result = withContext(Dispatchers.IO) {
                    FileOperations.deleteRecursively(localizedContext(), extras)
                }
                invalidateMediaLibraryCache()
                handleResult(result)
                val groups = withContext(Dispatchers.IO) {
                    val library = obtainMediaLibrary(forceRefresh = true)
                    val pool = library.images + library.videos + library.documents +
                        library.archives + library.apps
                    DuplicateFinder.findDuplicates(pool)
                }
                _uiState.update {
                    it.copy(duplicateGroups = groups, progress = null, showDuplicates = true)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                emit(str(R.string.cancelled))
                throw e
            } catch (e: Exception) {
                emit(str(R.string.delete_failed, e.message ?: str(R.string.error_generic)))
            } finally {
                _uiState.update { it.copy(progress = null) }
                if (activeJob === this) activeJob = null
            }
        }
    }

    private fun handleResult(result: OperationResult) {
        when (result) {
            is OperationResult.Success -> {
                invalidateMediaLibraryCache()
                emit(result.message)
            }
            is OperationResult.Error -> emit(result.message)
        }
    }

    private fun emit(message: String) {
        _events.tryEmit(message)
    }

    private fun localizedContext() =
        LocaleHelper.wrap(getApplication(), _uiState.value.appLanguage)

    private fun str(@StringRes id: Int, vararg args: Any): String {
        val localized = localizedContext()
        return if (args.isEmpty()) {
            localized.getString(id)
        } else {
            localized.getString(id, *args)
        }
    }

    private data class HomeDashboardData(
        val storageInfo: StorageInfo,
        val categories: List<CategorySummary>,
        val recentFiles: List<FileItem>,
    )
}
