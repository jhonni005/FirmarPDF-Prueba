package com.zonadev.firmarpdf.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zonadev.firmarpdf.data.history.HistoryRepository
import com.zonadev.firmarpdf.data.history.SignedPdfEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface HomeUiState {
    data object Loading : HomeUiState
    data object Error : HomeUiState
    data class Content(val results: List<SignedPdfEntity>) : HomeUiState
}

class HomeViewModel(private val repository: HistoryRepository) : ViewModel() {
    private val mutableState = MutableStateFlow<HomeUiState>(HomeUiState.Loading)
    val uiState = mutableState.asStateFlow()
    private var observation: Job? = null

    init {
        loadHistory()
    }

    fun loadHistory() {
        observation?.cancel()
        observation = viewModelScope.launch {
            mutableState.value = HomeUiState.Loading
            try {
                repository.results.collect { mutableState.value = HomeUiState.Content(it) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // No enviar excepciones con URI o nombres de archivos a telemetría.
                mutableState.value = HomeUiState.Error
            }
        }
    }
}
