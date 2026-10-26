package com.zonadev.firmarpdf.ui.pdf

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.unit.IntSize

/** Adaptación del pan/zoom de LectorDocumentos, sin herramientas ni selección de texto. */
@Stable
internal class PdfPreviewZoomState(scale: Float = 1f, x: Float = 0f, y: Float = 0f) {
    var scale by mutableFloatStateOf(scale)
        private set
    var x by mutableFloatStateOf(x)
        private set
    var y by mutableFloatStateOf(y)
        private set

    fun zoomAt(factor: Float, centroid: Offset, size: IntSize) {
        val newScale = (scale * factor).coerceIn(1f, 5f)
        val effective = newScale / scale
        val pivot = centroid - Offset(size.width / 2f, size.height / 2f)
        x = (pivot.x * (1 - effective) + x * effective)
            .coerceIn(-size.width * (newScale - 1) / 2, size.width * (newScale - 1) / 2)
        y = (pivot.y * (1 - effective) + y * effective)
            .coerceIn(-size.height * (newScale - 1) / 2, size.height * (newScale - 1) / 2)
        scale = newScale
        if (scale == 1f) { x = 0f; y = 0f }
    }

    fun doubleTap(position: Offset, size: IntSize) {
        val target = when { scale < 1.8f -> 2f; scale < 2.8f -> 3f; else -> 1f }
        zoomAt(target / scale, position, size)
    }

    fun clampTo(size: IntSize) {
        x = x.coerceIn(-size.width * (scale - 1) / 2, size.width * (scale - 1) / 2)
        y = y.coerceIn(-size.height * (scale - 1) / 2, size.height * (scale - 1) / 2)
    }

    fun pan(delta: Offset, size: IntSize, list: LazyListState) {
        if (scale <= 1f) return
        val maxX = size.width * (scale - 1) / 2
        val maxY = size.height * (scale - 1) / 2
        x = (x + delta.x).coerceIn(-maxX, maxX)
        var remaining = delta.y
        var offsetY = y.coerceIn(-maxY, maxY)
        // Primero recuperar el desplazamiento del viewport; después avanzar por todas las páginas.
        if (offsetY > 0 && remaining < 0) {
            val next = (offsetY + remaining).coerceAtLeast(0f)
            remaining -= next - offsetY
            offsetY = next
        } else if (offsetY < 0 && remaining > 0) {
            val next = (offsetY + remaining).coerceAtMost(0f)
            remaining -= next - offsetY
            offsetY = next
        }
        val consumed = list.dispatchRawDelta(-remaining / scale)
        remaining += consumed * scale
        y = (offsetY + remaining).coerceIn(-maxY, maxY)
    }

    companion object {
        val Saver = listSaver<PdfPreviewZoomState, Float>(
            save = { listOf(it.scale, it.x, it.y) },
            restore = { PdfPreviewZoomState(it[0], it[1], it[2]) },
        )
    }
}

@Composable
internal fun Modifier.pdfPreviewGestures(
    zoom: PdfPreviewZoomState, list: LazyListState, signatures: PdfPlacementGestureCallbacks, drawing: PdfDrawingGestureCallbacks,
): Modifier {
    val callbacks = rememberUpdatedState(signatures)
    val drawingCallbacks = rememberUpdatedState(drawing)
    return pointerInput(zoom) {
        detectTapGestures(onDoubleTap = {
            if (!drawingCallbacks.value.enabled && callbacks.value.viewportEnabled && callbacks.value.hitTest(it) == null) zoom.doubleTap(it, size)
        })
    }.pointerInput(zoom, list) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            if (drawingCallbacks.value.enabled) {
                down.consume()
                var completed = false
                var cancelled = false
                drawingCallbacks.value.begin(down.position)
                try {
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id }
                        if (change == null || event.changes.count { it.pressed } > 1) cancelled = true
                        if (!cancelled && change != null) {
                            drawingCallbacks.value.append(change.position)
                            if (!change.pressed) completed = true
                        }
                        event.changes.forEach { it.consume() }
                    } while (event.changes.any { it.pressed })
                } finally { drawingCallbacks.value.finish(cancelled || !completed) }
                return@awaitEachGesture
            }
            if (!callbacks.value.viewportEnabled) {
                down.consume()
                do {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    event.changes.forEach { it.consume() }
                } while (event.changes.any { it.pressed })
                return@awaitEachGesture
            }
            val hit = callbacks.value.hitTest(down.position)
            if (hit != null) {
                down.consume()
                callbacks.value.onPress(hit)
                var dragging = false
                var cancelled = false
                var total = Offset.Zero
                try {
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id }
                        val pointers = event.changes.count { it.pressed }
                        if (pointers > 1 || change == null) {
                            cancelled = true
                            dragging = false
                            callbacks.value.onCancel()
                        }
                        // Una pinza puede comenzar sobre una firma; pasa al zoom sin confirmar el arrastre.
                        if (cancelled) {
                            if (pointers > 1) zoom.zoomAt(event.calculateZoom(), event.calculateCentroid(), size)
                            if (pointers > 1 || zoom.scale > 1.01f) zoom.pan(event.calculatePan(), size, list)
                        }
                        if (change != null && !cancelled) {
                            val delta = change.position - change.previousPosition
                            total += delta
                            if (!dragging && total.getDistance() > viewConfiguration.touchSlop && hit.control != PlacementControl.REMOVE) {
                                dragging = true
                                callbacks.value.onStart(hit)
                                callbacks.value.onMove(total, change.position)
                            } else if (dragging) callbacks.value.onMove(delta, change.position)
                            if (!change.pressed) {
                                if (dragging) callbacks.value.onEnd()
                                else if (total.getDistance() <= viewConfiguration.touchSlop) callbacks.value.onTap(hit)
                                dragging = false
                            }
                        }
                        event.changes.forEach { it.consume() }
                    } while (event.changes.any { it.pressed })
                } finally {
                    if (dragging || cancelled) callbacks.value.onCancel()
                }
                return@awaitEachGesture
            }
            var accumulated = Offset.Zero
            var pastSlop = false
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val pointers = event.changes.count { it.pressed }
                // A tamaño normal un dedo queda enteramente a cargo de LazyColumn y su fling.
                if (pointers >= 2 || zoom.scale > 1.01f) {
                    val pan = event.calculatePan()
                    accumulated += pan
                    if (pointers >= 2 || accumulated.getDistance() > viewConfiguration.touchSlop) pastSlop = true
                    if (pastSlop) {
                        if (pointers >= 2) zoom.zoomAt(event.calculateZoom(), event.calculateCentroid(), size)
                        zoom.pan(pan, size, list)
                        event.changes.forEach { if (it.positionChanged()) it.consume() }
                    }
                }
            } while (event.changes.any { it.pressed })
        }
    }
}
