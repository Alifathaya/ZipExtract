package com.zipextract.app.ui.viewer

import com.zipextract.app.R

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zipextract.app.data.BitmapEditor
import com.zipextract.app.data.FileActions
import com.zipextract.app.data.StrokeData
import com.zipextract.app.data.StrokePoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max

private enum class EditorTool { NONE, CROP, PEN }

private data class PenColor(val color: Color, val argb: Int)

private val penColors = listOf(
    PenColor(Color(0xFFE11D48), 0xFFE11D48.toInt()),
    PenColor(Color(0xFF2563EB), 0xFF2563EB.toInt()),
    PenColor(Color(0xFF111827), 0xFF111827.toInt()),
    PenColor(Color(0xFFF59E0B), 0xFFF59E0B.toInt()),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaEditorScreen(
    title: String,
    sourceFile: File? = null,
    sourceBitmap: Bitmap? = null,
    onClose: () -> Unit,
) {
    BackHandler(onBack = onClose)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var working by remember { mutableStateOf<Bitmap?>(null) }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var tool by remember { mutableStateOf(EditorTool.NONE) }
    var selectedPen by remember { mutableStateOf(penColors.first()) }
    var strokeWidth by remember { mutableFloatStateOf(8f) }

    var cropLeft by remember { mutableFloatStateOf(0.1f) }
    var cropTop by remember { mutableFloatStateOf(0.1f) }
    var cropRight by remember { mutableFloatStateOf(0.9f) }
    var cropBottom by remember { mutableFloatStateOf(0.9f) }

    val strokes = remember { mutableStateListOf<StrokeData>() }
    var currentStroke by remember { mutableStateOf<List<StrokePoint>?>(null) }

    LaunchedEffect(sourceFile, sourceBitmap) {
        loading = true
        error = null
        val loaded = withContext(Dispatchers.IO) {
            when {
                sourceBitmap != null -> BitmapEditor.scaleForEditing(sourceBitmap)
                sourceFile != null && sourceFile.exists() -> {
                    val decoded = BitmapFactory.decodeFile(sourceFile.absolutePath)
                        ?: return@withContext null
                    BitmapEditor.scaleForEditing(decoded)
                }
                else -> null
            }
        }
        if (loaded == null) {
            error = context.getString(R.string.edit_load_failed)
        } else {
            working = loaded
        }
        loading = false
    }

    fun exportBitmap(): Bitmap? {
        val base = working ?: return null
        return BitmapEditor.drawStrokes(base, strokes.toList())
    }

    fun applyCrop() {
        val base = working ?: return
        val withInk = BitmapEditor.drawStrokes(base, strokes.toList())
        val cropped = BitmapEditor.crop(withInk, cropLeft, cropTop, cropRight, cropBottom)
        if (withInk !== base && withInk !== cropped) withInk.recycle()
        working = cropped
        strokes.clear()
        currentStroke = null
        cropLeft = 0.08f
        cropTop = 0.08f
        cropRight = 0.92f
        cropBottom = 0.92f
        tool = EditorTool.NONE
        Toast.makeText(context, context.getString(R.string.edit_crop_applied), Toast.LENGTH_SHORT).show()
    }

    fun saveAndMaybeShare(share: Boolean) {
        val bitmap = exportBitmap() ?: return
        busy = true
        scope.launch {
            val saved = withContext(Dispatchers.IO) {
                BitmapEditor.saveToPictures(context, bitmap, title)
            }
            busy = false
            if (saved == null) {
                Toast.makeText(context, context.getString(R.string.edit_save_failed), Toast.LENGTH_SHORT).show()
                return@launch
            }
            Toast.makeText(context, context.getString(R.string.edit_saved, saved.name), Toast.LENGTH_SHORT).show()
            if (share) FileActions.shareFile(context, saved)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(text = stringResource(R.string.edit), style = MaterialTheme.typography.titleLarge)
                        Text(
                            text = title,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(
                        onClick = { saveAndMaybeShare(share = false) },
                        enabled = working != null && !busy,
                    ) {
                        Icon(Icons.Default.Save, contentDescription = stringResource(R.string.save))
                    }
                    IconButton(
                        onClick = { saveAndMaybeShare(share = true) },
                        enabled = working != null && !busy,
                    ) {
                        Icon(Icons.Default.Share, contentDescription = stringResource(R.string.share))
                    }
                },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FilterChip(
                            selected = tool == EditorTool.CROP,
                            onClick = {
                                tool = if (tool == EditorTool.CROP) EditorTool.NONE else EditorTool.CROP
                            },
                            label = { Text(stringResource(R.string.edit_crop)) },
                            leadingIcon = { Icon(Icons.Default.Crop, null, Modifier.size(18.dp)) },
                        )
                        FilterChip(
                            selected = tool == EditorTool.PEN,
                            onClick = {
                                tool = if (tool == EditorTool.PEN) EditorTool.NONE else EditorTool.PEN
                            },
                            label = { Text(stringResource(R.string.edit_pen)) },
                            leadingIcon = { Icon(Icons.Default.Edit, null, Modifier.size(18.dp)) },
                        )
                        IconButton(
                            onClick = {
                                if (currentStroke != null) currentStroke = null
                                else if (strokes.isNotEmpty()) strokes.removeAt(strokes.lastIndex)
                            },
                            enabled = strokes.isNotEmpty() || currentStroke != null,
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = stringResource(R.string.undo))
                        }
                    }

                    if (tool == EditorTool.PEN) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            penColors.forEach { option ->
                                Box(
                                    modifier = Modifier
                                        .size(if (selectedPen == option) 30.dp else 24.dp)
                                        .clip(CircleShape)
                                        .background(option.color)
                                        .border(
                                            width = if (selectedPen == option) 2.dp else 0.dp,
                                            color = Color.White,
                                            shape = CircleShape,
                                        )
                                        .clickable { selectedPen = option },
                                )
                            }
                            Spacer(modifier = Modifier.width(4.dp))
                            TextButton(onClick = { strokeWidth = 5f }) { Text(stringResource(R.string.edit_stroke_thin)) }
                            TextButton(onClick = { strokeWidth = 8f }) { Text(stringResource(R.string.edit_stroke_medium)) }
                            TextButton(onClick = { strokeWidth = 14f }) { Text(stringResource(R.string.edit_stroke_thick)) }
                        }
                    }

                    if (tool == EditorTool.CROP) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            TextButton(
                                onClick = {
                                    cropLeft = 0.1f
                                    cropTop = 0.1f
                                    cropRight = 0.9f
                                    cropBottom = 0.9f
                                },
                                modifier = Modifier.weight(1f),
                            ) { Text(stringResource(R.string.edit_reset_crop)) }
                            Button(
                                onClick = { applyCrop() },
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(Icons.Default.Check, contentDescription = null)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(stringResource(R.string.edit_apply))
                            }
                        }
                        Text(
                            text = stringResource(R.string.edit_crop_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.9f)),
            contentAlignment = Alignment.Center,
        ) {
            when {
                loading || busy -> CircularProgressIndicator()
                error != null -> Text(error ?: stringResource(R.string.error_generic), color = MaterialTheme.colorScheme.error)
                working != null -> {
                    EditorCanvas(
                        bitmap = working!!,
                        tool = tool,
                        strokes = strokes,
                        currentStroke = currentStroke,
                        penColor = selectedPen.color,
                        strokeWidth = strokeWidth,
                        cropLeft = cropLeft,
                        cropTop = cropTop,
                        cropRight = cropRight,
                        cropBottom = cropBottom,
                        onCropChange = { l, t, r, b ->
                            cropLeft = l
                            cropTop = t
                            cropRight = r
                            cropBottom = b
                        },
                        onStrokeStart = { point -> currentStroke = listOf(point) },
                        onStrokeMove = { point ->
                            currentStroke = (currentStroke ?: emptyList()) + point
                        },
                        onStrokeEnd = { canvasWidth ->
                            val pts = currentStroke
                            currentStroke = null
                            if (pts != null && pts.size >= 2) {
                                strokes += StrokeData(
                                    points = pts,
                                    colorArgb = selectedPen.argb,
                                    widthPx = strokeWidth,
                                    canvasWidth = canvasWidth,
                                )
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun EditorCanvas(
    bitmap: Bitmap,
    tool: EditorTool,
    strokes: List<StrokeData>,
    currentStroke: List<StrokePoint>?,
    penColor: Color,
    strokeWidth: Float,
    cropLeft: Float,
    cropTop: Float,
    cropRight: Float,
    cropBottom: Float,
    onCropChange: (Float, Float, Float, Float) -> Unit,
    onStrokeStart: (StrokePoint) -> Unit,
    onStrokeMove: (StrokePoint) -> Unit,
    onStrokeEnd: (canvasWidth: Float) -> Unit,
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp),
        contentAlignment = Alignment.Center,
    ) {
        val maxW = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val maxH = constraints.maxHeight.toFloat().coerceAtLeast(1f)
        val imageAspect = bitmap.width.toFloat() / bitmap.height.toFloat().coerceAtLeast(1f)
        val boxAspect = maxW / maxH
        val drawW: Float
        val drawH: Float
        if (imageAspect > boxAspect) {
            drawW = maxW
            drawH = maxW / imageAspect
        } else {
            drawH = maxH
            drawW = maxH * imageAspect
        }
        val density = LocalDensity.current
        val drawWdp = with(density) { drawW.toDp() }
        val drawHdp = with(density) { drawH.toDp() }

        fun toNorm(offset: Offset): StrokePoint? {
            if (offset.x < 0f || offset.x > drawW || offset.y < 0f || offset.y > drawH) return null
            return StrokePoint(
                x = (offset.x / drawW).coerceIn(0f, 1f),
                y = (offset.y / drawH).coerceIn(0f, 1f),
            )
        }

        Box(
            modifier = Modifier
                .width(drawWdp)
                .height(drawHdp)
                .then(
                    if (tool == EditorTool.PEN) {
                        Modifier.pointerInput(tool, penColor, strokeWidth, drawW, drawH) {
                            detectDragGestures(
                                onDragStart = { start -> toNorm(start)?.let(onStrokeStart) },
                                onDrag = { change, _ ->
                                    change.consume()
                                    toNorm(change.position)?.let(onStrokeMove)
                                },
                                onDragEnd = { onStrokeEnd(drawW) },
                                onDragCancel = { onStrokeEnd(drawW) },
                            )
                        }
                    } else {
                        Modifier
                    },
                ),
        ) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                modifier = Modifier.fillMaxSize(),
            )

            Canvas(modifier = Modifier.fillMaxSize()) {
                fun strokePath(points: List<StrokePoint>): Path {
                    val path = Path()
                    if (points.isEmpty()) return path
                    path.moveTo(points.first().x * drawW, points.first().y * drawH)
                    for (i in 1 until points.size) {
                        path.lineTo(points[i].x * drawW, points[i].y * drawH)
                    }
                    return path
                }

                strokes.forEach { stroke ->
                    drawPath(
                        path = strokePath(stroke.points),
                        color = Color(stroke.colorArgb),
                        style = Stroke(
                            width = stroke.widthPx * (drawW / stroke.canvasWidth.coerceAtLeast(1f)),
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round,
                        ),
                    )
                }
                currentStroke?.let { pts ->
                    drawPath(
                        path = strokePath(pts),
                        color = penColor,
                        style = Stroke(
                            width = strokeWidth,
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round,
                        ),
                    )
                }

                if (tool == EditorTool.CROP) {
                    val cropRect = Rect(
                        cropLeft * drawW,
                        cropTop * drawH,
                        cropRight * drawW,
                        cropBottom * drawH,
                    )
                    drawRect(Color.Black.copy(alpha = 0.55f), Offset(0f, 0f), Size(drawW, max(0f, cropRect.top)))
                    drawRect(
                        Color.Black.copy(alpha = 0.55f),
                        Offset(0f, cropRect.bottom),
                        Size(drawW, max(0f, drawH - cropRect.bottom)),
                    )
                    drawRect(
                        Color.Black.copy(alpha = 0.55f),
                        Offset(0f, cropRect.top),
                        Size(max(0f, cropRect.left), cropRect.height),
                    )
                    drawRect(
                        Color.Black.copy(alpha = 0.55f),
                        Offset(cropRect.right, cropRect.top),
                        Size(max(0f, drawW - cropRect.right), cropRect.height),
                    )
                    drawRect(Color.White, cropRect.topLeft, cropRect.size, style = Stroke(width = 3f))
                    val handles = cropHandlePositions(cropRect)
                    handles.forEach { center ->
                        drawCircle(Color.White.copy(alpha = 0.35f), radius = 28f, center = center)
                        drawCircle(Color.White, radius = 18f, center = center)
                        drawCircle(Color(0xFF2563EB), radius = 12f, center = center)
                    }
                }
            }

            if (tool == EditorTool.CROP) {
                CropGestureLayer(
                    drawW = drawW,
                    drawH = drawH,
                    cropLeft = cropLeft,
                    cropTop = cropTop,
                    cropRight = cropRight,
                    cropBottom = cropBottom,
                    onCropChange = onCropChange,
                )
            }
        }
    }
}

private fun cropHandlePositions(cropRect: Rect): List<Offset> {
    val midX = (cropRect.left + cropRect.right) / 2f
    val midY = (cropRect.top + cropRect.bottom) / 2f
    return listOf(
        cropRect.topLeft, // 0 TL
        Offset(midX, cropRect.top), // 1 top-mid
        Offset(cropRect.right, cropRect.top), // 2 TR
        Offset(cropRect.left, midY), // 3 mid-left
        Offset(cropRect.right, midY), // 4 mid-right
        Offset(cropRect.left, cropRect.bottom), // 5 BL
        Offset(midX, cropRect.bottom), // 6 bottom-mid
        cropRect.bottomRight, // 7 BR
    )
}

@Composable
private fun CropGestureLayer(
    drawW: Float,
    drawH: Float,
    cropLeft: Float,
    cropTop: Float,
    cropRight: Float,
    cropBottom: Float,
    onCropChange: (Float, Float, Float, Float) -> Unit,
) {
    val leftState = rememberUpdatedState(cropLeft)
    val topState = rememberUpdatedState(cropTop)
    val rightState = rememberUpdatedState(cropRight)
    val bottomState = rememberUpdatedState(cropBottom)
    val minSize = 0.08f
    val hitRadius = 120f

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(drawW, drawH) {
                var active = -1 // 0..7 handles, 8 = move whole box
                var l = leftState.value
                var t = topState.value
                var r = rightState.value
                var b = bottomState.value

                detectDragGestures(
                    onDragStart = { start ->
                        l = leftState.value
                        t = topState.value
                        r = rightState.value
                        b = bottomState.value
                        val cropRect = Rect(l * drawW, t * drawH, r * drawW, b * drawH)
                        val handles = cropHandlePositions(cropRect)
                        active = handles.indices.minByOrNull { i ->
                            (handles[i] - start).getDistance()
                        }?.takeIf { i -> (handles[i] - start).getDistance() <= hitRadius } ?: -1

                        if (active < 0 && cropRect.contains(start)) {
                            active = 8
                        }
                    },
                    onDragEnd = { active = -1 },
                    onDragCancel = { active = -1 },
                    onDrag = { change, dragAmount ->
                        if (active < 0) return@detectDragGestures
                        change.consume()
                        val dx = dragAmount.x / drawW
                        val dy = dragAmount.y / drawH

                        when (active) {
                            0 -> { // TL
                                l = (l + dx).coerceIn(0f, r - minSize)
                                t = (t + dy).coerceIn(0f, b - minSize)
                            }
                            1 -> { // top-mid
                                t = (t + dy).coerceIn(0f, b - minSize)
                            }
                            2 -> { // TR
                                r = (r + dx).coerceIn(l + minSize, 1f)
                                t = (t + dy).coerceIn(0f, b - minSize)
                            }
                            3 -> { // mid-left
                                l = (l + dx).coerceIn(0f, r - minSize)
                            }
                            4 -> { // mid-right
                                r = (r + dx).coerceIn(l + minSize, 1f)
                            }
                            5 -> { // BL
                                l = (l + dx).coerceIn(0f, r - minSize)
                                b = (b + dy).coerceIn(t + minSize, 1f)
                            }
                            6 -> { // bottom-mid
                                b = (b + dy).coerceIn(t + minSize, 1f)
                            }
                            7 -> { // BR
                                r = (r + dx).coerceIn(l + minSize, 1f)
                                b = (b + dy).coerceIn(t + minSize, 1f)
                            }
                            8 -> { // move whole crop box
                                val width = r - l
                                val height = b - t
                                val nl = (l + dx).coerceIn(0f, 1f - width)
                                val nt = (t + dy).coerceIn(0f, 1f - height)
                                l = nl
                                t = nt
                                r = nl + width
                                b = nt + height
                            }
                        }
                        onCropChange(l, t, r, b)
                    },
                )
            },
    )
}
