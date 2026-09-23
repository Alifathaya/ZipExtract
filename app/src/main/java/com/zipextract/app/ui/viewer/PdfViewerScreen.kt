package com.zipextract.app.ui.viewer

import com.zipextract.app.R

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zipextract.app.data.FileActions
import com.zipextract.app.data.PdfPasswordHelper
import com.zipextract.app.data.SharePasswordExporter
import com.zipextract.app.ui.ShareChoiceDialog
import com.zipextract.app.ui.ShareGateDialog
import com.zipextract.app.ui.SharePasswordPromptDialog
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.LinkedHashMap
import androidx.compose.ui.graphics.Color as ComposeColor

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfViewerScreen(
    file: File,
    sourceUri: Uri? = null,
    onClose: () -> Unit,
    onDelete: () -> Unit,
) {
    BackHandler(onBack = onClose)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val zoomState = rememberZoomState()
    val density = LocalDensity.current
    val baseWidthPx = with(density) { 900.dp.roundToPx() }

    // Debounced render scale so pinch stays smooth; bitmaps refresh after zoom settles.
    var renderScale by remember { mutableFloatStateOf(1f) }
    LaunchedEffect(zoomState.scale) {
        delay(120)
        renderScale = zoomState.scale.coerceIn(1f, 3f)
    }
    val displayWidthPx = remember(baseWidthPx, renderScale) {
        (baseWidthPx * renderScale).roundToInt().coerceIn(1, 4096)
    }

    var pageCount by remember { mutableStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var rendererHolder by remember { mutableStateOf<PdfRendererHolder?>(null) }
    val listState = rememberLazyListState()
    val openKey = sourceUri?.toString() ?: file.absolutePath

    var confirmDelete by remember { mutableStateOf(false) }
    var shareGate by remember { mutableStateOf<ShareGateDialog?>(null) }
    var sharing by remember { mutableStateOf(false) }
    var editBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var editTitle by remember { mutableStateOf("") }
    var preparingEdit by remember { mutableStateOf(false) }

    var sourceFile by remember { mutableStateOf<File?>(null) }
    var renderFile by remember { mutableStateOf<File?>(null) }
    var showPasswordDialog by remember { mutableStateOf(false) }
    var passwordInput by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var passwordError by remember { mutableStateOf<String?>(null) }
    var unlocking by remember { mutableStateOf(false) }

    LaunchedEffect(openKey) {
        loading = true
        error = null
        showPasswordDialog = false
        passwordInput = ""
        passwordError = null
        renderFile = null
        sourceFile = null
        val prepared = withContext(Dispatchers.IO) {
            runCatching {
                PdfPasswordHelper.materializeLocalFile(context, file, sourceUri)
            }
        }
        val local = prepared.getOrElse {
            error = it.message ?: context.getString(R.string.pdf_open_failed)
            loading = false
            return@LaunchedEffect
        }
        sourceFile = local
        when (
            val probe = withContext(Dispatchers.IO) { PdfPasswordHelper.probe(context, local) }
        ) {
            PdfPasswordHelper.ProbeResult.Openable -> {
                val canRender = withContext(Dispatchers.IO) {
                    runCatching {
                        PdfRendererHolder.open(context, local, null).close()
                    }.isSuccess
                }
                if (canRender) {
                    renderFile = local
                } else {
                    // PdfRenderer failed — often encryption Android cannot open.
                    showPasswordDialog = true
                    loading = false
                }
            }
            PdfPasswordHelper.ProbeResult.NeedsPassword -> {
                showPasswordDialog = true
                loading = false
            }
            is PdfPasswordHelper.ProbeResult.Failed -> {
                error = probe.message
                loading = false
            }
        }
    }

    DisposableEffect(renderFile) {
        val target = renderFile
        if (target == null) {
            rendererHolder = null
            pageCount = 0
            onDispose { }
        } else {
            val holder = runCatching {
                PdfRendererHolder.open(context, target, null)
            }.getOrElse {
                if (PdfPasswordHelper.isPasswordRelated(it)) {
                    showPasswordDialog = true
                    error = null
                } else {
                    error = it.message ?: context.getString(R.string.pdf_open_failed)
                }
                loading = false
                null
            }
            rendererHolder = holder
            pageCount = holder?.pageCount ?: 0
            loading = false
            onDispose {
                holder?.close()
                if (rendererHolder === holder) {
                    rendererHolder = null
                }
            }
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

    fun submitPdfPassword() {
        val local = sourceFile ?: return
        val password = passwordInput
        if (password.isBlank()) {
            passwordError = context.getString(R.string.pdf_password_required)
            return
        }
        unlocking = true
        passwordError = null
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { PdfPasswordHelper.unlockToCache(context, local, password) }
            }
            unlocking = false
            result.fold(
                onSuccess = { unlocked ->
                    showPasswordDialog = false
                    passwordInput = ""
                    passwordError = null
                    loading = true
                    renderFile = unlocked
                },
                onFailure = { err ->
                    passwordError = when {
                        err is InvalidPasswordException || PdfPasswordHelper.isPasswordRelated(err) ->
                            context.getString(R.string.pdf_wrong_password)
                        else -> err.message ?: context.getString(R.string.pdf_open_failed)
                    }
                },
            )
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
                Toast.makeText(context, context.getString(R.string.pdf_prepare_edit_failed), Toast.LENGTH_SHORT).show()
            } else {
                editTitle = context.getString(R.string.pdf_page_title, file.name, page + 1)
                editBitmap = bitmap
            }
        }
    }

    if (showPasswordDialog) {
        AlertDialog(
            onDismissRequest = {
                if (!unlocking) {
                    showPasswordDialog = false
                    onClose()
                }
            },
            title = { Text(stringResource(R.string.pdf_password_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.pdf_password_required))
                    OutlinedTextField(
                        value = passwordInput,
                        onValueChange = {
                            passwordInput = it
                            passwordError = null
                        },
                        label = { Text(stringResource(R.string.pdf_password_hint)) },
                        singleLine = true,
                        enabled = !unlocking,
                        isError = passwordError != null,
                        visualTransformation = if (passwordVisible) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        trailingIcon = {
                            IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                Icon(
                                    if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = null,
                                )
                            }
                        },
                        supportingText = passwordError?.let { msg ->
                            { Text(msg, color = MaterialTheme.colorScheme.error) }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (unlocking) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp))
                            Text(stringResource(R.string.pdf_unlocking))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { submitPdfPassword() },
                    enabled = !unlocking && passwordInput.isNotBlank(),
                ) {
                    Text(stringResource(R.string.pdf_open))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showPasswordDialog = false
                        onClose()
                    },
                    enabled = !unlocking,
                ) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
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

    when (shareGate) {
        ShareGateDialog.Choice -> ShareChoiceDialog(
            onDismiss = { shareGate = null },
            onShareNormal = {
                shareGate = null
                if (!FileActions.shareFile(context, file)) {
                    Toast.makeText(context, context.getString(R.string.pdf_share_failed), Toast.LENGTH_SHORT).show()
                }
            },
            onShareWithPassword = { shareGate = ShareGateDialog.Password },
        )
        ShareGateDialog.Password -> SharePasswordPromptDialog(
            usesPdfPassword = true,
            onDismiss = { shareGate = null },
            onConfirm = { password ->
                shareGate = null
                sharing = true
                scope.launch {
                    val ok = withContext(Dispatchers.IO) {
                        runCatching {
                            val locked = SharePasswordExporter.exportLocked(context, file, password)
                            withContext(Dispatchers.Main) {
                                FileActions.shareFile(context, locked)
                            }
                        }.getOrDefault(false)
                    }
                    sharing = false
                    if (!ok) {
                        Toast.makeText(
                            context,
                            context.getString(R.string.share_password_failed),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
            },
        )
        null -> Unit
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = file.name,
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
                    IconButton(
                        onClick = { openEditorForVisiblePage() },
                        enabled = !preparingEdit && pageCount > 0,
                    ) {
                        Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.pdf_edit_page))
                    }
                    IconButton(
                        onClick = { shareGate = ShareGateDialog.Choice },
                        enabled = !sharing,
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
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
            contentAlignment = Alignment.Center,
        ) {
                when {
                loading || preparingEdit || unlocking -> CircularProgressIndicator()
                showPasswordDialog -> {
                    // Password dialog is shown above; keep a calm empty canvas behind it.
                }
                error != null -> Text(error ?: stringResource(R.string.error_generic), color = MaterialTheme.colorScheme.error)
                rendererHolder == null || pageCount == 0 -> {
                    Text(stringResource(R.string.pdf_empty))
                }
                else -> {
                    BoxWithConstraints(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(zoomState) {
                                awaitEachGesture {
                                    awaitFirstDown(requireUnconsumed = false)
                                    do {
                                        val event = awaitPointerEvent()
                                        val pressedCount = event.changes.count { it.pressed }
                                        val zoomChange = event.calculateZoom()
                                        val panChange = event.calculatePan()
                                        when {
                                            pressedCount >= 2 &&
                                                (abs(zoomChange - 1f) > 0.001f || panChange != Offset.Zero) -> {
                                                if (abs(zoomChange - 1f) > 0.001f) {
                                                    zoomState.onZoom(zoomChange)
                                                }
                                                if (zoomState.isZoomed && panChange != Offset.Zero) {
                                                    // Pinch-pan stays horizontal; vertical page scroll uses one finger.
                                                    zoomState.onTransform(Offset(panChange.x, 0f), 1f)
                                                }
                                                val maxPan = size.width * (zoomState.scale - 1f) / 2f
                                                zoomState.clampOffset(maxX = maxPan.coerceAtLeast(0f), maxY = 0f)
                                                event.changes.forEach {
                                                    if (it.positionChanged()) it.consume()
                                                }
                                            }
                                            pressedCount == 1 &&
                                                zoomState.isZoomed &&
                                                abs(panChange.x) > abs(panChange.y) &&
                                                abs(panChange.x) > 0.5f -> {
                                                zoomState.onTransform(Offset(panChange.x, 0f), 1f)
                                                val maxPan = size.width * (zoomState.scale - 1f) / 2f
                                                zoomState.clampOffset(maxX = maxPan.coerceAtLeast(0f), maxY = 0f)
                                                event.changes.forEach {
                                                    if (it.positionChanged()) it.consume()
                                                }
                                            }
                                        }
                                    } while (event.changes.any { it.pressed })
                                }
                            }
                            .pointerInput(zoomState) {
                                detectTapGestures(
                                    onDoubleTap = { zoomState.toggleDoubleTapZoom() },
                                )
                            },
                    ) {
                        val viewportWidth = maxWidth
                        LaunchedEffect(zoomState.scale, constraints.maxWidth) {
                            val maxPan = constraints.maxWidth * (zoomState.scale - 1f) / 2f
                            zoomState.clampOffset(maxX = maxPan.coerceAtLeast(0f), maxY = 0f)
                        }

                        LazyColumn(
                            state = listState,
                            userScrollEnabled = true,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            contentPadding = PaddingValues(12.dp),
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            itemsIndexed(
                                items = (0 until pageCount).toList(),
                                key = { _, page -> page },
                            ) { index, _ ->
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    Text(
                                        text = stringResource(R.string.pdf_page_of, index + 1, pageCount),
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(bottom = 6.dp),
                                    )
                                    ZoomedPdfPage(
                                        holder = rendererHolder!!,
                                        pageIndex = index,
                                        targetWidthPx = displayWidthPx,
                                        scale = zoomState.scale,
                                        panX = zoomState.offset.x,
                                        viewportWidth = viewportWidth,
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
private fun ZoomedPdfPage(
    holder: PdfRendererHolder,
    pageIndex: Int,
    targetWidthPx: Int,
    scale: Float,
    panX: Float,
    viewportWidth: Dp,
    aspectFallback: Float = 1.414f,
) {
    val aspect = remember(pageIndex, holder) {
        holder.pageAspectRatio(pageIndex) ?: aspectFallback
    }
    val layoutHeight = viewportWidth * aspect * scale

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(layoutHeight)
            .clip(RectangleShape)
            .background(ComposeColor.White),
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = panX
                    transformOrigin = TransformOrigin(0.5f, 0f)
                },
        ) {
            PdfPage(
                holder = holder,
                pageIndex = pageIndex,
                targetWidthPx = targetWidthPx,
                placeholderHeight = viewportWidth * aspect,
            )
        }
    }
}

@Composable
private fun PdfPage(
    holder: PdfRendererHolder,
    pageIndex: Int,
    targetWidthPx: Int,
    placeholderHeight: Dp,
) {
    // Show cached bitmap immediately when revisiting a page.
    var bitmap by remember(pageIndex, holder, targetWidthPx) {
        mutableStateOf(holder.peekPage(pageIndex, targetWidthPx))
    }
    var failed by remember(pageIndex, holder, targetWidthPx) { mutableStateOf(false) }

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
            .height(placeholderHeight)
            .background(ComposeColor.White),
        contentAlignment = Alignment.Center,
    ) {
        when {
            failed -> Text(stringResource(R.string.pdf_render_failed, pageIndex + 1))
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
                    contentDescription = stringResource(R.string.pdf_page_label, pageIndex + 1),
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
            } ?: throw IllegalStateException(context.getString(R.string.pdf_open_failed))
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
            val pageWidth = page.width.coerceAtLeast(1)
            val pageHeight = page.height.coerceAtLeast(1)
            // Cap size to avoid OOM on very large pages while keeping readable detail.
            val width = targetWidthPx.coerceIn(1, 4096)
            val height = ((pageHeight.toFloat() / pageWidth.toFloat()) * width)
                .toInt()
                .coerceIn(1, 8192)
            aspectCache[index] = pageHeight.toFloat() / pageWidth.toFloat()

            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            // Critical: PdfRenderer composites onto transparent pixels. Many PDFs do not paint an
            // opaque page background, so unfilled pixels stay transparent and appear black/blank.
            bitmap.eraseColor(Color.WHITE)
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
