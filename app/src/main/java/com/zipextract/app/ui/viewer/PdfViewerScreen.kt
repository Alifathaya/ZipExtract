package com.zipextract.app.ui.viewer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.widget.Toast
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zipextract.app.data.FileActions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.LinkedHashMap

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfViewerScreen(
    file: File,
    sourceUri: Uri? = null,
    onClose: () -> Unit,
) {
    BackHandler(onBack = onClose)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val zoomState = rememberZoomState()
    val density = LocalDensity.current
    val displayWidthPx = with(density) { 900.dp.roundToPx() }

    var pageCount by remember { mutableStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var rendererHolder by remember { mutableStateOf<PdfRendererHolder?>(null) }
    val listState = rememberLazyListState()
    val openKey = sourceUri?.toString() ?: file.absolutePath

    var editBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var editTitle by remember { mutableStateOf("") }
    var preparingEdit by remember { mutableStateOf(false) }

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

    // Prefetch nearby pages so scrolling back is instant.
    LaunchedEffect(rendererHolder, pageCount, displayWidthPx, listState) {
        val holder = rendererHolder ?: return@LaunchedEffect
        if (pageCount <= 0) return@LaunchedEffect
        snapshotFlow {
            val info = listState.layoutInfo
            val first = info.visibleItemsInfo.firstOrNull()?.index ?: listState.firstVisibleItemIndex
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: first
            first to last
        }
            .distinctUntilChanged()
            .collect { (first, last) ->
                val start = (first - 1).coerceAtLeast(0)
                val end = (last + 1).coerceAtMost(pageCount - 1)
                withContext(Dispatchers.IO) {
                    for (page in start..end) {
                        runCatching { holder.getPage(page, displayWidthPx) }
                    }
                }
            }
    }

    if (editBitmap != null) {
        MediaEditorScreen(
            title = editTitle,
            sourceBitmap = editBitmap,
            onClose = {
                editBitmap?.recycle()
                editBitmap = null
            },
        )
        return
    }

    fun openEditorForVisiblePage() {
        val holder = rendererHolder ?: return
        if (pageCount <= 0) return
        val page = listState.firstVisibleItemIndex.coerceIn(0, pageCount - 1)
        preparingEdit = true
        scope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                runCatching {
                    // Independent copy so editor can recycle without touching the page cache.
                    holder.getPage(page, targetWidthPx = 1600).copy(Bitmap.Config.ARGB_8888, false)
                }.getOrNull()
            }
            preparingEdit = false
            if (bitmap == null) {
                Toast.makeText(context, "Gagal menyiapkan halaman untuk diedit", Toast.LENGTH_SHORT).show()
            } else {
                editTitle = "${file.name} · halaman ${page + 1}"
                editBitmap = bitmap
            }
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
                        Text(
                            text = if (pageCount > 0) {
                                "$pageCount halaman · Edit crop & pen · Share"
                            } else {
                                "Cubit / double-tap untuk zoom"
                            },
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
                actions = {
                    IconButton(
                        onClick = { openEditorForVisiblePage() },
                        enabled = !preparingEdit && pageCount > 0,
                    ) {
                        Icon(Icons.Default.Edit, contentDescription = "Edit halaman")
                    }
                    IconButton(
                        onClick = {
                            if (!FileActions.shareFile(context, file)) {
                                Toast.makeText(context, "Gagal membagikan PDF", Toast.LENGTH_SHORT).show()
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
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
            contentAlignment = Alignment.Center,
        ) {
            when {
                loading || preparingEdit -> CircularProgressIndicator()
                error != null -> Text(error ?: "Error", color = MaterialTheme.colorScheme.error)
                rendererHolder == null || pageCount == 0 -> {
                    Text("PDF kosong atau tidak bisa dibaca")
                }
                else -> {
                    ZoomableBox(
                        zoomState = zoomState,
                        modifier = Modifier.fillMaxSize(),
                        preserveScrollGestures = true,
                    ) {
                        LazyColumn(
                            state = listState,
                            userScrollEnabled = !zoomState.isZoomed,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            contentPadding = PaddingValues(12.dp),
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            itemsIndexed(
                                items = (0 until pageCount).toList(),
                                key = { _, page -> page },
                            ) { index, _ ->
                                Column {
                                    Text(
                                        text = "Halaman ${index + 1} / $pageCount",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(bottom = 6.dp),
                                    )
                                    PdfPage(
                                        holder = rendererHolder!!,
                                        pageIndex = index,
                                        targetWidthPx = displayWidthPx,
                                    )
                                }
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
    targetWidthPx: Int,
) {
    // Show cached bitmap immediately when revisiting a page.
    var bitmap by remember(pageIndex, holder, targetWidthPx) {
        mutableStateOf(holder.peekPage(pageIndex, targetWidthPx))
    }
    var failed by remember(pageIndex, holder, targetWidthPx) { mutableStateOf(false) }
    val aspect = remember(pageIndex, holder) { holder.pageAspectRatio(pageIndex) }

    LaunchedEffect(pageIndex, holder, targetWidthPx) {
        if (bitmap != null) return@LaunchedEffect
        val rendered = withContext(Dispatchers.IO) {
            runCatching { holder.getPage(pageIndex, targetWidthPx) }.getOrNull()
        }
        if (rendered == null) failed = true else bitmap = rendered
    }

    Box(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .then(
                if (bitmap == null && aspect != null) {
                    Modifier.height((900.dp * aspect))
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        when {
            failed -> Text("Gagal merender halaman ${pageIndex + 1}")
            bitmap == null -> {
                Row(
                    Modifier.padding(24.dp),
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
                    modifier = Modifier.fillMaxWidth(),
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
    private val aspectCache = HashMap<Int, Float>()

    // Access-order LRU cache so revisiting pages does not re-render.
    private val pageCache = object : LinkedHashMap<CacheKey, Bitmap>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<CacheKey, Bitmap>?): Boolean {
            if (size <= MAX_CACHED_PAGES) return false
            eldest?.value?.recycle()
            return true
        }
    }

    val pageCount: Int get() = renderer.pageCount

    companion object {
        private const val MAX_CACHED_PAGES = 16

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

    fun pageAspectRatio(index: Int): Float? {
        synchronized(lock) {
            aspectCache[index]?.let { return it }
            return runCatching {
                renderer.openPage(index).use { page ->
                    val ratio = page.height.toFloat() / page.width.toFloat().coerceAtLeast(1f)
                    aspectCache[index] = ratio
                    ratio
                }
            }.getOrNull()
        }
    }

    fun peekPage(index: Int, targetWidthPx: Int): Bitmap? {
        synchronized(lock) {
            return pageCache[CacheKey(index, targetWidthPx)]
        }
    }

    fun getPage(index: Int, targetWidthPx: Int): Bitmap {
        synchronized(lock) {
            pageCache[CacheKey(index, targetWidthPx)]?.let { return it }
            val bitmap = renderPageLocked(index, targetWidthPx)
            pageCache[CacheKey(index, targetWidthPx)] = bitmap
            return bitmap
        }
    }

    /** Kept for callers that need a fresh uncached render. */
    fun renderPage(index: Int, targetWidthPx: Int): Bitmap {
        synchronized(lock) {
            return renderPageLocked(index, targetWidthPx)
        }
    }

    private fun renderPageLocked(index: Int, targetWidthPx: Int): Bitmap {
        renderer.openPage(index).use { page ->
            val width = targetWidthPx.coerceAtLeast(1)
            val height = ((page.height.toFloat() / page.width.toFloat()) * width)
                .toInt()
                .coerceAtLeast(1)
            aspectCache[index] = page.height.toFloat() / page.width.toFloat().coerceAtLeast(1f)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            return bitmap
        }
    }

    fun close() {
        synchronized(lock) {
            pageCache.values.forEach { bmp -> runCatching { bmp.recycle() } }
            pageCache.clear()
            aspectCache.clear()
            runCatching { renderer.close() }
            runCatching { pfd.close() }
        }
    }

    private data class CacheKey(val page: Int, val width: Int)
}
