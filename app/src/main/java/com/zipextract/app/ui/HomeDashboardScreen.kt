package com.zipextract.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import com.zipextract.app.data.CategorySummary
import com.zipextract.app.data.FileCategory
import com.zipextract.app.data.FileItem
import com.zipextract.app.data.StorageInfo

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeDashboardScreen(
    storageInfo: StorageInfo?,
    categories: List<CategorySummary>,
    recentFiles: List<FileItem>,
    searchQuery: String,
    searchResults: List<FileItem>,
    searchLoading: Boolean,
    isLoading: Boolean,
    onRefresh: () -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onClearSearch: () -> Unit,
    onOpenCategory: (FileCategory) -> Unit,
    onBrowseAll: () -> Unit,
    onOpenZips: () -> Unit,
    onOpenFavorites: () -> Unit,
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
                            text = "FileNest",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = "Kelola file & arsip dengan mudah",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
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
                .padding(padding)
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                StorageCard(storageInfo = storageInfo)
            }

            item {
                SearchBar(
                    query = searchQuery,
                    onQueryChange = onSearchQueryChange,
                    onClear = onClearSearch,
                )
            }

            if (isSearching) {
                item {
                    SearchResultsSection(
                        query = searchQuery,
                        results = searchResults,
                        loading = searchLoading,
                        onOpenFile = onOpenFile,
                    )
                }
            } else {
                item {
                    QuickActionsRow(
                        onBrowseAll = onBrowseAll,
                        onOpenZips = onOpenZips,
                        onOpenFavorites = onOpenFavorites,
                    )
                }

                item {
                    RecentPhotosSection(
                        photos = recentFiles,
                        loading = isLoading,
                        onOpenPhoto = onOpenFile,
                        onViewAll = onViewAllPhotos,
                    )
                }

                item {
                    Column {
                        Text(
                            text = "Kategori",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "Pilih jenis file yang ingin dibuka",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                if (isLoading && categories.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(120.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("Memuat kategori…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                } else {
                    val visibleCategories = categories.filter { it.category != FileCategory.ARCHIVES }
                    items(visibleCategories.chunked(2), key = { row -> row.first().category.name }) { row ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
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
        placeholder = { Text("Cari file di penyimpanan…") },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = onClear) {
                    Icon(Icons.Default.Clear, contentDescription = "Hapus pencarian")
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
            text = "Hasil pencarian",
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
                    text = "Tidak ada file ditemukan",
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
) {
    val samplePhotos = photos.take(6)

    Column {
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
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = "Foto Terbaru",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "Sample foto dari perangkat Anda",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            TextButton(onClick = onViewAll) {
                Text("Lihat semua")
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        when {
            loading && samplePhotos.isEmpty() -> {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(4) {
                        PhotoPlaceholderTile(modifier = Modifier.size(108.dp))
                    }
                }
            }
            samplePhotos.isEmpty() -> {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(4) {
                        PhotoPlaceholderTile(modifier = Modifier.size(108.dp))
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Belum ada foto ditemukan. Tap Lihat semua untuk membuka folder gambar.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            else -> {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(end = 4.dp),
                ) {
                    items(samplePhotos, key = { it.path }) { photo ->
                        PhotoThumbnail(
                            photo = photo,
                            onClick = { onOpenPhoto(photo) },
                        )
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
) {
    Surface(
        modifier = Modifier
            .size(108.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
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
private fun StorageCard(storageInfo: StorageInfo?) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.Storage,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Penyimpanan Internal",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = if (storageInfo != null) {
                            "${FileItem.formatBytes(storageInfo.usedBytes)} terpakai dari ${FileItem.formatBytes(storageInfo.totalBytes)}"
                        } else {
                            "Menghitung kapasitas…"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            LinearProgressIndicator(
                progress = { storageInfo?.usedFraction ?: 0f },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(8.dp)),
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = if (storageInfo != null) {
                    "Tersedia ${FileItem.formatBytes(storageInfo.freeBytes)}"
                } else {
                    "—"
                },
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun QuickActionsRow(
    onBrowseAll: () -> Unit,
    onOpenZips: () -> Unit,
    onOpenFavorites: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            QuickActionChip(
                modifier = Modifier.weight(1f),
                icon = Icons.Default.FolderOpen,
                label = "Semua File",
                container = MaterialTheme.colorScheme.secondaryContainer,
                onClick = onBrowseAll,
            )
            QuickActionChip(
                modifier = Modifier.weight(1f),
                icon = Icons.Default.Archive,
                label = "File ZIP",
                container = MaterialTheme.colorScheme.tertiaryContainer,
                onClick = onOpenZips,
            )
        }
        QuickActionChip(
            modifier = Modifier.fillMaxWidth(),
            icon = Icons.Default.Star,
            label = "Favorit",
            container = MaterialTheme.colorScheme.primaryContainer,
            onClick = onOpenFavorites,
        )
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
        shape = RoundedCornerShape(16.dp),
        color = container,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = label, style = MaterialTheme.typography.labelLarge)
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
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Brush.linearGradient(colors = listOf(style.start, style.end)))
                .padding(16.dp),
        ) {
            Column {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.22f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = style.icon,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(24.dp),
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    text = summary.category.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = summary.category.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.88f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )

                Spacer(modifier = Modifier.height(10.dp))

                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = Color.White.copy(alpha = 0.2f),
                ) {
                    Text(
                        text = "${summary.itemCount} item",
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = Color.White,
                    )
                }
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
        FileCategory.OTHERS -> CategoryStyle(Icons.Default.MusicNote, Color(0xFF475569), Color(0xFF334155))
    }
}
