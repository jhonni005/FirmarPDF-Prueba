package com.zonadev.firmarpdf.ui.pdf

import android.graphics.Bitmap
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zonadev.firmarpdf.R
import com.zonadev.firmarpdf.data.signature.PdfSignaturePlacement
import com.zonadev.firmarpdf.data.signature.SavedSignatureStore
import com.zonadev.firmarpdf.data.signature.VisibleSignatureTarget
import com.zonadev.firmarpdf.data.signature.createInitialSignaturePlacement
import com.zonadev.firmarpdf.data.signature.moveSignaturePlacement
import com.zonadev.firmarpdf.data.signature.resizeSignaturePlacement
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import java.util.UUID

data class PdfSignatureUiState(
    val libraryVisible: Boolean = false,
    val loading: Boolean = false,
    val selecting: Boolean = false,
    val deleting: Boolean = false,
    val savedIds: List<String> = emptyList(),
    val placements: List<PdfSignaturePlacement> = emptyList(),
    val selectedId: String? = null,
    val images: Map<String, Bitmap> = emptyMap(),
    val pageAspects: Map<Int, Float> = emptyMap(),
    @StringRes val error: Int? = null,
) {
    val busy get() = loading || selecting || deleting
}

/** Borrador de un destino PDF. No exporta ni escribe en Room o en el documento. */
class PdfSignatureViewModel(private val store: SavedSignatureStore) : ViewModel() {
    private val state = MutableStateFlow(PdfSignatureUiState())
    val uiState = state.asStateFlow()
    private val create = Channel<Unit>(Channel.BUFFERED)
    val createRequests = create.receiveAsFlow()
    private var returningFromCreation = false
    private var listing: Job? = null

    fun openLibrary() {
        if (state.value.busy || state.value.libraryVisible) return
        state.value = state.value.copy(libraryVisible = true, error = null)
        refresh(autoCreateIfEmpty = true)
    }

    fun dismissLibrary() {
        if (state.value.selecting || state.value.deleting) return
        state.value = state.value.copy(libraryVisible = false, error = null)
    }

    fun createSignature() {
        if (state.value.busy || returningFromCreation) return
        returningFromCreation = true
        state.value = state.value.copy(libraryVisible = false, error = null)
        create.trySend(Unit)
    }

    /** Se llama al RESUMED del destino PDF, también al volver sin guardar. */
    fun onViewerResumed() {
        if (!returningFromCreation) return
        returningFromCreation = false
        state.value = state.value.copy(libraryVisible = true)
        refresh(autoCreateIfEmpty = false)
    }

    fun refresh(autoCreateIfEmpty: Boolean = false) {
        if (state.value.selecting || state.value.deleting) return
        listing?.cancel()
        state.value = state.value.copy(loading = true, error = null)
        listing = viewModelScope.launch {
            try {
                val ids = store.list()
                val current = state.value
                val placements = current.placements.filter { it.signatureId in ids }
                state.value = current.copy(
                    loading = false, savedIds = ids, placements = placements,
                    selectedId = current.selectedId?.takeIf { id -> placements.any { it.id == id } },
                    images = current.images.filterKeys { id -> placements.any { it.signatureId == id } },
                )
                if (autoCreateIfEmpty && ids.isEmpty() && state.value.libraryVisible) createSignature()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                state.value = state.value.copy(loading = false, error = R.string.signature_library_error)
            }
        }
    }

    suspend fun thumbnail(id: String) = store.load(id, maxDimension = 256)

    fun selectSavedSignature(id: String, target: VisibleSignatureTarget?) {
        val current = state.value
        if (!current.libraryVisible || current.busy || id !in current.savedIds) return
        if (target == null) {
            state.value = current.copy(error = R.string.signature_wait_for_page)
            return
        }
        // Consumir la elección antes de suspender: un segundo toque no crea otra colocación.
        state.value = current.copy(libraryVisible = false, selecting = true, error = null)
        viewModelScope.launch {
            try {
                val bitmap = current.images[id] ?: store.load(id, maxDimension = 1024)
                if (bitmap == null) {
                    state.value = state.value.copy(selecting = false, error = R.string.signature_missing)
                    refresh()
                    return@launch
                }
                val placement = createInitialSignaturePlacement(
                    UUID.randomUUID().toString(), id, bitmap.width.toFloat() / bitmap.height, target,
                )
                state.value = state.value.copy(
                    selecting = false, placements = state.value.placements + placement,
                    selectedId = placement.id, images = state.value.images + (id to bitmap),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: OutOfMemoryError) {
                state.value = state.value.copy(selecting = false, error = R.string.signature_image_error)
            } catch (_: Exception) {
                state.value = state.value.copy(selecting = false, error = R.string.signature_image_error)
            }
        }
    }

    fun deleteSavedSignature(id: String) {
        if (state.value.busy || id !in state.value.savedIds) return
        state.value = state.value.copy(deleting = true, error = null)
        viewModelScope.launch {
            try {
                store.delete(id)
                val current = state.value
                val placements = current.placements.filterNot { it.signatureId == id }
                state.value = current.copy(
                    deleting = false, savedIds = current.savedIds - id, placements = placements,
                    images = current.images - id,
                    selectedId = current.selectedId?.takeIf { selected -> placements.any { it.id == selected } },
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                state.value = state.value.copy(deleting = false, error = R.string.signature_delete_error)
            }
        }
    }

    fun pageReady(index: Int, aspect: Float) {
        // Evitar cambios de layout al cargar de nuevo tras rotar o por redondeo del bitmap.
        if (index in state.value.pageAspects) return
        state.value = state.value.copy(pageAspects = state.value.pageAspects + (index to aspect))
    }

    fun selectPlacement(id: String) {
        if (state.value.placements.any { it.id == id }) state.value = state.value.copy(selectedId = id)
    }

    fun clearSelection() { state.value = state.value.copy(selectedId = null) }

    fun removePlacement(id: String) {
        val current = state.value
        val placements = current.placements.filterNot { it.id == id }
        state.value = current.copy(
            placements = placements, selectedId = current.selectedId?.takeUnless { it == id },
            images = current.images.filterKeys { key -> placements.any { it.signatureId == key } },
        )
    }

    fun finishPlacementDrag(placement: PdfSignaturePlacement) = updatePlacement(placement.id) { placement }
    fun resizePlacement(id: String, deltaWidth: Float) = updatePlacement(id) { resizeSignaturePlacement(it, deltaWidth) }
    fun movePlacement(id: String, dx: Float, dy: Float) = updatePlacement(id) { moveSignaturePlacement(it, dx, dy) }
    fun clearError() { state.value = state.value.copy(error = null) }

    private fun updatePlacement(id: String, transform: (PdfSignaturePlacement) -> PdfSignaturePlacement) {
        state.value = state.value.copy(placements = state.value.placements.map { if (it.id == id) transform(it) else it })
    }
}
