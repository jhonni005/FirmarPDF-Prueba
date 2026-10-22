package com.zonadev.firmarpdf.ui.signing

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
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

internal fun Modifier.pdfPreviewGestures(zoom: PdfPreviewZoomState, list: LazyListState): Modifier =
    pointerInput(zoom) {
        detectTapGestures(onDoubleTap = { zoom.doubleTap(it, size) })
    }.pointerInput(zoom, list) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
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
