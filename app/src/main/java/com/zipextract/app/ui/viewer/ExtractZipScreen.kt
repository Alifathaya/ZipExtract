package com.zipextract.app.ui.viewer

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zipextract.app.data.ZipEntryItem
import com.zipextract.app.ui.ExtractZipState
import java.io.File
import androidx.compose.ui.Modifier as ComposeModifier

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
) {
    BackHandler(onBack = onClose)

    val allSelected = state.entries.isNotEmpty() &&
        state.selectedPaths.size == state.entries.size
    val destination = state.zipFile.parentFile

    Scaffold(
        modifier = ComposeModifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Extract ZIP",
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
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Kembali")
                    }
                },
            )
        },
        bottomBar = {
            if (!state.isLoading && state.error == null) {
                Surface(tonalElevation = 3.dp) {
                    Column(
                        modifier = ComposeModifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        Row(
                            modifier = ComposeModifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = state.deleteOriginal,
                                onCheckedChange = onDeleteOriginalChange,
                            )
                            Text(
                                text = "Hapus file ZIP asli setelah extract",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        Spacer(modifier = ComposeModifier.height(8.dp))
                        Button(
                            onClick = onExtract,
                            enabled = state.selectedPaths.isNotEmpty(),
                            modifier = ComposeModifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = if (state.selectedPaths.isEmpty()) {
                                    "Pilih file untuk extract"
                                } else {
                                    "Extract ${state.selectedPaths.size} item"
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
                    modifier = ComposeModifier
                        .fillMaxSize()
                        .padding(padding),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator()
                    Spacer(modifier = ComposeModifier.height(12.dp))
                    Text("Membaca isi ZIP…")
                }
            }
            state.error != null -> {
                Column(
                    modifier = ComposeModifier
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
                    Spacer(modifier = ComposeModifier.height(16.dp))
                    TextButton(onClick = onClose) {
                        Text("Kembali")
                    }
                }
            }
            else -> {
                Column(
                    modifier = ComposeModifier
                        .fillMaxSize()
                        .padding(padding),
                ) {
                    DestinationInfo(destination = destination)

                    Row(
                        modifier = ComposeModifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = {
                            if (allSelected) onDeselectAll() else onSelectAll()
                        }) {
                            Text(if (allSelected) "Batal pilih semua" else "Pilih semua")
                        }
                    }

                    LazyColumn(
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = ComposeModifier.fillMaxSize(),
                    ) {
                        items(state.entries, key = { it.path }) { entry ->
                            ZipEntryRow(
                                entry = entry,
                                selected = entry.path in state.selectedPaths,
                                onToggle = { onToggleEntry(entry.path) },
                            )
                        }
                        item { Spacer(modifier = ComposeModifier.height(96.dp)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun DestinationInfo(destination: File?) {
    Surface(
        tonalElevation = 1.dp,
        modifier = ComposeModifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Column(modifier = ComposeModifier.padding(12.dp)) {
            Text(
                text = "Lokasi extract",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = ComposeModifier.height(4.dp))
            Text(
                text = destination?.absolutePath ?: "Tidak diketahui",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = ComposeModifier.height(4.dp))
            Text(
                text = "File akan diekstrak ke folder yang sama dengan file ZIP.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    HorizontalDivider(modifier = ComposeModifier.padding(horizontal = 12.dp))
}

@Composable
private fun ZipEntryRow(
    entry: ZipEntryItem,
    selected: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = ComposeModifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onToggle) {
            Icon(
                imageVector = if (selected) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
                contentDescription = if (selected) "Dipilih" else "Tidak dipilih",
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
            modifier = ComposeModifier.size(22.dp),
        )

        Spacer(modifier = ComposeModifier.width(10.dp))

        Column(modifier = ComposeModifier.weight(1f)) {
            Text(
                text = entry.path,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = entry.formattedSize,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
