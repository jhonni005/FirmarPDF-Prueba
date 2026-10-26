package com.zonadev.firmarpdf.ui.pdf

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zonadev.firmarpdf.data.pdf.PdfPreviewError
import com.zonadev.firmarpdf.data.pdf.PdfPreviewException
import com.zonadev.firmarpdf.data.pdf.PdfPreviewSession
import com.zonadev.firmarpdf.data.recentpdf.RecentPdfRepository
import io.legere.pdfiumandroid.PdfiumCore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface PdfPreviewState {
    data object Loading : PdfPreviewState
    data class Ready(val session: PdfPreviewSession, val recentSaveFailed: Boolean = false) : PdfPreviewState
    data class Error(val reason: PdfPreviewError) : PdfPreviewState
}

class PdfPreviewViewModel(
    private val resolver: ContentResolver,
    private val core: () -> PdfiumCore,
    private val uri: Uri,
    private val recents: RecentPdfRepository? = null,
) : ViewModel() {
    private val mutableState = MutableStateFlow<PdfPreviewState>(PdfPreviewState.Loading)
    val uiState = mutableState.asStateFlow()
    private var generation = 0L
    private var opening: Job? = null
    private var current: PdfPreviewSession? = null
    private val cleanupJob = SupervisorJob()
    private val cleanup = CoroutineScope(cleanupJob + Dispatchers.IO)

    init { reload() }

    fun reload() {
        val expected = ++generation
        opening?.cancel()
        releaseCurrent()
        mutableState.value = PdfPreviewState.Loading
        opening = viewModelScope.launch(Dispatchers.IO) {
            var opened: PdfPreviewSession? = null
            try {
                // Guardar la referencia antes de cualquier salto de dispatcher: evita fugas al cancelar.
                opened = PdfPreviewSession.open(resolver, core, uri)
                ensureActive()
                val recentSaveFailed = try {
                    recents?.recordOpened(uri)
                    false
                } catch (cancelled: CancellationException) { throw cancelled
                } catch (_: Exception) { true }
                withContext(Dispatchers.Main.immediate) {
                    if (generation == expected) {
                        current = opened
                        mutableState.value = PdfPreviewState.Ready(checkNotNull(opened), recentSaveFailed)
                        opened = null
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: PdfPreviewException) {
                try { recents?.markUnavailable(uri) } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { }
                withContext(Dispatchers.Main.immediate) {
                    if (generation == expected) mutableState.value = PdfPreviewState.Error(failure.reason)
                }
            } finally {
                withContext(NonCancellable) { opened?.close() }
            }
        }
    }

    private fun releaseCurrent() {
        val old = current
        current = null
        old?.requestClose()
        if (old != null) cleanup.launch { old.close() }
    }

    override fun onCleared() {
        generation++
        opening?.cancel()
        releaseCurrent()
        // Permitir que las liberaciones pendientes terminen incluso después de cancelar viewModelScope.
        cleanupJob.complete()
    }
}
