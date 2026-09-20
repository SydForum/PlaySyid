package com.darkxvenom.airbeats.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.darkxvenom.airbeats.innertube.YouTube
import com.darkxvenom.airbeats.innertube.pages.ChartsPage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ChartsViewModel @Inject constructor() : ViewModel() {
    private val _chartsPage = MutableStateFlow<ChartsPage?>(null)
    val chartsPage = _chartsPage.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    fun loadCharts() {
        if (_chartsPage.value != null && !_chartsPage.value?.sections.isNullOrEmpty()) return
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null

            YouTube.getChartsPage()
                .onSuccess { page ->
                    _chartsPage.value = page
                }
                .onFailure { e ->
                    _error.value = e.message ?: "Failed to load charts"
                }

            _isLoading.value = false
        }
    }

    fun retry() {
        _chartsPage.value = null
        loadCharts()
    }

    fun loadMore() {
        viewModelScope.launch {
            _chartsPage.value?.continuation?.let { continuation ->
                _isLoading.value = true
                YouTube.getChartsPage(continuation)
                    .onSuccess { newPage ->
                        _chartsPage.value = _chartsPage.value?.copy(
                            sections = _chartsPage.value?.sections.orEmpty() + newPage.sections,
                            continuation = newPage.continuation
                        )
                    }
                    .onFailure { e ->
                        _error.value = e.message ?: "Failed to load more charts"
                    }
                _isLoading.value = false
            }
        }
    }
}
