package com.zipextract.app.ui.viewer

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SdCard
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zipextract.app.R
import com.zipextract.app.data.DeviceStorageVolume
import com.zipextract.app.data.FileItem
import com.zipextract.app.data.FileOperations
import com.zipextract.app.data.StorageKind
import java.io.File

data class ExplorerBreadcrumb(
    val label: String,
    val directory: File,
)

/**
 * Builds clickable path segments from a volume root to [current].
 * First segment uses [rootLabel] (e.g. "My Phone") instead of a raw folder name.
 */
fun buildExplorerBreadcrumbs(
    root: File,
    rootLabel: String,
    current: File,
): List<ExplorerBreadcrumb> {
    val rootPath = runCatching { root.canonicalFile }.getOrDefault(root)
    val currentPath = runCatching { current.canonicalFile }.getOrDefault(current)
    val rootAbs = rootPath.absolutePath
    val currentAbs = currentPath.absolutePath
    val segments = mutableListOf(ExplorerBreadcrumb(rootLabel, rootPath))
    if (FileOperations.samePath(rootPath, currentPath)) return segments
    if (!currentAbs.startsWith(rootAbs)) return segments

    val relative = currentAbs.removePrefix(rootAbs).trimStart('/', '\\')
    if (relative.isEmpty()) return segments
    var cursor = rootPath
    relative.split('/', '\\').filter { it.isNotEmpty() }.forEach { part ->
        cursor = File(cursor, part)
        segments += ExplorerBreadcrumb(part, cursor)
    }
    return segments
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExplorerRootsScreen(
    volumes: List<DeviceStorageVolume>,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onOpenVolume: (DeviceStorageVolume) -> Unit,
) {
    BackHandler(onBack = onBack)
    val browsable = remember(volumes) { volumes.filter { it.canBrowse } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = stringResource(R.string.explorer_title),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = stringResource(R.string.explorer_pick_storage),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onRefresh) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.refresh),
                        )
                    }
                },
            )
        },
    ) { padding ->
        if (browsable.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = stringResource(R.string.explorer_no_storage),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    TextButton(onClick = onRefresh) {
                        Text(stringResource(R.string.refresh))
                    }
                }
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    text = stringResource(R.string.explorer_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(browsable, key = { it.id }) { volume ->
                ExplorerVolumeCard(
                    volume = volume,
                    onClick = { onOpenVolume(volume) },
                )
            }
        }
    }
}

@Composable
private fun ExplorerVolumeCard(
    volume: DeviceStorageVolume,
    onClick: () -> Unit,
) {
    val title = when {
        volume.isPrimary -> stringResource(R.string.explorer_my_phone)
        volume.label.isNotBlank() -> volume.label
        volume.kind == StorageKind.SD_CARD -> stringResource(R.string.storage_sd_card)
        volume.kind == StorageKind.USB -> stringResource(R.string.storage_usb)
        else -> stringResource(R.string.storage_external)
    }
    val subtitle = when (volume.kind) {
        StorageKind.INTERNAL -> stringResource(R.string.storage_internal)
        StorageKind.SD_CARD -> stringResource(R.string.storage_sd_card)
        StorageKind.USB -> stringResource(R.string.storage_usb)
        StorageKind.OTHER -> stringResource(R.string.storage_external)
    }
    val icon: ImageVector = when {
        volume.isPrimary -> Icons.Default.PhoneAndroid
        volume.kind == StorageKind.SD_CARD -> Icons.Default.SdCard
        volume.kind == StorageKind.USB -> Icons.Default.Usb
        else -> Icons.Default.Storage
    }
    val used = volume.usedBytes
    val total = volume.totalBytes

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (total > 0L) {
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { volume.usedFraction },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(6.dp)),
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(
                            R.string.storage_used_of,
                            FileItem.formatBytes(used),
                            FileItem.formatBytes(total),
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun ExplorerBreadcrumbBar(
    segments: List<ExplorerBreadcrumb>,
    onNavigate: (File) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (segments.isEmpty()) return
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        segments.forEachIndexed { index, segment ->
            val isLast = index == segments.lastIndex
            Text(
                text = segment.label,
                style = MaterialTheme.typography.labelLarge,
                color = if (isLast) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                fontWeight = if (isLast) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(enabled = !isLast) { onNavigate(segment.directory) }
                    .padding(horizontal = 6.dp, vertical = 4.dp),
            )
            if (!isLast) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}
