package com.zonadev.firmarpdf.ui.pdf

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zonadev.firmarpdf.BuildConfig
import com.zonadev.firmarpdf.data.pdf.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface PdfExportUiState {
    data object Idle : PdfExportUiState
    data class Saving(val stage: PdfExportStage, val pdfium: Boolean = false) : PdfExportUiState
    data class Saved(val result: PdfExportResult) : PdfExportUiState
    data class Error(val reason: PdfExportError) : PdfExportUiState
}

class PdfExportViewModel(
    private val exporter: PdfExporter,
    private val source: Uri,
    private val pdfiumExporter: PdfiumSignatureExporter? = null,
) : ViewModel() {
    private val state = MutableStateFlow<PdfExportUiState>(PdfExportUiState.Idle)
    val uiState = state.asStateFlow()
    private var work: Job? = null
    private var lastSaved: PdfExportDraft? = null

    fun hasUnsaved(draft: PdfExportDraft) = draft.hasAnnotations && draft != lastSaved

    fun save(draft: PdfExportDraft) {
        if (!draft.hasAnnotations || work?.isCompleted == false) return
        start(draft, pdfium = false)
    }

    fun checkPdfiumDraft(draft: PdfExportDraft): Boolean {
        if (!BuildConfig.DEBUG || work?.isCompleted == false) return false
        val error = pdfiumDraftError(draft)
        if (error != null) state.value = PdfExportUiState.Error(error)
        return error == null
    }

    fun savePdfium(draft: PdfExportDraft) {
        if (checkPdfiumDraft(draft)) start(draft, pdfium = true)
    }

    private fun start(draft: PdfExportDraft, pdfium: Boolean) {
        state.value = PdfExportUiState.Saving(PdfExportStage.SOURCE, pdfium)
        work = viewModelScope.launch {
            try {
                val progress: (PdfExportStage) -> Unit = { state.value = PdfExportUiState.Saving(it, pdfium) }
                val result = if (pdfium) {
                    (pdfiumExporter ?: throw PdfExportException(PdfExportError.INVALID)).export(source, draft, progress)
                } else exporter.export(source, draft, progress)
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
