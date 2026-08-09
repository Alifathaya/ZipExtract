package com.zipextract.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Archive
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
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zipextract.app.data.ClipboardMode
import com.zipextract.app.data.FileItem
import com.zipextract.app.ui.viewer.ImageViewerScreen
import com.zipextract.app.ui.viewer.PdfViewerScreen

private enum class DialogType {
    CREATE_FOLDER,
    RENAME,
    CREATE_ZIP,
    EXTRACT,
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
    onCreateZip: (String, Boolean) -> Unit,
    onExtract: (String?) -> Unit,
    onToggleSort: () -> Unit,
    onRequestPermission: () -> Unit,
    onCloseViewer: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<DialogType?>(null) }
    var inputText by remember { mutableStateOf("") }
    var bestCompression by remember { mutableStateOf(true) }

    val selectedCount = state.selectedPaths.size
    val selectedItems = state.items.filter { it.path in state.selectedPaths }
    val singleSelected = selectedItems.singleOrNull()
    val canExtract = selectedItems.size == 1 && singleSelected?.isArchive == true
    val canRename = selectedItems.size == 1

    BackHandler(enabled = state.viewer != null || state.selectionMode || state.canGoUp) {
        when {
            state.viewer != null -> onCloseViewer()
            state.selectionMode -> onClearSelection()
            state.canGoUp -> onGoUp()
        }
    }

    if (state.viewer != null) {
        when (val viewer = state.viewer) {
            is ViewerContent.Pdf -> PdfViewerScreen(file = viewer.file, onClose = onCloseViewer)
            is ViewerContent.Image -> ImageViewerScreen(file = viewer.file, onClose = onCloseViewer)
            null -> Unit
        }
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = if (state.selectionMode) "$selectedCount dipilih" else "ZipExtract",
                            style = MaterialTheme.typography.titleLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = state.currentDir.absolutePath,
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
                        state.canGoUp -> {
                            IconButton(onClick = onGoUp) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Naik folder")
                            }
                        }
                    }
                },
                actions = {
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
                        inputText = singleSelected?.file?.nameWithoutExtension.orEmpty()
                        dialog = DialogType.EXTRACT
                    },
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
                state.items.isEmpty() -> EmptyPane()
                else -> {
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
                                onClick = {
                                    when {
                                        state.selectionMode -> onToggleSelect(item)
                                        else -> onOpenItem(item)
                                    }
                                },
                                onLongClick = { onToggleSelect(item) },
                            )
                        }
                        item { Spacer(modifier = Modifier.height(88.dp)) }
                    }
                }
            }

            state.progress?.let { progress ->
                ProgressOverlay(progress)
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
            onConfirm = {
                onCreateZip(inputText, bestCompression)
                dialog = null
            },
        )
        DialogType.EXTRACT -> TextInputDialog(
            title = "Extract ZIP",
            label = "Nama folder tujuan",
            value = inputText,
            confirmLabel = "Extract",
            onValueChange = { inputText = it },
            onDismiss = { dialog = null },
            onConfirm = {
                onExtract(inputText)
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
}

@Composable
private fun ActionBar(
    canExtract: Boolean,
    canRename: Boolean,
    hasClipboard: Boolean,
    clipboardMode: ClipboardMode?,
    onCopy: () -> Unit,
    onCut: () -> Unit,
    onPaste: () -> Unit,
    onDelete: () -> Unit,
    onRename: () -> Unit,
    onZip: () -> Unit,
    onExtract: () -> Unit,
) {
    Surface(
        tonalElevation = 3.dp,
        shadowElevation = 8.dp,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ActionIcon(Icons.Default.ContentCopy, "Copy", onCopy)
            ActionIcon(Icons.Default.ContentCut, "Cut", onCut)
            ActionIcon(
                icon = Icons.Default.ContentPaste,
                label = when (clipboardMode) {
                    ClipboardMode.COPY -> "Paste"
                    ClipboardMode.CUT -> "Move"
                    null -> "Paste"
                },
                enabled = hasClipboard,
                onClick = onPaste,
            )
            ActionIcon(Icons.Default.FolderZip, "Zip", onZip)
            ActionIcon(Icons.Default.Unarchive, "Extract", onExtract, enabled = canExtract)
            ActionIcon(Icons.Default.DriveFileRenameOutline, "Rename", onRename, enabled = canRename)
            ActionIcon(Icons.Default.Delete, "Delete", onDelete)
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
private fun FileRow(
    item: FileItem,
    selected: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
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
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selectionMode) {
            Icon(
                imageVector = if (selected) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.width(10.dp))
        }

        Icon(
            imageVector = when {
                item.isDirectory -> Icons.Default.Folder
                item.isArchive -> Icons.Default.Archive
                item.isPdf -> Icons.Default.PictureAsPdf
                item.isImage -> Icons.Default.Image
                else -> Icons.AutoMirrored.Filled.InsertDriveFile
            },
            contentDescription = null,
            tint = when {
                item.isDirectory -> MaterialTheme.colorScheme.primary
                item.isArchive -> MaterialTheme.colorScheme.secondary
                item.isPdf -> MaterialTheme.colorScheme.error
                item.isImage -> MaterialTheme.colorScheme.tertiary
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.size(28.dp),
        )

        Spacer(modifier = Modifier.width(12.dp))

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
        Text("ZipExtract", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Izinkan akses penyimpanan untuk browse, zip, extract, copy, dan paste file.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(20.dp))
        TextButton(onClick = onRequestPermission) {
            Text("Berikan izin penyimpanan")
        }
    }
}

@Composable
private fun EmptyPane() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = "Folder kosong",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ProgressOverlay(progress: com.zipextract.app.data.ProgressState) {
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
    onConfirm: () -> Unit,
) {
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
            TextButton(onClick = onConfirm, enabled = value.isNotBlank()) {
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
