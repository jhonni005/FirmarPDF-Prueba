package com.zonadev.firmarpdf.ui.pdf

import androidx.lifecycle.ViewModel
import com.zonadev.firmarpdf.data.signature.SignaturePoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

class PdfDrawingViewModel : ViewModel() {
    private val state = MutableStateFlow(PdfDrawingUiState())
    val uiState = state.asStateFlow()
    fun start() { state.value = state.value.copy(drawing = true) }
    fun finishTool() { state.value = state.value.copy(drawing = false, active = null) }
    fun setColor(color: Int) { if (state.value.active == null) state.value = state.value.copy(color = color) }
    fun setThickness(width: Float) { if (state.value.active == null) state.value = state.value.copy(thickness = width) }
    fun begin(pageIndex: Int, point: SignaturePoint, width: Float) {
        if (state.value.drawing) state.value = state.value.copy(active = PdfPageStroke(UUID.randomUUID().toString(), pageIndex, state.value.color, width, listOf(point)))
    }
    fun append(point: SignaturePoint) {
        state.value.active?.let { state.value = state.value.copy(active = it.copy(points = it.points + point)) }
    }
    fun finishStroke(cancelled: Boolean) {
        val active = state.value.active
        state.value = state.value.copy(strokes = if (!cancelled && active != null) state.value.strokes + active else state.value.strokes, active = null)
    }
    fun undo() { if (state.value.active == null) state.value = state.value.copy(strokes = state.value.strokes.dropLast(1)) }
    fun clear() { if (state.value.active == null) state.value = state.value.copy(strokes = emptyList()) }
}
