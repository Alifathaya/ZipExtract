package com.zipextract.app.ui.viewer

import com.zipextract.app.R

import android.graphics.Bitmap
import android.graphics.Matrix
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.Share
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
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
import coil.size.Size
import coil.transform.Transformation
import com.zipextract.app.data.FileActions
import java.io.File
import kotlinx.coroutines.flow.distinctUntilChanged

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageViewerScreen(
    file: File,
    playlist: List<File> = listOf(file),
    initialIndex: Int = 0,
    onClose: () -> Unit,
    onDelete: () -> Unit,
    onPageChanged: (File) -> Unit = {},
) {
    BackHandler(onBack = onClose)
    val context = LocalContext.current
    val files = remember(playlist, file) {
        playlist.ifEmpty { listOf(file) }
            .distinctBy { it.absolutePath }
            .ifEmpty { listOf(file) }
    }
    val startIndex = remember(files, file, initialIndex) {
        val byPath = files.indexOfFirst { it.absolutePath == file.absolutePath }
        when {
            byPath >= 0 -> byPath
            initialIndex in files.indices -> initialIndex
            else -> 0
        }
    }
    val pagerState = rememberPagerState(
        initialPage = startIndex,
        pageCount = { files.size },
    )
    val currentFile = files.getOrElse(pagerState.currentPage) { file }
    val zoomState = rememberZoomState()
    var editing by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    // View-only orientation (not written to disk).
    var rotationDeg by remember { mutableFloatStateOf(0f) }
    var flipHorizontal by remember { mutableStateOf(false) }
    val onPageChangedState = rememberUpdatedState(onPageChanged)

    // Reset zoom/orientation when the page changes; notify host of the current file.
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }
            .distinctUntilChanged()
            .collect { page ->
                zoomState.reset()
                rotationDeg = 0f
                flipHorizontal = false
                files.getOrNull(page)?.let { onPageChangedState.value(it) }
            }
    }

    if (editing) {
        MediaEditorScreen(
            title = currentFile.name,
            sourceFile = currentFile,
            onClose = { editing = false },
        )
        return
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.dialog_delete_title)) },
            text = { Text(stringResource(R.string.dialog_delete_file_body, currentFile.name)) },
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
                    Text(
                        text = currentFile.name,
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
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
                            if (!FileActions.shareFile(context, currentFile)) {
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
            HorizontalPager(
                state = pagerState,
                // Allow swipe between photos only when not zoomed in.
                userScrollEnabled = !zoomState.isZoomed,
                key = { page -> files[page].absolutePath },
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                val pageFile = files[page]
                val isCurrent = page == pagerState.currentPage
                val pageRotation = if (isCurrent) rotationDeg else 0f
                val pageFlip = if (isCurrent) flipHorizontal else false
                // Rotate/mirror in the bitmap so ContentScale.Fit uses the new
                // aspect ratio — keeps 90°/270° full-size instead of shrinking.
                val imageRequest = remember(
                    pageFile.absolutePath,
                    pageFile.length(),
                    pageFile.lastModified(),
                    pageRotation,
                    pageFlip,
                ) {
                    val baseKey =
                        "${pageFile.absolutePath}:${pageFile.length()}:${pageFile.lastModified()}"
                    val builder = ImageRequest.Builder(context)
                        .data(pageFile)
                        .diskCachePolicy(CachePolicy.DISABLED)
                        .allowHardware(false)
                    if (pageRotation != 0f || pageFlip) {
                        builder
                            .transformations(
                                ImageOrientationTransformation(pageRotation, pageFlip),
                            )
                            .memoryCacheKey("$baseKey:r${pageRotation.toInt()}:f$pageFlip")
                    } else {
                        builder.memoryCacheKey(baseKey)
                    }
                    builder.build()
                }
                // Only the current page gets interactive zoom; neighbors stay fit.
                if (isCurrent) {
                    ZoomableBox(
                        zoomState = zoomState,
                        // At 1x, don't steal horizontal swipes from the pager.
                        preserveScrollGestures = true,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        SubcomposeAsyncImage(
                            model = imageRequest,
                            contentDescription = pageFile.name,
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
                } else {
                    SubcomposeAsyncImage(
                        model = imageRequest,
                        contentDescription = pageFile.name,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 20.dp)
                    .background(
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.82f),
                        shape = RoundedCornerShape(28.dp),
                    )
                    .padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = {
                        rotationDeg = (rotationDeg + 90f) % 360f
                        zoomState.reset()
                    },
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.RotateRight,
                        contentDescription = stringResource(R.string.image_rotate),
                    )
                }
                IconButton(
                    onClick = {
                        flipHorizontal = !flipHorizontal
                        zoomState.reset()
                    },
                ) {
                    Icon(
                        Icons.Default.Flip,
                        contentDescription = stringResource(R.string.image_flip),
                    )
                }
            }
        }
    }
}

/**
 * Bake rotate / mirror into the bitmap so Fit layout uses the true
 * post-orientation size (no artificial shrink at 90° / 270°).
 */
private class ImageOrientationTransformation(
    private val rotationDeg: Float,
    private val flipHorizontal: Boolean,
) : Transformation {
    override val cacheKey: String =
        "orient-r${rotationDeg.toInt()}-f$flipHorizontal"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        val normalized = ((rotationDeg % 360f) + 360f) % 360f
        if (normalized == 0f && !flipHorizontal) return input
        val matrix = Matrix()
        if (flipHorizontal) {
            matrix.postScale(-1f, 1f, input.width / 2f, input.height / 2f)
        }
        if (normalized != 0f) {
            matrix.postRotate(normalized)
        }
        return Bitmap.createBitmap(input, 0, 0, input.width, input.height, matrix, true)
    }
}
