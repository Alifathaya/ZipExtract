package com.zipextract.app.ui.viewer

import android.os.Environment
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.zipextract.app.R
import com.zipextract.app.data.ZipEntryItem
import com.zipextract.app.data.ZipManager
import com.zipextract.app.ui.ExtractZipState
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExtractZipScreen(
    state: ExtractZipState,
    onClose: () -> Unit,
    onToggleEntry: (String) -> Unit,
    onSelectAll: () -> Unit,
    onDeselectAll: () -> Unit,
    onDeleteOriginalChange: (Boolean) -> Unit,
    onExtract: () -> Unit,
    onSetDestination: (File) -> Unit,
) {
    BackHandler(onBack = onClose)

    val allSelected = state.entries.isNotEmpty() &&
        state.selectedPaths.size == state.entries.size
    val bottomInset = systemBottomInset(minimum = 48.dp)

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                windowInsets = WindowInsets.statusBars,
                title = {
                    Column {
                        Text(
                            text = stringResource(R.string.extract_title),
                            style = MaterialTheme.typography.titleLarge,
                        )
                        Text(
                            text = state.zipFile.name,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
            )
        },
        bottomBar = {
            if (!state.isLoading && state.error == null) {
                Surface(tonalElevation = 3.dp) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = bottomInset)
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = state.deleteOriginal,
                                onCheckedChange = onDeleteOriginalChange,
                            )
                            Text(
                                text = stringResource(R.string.extract_delete_original),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = onExtract,
                            enabled = state.selectedPaths.isNotEmpty(),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = if (state.selectedPaths.isEmpty()) {
                                    stringResource(R.string.extract_pick_files)
                                } else {
                                    stringResource(
                                        R.string.extract_n_items,
                                        state.selectedPaths.size,
                                    )
                                },
                            )
                        }
                    }
                }
            }
        },
    ) { padding ->
        when {
            state.isLoading -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(stringResource(R.string.extract_reading))
                }
            }
            state.error != null -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = state.error,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    TextButton(onClick = onClose) {
                        Text(stringResource(R.string.back))
                    }
                }
            }
            else -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                ) {
                    DestinationInfo(
                        destination = state.destinationDir,
                        zipFile = state.zipFile,
                        onSetDestination = onSetDestination,
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = {
                            if (allSelected) onDeselectAll() else onSelectAll()
                        }) {
                            Text(
                                if (allSelected) {
                                    stringResource(R.string.deselect_all)
                                } else {
                                    stringResource(R.string.select_all)
                                },
                            )
                        }
                    }

                    LazyColumn(
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(state.entries, key = { it.path }) { entry ->
                            ZipEntryRow(
                                entry = entry,
                                selected = entry.path in state.selectedPaths,
                                onToggle = { onToggleEntry(entry.path) },
                            )
                        }
                        item { Spacer(modifier = Modifier.height(24.dp)) }
                    }
                }
            }
        }
    }
}

/**
 * Bottom inset that stays above the system gesture/navigation bar.
 * Uses Compose insets, View root insets, and a hard minimum so the
 * Extract button never sits under the system UI.
 */
@Composable
private fun systemBottomInset(minimum: Dp): Dp {
    val view = LocalView.current
    val density = LocalDensity.current
    val composeNavBottom = WindowInsets.navigationBars
        .asPaddingValues()
        .calculateBottomPadding()

    val rootInsets = ViewCompat.getRootWindowInsets(view)
    val fromViewPx = rootInsets?.let { insets ->
        maxOf(
            insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom,
            insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom,
            insets.getInsets(WindowInsetsCompat.Type.tappableElement()).bottom,
        )
    } ?: 0
    val fromViewDp = with(density) { fromViewPx.toDp() }

    return max(max(composeNavBottom, fromViewDp), minimum)
}

@Composable
private fun DestinationInfo(
    destination: File,
    zipFile: File,
    onSetDestination: (File) -> Unit,
) {
    val context = LocalContext.current
    Surface(
        tonalElevation = 1.dp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = stringResource(R.string.extract_location),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = destination.absolutePath,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.extract_location_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                TextButton(
                    onClick = {
                        onSetDestination(ZipManager.defaultExtractDirectory(context, zipFile))
                    },
                ) {
                    Text(stringResource(R.string.extract_dest_auto))
                }
                TextButton(
                    onClick = {
                        onSetDestination(
                            File(
                                ZipManager.publicFileNestDir(),
                                zipFile.nameWithoutExtension.ifBlank { "extract" },
                            ),
                        )
                    },
                ) {
                    Text(stringResource(R.string.extract_dest_filenest))
                }
                TextButton(
                    onClick = {
                        zipFile.parentFile?.let(onSetDestination)
                    },
                ) {
                    Text(stringResource(R.string.extract_dest_same))
                }
                TextButton(
                    onClick = {
                        onSetDestination(
                            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                        )
                    },
                ) {
                    Text(stringResource(R.string.extract_dest_download))
                }
                TextButton(
                    onClick = {
                        val appDir = context.getExternalFilesDir("Extract")
                            ?: File(context.filesDir, "Extract")
                        onSetDestination(File(appDir, zipFile.nameWithoutExtension.ifBlank { "extract" }))
                    },
                ) {
                    Text(stringResource(R.string.extract_dest_safe))
                }
            }
        }
    }
    HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp))
}

@Composable
private fun ZipEntryRow(
    entry: ZipEntryItem,
    selected: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onToggle) {
            Icon(
                imageVector = if (selected) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
                contentDescription = if (selected) {
                    stringResource(R.string.selected)
                } else {
                    stringResource(R.string.not_selected)
                },
                tint = MaterialTheme.colorScheme.primary,
            )
        }

        Icon(
            imageVector = if (entry.isDirectory) Icons.Default.Folder else Icons.AutoMirrored.Filled.InsertDriveFile,
            contentDescription = null,
            tint = if (entry.isDirectory) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.size(22.dp),
        )

        Spacer(modifier = Modifier.width(10.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.path,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (entry.isDirectory) {
                    stringResource(R.string.folder_label)
                } else {
                    entry.formattedSize
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
