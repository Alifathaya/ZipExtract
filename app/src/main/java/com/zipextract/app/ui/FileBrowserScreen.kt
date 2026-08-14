package com.zipextract.app.ui

import android.widget.Toast
import androidx.annotation.StringRes
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.foundation.lazy.grid.GridItemSpan
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
import androidx.compose.material.icons.filled.DataUsage
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
import androidx.compose.ui.text.style.TextAlign
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
import com.zipextract.app.ui.viewer.ExtractZipDialog
import com.zipextract.app.ui.viewer.ImageViewerScreen
import com.zipextract.app.ui.viewer.PdfViewerScreen
import com.zipextract.app.ui.viewer.VideoPlayerScreen
import java.io.File
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class DialogType {
    CREATE_FOLDER,
    RENAME,
    CREATE_ZIP,
    DELETE_CONFIRM,
}

private enum class TimeBucket(@StringRes val labelRes: Int) {
    TODAY(R.string.time_today),
    THIS_WEEK(R.string.time_this_week),
    LAST_WEEK(R.string.time_last_week),
    LAST_MONTH(R.string.time_last_month),
    OLDER(R.string.time_older),
}

private data class TimeSection(
    val bucket: TimeBucket,
    val items: List<FileItem>,
)

/**
 * Stable local-calendar grouping used by every category. "Last month" is the
 * remaining recent period back through the start of the previous calendar month,
 * so every file belongs to exactly one section with no date gaps.
 */
private fun groupByTime(items: List<FileItem>): List<TimeSection> {
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    val thisWeekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    val lastWeekStart = thisWeekStart.minusWeeks(1)
    val recentMonthStart = today.withDayOfMonth(1).minusMonths(1)
    val grouped = TimeBucket.entries.associateWith { mutableListOf<FileItem>() }

    items.sortedByDescending { it.lastModified }.forEach { item ->
        val date = item.lastModified.takeIf { it > 0L }?.let {
            Instant.ofEpochMilli(it).atZone(zone).toLocalDate()
        }
        val bucket = when {
            date == null -> TimeBucket.OLDER
            date == today -> TimeBucket.TODAY
            !date.isBefore(thisWeekStart) -> TimeBucket.THIS_WEEK
            !date.isBefore(lastWeekStart) -> TimeBucket.LAST_WEEK
            !date.isBefore(recentMonthStart) -> TimeBucket.LAST_MONTH
            else -> TimeBucket.OLDER
        }
        grouped.getValue(bucket) += item
    }
    return TimeBucket.entries.mapNotNull { bucket ->
        grouped.getValue(bucket).takeIf { it.isNotEmpty() }?.let {
            TimeSection(bucket, it)
        }
    }
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
    onDeleteOriginalZipChange: (Boolean) -> Unit,
    onExtractPasswordChange: (String) -> Unit,
    onExtractDestinationChange: (ExtractDestination) -> Unit,
    onConfirmExtract: () -> Unit,
    onDismissExtractResult: () -> Unit,
    onOpenExtractResultFolder: () -> Unit,
    onShareSelected: () -> Unit,
    onOpenWithSelected: () -> Unit,
    onToggleFavoriteSelected: () -> Unit,
    onShowSelectedDetails: () -> Unit,
    onCloseFileDetails: () -> Unit,
    onOpenParentOfDetails: () -> Unit,
    onOpenFavorites: () -> Unit,
    onOpenLargestFiles: () -> Unit,
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

    // Compact dialog overlay — keep browser visible underneath.
    if (state.extractDialog != null) {
        ExtractZipDialog(
            state = state.extractDialog,
            onClose = onCloseExtract,
            onDeleteOriginalChange = onDeleteOriginalZipChange,
            onPasswordChange = onExtractPasswordChange,
            onDestinationChange = onExtractDestinationChange,
            onExtract = onConfirmExtract,
        )
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
            onOpenLargestFiles = onOpenLargestFiles,
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
                                state.selectionMode -> stringResource(R.string.items_selected, selectedCount)
                                state.showDuplicates -> stringResource(R.string.duplicates_title_short)
                                state.showFavoritesOnly -> stringResource(R.string.favorites)
                                state.showLargestFiles -> stringResource(R.string.largest_files)
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
                                    stringResource(R.string.duplicates_groups_found, state.duplicateGroups.size)
                                state.showFavoritesOnly ->
                                    stringResource(R.string.favorites_count, state.items.size)
                                state.showLargestFiles -> stringResource(R.string.largest_files_hint)
                                state.libraryMode && state.activeCategory != null && state.items.isNotEmpty() ->
                                    stringResource(
                                        R.string.library_items_in_device,
                                        state.items.size,
                                        stringResource(state.activeCategory.nounRes),
                                    )
                                state.libraryMode && state.activeCategory != null ->
                                    stringResource(
                                        R.string.library_all_in_device,
                                        stringResource(state.activeCategory.nounRes),
                                    )
                                state.libraryMode -> stringResource(R.string.library_all_files_in_device)
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
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = stringResource(R.string.cd_cancel_selection),
                                )
                            }
                        }
                        state.canGoUp || state.showDuplicates || state.showFavoritesOnly || state.showLargestFiles -> {
                            IconButton(
                                onClick = {
                                    if (state.showDuplicates) onCloseDuplicates() else onGoUp()
                                },
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = stringResource(R.string.cd_back_home),
                                )
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
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.refresh))
                    }
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.menu))
                    }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    if (state.selectionMode) {
                                        stringResource(R.string.exit_selection)
                                    } else {
                                        stringResource(R.string.selection_mode)
                                    },
                                )
                            },
                            onClick = {
                                menuExpanded = false
                                onToggleSelectionMode()
                            },
                            leadingIcon = { Icon(Icons.Default.CheckBox, contentDescription = null) },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.select_all)) },
                            onClick = {
                                menuExpanded = false
                                onSelectAll()
                            },
                            leadingIcon = { Icon(Icons.Default.SelectAll, contentDescription = null) },
                        )
                        DropdownMenuItem(
                            text = {
                                Text(
                                    if (state.sortNewestFirst) {
                                        stringResource(R.string.sort_name)
                                    } else {
                                        stringResource(R.string.sort_newest)
                                    },
                                )
                            },
                            onClick = {
                                menuExpanded = false
                                onToggleSort()
                            },
                            leadingIcon = { Icon(Icons.Default.Sort, contentDescription = null) },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.create_folder)) },
                            onClick = {
                                menuExpanded = false
                                inputText = ""
                                dialog = DialogType.CREATE_FOLDER
                            },
                            leadingIcon = { Icon(Icons.Default.CreateNewFolder, contentDescription = null) },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.favorites)) },
                            onClick = {
                                menuExpanded = false
                                onOpenFavorites()
                            },
                            leadingIcon = { Icon(Icons.Default.Star, contentDescription = null) },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.find_duplicates)) },
                            onClick = {
                                menuExpanded = false
                                onFindDuplicates()
                            },
                            leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null) },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.largest_files)) },
                            onClick = {
                                menuExpanded = false
                                onOpenLargestFiles()
                            },
                            leadingIcon = { Icon(Icons.Default.DataUsage, contentDescription = null) },
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
                    Icon(Icons.Default.ContentPaste, contentDescription = stringResource(R.string.paste))
                }
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
            val showSelectionRail = state.selectionMode && selectedCount > 0
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = if (showSelectionRail) 76.dp else 0.dp),
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
                                text = state.progress.message.ifBlank { stringResource(R.string.loading) },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                state.items.isEmpty() -> EmptyPane(
                    message = when {
                        state.showFavoritesOnly -> stringResource(R.string.no_favorites)
                        state.showLargestFiles -> stringResource(R.string.largest_files_empty)
                        state.libraryMode && state.activeCategory != null ->
                            stringResource(
                                R.string.library_empty_category,
                                stringResource(state.activeCategory.nounRes),
                            )
                        state.libraryMode -> stringResource(R.string.library_empty_all)
                        state.fileFilter != FileFilter.ALL ->
                            stringResource(
                                R.string.folder_empty_filter,
                                stringResource(state.fileFilter.labelRes).lowercase(),
                            )
                        else -> stringResource(R.string.folder_empty)
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
            } // content (shifted when selection rail is open)

            AnimatedVisibility(
                visible = showSelectionRail,
                enter = slideInHorizontally { -it } + fadeIn(),
                exit = slideOutHorizontally { -it } + fadeOut(),
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxHeight(),
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
        }
    }
    }

    when (dialog) {
        DialogType.CREATE_FOLDER -> TextInputDialog(
            title = stringResource(R.string.dialog_folder_title),
            label = stringResource(R.string.dialog_folder_hint),
            value = inputText,
            confirmLabel = stringResource(R.string.dialog_folder_confirm),
            onValueChange = { inputText = it },
            onDismiss = { dialog = null },
            onConfirm = {
                onCreateFolder(inputText)
                dialog = null
            },
        )
        DialogType.RENAME -> TextInputDialog(
            title = stringResource(R.string.dialog_rename_title),
            label = stringResource(R.string.dialog_rename_hint),
            value = inputText,
            confirmLabel = stringResource(R.string.save),
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
            title = { Text(stringResource(R.string.dialog_delete_title)) },
            text = { Text(stringResource(R.string.dialog_delete_body, selectedCount)) },
            confirmButton = {
                TextButton(onClick = {
                    dialog = null
                    onDelete()
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { dialog = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
        null -> Unit
    }

    state.extractResult?.let { result ->
        AlertDialog(
            onDismissRequest = onDismissExtractResult,
            title = { Text(result.title) },
            text = {
                Text(
                    text = result.message,
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                if (result.destination != null) {
                    TextButton(onClick = onOpenExtractResultFolder) {
                        Text(stringResource(R.string.open_download))
                    }
                } else {
                    TextButton(onClick = onDismissExtractResult) {
                        Text(stringResource(R.string.ok))
                    }
                }
            },
            dismissButton = {
                if (result.destination != null) {
                    TextButton(onClick = onDismissExtractResult) {
                        Text(stringResource(R.string.close))
                    }
                }
            },
        )
    }

    state.fileDetails?.let { details ->
        AlertDialog(
            onDismissRequest = onCloseFileDetails,
            title = { Text(details.name) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.details_size, details.formattedSize))
                    Text(stringResource(R.string.details_date, details.formattedDate))
                    Text(
                        text = stringResource(R.string.details_path, details.path),
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = onCloseFileDetails) { Text(stringResource(R.string.close)) }
                    TextButton(onClick = onOpenParentOfDetails) { Text(stringResource(R.string.open_folder)) }
                    TextButton(onClick = { onToggleFavoritePath(details.path) }) {
                        Text(stringResource(R.string.favorites))
                    }
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
        modifier = Modifier.fillMaxHeight(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .width(76.dp)
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(vertical = 8.dp, horizontal = 2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Favorit & Detail first so they stay reachable at the top of the rail.
            ActionIcon(
                Icons.Default.Star,
                stringResource(R.string.favorites),
                onFavorite,
                enabled = canFavoriteOrDetails,
            )
            ActionIcon(Icons.Default.Info, stringResource(R.string.details), onDetails, enabled = canFavoriteOrDetails)
            ActionIcon(Icons.Default.Share, stringResource(R.string.share), onShare)
            ActionIcon(Icons.Default.OpenInNew, stringResource(R.string.open), onOpenWith)
            ActionIcon(Icons.Default.ContentCopy, stringResource(R.string.copy), onCopy)
            ActionIcon(Icons.Default.ContentCut, stringResource(R.string.cut), onCut)
            ActionIcon(
                icon = Icons.Default.ContentPaste,
                label = when (clipboardMode) {
                    ClipboardMode.COPY -> stringResource(R.string.paste)
                    ClipboardMode.CUT -> stringResource(R.string.move)
                    null -> stringResource(R.string.paste)
                },
                enabled = hasClipboard,
                onClick = onPaste,
            )
            ActionIcon(Icons.Default.FolderZip, stringResource(R.string.zip_action), onZip)
            ActionIcon(Icons.Default.Unarchive, stringResource(R.string.extract), onExtract, enabled = canExtract)
            ActionIcon(Icons.Default.DriveFileRenameOutline, stringResource(R.string.rename), onRename, enabled = canRename)
            ActionIcon(Icons.Default.Delete, stringResource(R.string.delete), onDelete)
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
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(72.dp)
            .padding(vertical = 2.dp),
    ) {
        IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(40.dp)) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(22.dp))
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = if (enabled) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 2.dp),
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
        EmptyPane(message = stringResource(R.string.duplicates_none))
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
                            text = stringResource(
                                R.string.duplicates_copies_save,
                                group.files.size,
                                FileItem.formatBytes(group.wastedBytes),
                            ),
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
                Text(stringResource(R.string.delete_copies))
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
        EmptyPane(message = stringResource(R.string.no_videos_in_album))
        return
    }
    val sections = remember(items) { groupByTime(items) }

    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 96.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier.fillMaxSize(),
    ) {
        sections.forEach { section ->
            item(
                key = "time-header-${section.bucket.name}",
                span = { GridItemSpan(maxLineSpan) },
            ) {
                TimeSectionHeader(section)
            }
            gridItems(section.items, key = { it.path }) { item ->
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
        EmptyPane(message = stringResource(R.string.no_photos_in_album))
        return
    }
    val sections = remember(items) { groupByTime(items) }

    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 96.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier.fillMaxSize(),
    ) {
        sections.forEach { section ->
            item(
                key = "time-header-${section.bucket.name}",
                span = { GridItemSpan(maxLineSpan) },
            ) {
                TimeSectionHeader(section)
            }
            gridItems(section.items, key = { it.path }) { item ->
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
    val sections = remember(items) { groupByTime(items) }
    LazyColumn(
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier.fillMaxSize(),
    ) {
        sections.forEach { section ->
            item(key = "time-header-${section.bucket.name}") {
                TimeSectionHeader(section)
            }
            items(section.items, key = { it.path }) { item ->
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
        }
        item { Spacer(modifier = Modifier.height(88.dp)) }
    }
}

@Composable
private fun TimeSectionHeader(section: TimeSection) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(section.bucket.labelRes),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = section.items.size.toString(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
                label = { Text(stringResource(filter.labelRes)) },
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
                label = { Text(stringResource(filter.labelRes)) },
            )
        }
    }
}

@Composable
private fun EmptyPane(message: String? = null) {
    val text = message ?: stringResource(R.string.folder_empty)
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = text,
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
                    Text(stringResource(R.string.progress_cancel))
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
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
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
        title = { Text(stringResource(R.string.dialog_zip_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    label = { Text(stringResource(R.string.dialog_zip_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.dialog_zip_password_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = bestCompression,
                        onCheckedChange = onBestCompressionChange,
                    )
                    Text(stringResource(R.string.zip_max_compression))
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(password.ifBlank { null }) },
                enabled = value.isNotBlank(),
            ) {
                Text(stringResource(R.string.dialog_zip_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
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
