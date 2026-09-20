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
import com.darkxvenom.airbeats.jiosaavn.TrackMatcher
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
            filter.collect { filter ->
                val enableJioSaavn = context.dataStore.get(EnableJioSaavnKey, true)
                val hideExplicit = context.dataStore.get(HideExplicitKey, false)
                val hideVideo = context.dataStore.get(HideVideoKey, false)

                if (filter == null) {
                    if (summaryPage == null) {
                        if (enableJioSaavn) {
                            val jioDeferred = async(Dispatchers.IO) {
                                val raw = JioSaavnApi.searchSongs(query).getOrNull().orEmpty()
                                raw.filter { TrackMatcher.matchesQuery(it, query) }
                            }
                            val ytDeferred = async(Dispatchers.IO) {
                                YouTube.searchSummary(query).getOrNull()
                            }
                            val accurateJioSongs = jioDeferred.await()
                            var ytSummary = ytDeferred.await()

                            // Fallback if YouTube searchSummary fails: try YouTube FILTER_SONG
                            if (ytSummary == null) {
                                val ytSongs = async(Dispatchers.IO) {
                                    YouTube.search(query, YouTube.SearchFilter.FILTER_SONG).getOrNull()
                                }.await()
                                if (ytSongs != null && ytSongs.items.isNotEmpty()) {
                                    ytSummary = SearchSummaryPage(
                                        summaries = listOf(
                                            SearchSummary(title = "Songs", items = ytSongs.items)
                                        )
                                    )
                                }
                            }

                            if (ytSummary != null) {
                                val filteredSummary = ytSummary
                                    .filterExplicit(hideExplicit)
                                    .filterVideo(hideVideo)
                                if (accurateJioSongs.isNotEmpty()) {
                                    val summaries = filteredSummary.summaries.toMutableList()
                                    val songsIndex = summaries.indexOfFirst { it.title.equals("Songs", ignoreCase = true) }
                                    val topJio = accurateJioSongs.take(3)
                                    if (songsIndex != -1) {
                                        val orig = summaries[songsIndex]
                                        summaries[songsIndex] = orig.copy(
                                            items = (topJio + orig.items).distinctBy { it.id }
                                        )
                                    } else {
                                        summaries.add(0, SearchSummary(title = "Songs", items = topJio))
                                    }
                                    summaryPage = SearchSummaryPage(summaries = summaries)
                                } else {
                                    summaryPage = filteredSummary
                                }
                            } else if (accurateJioSongs.isNotEmpty()) {
                                summaryPage = SearchSummaryPage(
                                    summaries = listOf(SearchSummary(title = "Songs", items = accurateJioSongs.take(10)))
                                )
                            } else {
                                reportException(Exception("Search failed for query: $query"))
                            }
                        } else {
                            YouTube
                                .searchSummary(query)
                                .onSuccess {
                                    summaryPage = it.filterExplicit(hideExplicit).filterVideo(hideVideo)
                                }.onFailure {
                                    reportException(it)
                                }
                        }
                    }
                } else {
                    if (viewStateMap[filter.value] == null) {
                        if (enableJioSaavn && filter == YouTube.SearchFilter.FILTER_SONG) {
                            val jioDeferred = async(Dispatchers.IO) {
                                val raw = JioSaavnApi.searchSongs(query).getOrNull().orEmpty()
                                raw.filter { TrackMatcher.matchesQuery(it, query) }
                            }
                            val ytDeferred = async(Dispatchers.IO) {
                                YouTube.search(query, filter).getOrNull()
                            }
                            val accurateJioSongs = jioDeferred.await()
                            val ytResult = ytDeferred.await()

                            val combined = mutableListOf<YTItem>()
                            combined.addAll(accurateJioSongs.take(3))
                            if (ytResult != null) {
                                val ytItems = ytResult.items
                                    .distinctBy { it.id }
                                    .filterExplicit(hideExplicit)
                                    .filterVideo(hideVideo)
                                combined.addAll(ytItems)
                                viewStateMap[filter.value] = ItemsPage(
                                    combined.distinctBy { it.id },
                                    ytResult.continuation
                                )
                            } else if (accurateJioSongs.isNotEmpty()) {
                                viewStateMap[filter.value] = ItemsPage(accurateJioSongs, null)
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
                                                .filterExplicit(hideExplicit)
                                                .filterVideo(hideVideo),
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

