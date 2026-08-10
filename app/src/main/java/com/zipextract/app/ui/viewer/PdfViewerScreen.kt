package com.zipextract.app.ui.viewer

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import android.content.Context
import android.net.Uri
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import androidx.compose.ui.Modifier as ComposeModifier

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfViewerScreen(
    file: File,
    sourceUri: Uri? = null,
    onClose: () -> Unit,
) {
    BackHandler(onBack = onClose)
    val context = LocalContext.current

    var pageCount by remember { mutableStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var rendererHolder by remember { mutableStateOf<PdfRendererHolder?>(null) }
    val listState = rememberLazyListState()
    val openKey = sourceUri?.toString() ?: file.absolutePath

    DisposableEffect(openKey) {
        val holder = runCatching {
            PdfRendererHolder.open(context, file, sourceUri)
        }.getOrElse {
            error = it.message ?: "Gagal membuka PDF"
            loading = false
            null
        }
        rendererHolder = holder
        pageCount = holder?.pageCount ?: 0
        loading = false
        onDispose {
            holder?.close()
            rendererHolder = null
        }
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
                        if (pageCount > 0) {
                            Text(
                                text = "$pageCount halaman",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Kembali")
                    }
                },
            )
        },
    ) { padding ->
        Box(
            ComposeModifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
            contentAlignment = Alignment.Center,
        ) {
            when {
                loading -> CircularProgressIndicator()
                error != null -> Text(error ?: "Error", color = MaterialTheme.colorScheme.error)
                rendererHolder == null || pageCount == 0 -> {
                    Text("PDF kosong atau tidak bisa dibaca")
                }
                else -> {
                    LazyColumn(
                        state = listState,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = PaddingValues(12.dp),
                        modifier = ComposeModifier.fillMaxSize(),
                    ) {
                        itemsIndexed((0 until pageCount).toList()) { index, _ ->
                            Column {
                                Text(
                                    text = "Halaman ${index + 1} / $pageCount",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = ComposeModifier.padding(bottom = 6.dp),
                                )
                                PdfPage(
                                    holder = rendererHolder!!,
                                    pageIndex = index,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PdfPage(
    holder: PdfRendererHolder,
    pageIndex: Int,
) {
    val density = LocalDensity.current
    var bitmap by remember(pageIndex, holder) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(pageIndex) { mutableStateOf(false) }

    LaunchedEffect(pageIndex, holder) {
        val rendered = withContext(Dispatchers.IO) {
            runCatching {
                holder.renderPage(pageIndex, targetWidthPx = with(density) { 900.dp.roundToPx() })
            }.getOrNull()
        }
        if (rendered == null) failed = true else bitmap = rendered
    }

    Box(
        ComposeModifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface),
        contentAlignment = Alignment.Center,
    ) {
        when {
            failed -> Text("Gagal merender halaman ${pageIndex + 1}")
            bitmap == null -> {
                Row(
                    ComposeModifier.padding(24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator()
                }
            }
            else -> {
                Image(
                    bitmap = bitmap!!.asImageBitmap(),
                    contentDescription = "Halaman ${pageIndex + 1}",
                    contentScale = ContentScale.FillWidth,
                    modifier = ComposeModifier.fillMaxWidth(),
                )
            }
        }
    }
}

private class PdfRendererHolder private constructor(
    private val pfd: ParcelFileDescriptor,
) {
    private val renderer = PdfRenderer(pfd)
    private val lock = Any()

    val pageCount: Int get() = renderer.pageCount

    companion object {
        fun open(context: Context, file: File, sourceUri: Uri? = null): PdfRendererHolder {
            val descriptor = when {
                file.exists() && file.isFile && file.length() > 0L -> {
                    ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                }
                sourceUri != null -> {
                    context.contentResolver.openFileDescriptor(sourceUri, "r")
                }
                else -> null
            } ?: throw IllegalStateException("Gagal membuka PDF")
            return PdfRendererHolder(descriptor)
        }
    }

    fun renderPage(index: Int, targetWidthPx: Int): Bitmap {
        synchronized(lock) {
            renderer.openPage(index).use { page ->
                val width = targetWidthPx.coerceAtLeast(1)
                val height = ((page.height.toFloat() / page.width.toFloat()) * width)
                    .toInt()
                    .coerceAtLeast(1)
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                return bitmap
            }
        }
    }

    fun close() {
        synchronized(lock) {
            runCatching { renderer.close() }
            runCatching { pfd.close() }
        }
    }
}
