package com.zipextract.app.ui.viewer

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import java.io.File
import androidx.compose.ui.modifier as ComposeModifier

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageViewerScreen(
    file: File,
    onClose: () -> Unit,
) {
    BackHandler(onBack = onClose)
    val zoomState = rememberZoomState()

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
                            text = "Cubit / double-tap untuk zoom",
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
            ComposeModifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.92f)),
            contentAlignment = Alignment.Center,
        ) {
            ZoomableBox(
                zoomState = zoomState,
                modifier = ComposeModifier.fillMaxSize(),
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
                            modifier = ComposeModifier.padding(24.dp),
                        )
                    },
                    modifier = ComposeModifier.fillMaxSize(),
                )
            }
        }
    }
}
