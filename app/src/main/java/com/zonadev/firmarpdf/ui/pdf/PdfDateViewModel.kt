package com.zonadev.firmarpdf.ui.pdf

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import com.zonadev.firmarpdf.R
import com.zonadev.firmarpdf.data.signature.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalDate
import java.util.UUID

data class PdfDateUiState(
    val sheetVisible: Boolean = false,
    val dates: List<PdfDateAnnotation> = emptyList(),
    val selectedId: String? = null,
    val images: Map<String, Bitmap> = emptyMap(),
    val error: Int? = null,
)

class PdfDateViewModel : ViewModel() {
    private val state = MutableStateFlow(PdfDateUiState())
    val uiState = state.asStateFlow()

    fun openSheet() { state.value = state.value.copy(sheetVisible = true, error = null) }
    fun dismissSheet() { state.value = state.value.copy(sheetVisible = false, error = null) }
    fun select(id: String?) { state.value = state.value.copy(selectedId = id) }
    fun clearError() { state.value = state.value.copy(error = null) }

    fun choose(format: PdfDateFormat, target: VisibleSignatureTarget?) {
        if (!state.value.sheetVisible) return
        if (target == null) { state.value = state.value.copy(error = R.string.signature_wait_for_page); return }
        state.value = state.value.copy(sheetVisible = false, error = null)
        // Volver a consultar el reloj al tocar, incluso si el sheet se abrió antes de medianoche.
        val today = LocalDate.now()
        val text = format.format(today)
        try {
            val bitmap = renderPdfDate(text)
            val id = "date_${UUID.randomUUID()}"
            val placement = createInitialSignaturePlacement(id, id, bitmap.width.toFloat() / bitmap.height, target)
            val date = PdfDateAnnotation(today, format, text, placement)
            state.value = state.value.copy(dates = state.value.dates + date, selectedId = id, images = state.value.images + (id to bitmap))
        } catch (_: OutOfMemoryError) { state.value = state.value.copy(error = R.string.date_create_error)
        } catch (_: Exception) { state.value = state.value.copy(error = R.string.date_create_error) }
    }

    fun remove(id: String) {
        state.value = state.value.copy(dates = state.value.dates.filterNot { it.placement.id == id }, images = state.value.images - id,
            selectedId = state.value.selectedId?.takeUnless { it == id })
    }
    fun move(id: String, dx: Float, dy: Float) = update(id) { moveSignaturePlacement(it, dx, dy) }
    fun resize(id: String, delta: Float) = update(id) { resizeSignaturePlacement(it, delta) }
    fun finishDrag(placement: PdfSignaturePlacement) = update(placement.id) { placement }

    private fun update(id: String, transform: (PdfSignaturePlacement) -> PdfSignaturePlacement) {
        state.value = state.value.copy(dates = state.value.dates.map { if (it.placement.id == id) it.copy(placement = transform(it.placement)) else it })
    }
}
