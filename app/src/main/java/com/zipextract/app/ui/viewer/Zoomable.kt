package com.zipextract.app.ui.viewer

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.ui.input.pointer.positionChanged
import kotlin.math.abs

class ZoomState(
    val minScale: Float = 1f,
    val maxScale: Float = 5f,
) {
    var scale by mutableFloatStateOf(1f)
        private set
    var offset by mutableStateOf(Offset.Zero)
        private set

    val isZoomed: Boolean
        get() = scale > minScale + 0.01f

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
        if (isZoomed) {
            reset()
        } else {
            scale = 2.5f.coerceIn(minScale, maxScale)
            offset = Offset.Zero
        }
    }

    fun onTransform(pan: Offset, zoom: Float) {
        val nextScale = (scale * zoom).coerceIn(minScale, maxScale)
        scale = nextScale
        if (nextScale > minScale) {
            offset += pan
        } else {
            offset = Offset.Zero
        }
    }

    /** Scale-only transform (layout-based zoom; pan handled by scroll). */
    fun onZoom(zoom: Float) {
        scale = (scale * zoom).coerceIn(minScale, maxScale)
        if (scale <= minScale) {
            offset = Offset.Zero
        }
    }

    fun clampOffset(maxX: Float, maxY: Float = Float.MAX_VALUE) {
        offset = Offset(
            x = offset.x.coerceIn(-maxX, maxX),
            y = offset.y.coerceIn(-maxY, maxY),
        )
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
    /**
     * If true, only multi-touch pinch changes zoom and single-finger pan
     * only applies while already zoomed. Lets nested scroll views keep
     * working at 1x scale (important for PDF page lists).
     */
    preserveScrollGestures: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .pointerInput(zoomState, preserveScrollGestures) {
                if (preserveScrollGestures) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        do {
                            val event = awaitPointerEvent()
                            val pressedCount = event.changes.count { it.pressed }
                            val zoomChange = event.calculateZoom()
                            val panChange = event.calculatePan()

                            when {
                                pressedCount >= 2 && (abs(zoomChange - 1f) > 0.001f || panChange != Offset.Zero) -> {
                                    zoomState.onTransform(panChange, zoomChange)
                                    event.changes.forEach {
                                        if (it.positionChanged()) it.consume()
                                    }
                                }
                                pressedCount == 1 && zoomState.isZoomed && panChange != Offset.Zero -> {
                                    zoomState.onTransform(panChange, 1f)
                                    event.changes.forEach {
                                        if (it.positionChanged()) it.consume()
                                    }
                                }
                            }
                        } while (event.changes.any { it.pressed })
                    }
                } else {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        var pastTouchSlop = false
                        var zoom = 1f
                        var pan = Offset.Zero
                        val touchSlop = viewConfiguration.touchSlop
                        do {
                            val event = awaitPointerEvent()
                            val zoomChange = event.calculateZoom()
                            val panChange = event.calculatePan()
                            if (!pastTouchSlop) {
                                zoom *= zoomChange
                                pan += panChange
                                val centroidSize = event.calculateCentroidSize(useCurrent = false)
                                val zoomMotion = abs(1f - zoom) * centroidSize
                                val panMotion = pan.getDistance()
                                if (zoomMotion > touchSlop || panMotion > touchSlop) {
                                    pastTouchSlop = true
                                }
                            }
                            if (pastTouchSlop) {
                                zoomState.onTransform(panChange, zoomChange)
                                event.changes.forEach {
                                    if (it.positionChanged()) it.consume()
                                }
                            }
                        } while (event.changes.any { it.pressed })
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
        content = content,
    )
}
