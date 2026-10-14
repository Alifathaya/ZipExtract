package com.zipextract.app.ui.viewer

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.Crop
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.effect.SpeedChangeEffect
import androidx.media3.exoplayer.ExoPlayer
import com.google.common.collect.ImmutableList
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.ui.PlayerView
import com.zipextract.app.R
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * First in-app video editor. Exports to Movies/FileNest and never writes over the source.
 * Transformer work runs off the UI thread internally; successful output is copied to MediaStore.
 */
@OptIn(UnstableApi::class, ExperimentalMaterial3Api::class)
@Composable
fun VideoEditorScreen(
    sourceFile: File,
    onClose: () -> Unit,
    onExported: () -> Unit = {},
) {
    BackHandler(onBack = onClose)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var durationMs by remember { mutableLongStateOf(0L) }
    var trimStartMs by remember { mutableLongStateOf(0L) }
    var trimEndMs by remember { mutableLongStateOf(0L) }
    var rotation by remember { mutableFloatStateOf(0f) }
    var mirrorHorizontal by remember { mutableStateOf(false) }
    var mirrorVertical by remember { mutableStateOf(false) }
    var cropAmount by remember { mutableFloatStateOf(0f) }
    var speed by remember { mutableFloatStateOf(1f) }
    var overlayText by remember { mutableStateOf("") }
    var muteAudio by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    var exportProgress by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(sourceFile.absolutePath) {
        durationMs = withContext(Dispatchers.IO) {
            runCatching {
                MediaMetadataRetriever().use { retriever ->
                    retriever.setDataSource(sourceFile.absolutePath)
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                        ?.toLongOrNull() ?: 0L
                }
            }.getOrDefault(0L)
        }
        trimEndMs = durationMs
    }

    val player = remember(sourceFile.absolutePath) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(sourceFile.toURI().toString()))
            prepare()
            playWhenReady = false
        }
    }
    DisposableEffect(player) {
        onDispose { player.release() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.video_editor_title)) },
                navigationIcon = {
                    IconButton(onClick = onClose, enabled = !exporting) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(sourceFile.name, style = MaterialTheme.typography.titleSmall)
            AndroidView(
                modifier = Modifier.fillMaxWidth().height(220.dp),
                factory = { viewContext ->
                    PlayerView(viewContext).apply {
                        this.player = player
                        useController = true
                    }
                },
                update = { it.player = player },
            )

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.video_editor_trim), style = MaterialTheme.typography.titleMedium)
                    Text("${formatTime(trimStartMs)} — ${formatTime(trimEndMs)} / ${formatTime(durationMs)}")
                    Text(stringResource(R.string.video_editor_start))
                    Slider(
                        value = trimStartMs.toFloat(),
                        onValueChange = { trimStartMs = it.toLong().coerceIn(0L, (trimEndMs - 500L).coerceAtLeast(0L)) },
                        valueRange = 0f..durationMs.coerceAtLeast(1000L).toFloat(),
                        enabled = durationMs > 0 && !exporting,
                    )
                    Text(stringResource(R.string.video_editor_end))
                    Slider(
                        value = trimEndMs.toFloat().coerceIn(0f, durationMs.coerceAtLeast(1000L).toFloat()),
                        onValueChange = { trimEndMs = it.toLong().coerceIn(trimStartMs + 500L, durationMs) },
                        valueRange = 0f..durationMs.coerceAtLeast(1000L).toFloat(),
                        enabled = durationMs > 0 && !exporting,
                    )
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.video_editor_transform), style = MaterialTheme.typography.titleMedium)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = mirrorHorizontal,
                            onClick = { mirrorHorizontal = !mirrorHorizontal },
                            label = { Text(stringResource(R.string.video_editor_mirror_h)) },
                            enabled = !exporting,
                        )
                        FilterChip(
                            selected = mirrorVertical,
                            onClick = { mirrorVertical = !mirrorVertical },
                            label = { Text(stringResource(R.string.video_editor_mirror_v)) },
                            enabled = !exporting,
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.video_editor_rotation))
                        listOf(0f, 90f, 180f, 270f).forEach { degrees ->
                            FilterChip(
                                selected = rotation == degrees,
                                onClick = { rotation = degrees },
                                label = { Text("${degrees.toInt()}°") },
                                enabled = !exporting,
                            )
                        }
                    }
                    Text(stringResource(R.string.video_editor_crop, (cropAmount * 100).toInt()))
                    Slider(
                        value = cropAmount,
                        onValueChange = { cropAmount = it },
                        valueRange = 0f..0.3f,
                        enabled = !exporting,
                    )
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.video_editor_text_overlay), style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        value = overlayText,
                        onValueChange = { overlayText = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.video_editor_text_hint)) },
                        enabled = !exporting,
                        singleLine = false,
                    )
                    Text(stringResource(R.string.video_editor_text_note), style = MaterialTheme.typography.bodySmall)
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.video_editor_audio_speed), style = MaterialTheme.typography.titleMedium)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.video_editor_mute), modifier = Modifier.weight(1f))
                        Switch(checked = muteAudio, onCheckedChange = { muteAudio = it }, enabled = !exporting)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.video_editor_speed))
                        listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f).forEach { factor ->
                            FilterChip(
                                selected = speed == factor,
                                onClick = { speed = factor },
                                label = { Text("${factor}x") },
                                enabled = !exporting,
                            )
                        }
                    }
                }
            }

            if (exporting) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.video_editor_exporting))
            }

            Button(
                onClick = {
                    if (!sourceFile.exists() || durationMs <= 0L || trimEndMs <= trimStartMs) {
                        Toast.makeText(context, context.getString(R.string.video_editor_invalid_trim), Toast.LENGTH_LONG).show()
                        return@Button
                    }
                    exporting = true
                    exportProgress = 0f
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            runCatching {
                                val tempDir = File(context.cacheDir, "video-editor").apply { mkdirs() }
                                val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                                val base = sourceFile.nameWithoutExtension.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "video" }
                                val tempOutput = File(tempDir, "${base}_edit_${stamp}.mp4")
                                tempOutput.delete()

                                val clipping = MediaItem.ClippingConfiguration.Builder()
                                    .setStartPositionMs(trimStartMs)
                                    .setEndPositionMs(trimEndMs)
                                    .build()
                                val input = MediaItem.Builder()
                                    .setUri(sourceFile.toURI().toString())
                                    .setClippingConfiguration(clipping)
                                    .build()

                                val effects = mutableListOf<androidx.media3.common.Effect>()
                                if (rotation != 0f || mirrorHorizontal || mirrorVertical) {
                                    effects += ScaleAndRotateTransformation.Builder()
                                        .setScale(if (mirrorHorizontal) -1f else 1f, if (mirrorVertical) -1f else 1f)
                                        .setRotationDegrees(rotation)
                                        .build()
                                }
                                if (cropAmount > 0f) {
                                    val edge = 1f - cropAmount
                                    effects += Crop(-edge, edge, -edge, edge)
                                }
                                if (overlayText.isNotBlank()) {
                                    val bitmap = createTextOverlay(overlayText)
                                    effects += OverlayEffect(ImmutableList.of(BitmapOverlay.createStaticBitmapOverlay(bitmap)))
                                }
                                if (speed != 1f) effects += SpeedChangeEffect(speed)

                                // Keep audio speed aligned with the video timestamp effect.
                                val audioProcessors = if (speed != 1f && !muteAudio) {
                                    listOf(SonicAudioProcessor().apply { setSpeed(speed) })
                                } else {
                                    emptyList()
                                }
                                val edited = EditedMediaItem.Builder(input)
                                    .setEffects(Effects(audioProcessors, effects))
                                    .setRemoveAudio(muteAudio)
                                    .build()

                                suspendExport(context, edited, tempOutput.absolutePath)
                                val published = publishVideo(context, tempOutput, "${base}_edited_${stamp}.mp4")
                                tempOutput.delete()
                                published
                            }
                        }
                        exporting = false
                        result.onSuccess {
                            exportProgress = 1f
                            Toast.makeText(context, context.getString(R.string.video_editor_export_success, it.name), Toast.LENGTH_LONG).show()
                            onExported()
                        }.onFailure {
                            Toast.makeText(context, context.getString(R.string.video_editor_export_failed, it.message ?: "unknown"), Toast.LENGTH_LONG).show()
                        }
                    }
                },
                enabled = !exporting && durationMs > 0L,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (exporting) {
                    CircularProgressIndicator()
                    Spacer(Modifier.padding(4.dp))
                }
                Text(stringResource(R.string.video_editor_export))
            }
            Text(stringResource(R.string.video_editor_original_kept), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@OptIn(UnstableApi::class)
private suspend fun suspendExport(context: Context, edited: EditedMediaItem, outputPath: String) {
    kotlinx.coroutines.suspendCancellableCoroutine<Unit> { continuation ->
        val transformer = Transformer.Builder(context)
            .setVideoMimeType(androidx.media3.common.MimeTypes.VIDEO_H264)
            .setAudioMimeType(androidx.media3.common.MimeTypes.AUDIO_AAC)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: androidx.media3.transformer.Composition, exportResult: ExportResult) {
                    if (continuation.isActive) continuation.resumeWith(Result.success(Unit))
                }

                override fun onError(
                    composition: androidx.media3.transformer.Composition,
                    exportResult: ExportResult,
                    exportException: ExportException,
                ) {
                    if (continuation.isActive) continuation.resumeWith(Result.failure(exportException))
                }
            })
            .build()
        continuation.invokeOnCancellation { runCatching { transformer.cancel() } }
        transformer.start(edited, outputPath)
    }
}

private fun createTextOverlay(text: String): Bitmap {
    val bitmap = Bitmap.createBitmap(1280, 720, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 58f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        color = android.graphics.Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }
    val lines = text.lines().take(3)
    val startY = 600f - (lines.size - 1) * 34f
    lines.forEachIndexed { index, line ->
        canvas.drawText(line.take(50), 640f, startY + index * 68f, paint)
    }
    paint.style = Paint.Style.FILL
    paint.color = android.graphics.Color.WHITE
    lines.forEachIndexed { index, line ->
        canvas.drawText(line.take(50), 640f, startY + index * 68f, paint)
    }
    return bitmap
}

private fun publishVideo(context: Context, source: File, displayName: String): File {
    val resolver = context.contentResolver
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/FileNest")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("Could not create output media entry")
        try {
            resolver.openOutputStream(uri)?.use { output ->
                FileInputStream(source).use { input -> input.copyTo(output, 1024 * 1024) }
            } ?: error("Could not open output stream")
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (t: Throwable) {
            resolver.delete(uri, null, null)
            throw t
        }
    } else {
        val directory = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "FileNest")
        if (!directory.exists() && !directory.mkdirs()) error("Could not create Movies/FileNest")
        FileInputStream(source).use { input ->
            FileOutputStream(File(directory, displayName)).use { output -> input.copyTo(output, 1024 * 1024) }
        }
    }
    return File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "FileNest/$displayName")
}

private fun formatTime(milliseconds: Long): String {
    val totalSeconds = (milliseconds / 1000L).coerceAtLeast(0L)
    return String.format(Locale.getDefault(), "%02d:%02d", totalSeconds / 60L, totalSeconds % 60L)
}
