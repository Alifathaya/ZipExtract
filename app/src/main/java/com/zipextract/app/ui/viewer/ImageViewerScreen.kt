package com.zipextract.app.ui.viewer

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material.icons.filled.ZoomOutMap
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
) {
    BackHandler(onBack = onClose)
    val context = LocalContext.current
    val zoomState = rememberZoomState()
    var editing by remember { mutableStateOf(false) }
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
                            text = "Cubit / double-tap zoom · Edit crop & pen",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Kembali")
                    }
                },
                actions = {
                    IconButton(onClick = { editing = true }) {
                        Icon(Icons.Default.Edit, contentDescription = "Edit")
                    }
                    IconButton(
                        onClick = {
                            if (!FileActions.shareFile(context, file)) {
                                Toast.makeText(context, "Gagal membagikan foto", Toast.LENGTH_SHORT).show()
                            }
                        },
                    ) {
                        Icon(Icons.Default.Share, contentDescription = "Bagikan")
                    }
                    IconButton(onClick = { zoomState.zoomOut() }) {
                        Icon(Icons.Default.ZoomOut, contentDescription = "Perkecil")
                    }
                    IconButton(onClick = { zoomState.zoomIn() }) {
                        Icon(Icons.Default.ZoomIn, contentDescription = "Perbesar")
                    }
                    IconButton(onClick = { zoomState.reset() }) {
                        Icon(Icons.Default.ZoomOutMap, contentDescription = "Reset zoom")
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
                    model = file,
                    contentDescription = file.name,
                    contentScale = ContentScale.Fit,
                    loading = {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.onPrimary)
                    },
                    error = {
                        Text(
                            text = "Gagal memuat gambar",
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
