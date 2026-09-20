package com.darkxvenom.airbeats.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.darkxvenom.airbeats.innertube.YouTube
import com.darkxvenom.airbeats.innertube.pages.ChartsPage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
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
        val current = _chartsPage.value
        if (current != null && current.sections.isNotEmpty()) {
            Timber.tag("AirBeatsCharts").d("loadCharts(): Cache hit with %d sections", current.sections.size)
            return
        }
        viewModelScope.launch {
            Timber.tag("AirBeatsCharts").i("loadCharts(): Fetching charts data from YouTube...")
            _isLoading.value = true
            _error.value = null

            YouTube.getChartsPage()
                .onSuccess { page ->
                    Timber.tag("AirBeatsCharts").i(
                        "loadCharts() SUCCESS: %d sections loaded -> %s",
                        page.sections.size,
                        page.sections.map { "${it.title} (${it.items.size} items)" }
                    )
                    _chartsPage.value = page
                }
                .onFailure { e ->
                    Timber.tag("AirBeatsCharts").e(e, "loadCharts() FAILED: %s", e.message)
                    _error.value = e.message ?: "Failed to load charts"
                }

            _isLoading.value = false
        }
    }

    fun retry() {
        Timber.tag("AirBeatsCharts").d("retry() called: Clearing cache and reloading charts")
        _chartsPage.value = null
        loadCharts()
    }

    fun loadMore() {
        viewModelScope.launch {
            _chartsPage.value?.continuation?.let { continuation ->
                Timber.tag("AirBeatsCharts").d("loadMore(): Requesting continuation %s", continuation)
                _isLoading.value = true
                YouTube.getChartsPage(continuation)
                    .onSuccess { newPage ->
                        Timber.tag("AirBeatsCharts").i("loadMore() SUCCESS: %d new sections loaded", newPage.sections.size)
                        _chartsPage.value = _chartsPage.value?.copy(
                            sections = _chartsPage.value?.sections.orEmpty() + newPage.sections,
                            continuation = newPage.continuation
                        )
                    }
                    .onFailure { e ->
                        Timber.tag("AirBeatsCharts").e(e, "loadMore() FAILED: %s", e.message)
                        _error.value = e.message ?: "Failed to load more charts"
                    }
                _isLoading.value = false
            }
        }
    }
}
