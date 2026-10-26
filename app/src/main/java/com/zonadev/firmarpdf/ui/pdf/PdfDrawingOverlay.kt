package com.zonadev.firmarpdf.ui.pdf

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import com.zonadev.firmarpdf.data.signature.*

internal class PdfDrawingGestureCallbacks(
    val enabled: Boolean,
    val begin: (Offset) -> Unit,
    val append: (Offset) -> Unit,
    val finish: (Boolean) -> Unit,
)

@Composable
internal fun PdfDrawingOverlay(state: PdfDrawingUiState, pages: List<PdfSignaturePageBounds>, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val strokes = state.strokes + listOfNotNull(state.active)
        drawIntoCanvas { target ->
            pages.forEach { page ->
                val pageStrokes = strokes.filter { it.pageIndex == page.pageIndex }
                if (pageStrokes.isNotEmpty()) {
                    val canvas = target.nativeCanvas
                    canvas.save()
                    canvas.clipRect(page.bounds.left, page.bounds.top, page.bounds.right, page.bounds.bottom)
                    canvas.translate(page.bounds.left, page.bounds.top)
                    drawSignature(canvas, pageStrokes.map { SignatureStroke(it.color, it.width, it.points) }, page.bounds.width, page.bounds.height)
                    canvas.restore()
                }
            }
        }
    }
}
