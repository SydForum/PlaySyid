package com.darkxvenom.airbeats.playback.queues

import androidx.media3.common.MediaItem
import com.darkxvenom.airbeats.innertube.YouTube
import com.darkxvenom.airbeats.innertube.models.SongItem
import com.darkxvenom.airbeats.innertube.models.WatchEndpoint
import com.darkxvenom.airbeats.extensions.toMediaItem
import com.darkxvenom.airbeats.models.MediaMetadata
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.withContext

class YouTubeQueue(
    private var endpoint: WatchEndpoint,
    override val preloadItem: MediaMetadata? = null,
) : Queue {
    private var continuation: String? = null

    override suspend fun getInitialStatus(): Queue.Status {
        if (endpoint.videoId?.startsWith("JS:") == true || preloadItem?.id?.startsWith("JS:") == true) {
            val jioItem = preloadItem?.toMediaItem()
            val title = preloadItem?.title.orEmpty()
            val artist = preloadItem?.artists?.firstOrNull()?.name.orEmpty()
            val query = "$title $artist".trim()

            var ytMatchedEndpoint: WatchEndpoint? = null
            if (query.isNotEmpty()) {
                withContext(IO) {
                    runCatching {
                        val searchRes = YouTube.search(query, YouTube.SearchFilter.FILTER_SONG).getOrNull()
                        val match = searchRes?.items?.firstOrNull() as? SongItem
                        if (match != null) {
                            ytMatchedEndpoint = WatchEndpoint(videoId = match.id)
                        }
                    }
                }
            }

            if (ytMatchedEndpoint != null) {
                val nextResult = withContext(IO) {
                    runCatching { YouTube.next(ytMatchedEndpoint!!).getOrNull() }.getOrNull()
                }
                if (nextResult != null) {
                    endpoint = nextResult.endpoint
                    continuation = nextResult.continuation
                    val recItems = nextResult.items.map { it.toMediaItem() }
                    val allItems = if (jioItem != null) {
                        listOf(jioItem) + recItems.filter { it.mediaId != jioItem.mediaId }
                    } else {
                        recItems
                    }
                    return Queue.Status(
                        title = nextResult.title ?: preloadItem?.title,
                        items = allItems,
                        mediaItemIndex = 0,
                    )
                }
            }

            // Fallback: If YouTube radio matching failed, play the JioSaavn item standalone
            if (jioItem != null) {
                return Queue.Status(
                    title = preloadItem?.title,
                    items = listOf(jioItem),
                    mediaItemIndex = 0,
                )
            }
        }

        val nextResult =
            withContext(IO) {
                YouTube.next(endpoint, continuation).getOrThrow()
            }
        endpoint = nextResult.endpoint
        continuation = nextResult.continuation
        return Queue.Status(
            title = nextResult.title,
            items = nextResult.items.map { it.toMediaItem() },
            mediaItemIndex = nextResult.currentIndex ?: 0,
        )
    }

    override fun hasNextPage(): Boolean = continuation != null

    override suspend fun nextPage(): List<MediaItem> {
        val nextResult =
            withContext(IO) {
                YouTube.next(endpoint, continuation).getOrThrow()
            }
        endpoint = nextResult.endpoint
        continuation = nextResult.continuation
        return nextResult.items.map { it.toMediaItem() }
    }

    companion object {
        fun radio(song: MediaMetadata) = YouTubeQueue(WatchEndpoint(song.id), song)
    }
}
