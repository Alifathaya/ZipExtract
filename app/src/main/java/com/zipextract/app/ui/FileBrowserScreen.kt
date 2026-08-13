package com.zipextract.app.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import com.zipextract.app.R
import com.zipextract.app.data.AppLanguage
import com.zipextract.app.data.ClipboardMode
import com.zipextract.app.data.DuplicateGroup
import com.zipextract.app.data.FileFilter
import com.zipextract.app.data.FileCategory
import com.zipextract.app.data.FileItem
import com.zipextract.app.data.MediaAlbum
import com.zipextract.app.data.MediaAlbumChip
import com.zipextract.app.data.LibrarySubFilter
import com.zipextract.app.data.ThemeMode
import com.zipextract.app.data.VideoThumbnailLoader
import com.zipextract.app.data.cloud.SafCloudAccess
import com.zipextract.app.ui.viewer.ExtractZipScreen
import com.zipextract.app.ui.viewer.ImageViewerScreen
import com.zipextract.app.ui.viewer.PdfViewerScreen
import com.zipextract.app.ui.viewer.VideoPlayerScreen
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class DialogType {
    CREATE_FOLDER,
    RENAME,
    CREATE_ZIP,
    DELETE_CONFIRM,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileBrowserScreen(
    state: BrowserUiState,
    onOpen: (FileItem) -> Unit,
    onOpenItem: (FileItem) -> Unit,
    onGoUp: () -> Unit,
    onRefresh: () -> Unit,
    onToggleSelect: (FileItem) -> Unit,
    onToggleSelectionMode: () -> Unit,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
    onCopy: () -> Unit,
    onCut: () -> Unit,
    onPaste: () -> Unit,
    onDelete: () -> Unit,
    onCreateFolder: (String) -> Unit,
    onRename: (String) -> Unit,
    onCreateZip: (String, Boolean, String?) -> Unit,
    onOpenExtract: (com.zipextract.app.data.FileItem) -> Unit,
    onOpenCategory: (com.zipextract.app.data.FileCategory) -> Unit,
    onBrowseAll: () -> Unit,
    onGoHome: () -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onClearSearch: () -> Unit,
    onOpenFileAnywhere: (FileItem) -> Unit,
    onSetFileFilter: (FileFilter) -> Unit,
    onToggleSort: () -> Unit,
    onRequestPermission: () -> Unit,
    onCloseViewer: () -> Unit,
    onCloseExtract: () -> Unit,
    onToggleExtractEntry: (String) -> Unit,
    onSelectAllExtractEntries: () -> Unit,
    onDeselectAllExtractEntries: () -> Unit,
    onDeleteOriginalZipChange: (Boolean) -> Unit,
    onConfirmExtract: () -> Unit,
    onSetExtractDestination: (File) -> Unit,
    onShareSelected: () -> Unit,
    onOpenWithSelected: () -> Unit,
    onToggleFavoriteSelected: () -> Unit,
    onShowSelectedDetails: () -> Unit,
    onCloseFileDetails: () -> Unit,
    onOpenParentOfDetails: () -> Unit,
    onOpenFavorites: () -> Unit,
    onSetThemeMode: (ThemeMode) -> Unit,
    onSetAppLanguage: (AppLanguage) -> Unit,
    onSetLibrarySubFilter: (LibrarySubFilter) -> Unit,
    onSetMediaAlbum: (String) -> Unit,
    onFindDuplicates: () -> Unit,
    onCloseDuplicates: () -> Unit,
    onDeleteDuplicateExtras: () -> Unit,
    onCancelProgress: () -> Unit,
    onToggleFavoritePath: (String) -> Unit,
    onShowFileDetails: (FileItem) -> Unit,
    onOpenCloud: () -> Unit,
    onCloseCloud: () -> Unit,
    onUpdateSafBookmarks: (List<com.zipextract.app.data.cloud.SafBookmark>) -> Unit,
    onOpenImportedCloudFile: (java.io.File) -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<DialogType?>(null) }
    var inputText by remember { mutableStateOf("") }
    var bestCompression by remember { mutableStateOf(true) }
    var cloudImporting by remember { mutableStateOf(false) }
    var showLanguagePicker by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val cloudScope = rememberCoroutineScope()

    // First launch: force language choice once.
    if (!state.languageChosen) {
        LanguagePickerDialog(
            selected = state.appLanguage,
            dismissible = false,
            onConfirm = onSetAppLanguage,
        )
    } else if (showLanguagePicker) {
        LanguagePickerDialog(
            selected = state.appLanguage,
            dismissible = true,
            onDismiss = { showLanguagePicker = false },
            onConfirm = {
                showLanguagePicker = false
                onSetAppLanguage(it)
            },
        )
    }

    val cloudPickerLauncher = rememberLauncherForActivityResult(
        SafCloudAccess.GetCloudContent(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        if (cloudImporting) {
            Toast.makeText(
                context,
                context.getString(R.string.cloud_busy),
                Toast.LENGTH_SHORT,
            ).show()
            return@rememberLauncherForActivityResult
        }
        cloudImporting = true
        cloudScope.launch {
            try {
                val (file, error) = withContext(Dispatchers.IO) {
                    SafCloudAccess.copyUriToCache(
                        context = context,
                        uri = uri,
                        tryPersist = false,
                    )
                }
                if (file == null) {
                    Toast.makeText(
                        context,
                        error ?: context.getString(R.string.cloud_open_failed),
                        Toast.LENGTH_LONG,
                    ).show()
                } else {
                    onOpenImportedCloudFile(file)
                }
            } catch (t: Throwable) {
                Toast.makeText(
                    context,
                    t.message ?: context.getString(R.string.cloud_open_failed),
                    Toast.LENGTH_LONG,
                ).show()
            } finally {
                cloudImporting = false
            }
        }
    }

    val selectedCount = state.selectedPaths.size
    val selectedItems = state.items.filter { it.path in state.selectedPaths }
    val singleSelected = selectedItems.singleOrNull()
    val canExtract = selectedItems.size == 1 && singleSelected?.isArchive == true
    val canRename = selectedItems.size == 1
    val canFavoriteOrDetails = selectedItems.size == 1

    BackHandler(
        enabled = state.extractDialog != null ||
            state.viewer != null ||
            state.fileDetails != null ||
            state.selectionMode ||
            !state.showHome,
    ) {
        when {
            state.extractDialog != null -> onCloseExtract()
            state.viewer != null -> onCloseViewer()
            state.fileDetails != null -> onCloseFileDetails()
            state.selectionMode -> onClearSelection()
            state.showDuplicates -> onCloseDuplicates()
            !state.showHome -> onGoUp()
        }
    }

    if (state.extractDialog != null) {
        ExtractZipScreen(
            state = state.extractDialog,
            onClose = onCloseExtract,
            onToggleEntry = onToggleExtractEntry,
            onSelectAll = onSelectAllExtractEntries,
            onDeselectAll = onDeselectAllExtractEntries,
            onDeleteOriginalChange = onDeleteOriginalZipChange,
            onExtract = onConfirmExtract,
            onSetDestination = onSetExtractDestination,
        )
        return
    }

    if (state.viewer != null) {
        when (val viewer = state.viewer) {
            is ViewerContent.Pdf -> PdfViewerScreen(
                file = viewer.file,
                sourceUri = viewer.sourceUri,
                onClose = onCloseViewer,
            )
            is ViewerContent.Image -> ImageViewerScreen(file = viewer.file, onClose = onCloseViewer)
            is ViewerContent.Video -> VideoPlayerScreen(
                file = viewer.file,
                sourceUri = viewer.sourceUri,
                onClose = onCloseViewer,
            )
            null -> Unit
        }
        return
    }

    if (state.showHome) {
        if (!state.storageGranted) {
            PermissionPane(onRequestPermission)
            return
        }
        HomeDashboardScreen(
            storageInfo = state.storageInfo,
            categories = state.categorySummaries,
            recentFiles = state.recentFiles,
            searchQuery = state.searchQuery,
            searchResults = state.searchResults,
            searchLoading = state.searchLoading,
            isLoading = state.homeLoading,
            onRefresh = onRefresh,
            onSearchQueryChange = onSearchQueryChange,
            onClearSearch = onClearSearch,
            onOpenCategory = onOpenCategory,
            onBrowseAll = onBrowseAll,
            onOpenZips = { onOpenCategory(FileCategory.ARCHIVES) },
            onOpenFavorites = onOpenFavorites,
            onOpenCloud = {
                if (cloudImporting) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.cloud_busy),
                        Toast.LENGTH_SHORT,
                    ).show()
                } else {
                    cloudPickerLauncher.launch("*/*")
                }
            },
            onOpenLanguage = { showLanguagePicker = true },
            onOpenFile = onOpenFileAnywhere,
            onViewAllPhotos = { onOpenCategory(FileCategory.IMAGES) },
        )
        return
    }

    Box(modifier = Modifier.fillMaxSize()) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = when {
                                state.selectionMode -> "$selectedCount dipilih"
                                state.showDuplicates -> "Duplikat"
                                state.showFavoritesOnly -> stringResource(R.string.favorites)
                                state.libraryMode && state.activeCategory != null ->
                                    stringResource(state.activeCategory.titleRes)
                                state.activeCategory != null ->
                                    stringResource(state.activeCategory.titleRes)
                                else -> stringResource(R.string.all_files)
                            },
                            style = MaterialTheme.typography.titleLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = when {
                                state.showDuplicates ->
                                    "${state.duplicateGroups.size} grup ditemukan"
                                state.showFavoritesOnly ->
                                    "${state.items.size} item favorit"
                                state.libraryMode && state.activeCategory != null && state.items.isNotEmpty() ->
                                    "${state.items.size} ${state.activeCategory.libraryNoun} di perangkat"
                                state.libraryMode && state.activeCategory != null ->
                                    "Semua ${state.activeCategory.libraryNoun} di perangkat"
                                state.libraryMode -> "Semua file di perangkat"
                                else -> state.currentDir.absolutePath
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                navigationIcon = {
                    when {
                        state.selectionMode -> {
                            IconButton(onClick = onClearSelection) {
                                Icon(Icons.Default.Close, contentDescription = "Batal seleksi")
                            }
                        }
                        state.canGoUp || state.showDuplicates || state.showFavoritesOnly -> {
                            IconButton(
                                onClick = {
                                    if (state.showDuplicates) onCloseDuplicates() else onGoUp()
                                },
                            ) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Kembali ke beranda")
                            }
                        }
                    }
                },
                actions = {
                    if (state.selectionMode && selectedCount == 1) {
                        IconButton(onClick = onToggleFavoriteSelected) {
                            val path = singleSelected?.path
                            val isFav = path != null && path in state.favoritePaths
                            Icon(
                                imageVector = if (isFav) Icons.Default.Star else Icons.Default.StarBorder,
                                contentDescription = stringResource(R.string.favorites),
                                tint = if (isFav) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    }
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Menu")
                    }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        DropdownMenuItem(
                            text = { Text(if (state.selectionMode) "Nonaktifkan seleksi" else "Mode seleksi") },
                            onClick = {
                                menuExpanded = false
                                onToggleSelectionMode()
                            },
                            leadingIcon = { Icon(Icons.Default.CheckBox, contentDescription = null) },
                        )
                        DropdownMenuItem(
                            text = { Text("Pilih semua") },
                            onClick = {
                                menuExpanded = false
                                onSelectAll()
                            },
                            leadingIcon = { Icon(Icons.Default.SelectAll, contentDescription = null) },
                        )
                        DropdownMenuItem(
                            text = { Text(if (state.sortNewestFirst) "Urut nama" else "Urut terbaru") },
                            onClick = {
                                menuExpanded = false
                                onToggleSort()
                            },
                            leadingIcon = { Icon(Icons.Default.Sort, contentDescription = null) },
                        )
                        DropdownMenuItem(
                            text = { Text("Folder baru") },
                            onClick = {
                                menuExpanded = false
                                inputText = ""
                                dialog = DialogType.CREATE_FOLDER
                            },
                            leadingIcon = { Icon(Icons.Default.CreateNewFolder, contentDescription = null) },
                        )
                        DropdownMenuItem(
                            text = { Text("Favorit") },
                            onClick = {
                                menuExpanded = false
                                onOpenFavorites()
                            },
                            leadingIcon = { Icon(Icons.Default.Star, contentDescription = null) },
                        )
                        DropdownMenuItem(
                            text = { Text("Cari duplikat") },
                            onClick = {
                                menuExpanded = false
                                onFindDuplicates()
                            },
                            leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null) },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.language_menu)) },
                            onClick = {
                                menuExpanded = false
                                showLanguagePicker = true
                            },
                            leadingIcon = { Icon(Icons.Default.Language, contentDescription = null) },
                        )
                        ThemeMode.entries.forEach { mode ->
                            val modeLabel = stringResource(mode.labelRes)
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        if (state.themeMode == mode) {
                                            stringResource(R.string.theme_with_label, "$modeLabel ✓")
                                        } else {
                                            stringResource(R.string.theme_with_label, modeLabel)
                                        },
                                    )
                                },
                                onClick = {
                                    menuExpanded = false
                                    onSetThemeMode(mode)
                                },
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                ),
            )
        },
        floatingActionButton = {
            if (state.storageGranted && state.clipboard != null) {
                FloatingActionButton(onClick = onPaste) {
                    Icon(Icons.Default.ContentPaste, contentDescription = "Paste")
                }
            }
        },
        bottomBar = {
            AnimatedVisibility(
                visible = state.selectionMode && selectedCount > 0,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
            ) {
                ActionBar(
                    canExtract = canExtract,
                    canRename = canRename,
                    canFavoriteOrDetails = canFavoriteOrDetails,
                    hasClipboard = state.clipboard != null,
                    clipboardMode = state.clipboard?.mode,
                    onCopy = onCopy,
                    onCut = onCut,
                    onPaste = onPaste,
                    onDelete = { dialog = DialogType.DELETE_CONFIRM },
                    onRename = {
                        inputText = singleSelected?.name.orEmpty()
                        dialog = DialogType.RENAME
                    },
                    onZip = {
                        inputText = if (selectedCount == 1) {
                            singleSelected?.nameWithoutZip().orEmpty()
                        } else {
                            "archive"
                        }
                        bestCompression = true
                        dialog = DialogType.CREATE_ZIP
                    },
                    onExtract = {
                        singleSelected?.let { onOpenExtract(it) }
                    },
                    onShare = onShareSelected,
                    onOpenWith = onOpenWithSelected,
                    onFavorite = onToggleFavoriteSelected,
                    onDetails = onShowSelectedDetails,
                )
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
                            MaterialTheme.colorScheme.background,
                            MaterialTheme.colorScheme.background,
                        )
                    )
                )
                .padding(padding),
        ) {
            when {
                !state.storageGranted -> PermissionPane(onRequestPermission)
                state.showDuplicates -> {
                    DuplicateGroupsPane(
                        groups = state.duplicateGroups,
                        onDeleteExtras = onDeleteDuplicateExtras,
                        onOpenItem = onOpenItem,
                        onShowDetails = onShowFileDetails,
                    )
                }
                state.items.isEmpty() && state.progress != null -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = state.progress.message.ifBlank { "Memuat…" },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                state.items.isEmpty() -> EmptyPane(
                    message = when {
                        state.showFavoritesOnly -> "Belum ada favorit"
                        state.libraryMode && state.activeCategory != null ->
                            "Tidak ada ${state.activeCategory.libraryNoun} ditemukan di perangkat"
                        state.libraryMode -> "Tidak ada file ditemukan di perangkat"
                        state.fileFilter != FileFilter.ALL ->
                            "Tidak ada file ${state.fileFilter.label.lowercase()} di folder ini"
                        else -> "Folder kosong"
                    },
                )
                state.showFavoritesOnly -> {
                    FavoritesGalleryGrid(
                        items = state.items,
                        selectedPaths = state.selectedPaths,
                        favoritePaths = state.favoritePaths,
                        selectionMode = state.selectionMode,
                        onOpenItem = onOpenItem,
                        onOpenExtract = onOpenExtract,
                        onToggleSelect = onToggleSelect,
                        onToggleFavorite = onToggleFavoritePath,
                    )
                }
                state.libraryMode && (
                    state.activeCategory == FileCategory.IMAGES ||
                        state.fileFilter == FileFilter.IMAGES
                    ) -> {
                    Column(modifier = Modifier.fillMaxSize()) {
                        MediaAlbumChips(
                            albums = state.mediaAlbums,
                            selectedId = state.mediaAlbumId,
                            onSelect = onSetMediaAlbum,
                        )
                        ImageGalleryGrid(
                            items = state.items.filter { it.isImage },
                            selectedPaths = state.selectedPaths,
                            favoritePaths = state.favoritePaths,
                            selectionMode = state.selectionMode,
                            onOpenItem = onOpenItem,
                            onToggleSelect = onToggleSelect,
                            onToggleFavorite = onToggleFavoritePath,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                state.libraryMode && (
                    state.activeCategory == FileCategory.VIDEOS ||
                        state.fileFilter == FileFilter.VIDEOS
                    ) -> {
                    Column(modifier = Modifier.fillMaxSize()) {
                        MediaAlbumChips(
                            albums = state.mediaAlbums,
                            selectedId = state.mediaAlbumId,
                            onSelect = onSetMediaAlbum,
                        )
                        VideoGalleryGrid(
                            items = state.items.filter { it.isVideo },
                            selectedPaths = state.selectedPaths,
                            favoritePaths = state.favoritePaths,
                            selectionMode = state.selectionMode,
                            onOpenItem = onOpenItem,
                            onToggleSelect = onToggleSelect,
                            onToggleFavorite = onToggleFavoritePath,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                state.libraryMode -> {
                    Column(modifier = Modifier.fillMaxSize()) {
                        if (state.activeCategory == FileCategory.DOCUMENTS) {
                            LibrarySubFilterChips(
                                selected = state.librarySubFilter,
                                onSelect = onSetLibrarySubFilter,
                            )
                        }
                        CategoryLibraryList(
                            items = state.items,
                            selectedPaths = state.selectedPaths,
                            favoritePaths = state.favoritePaths,
                            selectionMode = state.selectionMode,
                            onOpenItem = onOpenItem,
                            onOpenExtract = onOpenExtract,
                            onToggleSelect = onToggleSelect,
                            onToggleFavorite = onToggleFavoritePath,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                else -> {
                    Column(modifier = Modifier.fillMaxSize()) {
                        if (!state.selectionMode && !state.showFavoritesOnly) {
                            FileFilterChips(
                                selected = state.fileFilter,
                                onSelect = onSetFileFilter,
                            )
                        }
                        LazyColumn(
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            items(state.items, key = { it.path }) { item ->
                                FileRow(
                                    item = item,
                                    selected = item.path in state.selectedPaths,
                                    selectionMode = state.selectionMode,
                                    isFavorite = item.path in state.favoritePaths,
                                    showFolder = state.showFavoritesOnly,
                                    onClick = {
                                        when {
                                            item.isArchive -> onOpenExtract(item)
                                            state.selectionMode -> onToggleSelect(item)
                                            else -> onOpenItem(item)
                                        }
                                    },
                                    onLongClick = { onToggleSelect(item) },
                                    onToggleFavorite = { onToggleFavoritePath(item.path) },
                                )
                            }
                            item { Spacer(modifier = Modifier.height(88.dp)) }
                        }
                    }
                }
            }

            state.progress?.let { progress ->
                ProgressOverlay(progress = progress, onCancel = onCancelProgress)
            }
        }
    }
    }

    when (dialog) {
        DialogType.CREATE_FOLDER -> TextInputDialog(
            title = "Buat folder",
            label = "Nama folder",
            value = inputText,
            confirmLabel = "Buat",
            onValueChange = { inputText = it },
            onDismiss = { dialog = null },
            onConfirm = {
                onCreateFolder(inputText)
                dialog = null
            },
        )
        DialogType.RENAME -> TextInputDialog(
            title = "Ganti nama",
            label = "Nama baru",
            value = inputText,
            confirmLabel = "Simpan",
            onValueChange = { inputText = it },
            onDismiss = { dialog = null },
            onConfirm = {
                onRename(inputText)
                dialog = null
            },
        )
        DialogType.CREATE_ZIP -> ZipDialog(
            value = inputText,
            bestCompression = bestCompression,
            onValueChange = { inputText = it },
            onBestCompressionChange = { bestCompression = it },
            onDismiss = { dialog = null },
            onConfirm = { password ->
                onCreateZip(inputText, bestCompression, password)
                dialog = null
            },
        )
        DialogType.DELETE_CONFIRM -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("Hapus item?") },
            text = { Text("$selectedCount item akan dihapus permanen.") },
            confirmButton = {
                TextButton(onClick = {
                    dialog = null
                    onDelete()
                }) { Text("Hapus") }
            },
            dismissButton = {
                TextButton(onClick = { dialog = null }) { Text("Batal") }
            },
        )
        null -> Unit
    }

    state.fileDetails?.let { details ->
        AlertDialog(
            onDismissRequest = onCloseFileDetails,
            title = { Text(details.name) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Ukuran: ${details.formattedSize}")
                    Text("Tanggal: ${details.formattedDate}")
                    Text(
                        text = "Path: ${details.path}",
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = onCloseFileDetails) { Text("Tutup") }
                    TextButton(onClick = onOpenParentOfDetails) { Text("Buka folder") }
                    TextButton(onClick = { onToggleFavoritePath(details.path) }) { Text("Favorit") }
                }
            },
        )
    }
}

@Composable
private fun ActionBar(
    canExtract: Boolean,
    canRename: Boolean,
    canFavoriteOrDetails: Boolean,
    hasClipboard: Boolean,
    clipboardMode: ClipboardMode?,
    onCopy: () -> Unit,
    onCut: () -> Unit,
    onPaste: () -> Unit,
    onDelete: () -> Unit,
    onRename: () -> Unit,
    onZip: () -> Unit,
    onExtract: () -> Unit,
    onShare: () -> Unit,
    onOpenWith: () -> Unit,
    onFavorite: () -> Unit,
    onDetails: () -> Unit,
) {
    Surface(
        tonalElevation = 3.dp,
        shadowElevation = 8.dp,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Favorit & Detail first so they are not cut off / off-screen on the right.
            ActionIcon(
                Icons.Default.Star,
                stringResource(R.string.favorites),
                onFavorite,
                enabled = canFavoriteOrDetails,
            )
            ActionIcon(Icons.Default.Info, "Detail", onDetails, enabled = canFavoriteOrDetails)
            ActionIcon(Icons.Default.Share, "Bagikan", onShare)
            ActionIcon(Icons.Default.OpenInNew, "Buka", onOpenWith)
            ActionIcon(Icons.Default.ContentCopy, "Salin", onCopy)
            ActionIcon(Icons.Default.ContentCut, "Potong", onCut)
            ActionIcon(
                icon = Icons.Default.ContentPaste,
                label = when (clipboardMode) {
                    ClipboardMode.COPY -> "Tempel"
                    ClipboardMode.CUT -> "Pindah"
                    null -> "Tempel"
                },
                enabled = hasClipboard,
                onClick = onPaste,
            )
            ActionIcon(Icons.Default.FolderZip, "Zip", onZip)
            ActionIcon(Icons.Default.Unarchive, "Extract", onExtract, enabled = canExtract)
            ActionIcon(Icons.Default.DriveFileRenameOutline, "Rename", onRename, enabled = canRename)
            ActionIcon(Icons.Default.Delete, "Hapus", onDelete)
        }
    }
}

@Composable
private fun ActionIcon(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(onClick = onClick, enabled = enabled) {
            Icon(icon, contentDescription = label)
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = if (enabled) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DuplicateGroupsPane(
    groups: List<DuplicateGroup>,
    onDeleteExtras: () -> Unit,
    onOpenItem: (FileItem) -> Unit,
    onShowDetails: (FileItem) -> Unit,
) {
    if (groups.isEmpty()) {
        EmptyPane(message = "Tidak ada file duplikat")
        return
    }

    Column(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.weight(1f),
        ) {
            items(groups, key = { it.key }) { group ->
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    tonalElevation = 1.dp,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "${group.files.size} salinan · hemat ${FileItem.formatBytes(group.wastedBytes)}",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        group.files.forEach { file ->
                            Text(
                                text = file.name,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .combinedClickable(
                                        onClick = { onOpenItem(file) },
                                        onLongClick = { onShowDetails(file) },
                                    )
                                    .padding(vertical = 4.dp),
                            )
                            Text(
                                text = file.path,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
            item { Spacer(modifier = Modifier.height(72.dp)) }
        }
        Surface(tonalElevation = 3.dp) {
            TextButton(
                onClick = onDeleteExtras,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
            ) {
                Text("Hapus salinan")
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FavoritesGalleryGrid(
    items: List<FileItem>,
    selectedPaths: Set<String>,
    favoritePaths: Set<String>,
    selectionMode: Boolean,
    onOpenItem: (FileItem) -> Unit,
    onOpenExtract: (FileItem) -> Unit,
    onToggleSelect: (FileItem) -> Unit,
    onToggleFavorite: (String) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 96.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        gridItems(items, key = { it.path }) { item ->
            when {
                item.isImage -> {
                    ImageThumbnailCell(
                        item = item,
                        selected = item.path in selectedPaths,
                        isFavorite = item.path in favoritePaths,
                        selectionMode = selectionMode,
                        onClick = {
                            if (selectionMode) onToggleSelect(item) else onOpenItem(item)
                        },
                        onLongClick = { onToggleSelect(item) },
                        onToggleFavorite = { onToggleFavorite(item.path) },
                    )
                }
                item.isVideo -> {
                    VideoThumbnailCell(
                        item = item,
                        selected = item.path in selectedPaths,
                        isFavorite = item.path in favoritePaths,
                        selectionMode = selectionMode,
                        onClick = {
                            if (selectionMode) onToggleSelect(item) else onOpenItem(item)
                        },
                        onLongClick = { onToggleSelect(item) },
                        onToggleFavorite = { onToggleFavorite(item.path) },
                    )
                }
                else -> {
                    FavoriteFileCell(
                        item = item,
                        selected = item.path in selectedPaths,
                        isFavorite = item.path in favoritePaths,
                        selectionMode = selectionMode,
                        onClick = {
                            when {
                                item.isArchive -> onOpenExtract(item)
                                selectionMode -> onToggleSelect(item)
                                else -> onOpenItem(item)
                            }
                        },
                        onLongClick = { onToggleSelect(item) },
                        onToggleFavorite = { onToggleFavorite(item.path) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FavoriteFileCell(
    item: FileItem,
    selected: Boolean,
    isFavorite: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    val icon = when {
        item.isDirectory -> Icons.Default.Folder
        item.isArchive -> Icons.Default.Archive
        item.isApp -> Icons.Default.Android
        item.isPdf -> Icons.Default.PictureAsPdf
        item.isAudio -> Icons.Default.MusicNote
        else -> Icons.AutoMirrored.Filled.InsertDriveFile
    }
    val tint = when {
        item.isDirectory -> MaterialTheme.colorScheme.primary
        item.isArchive -> MaterialTheme.colorScheme.secondary
        item.isApp -> MaterialTheme.colorScheme.tertiary
        item.isPdf -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(10.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(36.dp),
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = item.name,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        IconButton(
            onClick = onToggleFavorite,
            modifier = Modifier
                .align(Alignment.TopStart)
                .size(34.dp),
        ) {
            Icon(
                imageVector = if (isFavorite) Icons.Default.Star else Icons.Default.StarBorder,
                contentDescription = stringResource(R.string.favorites),
                tint = if (isFavorite) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }

        if (selected || selectionMode) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.88f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (selected) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VideoGalleryGrid(
    items: List<FileItem>,
    selectedPaths: Set<String>,
    favoritePaths: Set<String>,
    selectionMode: Boolean,
    onOpenItem: (FileItem) -> Unit,
    onToggleSelect: (FileItem) -> Unit,
    onToggleFavorite: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (items.isEmpty()) {
        EmptyPane(message = "Tidak ada video di album ini")
        return
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 96.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier.fillMaxSize(),
    ) {
        gridItems(items, key = { it.path }) { item ->
            VideoThumbnailCell(
                item = item,
                selected = item.path in selectedPaths,
                isFavorite = item.path in favoritePaths,
                selectionMode = selectionMode,
                onClick = {
                    if (selectionMode) onToggleSelect(item) else onOpenItem(item)
                },
                onLongClick = { onToggleSelect(item) },
                onToggleFavorite = { onToggleFavorite(item.path) },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VideoThumbnailCell(
    item: FileItem,
    selected: Boolean,
    isFavorite: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    var bitmap by remember(item.path, item.sizeBytes, item.lastModified) {
        mutableStateOf(VideoThumbnailLoader.peek(item.file))
    }
    var failed by remember(item.path, item.sizeBytes, item.lastModified) {
        mutableStateOf(false)
    }

    LaunchedEffect(item.path, item.sizeBytes, item.lastModified) {
        if (bitmap != null) return@LaunchedEffect
        val loaded = withContext(Dispatchers.IO) {
            runCatching { VideoThumbnailLoader.load(item.file) }.getOrNull()
        }
        if (loaded == null) {
            failed = true
        } else {
            bitmap = loaded
            failed = false
        }
    }

    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        when {
            bitmap != null -> {
                Image(
                    bitmap = bitmap!!.asImageBitmap(),
                    contentDescription = item.name,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
            failed -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.Movie,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                        modifier = Modifier.size(32.dp),
                    )
                }
            }
            else -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp))
                }
            }
        }

        // Dark scrim + play badge so video thumbs are distinct from photos.
        if (bitmap != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.18f)),
            )
        }
        Icon(
            imageVector = Icons.Default.PlayCircle,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.92f),
            modifier = Modifier
                .align(Alignment.Center)
                .size(36.dp),
        )

        Text(
            text = item.formattedSize,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(6.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color.Black.copy(alpha = 0.55f))
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )

        IconButton(
            onClick = onToggleFavorite,
            modifier = Modifier
                .align(Alignment.TopStart)
                .size(34.dp),
        ) {
            Icon(
                imageVector = if (isFavorite) Icons.Default.Star else Icons.Default.StarBorder,
                contentDescription = stringResource(R.string.favorites),
                tint = if (isFavorite) {
                    MaterialTheme.colorScheme.primary
                } else {
                    Color.White
                },
            )
        }

        if (selected || selectionMode) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.88f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (selected) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ImageGalleryGrid(
    items: List<FileItem>,
    selectedPaths: Set<String>,
    favoritePaths: Set<String>,
    selectionMode: Boolean,
    onOpenItem: (FileItem) -> Unit,
    onToggleSelect: (FileItem) -> Unit,
    onToggleFavorite: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (items.isEmpty()) {
        EmptyPane(message = "Tidak ada foto di album ini")
        return
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 96.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier.fillMaxSize(),
    ) {
        gridItems(items, key = { it.path }) { item ->
            ImageThumbnailCell(
                item = item,
                selected = item.path in selectedPaths,
                isFavorite = item.path in favoritePaths,
                selectionMode = selectionMode,
                onClick = {
                    if (selectionMode) onToggleSelect(item) else onOpenItem(item)
                },
                onLongClick = { onToggleSelect(item) },
                onToggleFavorite = { onToggleFavorite(item.path) },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ImageThumbnailCell(
    item: FileItem,
    selected: Boolean,
    isFavorite: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        SubcomposeAsyncImage(
            model = item.file,
            contentDescription = item.name,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            loading = {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp))
                }
            },
            error = {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.Image,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                        modifier = Modifier.size(28.dp),
                    )
                }
            },
            success = {
                SubcomposeAsyncImageContent()
            },
        )

        IconButton(
            onClick = onToggleFavorite,
            modifier = Modifier
                .align(Alignment.TopStart)
                .size(34.dp),
        ) {
            Icon(
                imageVector = if (isFavorite) Icons.Default.Star else Icons.Default.StarBorder,
                contentDescription = stringResource(R.string.favorites),
                tint = if (isFavorite) {
                    MaterialTheme.colorScheme.primary
                } else {
                    Color.White
                },
            )
        }

        if (selected || selectionMode) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.88f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (selected) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun CategoryLibraryList(
    items: List<FileItem>,
    selectedPaths: Set<String>,
    favoritePaths: Set<String>,
    selectionMode: Boolean,
    onOpenItem: (FileItem) -> Unit,
    onOpenExtract: (FileItem) -> Unit,
    onToggleSelect: (FileItem) -> Unit,
    onToggleFavorite: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier.fillMaxSize(),
    ) {
        items(items, key = { it.path }) { item ->
            FileRow(
                item = item,
                selected = item.path in selectedPaths,
                selectionMode = selectionMode,
                isFavorite = item.path in favoritePaths,
                showFolder = true,
                onClick = {
                    when {
                        item.isArchive -> onOpenExtract(item)
                        selectionMode -> onToggleSelect(item)
                        else -> onOpenItem(item)
                    }
                },
                onLongClick = { onToggleSelect(item) },
                onToggleFavorite = { onToggleFavorite(item.path) },
            )
        }
        item { Spacer(modifier = Modifier.height(88.dp)) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileRow(
    item: FileItem,
    selected: Boolean,
    selectionMode: Boolean,
    isFavorite: Boolean,
    showFolder: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    val container = if (selected) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
    } else {
        MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(container)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selectionMode) {
            Icon(
                imageVector = if (selected) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.width(8.dp))
        }

        Icon(
            imageVector = when {
                item.isDirectory -> Icons.Default.Folder
                item.isArchive -> Icons.Default.Archive
                item.isApp -> Icons.Default.Android
                item.isPdf -> Icons.Default.PictureAsPdf
                item.isImage -> Icons.Default.Image
                item.isVideo -> Icons.Default.Movie
                item.isAudio -> Icons.Default.MusicNote
                else -> Icons.AutoMirrored.Filled.InsertDriveFile
            },
            contentDescription = null,
            tint = when {
                item.isDirectory -> MaterialTheme.colorScheme.primary
                item.isArchive -> MaterialTheme.colorScheme.secondary
                item.isApp -> MaterialTheme.colorScheme.tertiary
                item.isPdf -> MaterialTheme.colorScheme.error
                item.isImage -> MaterialTheme.colorScheme.tertiary
                item.isVideo -> MaterialTheme.colorScheme.secondary
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.size(28.dp),
        )

        Spacer(modifier = Modifier.width(10.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${item.formattedSize} · ${item.formattedDate}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (showFolder) {
                Text(
                    text = item.parentFolderName,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        if (!item.isDirectory) {
            IconButton(onClick = onToggleFavorite) {
                Icon(
                    imageVector = if (isFavorite) Icons.Default.Star else Icons.Default.StarBorder,
                    contentDescription = stringResource(R.string.favorites),
                    tint = if (isFavorite) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

@Composable
private fun PermissionPane(onRequestPermission: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.permission_body),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(20.dp))
        TextButton(onClick = onRequestPermission) {
            Text(stringResource(R.string.permission_action))
        }
    }
}

@Composable
private fun FileFilterChips(
    selected: FileFilter,
    onSelect: (FileFilter) -> Unit,
) {
    val scrollState = rememberScrollState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(scrollState)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FileFilter.entries.forEach { filter ->
            FilterChip(
                selected = selected == filter,
                onClick = { onSelect(filter) },
                label = { Text(filter.label) },
            )
        }
    }
}

@Composable
private fun MediaAlbumChips(
    albums: List<MediaAlbumChip>,
    selectedId: String,
    onSelect: (String) -> Unit,
) {
    if (albums.isEmpty()) return
    val scrollState = rememberScrollState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(scrollState)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        albums.forEach { album ->
            val label = when (album.id) {
                MediaAlbum.ALL -> stringResource(R.string.album_all)
                MediaAlbum.CAMERA -> stringResource(R.string.album_camera)
                MediaAlbum.SCREENSHOTS -> stringResource(R.string.album_screenshots)
                MediaAlbum.WHATSAPP -> stringResource(R.string.album_whatsapp)
                else -> album.label
            }
            FilterChip(
                selected = selectedId == album.id,
                onClick = { onSelect(album.id) },
                label = {
                    Text(
                        text = if (album.count > 0) "$label (${album.count})" else label,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
            )
        }
    }
}

@Composable
private fun LibrarySubFilterChips(
    selected: LibrarySubFilter,
    onSelect: (LibrarySubFilter) -> Unit,
) {
    val scrollState = rememberScrollState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(scrollState)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        LibrarySubFilter.entries.forEach { filter ->
            FilterChip(
                selected = selected == filter,
                onClick = { onSelect(filter) },
                label = { Text(filter.label) },
            )
        }
    }
}

@Composable
private fun EmptyPane(message: String = "Folder kosong") {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = message,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ProgressOverlay(
    progress: com.zipextract.app.data.ProgressState,
    onCancel: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.35f)),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            tonalElevation = 4.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(28.dp),
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(progress.title, style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = progress.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(16.dp))
                if (progress.indeterminate) {
                    CircularProgressIndicator()
                } else {
                    LinearProgressIndicator(
                        progress = { progress.progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("${(progress.progress * 100).toInt()}%")
                }
                Spacer(modifier = Modifier.height(12.dp))
                TextButton(onClick = onCancel) {
                    Text("Batalkan")
                }
            }
        }
    }
}

@Composable
private fun TextInputDialog(
    title: String,
    label: String,
    value: String,
    confirmLabel: String,
    onValueChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                label = { Text(label) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = value.isNotBlank()) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Batal") }
        },
    )
}

@Composable
private fun ZipDialog(
    value: String,
    bestCompression: Boolean,
    onValueChange: (String) -> Unit,
    onBestCompressionChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: (String?) -> Unit,
) {
    var password by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Buat ZIP") },
        text = {
            Column {
                OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    label = { Text("Nama file ZIP") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password (opsional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = bestCompression,
                        onCheckedChange = onBestCompressionChange,
                    )
                    Text("Kompresi maksimal")
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(password.ifBlank { null }) },
                enabled = value.isNotBlank(),
            ) {
                Text("Buat ZIP")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Batal") }
        },
    )
}

private fun FileItem.nameWithoutZip(): String {
    return if (name.endsWith(".zip", ignoreCase = true)) {
        name.dropLast(4)
    } else {
        name
    }
}
