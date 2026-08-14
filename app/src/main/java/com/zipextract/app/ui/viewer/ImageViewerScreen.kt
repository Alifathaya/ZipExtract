package com.zipextract.app.ui.viewer

import com.zipextract.app.R

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material.icons.filled.ZoomOutMap
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.zipextract.app.data.FileActions
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageViewerScreen(
    file: File,
    onClose: () -> Unit,
    onDelete: () -> Unit,
) {
    BackHandler(onBack = onClose)
    val context = LocalContext.current
    val zoomState = rememberZoomState()
    var editing by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val imageRequest = remember(file.absolutePath, file.length(), file.lastModified()) {
        ImageRequest.Builder(context)
            .data(file)
            .memoryCacheKey("${file.absolutePath}:${file.length()}:${file.lastModified()}")
            .diskCachePolicy(CachePolicy.DISABLED)
            .allowHardware(false)
            .build()
    }

    if (editing) {
        MediaEditorScreen(
            title = file.name,
            sourceFile = file,
            onClose = { editing = false },
        )
        return
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.dialog_delete_title)) },
            text = { Text(stringResource(R.string.dialog_delete_file_body, file.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        onDelete()
                    },
                ) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = file.name,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = stringResource(R.string.image_viewer_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(onClick = { editing = true }) {
                        Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.edit))
                    }
                    IconButton(
                        onClick = {
                            if (!FileActions.shareFile(context, file)) {
                                Toast.makeText(context, context.getString(R.string.image_share_failed), Toast.LENGTH_SHORT).show()
                            }
                        },
                    ) {
                        Icon(Icons.Default.Share, contentDescription = stringResource(R.string.share))
                    }
                    IconButton(onClick = { confirmDelete = true }) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = stringResource(R.string.delete),
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                    IconButton(
                        onClick = { zoomState.zoomOut() },
                        enabled = zoomState.isZoomed,
                    ) {
                        Icon(Icons.Default.ZoomOut, contentDescription = stringResource(R.string.zoom_out))
                    }
                    IconButton(onClick = { zoomState.zoomIn() }) {
                        Icon(Icons.Default.ZoomIn, contentDescription = stringResource(R.string.zoom_in))
                    }
                    IconButton(
                        onClick = { zoomState.reset() },
                        enabled = zoomState.isZoomed,
                    ) {
                        Icon(Icons.Default.ZoomOutMap, contentDescription = stringResource(R.string.zoom_reset))
                    }
                },
            )
        },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.92f)),
            contentAlignment = Alignment.Center,
        ) {
            ZoomableBox(
                zoomState = zoomState,
                modifier = Modifier.fillMaxSize(),
            ) {
                SubcomposeAsyncImage(
                    model = imageRequest,
                    contentDescription = file.name,
                    contentScale = ContentScale.Fit,
                    loading = {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.onPrimary)
                    },
                    error = {
                        Text(
                            text = stringResource(R.string.image_load_failed),
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(24.dp),
                        )
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}
