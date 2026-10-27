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
    data class Saving(val stage: PdfExportStage, val pdfium: Boolean = false, val embedPdf: Boolean = false) : PdfExportUiState
    data class Saved(val result: PdfExportResult) : PdfExportUiState
    data class Error(val reason: PdfExportError) : PdfExportUiState
}

class PdfExportViewModel(
    private val exporter: PdfExporter,
    private val source: Uri,
    private val pdfiumExporter: PdfiumSignatureExporter? = null,
    private val embedPdfExporter: EmbedPdfSignatureExporter? = null,
) : ViewModel() {
    private val state = MutableStateFlow<PdfExportUiState>(PdfExportUiState.Idle)
    val uiState = state.asStateFlow()
    private var work: Job? = null
    private var lastSaved: PdfExportDraft? = null
    val embedPdfAvailable: Boolean get() = BuildConfig.DEBUG && embedPdfExporter?.available == true

    fun hasUnsaved(draft: PdfExportDraft) = draft.hasAnnotations && draft != lastSaved

    fun save(draft: PdfExportDraft) {
        if (!draft.hasAnnotations || work?.isCompleted == false) return
        start(draft, pdfiumSaveMode = null)
    }

    fun checkPdfiumDraft(draft: PdfExportDraft): Boolean {
        if (!BuildConfig.DEBUG || work?.isCompleted == false) return false
        val error = pdfiumDraftError(draft)
        if (error != null) state.value = PdfExportUiState.Error(error)
        return error == null
    }

    fun savePdfium(draft: PdfExportDraft, saveMode: PdfiumSaveMode = PdfiumSaveMode.FULL) {
        if (checkPdfiumDraft(draft)) start(draft, pdfiumSaveMode = saveMode)
    }

    fun checkEmbedPdfDraft(draft: PdfExportDraft): Boolean {
        if (!BuildConfig.DEBUG || work?.isCompleted == false) return false
        val error = when {
            !embedPdfAvailable -> PdfExportError.EMBEDPDF_UNAVAILABLE
            draft.dates.isNotEmpty() || draft.strokes.isNotEmpty() -> PdfExportError.EMBEDPDF_SIGNATURES_ONLY
            else -> pdfiumDraftError(draft)
        }
        if (error != null) state.value = PdfExportUiState.Error(error)
        return error == null
    }

    fun saveEmbedPdf(draft: PdfExportDraft) {
        if (checkEmbedPdfDraft(draft)) start(draft, pdfiumSaveMode = null, embedPdf = true)
    }

    private fun start(draft: PdfExportDraft, pdfiumSaveMode: PdfiumSaveMode?, embedPdf: Boolean = false) {
        val pdfium = pdfiumSaveMode != null
        state.value = PdfExportUiState.Saving(PdfExportStage.SOURCE, pdfium, embedPdf)
        work = viewModelScope.launch {
            try {
                val progress: (PdfExportStage) -> Unit = { state.value = PdfExportUiState.Saving(it, pdfium, embedPdf) }
                val result = if (embedPdf) {
                    (embedPdfExporter ?: throw PdfExportException(PdfExportError.EMBEDPDF_UNAVAILABLE)).export(source, draft, progress)
                } else if (pdfiumSaveMode != null) {
                    (pdfiumExporter ?: throw PdfExportException(PdfExportError.INVALID))
                        .export(source, draft, saveMode = pdfiumSaveMode, progress = progress)
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
