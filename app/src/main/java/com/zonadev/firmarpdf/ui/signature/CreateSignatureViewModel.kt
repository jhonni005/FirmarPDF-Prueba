package com.zonadev.firmarpdf.ui.signature

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zonadev.firmarpdf.data.signature.SignaturePoint
import com.zonadev.firmarpdf.data.signature.SignatureStore
import com.zonadev.firmarpdf.data.signature.SignatureStroke
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

class CreateSignatureViewModel(private val store: SignatureStore) : ViewModel() {
    var strokes by mutableStateOf<List<SignatureStroke>>(emptyList())
        private set
    var active by mutableStateOf<SignatureStroke?>(null)
        private set
    var color by mutableStateOf(0xFF000000.toInt())
    var thickness by mutableStateOf(3f)
    var saving by mutableStateOf(false)
        private set
    var error by mutableStateOf(false)
        private set
    private val completed = Channel<Unit>(Channel.BUFFERED)
    val saved = completed.receiveAsFlow()

    fun begin(point: SignaturePoint, relativeWidth: Float) {
        if (!saving) { error = false; active = SignatureStroke(color, relativeWidth, listOf(point)) }
    }
    fun append(point: SignaturePoint) { active = active?.let { it.copy(points = it.points + point) } }
    fun finish(cancelled: Boolean = false) {
        if (!cancelled) active?.let { strokes = strokes + it }
        active = null
    }
    fun undo() { if (!saving) { strokes = strokes.dropLast(1); error = false } }
    fun clear() { if (!saving) { strokes = emptyList(); active = null; error = false } }
    fun save(aspectRatio: Float) {
        if (saving || strokes.isEmpty() || active != null) return
        saving = true
        error = false
        val snapshot = strokes
        viewModelScope.launch {
            try {
                store.save(snapshot, aspectRatio)
                strokes = emptyList()
                completed.send(Unit)
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { error = true
            } catch (_: OutOfMemoryError) { error = true
            } finally { saving = false }
        }
    }
}
