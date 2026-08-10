package com.zipextract.app.ui.viewer

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput

class ZoomState(
    val minScale: Float = 1f,
    val maxScale: Float = 5f,
) {
    var scale by mutableFloatStateOf(1f)
    var offset by mutableStateOf(Offset.Zero)

    fun zoomIn(step: Float = 0.5f) {
        scale = (scale + step).coerceIn(minScale, maxScale)
    }

    fun zoomOut(step: Float = 0.5f) {
        scale = (scale - step).coerceIn(minScale, maxScale)
        if (scale <= minScale) {
            offset = Offset.Zero
        }
    }

    fun reset() {
        scale = minScale
        offset = Offset.Zero
    }

    fun toggleDoubleTapZoom() {
        if (scale > minScale) {
            reset()
        } else {
            scale = 2.5f.coerceIn(minScale, maxScale)
        }
    }
}

@Composable
fun rememberZoomState(
    minScale: Float = 1f,
    maxScale: Float = 5f,
): ZoomState {
    return remember(minScale, maxScale) { ZoomState(minScale, maxScale) }
}

@Composable
fun ZoomableBox(
    zoomState: ZoomState,
    modifier: Modifier = Modifier,
    contentAlignment: Alignment = Alignment.Center,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .pointerInput(zoomState) {
                detectTransformGestures { _, pan, zoom, _ ->
                    val nextScale = (zoomState.scale * zoom).coerceIn(zoomState.minScale, zoomState.maxScale)
                    zoomState.scale = nextScale
                    if (nextScale > zoomState.minScale) {
                        zoomState.offset += pan
                    } else {
                        zoomState.offset = Offset.Zero
                    }
                }
            }
            .pointerInput(zoomState) {
                detectTapGestures(
                    onDoubleTap = { zoomState.toggleDoubleTapZoom() },
                )
            }
            .graphicsLayer {
                scaleX = zoomState.scale
                scaleY = zoomState.scale
                translationX = zoomState.offset.x
                translationY = zoomState.offset.y
            },
        contentAlignment = contentAlignment,
    ) {
        content()
    }
}
