package com.zonadev.firmarpdf.ui.pdf

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zonadev.firmarpdf.data.pdf.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface PdfExportUiState {
    data object Idle : PdfExportUiState
    data class Saving(val stage: PdfExportStage) : PdfExportUiState
    data class Saved(val result: PdfExportResult) : PdfExportUiState
    data class Error(val reason: PdfExportError) : PdfExportUiState
}

class PdfExportViewModel(private val exporter: PdfExporter, private val source: Uri) : ViewModel() {
    private val state = MutableStateFlow<PdfExportUiState>(PdfExportUiState.Idle)
    val uiState = state.asStateFlow()
    private var work: Job? = null
    private var lastSaved: PdfExportDraft? = null

    fun hasUnsaved(draft: PdfExportDraft) = draft.hasAnnotations && draft != lastSaved

    fun save(draft: PdfExportDraft) {
        if (!draft.hasAnnotations || work?.isCompleted == false) return
        state.value = PdfExportUiState.Saving(PdfExportStage.SOURCE)
        work = viewModelScope.launch {
            try {
                val result = exporter.export(source, draft) { state.value = PdfExportUiState.Saving(it) }
                lastSaved = draft
                state.value = PdfExportUiState.Saved(result)
            } catch (cancelled: CancellationException) {
                state.value = PdfExportUiState.Idle
                throw cancelled
            } catch (failure: PdfExportException) {
                state.value = PdfExportUiState.Error(failure.reason)
            }
        }
    }

    fun permissionDenied() { state.value = PdfExportUiState.Error(PdfExportError.PERMISSION) }
    fun cancel() { work?.cancel() }
    fun dismiss() { if (state.value !is PdfExportUiState.Saving) state.value = PdfExportUiState.Idle }
}
