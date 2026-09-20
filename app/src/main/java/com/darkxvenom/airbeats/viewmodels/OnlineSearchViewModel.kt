/*
 * AirBeats Project Original (2026)
 * Licensed Under GPL-3.0 | see git history for contributors
 */



package com.darkxvenom.airbeats.viewmodels

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.darkxvenom.airbeats.innertube.YouTube
import com.darkxvenom.airbeats.innertube.models.filterExplicit
import com.darkxvenom.airbeats.innertube.models.filterVideo
import com.darkxvenom.airbeats.innertube.pages.SearchSummaryPage
import com.darkxvenom.airbeats.constants.HideExplicitKey
import com.darkxvenom.airbeats.constants.HideVideoKey
import com.darkxvenom.airbeats.models.ItemsPage
import com.darkxvenom.airbeats.utils.dataStore
import com.darkxvenom.airbeats.utils.get
import com.darkxvenom.airbeats.utils.reportException
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

import com.darkxvenom.airbeats.innertube.models.YTItem
import com.darkxvenom.airbeats.innertube.pages.SearchSummary
import com.darkxvenom.airbeats.constants.EnableJioSaavnKey
import com.darkxvenom.airbeats.jiosaavn.JioSaavnApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async

@HiltViewModel
class OnlineSearchViewModel
@Inject
constructor(
    @ApplicationContext val context: Context,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    val query = savedStateHandle.get<String>("query")!!
    val filter = MutableStateFlow<YouTube.SearchFilter?>(null)
    var summaryPage by mutableStateOf<SearchSummaryPage?>(null)
    val viewStateMap = mutableStateMapOf<String, ItemsPage?>()

    init {
        viewModelScope.launch {
            val enableJioSaavn = context.dataStore.get(EnableJioSaavnKey, true)
            filter.collect { filter ->
                if (filter == null) {
                    if (summaryPage == null) {
                        if (enableJioSaavn) {
                            val jioDeferred = async(Dispatchers.IO) {
                                JioSaavnApi.searchSongs(query).getOrNull().orEmpty()
                            }
                            val ytDeferred = async(Dispatchers.IO) {
                                YouTube.searchSummary(query).getOrNull()
                            }
                            val jioSongs = jioDeferred.await()
                            val ytSummary = ytDeferred.await()

                            if (ytSummary != null) {
                                val filteredSummary = ytSummary
                                    .filterExplicit(context.dataStore.get(HideExplicitKey, false))
                                    .filterVideo(context.dataStore.get(HideVideoKey, false))
                                if (jioSongs.isNotEmpty()) {
                                    val summaries = filteredSummary.summaries.toMutableList()
                                    val songsIndex = summaries.indexOfFirst { it.title.equals("Songs", ignoreCase = true) }
                                    if (songsIndex != -1) {
                                        val orig = summaries[songsIndex]
                                        summaries[songsIndex] = orig.copy(
                                            items = (jioSongs + orig.items).distinctBy { it.id }
                                        )
                                    } else {
                                        summaries.add(0, SearchSummary(title = "Songs", items = jioSongs))
                                    }
                                    summaryPage = SearchSummaryPage(summaries = summaries)
                                } else {
                                    summaryPage = filteredSummary
                                }
                            } else if (jioSongs.isNotEmpty()) {
                                summaryPage = SearchSummaryPage(
                                    summaries = listOf(SearchSummary(title = "Songs", items = jioSongs))
                                )
                            } else {
                                reportException(Exception("Search failed for query: $query"))
                            }
                        } else {
                            YouTube
                                .searchSummary(query)
                                .onSuccess {
                                    summaryPage = it.filterExplicit(context.dataStore.get(HideExplicitKey, false)).filterVideo(context.dataStore.get(HideVideoKey, false))
                                }.onFailure {
                                    reportException(it)
                                }
                        }
                    }
                } else {
                    if (viewStateMap[filter.value] == null) {
                        if (enableJioSaavn && filter == YouTube.SearchFilter.FILTER_SONG) {
                            val jioDeferred = async(Dispatchers.IO) {
                                JioSaavnApi.searchSongs(query).getOrNull().orEmpty()
                            }
                            val ytDeferred = async(Dispatchers.IO) {
                                YouTube.search(query, filter).getOrNull()
                            }
                            val jioSongs = jioDeferred.await()
                            val ytResult = ytDeferred.await()

                            val combined = mutableListOf<YTItem>()
                            combined.addAll(jioSongs)
                            if (ytResult != null) {
                                val ytItems = ytResult.items
                                    .distinctBy { it.id }
                                    .filterExplicit(context.dataStore.get(HideExplicitKey, false))
                                    .filterVideo(context.dataStore.get(HideVideoKey, false))
                                combined.addAll(ytItems)
                                viewStateMap[filter.value] = ItemsPage(
                                    combined.distinctBy { it.id },
                                    ytResult.continuation
                                )
                            } else if (jioSongs.isNotEmpty()) {
                                viewStateMap[filter.value] = ItemsPage(jioSongs, null)
                            } else {
                                reportException(Exception("Search songs failed for query: $query"))
                            }
                        } else {
                            YouTube
                                .search(query, filter)
                                .onSuccess { result ->
                                    viewStateMap[filter.value] =
                                        ItemsPage(
                                            result.items
                                                .distinctBy { it.id }
                                                .filterExplicit(
                                                    context.dataStore.get(
                                                        HideExplicitKey,
                                                        false
                                                    )
                                                ).filterVideo(context.dataStore.get(HideVideoKey, false)),
                                            result.continuation,
                                        )
                                }.onFailure {
                                    reportException(it)
                                }
                        }
                    }
                }
            }
        }
    }

    fun loadMore() {
        val filter = filter.value?.value
        viewModelScope.launch {
            if (filter == null) return@launch
            val viewState = viewStateMap[filter] ?: return@launch
            val continuation = viewState.continuation
            if (continuation != null) {
                val searchResult =
                    YouTube.searchContinuation(continuation).getOrNull() ?: return@launch
                viewStateMap[filter] = ItemsPage(
                    (viewState.items + searchResult.items).distinctBy { it.id },
                    searchResult.continuation
                )
            }
        }
    }
}

