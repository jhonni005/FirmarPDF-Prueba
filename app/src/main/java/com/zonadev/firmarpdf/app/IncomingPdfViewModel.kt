package com.zonadev.firmarpdf.app

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel

/** Conserva una entrada pendiente durante recreaciones, sin apropiarse de permisos persistentes. */
class IncomingPdfViewModel(private val savedState: SavedStateHandle) : ViewModel() {
    val pending = savedState.getStateFlow<String?>("incoming_pdf", null)
    fun receive(uri: Uri) { savedState["incoming_pdf"] = uri.toString() }
    fun consume(uri: String) { if (pending.value == uri) savedState["incoming_pdf"] = null }
}
