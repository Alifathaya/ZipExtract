package com.zipextract.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.DataUsage
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SdCard
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import com.zipextract.app.R
import com.zipextract.app.data.CategorySummary
import com.zipextract.app.data.DeviceStorageVolume
import com.zipextract.app.data.FileCategory
import com.zipextract.app.data.FileItem
import com.zipextract.app.data.StorageInfo
import com.zipextract.app.data.StorageKind

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeDashboardScreen(
    storageInfo: StorageInfo?,
    storageVolumes: List<DeviceStorageVolume> = emptyList(),
    categories: List<CategorySummary>,
    recentFiles: List<FileItem>,
    searchQuery: String,
    searchResults: List<FileItem>,
    searchLoading: Boolean,
    isLoading: Boolean,
    isRefreshing: Boolean = false,
    onRefresh: () -> Unit,
    onFullRescan: () -> Unit = {},
    onSearchQueryChange: (String) -> Unit,
    onClearSearch: () -> Unit,
    onOpenCategory: (FileCategory) -> Unit,
    onBrowseAll: () -> Unit,
    onOpenStorageVolume: (DeviceStorageVolume) -> Unit = {},
    onOpenZips: () -> Unit,
    onOpenFavorites: () -> Unit,
    onOpenLargestFiles: () -> Unit,
    onOpenCloud: () -> Unit,
    onOpenLanguage: () -> Unit,
    onOpenFile: (FileItem) -> Unit,
    onViewAllPhotos: () -> Unit,
) {
    val isSearching = searchQuery.trim().length >= 2

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = stringResource(R.string.app_name),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = stringResource(R.string.app_tagline),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onOpenLanguage) {
                        Icon(
                            Icons.Default.Language,
                            contentDescription = stringResource(R.string.language_menu),
                        )
                    }
                    IconButton(onClick = onRefresh) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.refresh),
                        )
                    }
                    var menuExpanded by remember { mutableStateOf(false) }
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(
                            Icons.Default.MoreVert,
                            contentDescription = stringResource(R.string.menu),
                        )
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.full_rescan)) },
                            onClick = {
                                menuExpanded = false
                                onFullRescan()
                            },
                            leadingIcon = {
                                Icon(Icons.Default.TravelExplore, contentDescription = null)
                            },
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
    ) { padding ->
        val pullState = rememberPullToRefreshState()
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = onRefresh,
            state = pullState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
        val pageModifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                        MaterialTheme.colorScheme.background,
                        MaterialTheme.colorScheme.background,
                    ),
                ),
            )
            .padding(horizontal = 16.dp)

        if (isSearching) {
            LazyColumn(
                modifier = pageModifier,
                contentPadding = PaddingValues(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item {
                    StorageSection(
                        volumes = storageVolumes,
                        storageInfo = storageInfo,
                        compact = false,
                        onOpenVolume = onOpenStorageVolume,
                    )
                }
                item {
                    SearchBar(
                        query = searchQuery,
                        onQueryChange = onSearchQueryChange,
                        onClear = onClearSearch,
                    )
                }
                item {
                    SearchResultsSection(
                        query = searchQuery,
                        results = searchResults,
                        loading = searchLoading,
                        onOpenFile = onOpenFile,
                    )
                }
            }
        } else {
            Column(
                modifier = pageModifier
                    .navigationBarsPadding()
                    .padding(bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                StorageSection(
                    volumes = storageVolumes,
                    storageInfo = storageInfo,
                    compact = true,
                    onOpenVolume = onOpenStorageVolume,
                )

                SearchBar(
                    query = searchQuery,
                    onQueryChange = onSearchQueryChange,
                    onClear = onClearSearch,
                )

                QuickActionsRow(
                    onBrowseAll = onBrowseAll,
                    onOpenZips = onOpenZips,
                    onOpenFavorites = onOpenFavorites,
                    onOpenLargestFiles = onOpenLargestFiles,
                    onOpenCloud = onOpenCloud,
                )

                RecentPhotosSection(
                    photos = recentFiles,
                    loading = isLoading,
                    onOpenPhoto = onOpenFile,
                    onViewAll = onViewAllPhotos,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                )

                Column {
                    Text(
                        text = stringResource(R.string.categories),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = stringResource(R.string.categories_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                val visibleCategories = remember(categories) {
                    val base = if (categories.isEmpty()) {
                        FileCategory.entries.map { category ->
                            CategorySummary(
                                category = category,
                                itemCount = 0,
                                folder = category.resolveFolder(),
                            )
                        }
                    } else {
                        categories
                    }
                    base.filter { it.category != FileCategory.ARCHIVES }
                }
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    visibleCategories.chunked(2).forEach { row ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            row.forEach { summary ->
                                CategoryCard(
                                    summary = summary,
                                    onClick = { onOpenCategory(summary.category) },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            if (row.size == 1) {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
        }
    }
}

@Composable
private fun SearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onClear: () -> Unit,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        placeholder = { Text(stringResource(R.string.search_files_hint)) },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = onClear) {
                    Icon(
                        Icons.Default.Clear,
                        contentDescription = stringResource(R.string.clear_search),
                    )
                }
            }
        },
        singleLine = true,
    )
}

@Composable
private fun SearchResultsSection(
    query: String,
    results: List<FileItem>,
    loading: Boolean,
    onOpenFile: (FileItem) -> Unit,
) {
    Column {
        Text(
            text = stringResource(R.string.search_results),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = "\"$query\"",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(8.dp))

        when {
            loading -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
            results.isEmpty() -> {
                Text(
                    text = stringResource(R.string.search_none_found),
                    modifier = Modifier.padding(vertical = 16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            else -> {
                results.forEach { item ->
                    FilePreviewRow(item = item, onClick = { onOpenFile(item) })
                    Spacer(modifier = Modifier.height(6.dp))
                }
            }
        }
    }
}

@Composable
private fun RecentPhotosSection(
    photos: List<FileItem>,
    loading: Boolean,
    onOpenPhoto: (FileItem) -> Unit,
    onViewAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val samplePhotos = photos.take(6)

    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Image,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.recent_photos),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            TextButton(onClick = onViewAll) {
                Text(stringResource(R.string.view_all))
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = true),
        ) {
            // Fill leftover dashboard height so category buttons sit at the bottom.
            val thumbSize = min(maxHeight, 220.dp).coerceAtLeast(96.dp)

            when {
                loading && samplePhotos.isEmpty() -> {
                    LazyRow(
                        modifier = Modifier.fillMaxHeight(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        items(4) {
                            PhotoPlaceholderTile(modifier = Modifier.size(thumbSize))
                        }
                    }
                }
                samplePhotos.isEmpty() -> {
                    LazyRow(
                        modifier = Modifier.fillMaxHeight(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        items(4) {
                            PhotoPlaceholderTile(modifier = Modifier.size(thumbSize))
                        }
                    }
                }
                else -> {
                    LazyRow(
                        modifier = Modifier.fillMaxHeight(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        contentPadding = PaddingValues(end = 4.dp),
                    ) {
                        items(samplePhotos, key = { it.path }) { photo ->
                            PhotoThumbnail(
                                photo = photo,
                                onClick = { onOpenPhoto(photo) },
                                size = thumbSize,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PhotoThumbnail(
    photo: FileItem,
    onClick: () -> Unit,
    size: Dp = 108.dp,
) {
    Surface(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        tonalElevation = 2.dp,
    ) {
        SubcomposeAsyncImage(
            model = photo.file,
            contentDescription = photo.name,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            loading = {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                }
            },
            error = {
                PhotoPlaceholderTile(modifier = Modifier.fillMaxSize())
            },
            success = {
                SubcomposeAsyncImageContent()
            },
        )
    }
}

@Composable
private fun PhotoPlaceholderTile(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Default.Image,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.size(32.dp),
        )
    }
}

@Composable
private fun FilePreviewRow(
    item: FileItem,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        tonalElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = iconForItem(item),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${item.formattedSize} · ${item.formattedDate}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = item.file.parentFile?.name ?: item.path,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun iconForItem(item: FileItem): ImageVector {
    return when {
        item.isDirectory -> Icons.Default.Folder
        item.isArchive -> Icons.Default.Archive
        item.isPdf -> Icons.Default.PictureAsPdf
        item.isImage -> Icons.Default.Image
        item.isVideo -> Icons.Default.Movie
        item.isApp -> Icons.Default.Android
        item.isDocument -> Icons.Default.Description
        else -> Icons.AutoMirrored.Filled.InsertDriveFile
    }
}

@Composable
private fun StorageSection(
    volumes: List<DeviceStorageVolume>,
    storageInfo: StorageInfo?,
    compact: Boolean,
    onOpenVolume: (DeviceStorageVolume) -> Unit,
) {
    val displayVolumes = remember(volumes, storageInfo) {
        if (volumes.isNotEmpty()) {
            volumes
        } else if (storageInfo != null) {
            listOf(
                DeviceStorageVolume(
                    id = "primary",
                    label = "",
                    kind = StorageKind.INTERNAL,
                    root = null,
                    totalBytes = storageInfo.totalBytes,
                    freeBytes = storageInfo.freeBytes,
                    isPrimary = true,
                    isRemovable = false,
                    isMounted = true,
                ),
            )
        } else {
            emptyList()
        }
    }

    if (displayVolumes.isEmpty()) {
        StorageVolumeCard(
            title = stringResource(R.string.storage_internal),
            kind = StorageKind.INTERNAL,
            totalBytes = null,
            freeBytes = null,
            compact = compact,
            clickable = false,
            onClick = {},
            modifier = Modifier.fillMaxWidth(),
        )
        return
    }

    if (compact && displayVolumes.size > 1) {
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            items(displayVolumes, key = { it.id }) { volume ->
                val title = volumeTitle(volume)
                StorageVolumeCard(
                    title = title,
                    kind = volume.kind,
                    totalBytes = volume.totalBytes.takeIf { it > 0L },
                    freeBytes = volume.freeBytes.takeIf { volume.totalBytes > 0L },
                    compact = true,
                    clickable = volume.canBrowse,
                    onClick = { onOpenVolume(volume) },
                    modifier = Modifier.width(220.dp),
                )
            }
        }
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 10.dp)) {
        displayVolumes.forEach { volume ->
            StorageVolumeCard(
                title = volumeTitle(volume),
                kind = volume.kind,
                totalBytes = volume.totalBytes.takeIf { it > 0L },
                freeBytes = volume.freeBytes.takeIf { volume.totalBytes > 0L },
                compact = compact,
                clickable = volume.canBrowse,
                onClick = { onOpenVolume(volume) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun volumeTitle(volume: DeviceStorageVolume): String {
    return when {
        volume.label.isNotBlank() -> volume.label
        volume.kind == StorageKind.INTERNAL -> stringResource(R.string.storage_internal)
        volume.kind == StorageKind.SD_CARD -> stringResource(R.string.storage_sd_card)
        volume.kind == StorageKind.USB -> stringResource(R.string.storage_usb)
        else -> stringResource(R.string.storage_external)
    }
}

@Composable
private fun StorageVolumeCard(
    title: String,
    kind: StorageKind,
    totalBytes: Long?,
    freeBytes: Long?,
    compact: Boolean,
    clickable: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val usedBytes = if (totalBytes != null && freeBytes != null) {
        (totalBytes - freeBytes).coerceAtLeast(0L)
    } else {
        null
    }
    val usedFraction = if (totalBytes != null && totalBytes > 0L && usedBytes != null) {
        usedBytes.toFloat() / totalBytes.toFloat()
    } else {
        0f
    }
    val icon = when (kind) {
        StorageKind.SD_CARD -> Icons.Default.SdCard
        StorageKind.USB -> Icons.Default.Usb
        StorageKind.INTERNAL, StorageKind.OTHER -> Icons.Default.Storage
    }

    Card(
        modifier = modifier
            .then(if (clickable) Modifier.clickable(onClick = onClick) else Modifier),
        shape = RoundedCornerShape(if (compact) 16.dp else 20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(modifier = Modifier.padding(if (compact) 12.dp else 18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(if (compact) 36.dp else 44.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(if (compact) 18.dp else 24.dp),
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = if (compact) {
                            MaterialTheme.typography.titleSmall
                        } else {
                            MaterialTheme.typography.titleMedium
                        },
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = if (usedBytes != null && totalBytes != null) {
                            stringResource(
                                R.string.storage_used_of,
                                FileItem.formatBytes(usedBytes),
                                FileItem.formatBytes(totalBytes),
                            )
                        } else {
                            stringResource(R.string.storage_calculating)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Spacer(modifier = Modifier.height(if (compact) 8.dp else 14.dp))

            LinearProgressIndicator(
                progress = { usedFraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(if (compact) 6.dp else 8.dp)
                    .clip(RoundedCornerShape(8.dp)),
            )

            if (!compact) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = if (freeBytes != null) {
                        stringResource(
                            R.string.storage_available,
                            FileItem.formatBytes(freeBytes),
                        )
                    } else {
                        "—"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun StorageCard(
    storageInfo: StorageInfo?,
    compact: Boolean = false,
) {
    StorageVolumeCard(
        title = stringResource(R.string.storage_internal),
        kind = StorageKind.INTERNAL,
        totalBytes = storageInfo?.totalBytes,
        freeBytes = storageInfo?.freeBytes,
        compact = compact,
        clickable = false,
        onClick = {},
    )
}

@Composable
private fun QuickActionsRow(
    onBrowseAll: () -> Unit,
    onOpenZips: () -> Unit,
    onOpenFavorites: () -> Unit,
    onOpenLargestFiles: () -> Unit,
    onOpenCloud: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            QuickActionChip(
                modifier = Modifier.weight(1f),
                icon = Icons.Default.PhoneAndroid,
                label = stringResource(R.string.explorer_title),
                container = MaterialTheme.colorScheme.secondaryContainer,
                onClick = onBrowseAll,
            )
            QuickActionChip(
                modifier = Modifier.weight(1f),
                icon = Icons.Default.Archive,
                label = stringResource(R.string.zips),
                container = MaterialTheme.colorScheme.tertiaryContainer,
                onClick = onOpenZips,
            )
            QuickActionChip(
                modifier = Modifier.weight(1f),
                icon = Icons.Default.Star,
                label = stringResource(R.string.favorites),
                container = MaterialTheme.colorScheme.primaryContainer,
                onClick = onOpenFavorites,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            QuickActionChip(
                modifier = Modifier.weight(1f),
                icon = Icons.Default.DataUsage,
                label = stringResource(R.string.largest_files),
                container = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f),
                onClick = onOpenLargestFiles,
            )
            QuickActionChip(
                modifier = Modifier.weight(1f),
                icon = Icons.Default.Cloud,
                label = stringResource(R.string.cloud),
                container = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.85f),
                onClick = onOpenCloud,
            )
        }
    }
}

@Composable
private fun QuickActionChip(
    icon: ImageVector,
    label: String,
    container: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        color = container,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun CategoryCard(
    summary: CategorySummary,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val style = categoryStyle(summary.category)

    Card(
        modifier = modifier
            .height(52.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.linearGradient(colors = listOf(style.start, style.end)))
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.22f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = style.icon,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(16.dp),
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(summary.category.titleRes),
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(R.string.items_count, summary.itemCount),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.88f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private data class CategoryStyle(
    val icon: ImageVector,
    val start: Color,
    val end: Color,
)

private fun categoryStyle(category: FileCategory): CategoryStyle {
    return when (category) {
        FileCategory.DOWNLOADS -> CategoryStyle(Icons.Default.Download, Color(0xFF2563EB), Color(0xFF1D4ED8))
        FileCategory.IMAGES -> CategoryStyle(Icons.Default.Image, Color(0xFFDB2777), Color(0xFFBE185D))
        FileCategory.VIDEOS -> CategoryStyle(Icons.Default.Movie, Color(0xFF7C3AED), Color(0xFF6D28D9))
        FileCategory.DOCUMENTS -> CategoryStyle(Icons.Default.Description, Color(0xFFD97706), Color(0xFFB45309))
        FileCategory.ARCHIVES -> CategoryStyle(Icons.Default.Archive, Color(0xFF0F766E), Color(0xFF0D9488))
        FileCategory.APPS -> CategoryStyle(Icons.Default.Android, Color(0xFF059669), Color(0xFF047857))
        FileCategory.AUDIO -> CategoryStyle(Icons.Default.MusicNote, Color(0xFFEA580C), Color(0xFFC2410C))
        FileCategory.RAW_APK -> CategoryStyle(Icons.Default.Android, Color(0xFF0891B2), Color(0xFF0E7490))
        FileCategory.OTHERS -> CategoryStyle(Icons.AutoMirrored.Filled.InsertDriveFile, Color(0xFF475569), Color(0xFF334155))
    }
}
