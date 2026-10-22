package com.zonadev.firmarpdf.ui.home

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zonadev.firmarpdf.data.signedpdf.SignedPdfRepository
import com.zonadev.firmarpdf.data.signedpdf.SignedPdfEntity
import com.zonadev.firmarpdf.data.pdf.SelectedPdf
import com.zonadev.firmarpdf.data.pdf.SelectedPdfReader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface SignedPdfsState {
    data object Loading : SignedPdfsState
    data object Error : SignedPdfsState
    data class Content(val results: List<SignedPdfEntity>) : SignedPdfsState
}

enum class PdfSelectionError { UNREADABLE, PICKER_UNAVAILABLE }

data class HomeUiState(
    val signedPdfs: SignedPdfsState = SignedPdfsState.Loading,
    val selectedPdf: SelectedPdf? = null,
    val checkingSelection: Boolean = false,
    val selectionError: PdfSelectionError? = null,
)

class HomeViewModel(
    private val repository: SignedPdfRepository,
    private val selectedPdfReader: SelectedPdfReader,
    private val savedState: SavedStateHandle,
) : ViewModel() {
    private val mutableState = MutableStateFlow(HomeUiState())
    val uiState = mutableState.asStateFlow()
    private var observation: Job? = null
    private var selection: Job? = null
    private val previews = Channel<Uri>(Channel.BUFFERED)
    val previewRequests = previews.receiveAsFlow()

    init {
        loadHistory()
        savedState.get<String>(SELECTED_URI)?.let { selectPdf(Uri.parse(it), openPreview = false) }
    }

    fun loadHistory() {
        observation?.cancel()
        observation = viewModelScope.launch {
            mutableState.update { it.copy(signedPdfs = SignedPdfsState.Loading) }
            try {
                repository.results.collect { results ->
                    mutableState.update { it.copy(signedPdfs = SignedPdfsState.Content(results)) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // No enviar excepciones con URI o nombres de archivos a telemetría.
                mutableState.update { it.copy(signedPdfs = SignedPdfsState.Error) }
            }
        }
    }

    fun onPdfSelected(uri: Uri?) {
        // Cancelar el selector conserva tanto la selección anterior como el historial.
        if (uri == null) return
        selectPdf(uri, openPreview = true)
    }

    private fun selectPdf(uri: Uri, openPreview: Boolean) {
        selection?.cancel()
        selection = viewModelScope.launch {
            mutableState.update { it.copy(checkingSelection = true, selectionError = null) }
            try {
                val pdf = selectedPdfReader.read(uri)
                savedState[SELECTED_URI] = pdf.uri.toString()
                mutableState.update {
                    it.copy(selectedPdf = pdf, checkingSelection = false, selectionError = null)
                }
                if (openPreview) previews.send(uri)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.update {
                    it.copy(checkingSelection = false, selectionError = PdfSelectionError.UNREADABLE)
                }
            }
        }
    }

    fun onPickerUnavailable() {
        mutableState.update { it.copy(selectionError = PdfSelectionError.PICKER_UNAVAILABLE) }
    }

    private companion object {
        const val SELECTED_URI = "selected_pdf_uri"
    }
}
