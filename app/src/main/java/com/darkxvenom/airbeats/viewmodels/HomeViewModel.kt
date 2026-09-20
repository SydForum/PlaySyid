package com.darkxvenom.airbeats.viewmodels

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.darkxvenom.airbeats.innertube.YouTube
import com.darkxvenom.airbeats.innertube.models.PlaylistItem
import com.darkxvenom.airbeats.innertube.models.SongItem
import com.darkxvenom.airbeats.innertube.models.WatchEndpoint
import com.darkxvenom.airbeats.innertube.models.YTItem
import com.darkxvenom.airbeats.innertube.pages.ExplorePage
import com.darkxvenom.airbeats.innertube.pages.HomePage
import com.darkxvenom.airbeats.innertube.utils.completedLibraryPage
import com.darkxvenom.airbeats.db.MusicDatabase
import com.darkxvenom.airbeats.db.entities.Album
import com.darkxvenom.airbeats.db.entities.Artist
import com.darkxvenom.airbeats.db.entities.LocalItem
import com.darkxvenom.airbeats.db.entities.Playlist
import com.darkxvenom.airbeats.db.entities.Song
import com.darkxvenom.airbeats.models.SimilarRecommendation
import com.darkxvenom.airbeats.models.toMediaMetadata
import com.darkxvenom.airbeats.utils.dataStore
import com.darkxvenom.airbeats.utils.get
import com.darkxvenom.airbeats.utils.reportException
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import javax.inject.Inject

data class HeroPlaylistData(
    val title: String,
    val subtitle: String,
    val tag: String,
    val thumbnailUrl: String?,
    val songs: List<Song>,
    val playlistId: String? = null,
)


@HiltViewModel
class HomeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    val database: MusicDatabase,
) : ViewModel() {
    private val homeRandom = kotlin.random.Random(System.currentTimeMillis())
    val isRefreshing = MutableStateFlow(false)
    val isLoading = MutableStateFlow(false)

    val quickPicks = MutableStateFlow<List<Song>?>(null)
    val forgottenFavorites = MutableStateFlow<List<Song>?>(null)
    val keepListening = MutableStateFlow<List<LocalItem>?>(null)
    val similarRecommendations = MutableStateFlow<List<SimilarRecommendation>?>(null)
    val accountPlaylists = MutableStateFlow<List<PlaylistItem>?>(null)
    val homePage = MutableStateFlow<HomePage?>(null)
    val explorePage = MutableStateFlow<ExplorePage?>(null)
    val recentActivity = MutableStateFlow<List<YTItem>?>(null)
    val recentPlaylistsDb = MutableStateFlow<List<Playlist>?>(null)

    val aiRecommendedPlaylist = database.playlistsByNameAsc()
        .map { playlists -> playlists.find { it.playlist.name == "Recommended by AI" } }
        .flatMapLatest { playlist ->
            if (playlist != null && playlist.songCount > 0) {
                database.playlistSongs(playlist.playlist.id).map { playlistSongs ->
                    playlist to playlistSongs.map { it.song }
                }
            } else {
                flowOf(null)
            }
        }
        .stateIn(viewModelScope, SharingStarted.Lazily, null)

    val heroPlaylist = database.playlists(com.darkxvenom.airbeats.constants.PlaylistSortType.LAST_UPDATED, descending = true)
        .flatMapLatest { playlists ->
            val topPlaylist = playlists.firstOrNull { it.songCount > 0 }
            if (topPlaylist != null) {
                database.playlistSongs(topPlaylist.playlist.id).map { playlistSongs ->
                    val songs = playlistSongs.map { it.song }
                    HeroPlaylistData(
                        title = topPlaylist.playlist.name,
                        subtitle = "Based on your last listening habits and artists...",
                        tag = "TOP PLAYLIST",
                        thumbnailUrl = topPlaylist.thumbnails.firstOrNull() ?: songs.firstOrNull()?.thumbnailUrl,
                        songs = songs,
                        playlistId = topPlaylist.playlist.id,
                    )
                }
            } else {
                flowOf(null)
            }
        }
        .stateIn(viewModelScope, SharingStarted.Lazily, null)


    val allLocalItems = MutableStateFlow<List<LocalItem>>(emptyList())
    val allYtItems = MutableStateFlow<List<YTItem>>(emptyList())

    val accountName = MutableStateFlow("Guest")
    val accountImageUrl = MutableStateFlow<String?>(null)

    private var loadJob: Job? = null
    private var accountFingerprint: Int? = null

    private fun mapToSong(item: SongItem): Song {
        return Song(
            song = item.toMediaMetadata().toSongEntity(),
            artists = item.artists.map { a ->
                com.darkxvenom.airbeats.db.entities.ArtistEntity(id = a.id ?: "", name = a.name)
            },
            album = item.album?.let { a ->
                com.darkxvenom.airbeats.db.entities.AlbumEntity(id = a.id, title = a.name, songCount = 0, duration = 0)
            }
        )
    }

    private suspend fun load() {
        isLoading.value = true

        val musicProvider = context.dataStore.get(com.darkxvenom.airbeats.constants.MusicProviderKey, "YT")
        val isJioSaavn = musicProvider == "JIOSAAVN"

        if (isJioSaavn) {
            com.darkxvenom.airbeats.jiosaavn.JioSaavnApi.getTrendingSongs().onSuccess { songs ->
                homePage.value = HomePage(
                    chips = null,
                    sections = listOf(
                        HomePage.Section(
                            title = "Trending Songs",
                            label = "JioSaavn",
                            thumbnail = null,
                            endpoint = null,
                            items = songs
                        )
                    )
                )
                if (quickPicks.value.isNullOrEmpty()) {
                    quickPicks.value = songs.filterIsInstance<SongItem>().map(::mapToSong).take(20)
                }
            }.onFailure {
                reportException(it)
            }
            explorePage.value = ExplorePage(emptyList(), emptyList())
        }

        supervisorScope {
            // 1. Quick Picks snapshot
            launch(Dispatchers.IO) {
                if (isJioSaavn) return@launch
                val qpList = runCatching { database.quickPicks().first() }.getOrDefault(emptyList()).filter { !it.id.startsWith("JS:") }
                val rawPicks = if (qpList.isNotEmpty()) {
                    qpList
                } else {
                    runCatching { database.recentSongs(limit = 60).first() }.getOrDefault(emptyList()).filter { !it.id.startsWith("JS:") }
                }
                quickPicks.value = rawPicks.distinctBy { it.id }.shuffled(homeRandom).take(20).takeIf { it.isNotEmpty() }
            }

            // 2. Keep Listening snapshot
            launch(Dispatchers.IO) {
                val songs = runCatching { database.recentSongs(limit = 50, offset = 0).first() }.getOrDefault(emptyList())
                    .filter { if (isJioSaavn) it.id.startsWith("JS:") else !it.id.startsWith("JS:") }
                    .distinctBy { it.id }.shuffled(homeRandom).take(10)
                val albums = runCatching { database.recentAlbums(limit = 50, offset = 0).first() }.getOrDefault(emptyList())
                    .filter { it.album.thumbnailUrl != null && (if (isJioSaavn) it.id.startsWith("JS:") else !it.id.startsWith("JS:")) }
                    .distinctBy { it.id }.shuffled(homeRandom).take(5)
                val artists = runCatching { database.recentArtists(limit = 50, offset = 0).first() }.getOrDefault(emptyList())
                    .filter { it.artist.isYouTubeArtist && it.artist.thumbnailUrl != null && (if (isJioSaavn) it.id.startsWith("JS:") else !it.id.startsWith("JS:")) }
                    .distinctBy { it.id }.shuffled(homeRandom).take(5)
                keepListening.value = (songs + albums + artists).shuffled(homeRandom).takeIf { it.isNotEmpty() }
            }

            // 3. Forgotten Favorites snapshot
            launch(Dispatchers.IO) {
                val favs = runCatching { database.forgottenFavorites().first() }.getOrDefault(emptyList())
                    .filter { if (isJioSaavn) it.id.startsWith("JS:") else !it.id.startsWith("JS:") }
                    .distinctBy { it.id }.shuffled(homeRandom).take(20)
                forgottenFavorites.value = favs.takeIf { it.isNotEmpty() }
            }

            // 4. Remote items snapshot (YouTube)
            if (!isJioSaavn) {
                launch(Dispatchers.IO) {
                    if (YouTube.cookie?.contains("SAPISID=") == true) {
                        YouTube.library("FEmusic_liked_playlists").completedLibraryPage().onSuccess {
                            accountPlaylists.value = it.items.filterIsInstance<PlaylistItem>().filterNot { it.id == "SE" }
                        }.onFailure { reportException(it) }
                    }
                }

                launch(Dispatchers.IO) {
                    val recentArtistsList = runCatching { database.recentArtists(limit = 10).first() }.getOrDefault(emptyList())
                    val artistRecs = recentArtistsList.filter { it.artist.isYouTubeArtist }.shuffled(homeRandom).take(3).mapNotNull {
                        val items = mutableListOf<YTItem>()
                        YouTube.artist(it.id).onSuccess { page ->
                            items += page.sections.getOrNull(page.sections.size - 2)?.items.orEmpty()
                            items += page.sections.lastOrNull()?.items.orEmpty()
                        }
                        SimilarRecommendation(title = it, items = items.distinctBy { it.id }.shuffled(homeRandom).take(8)).takeIf { it.items.isNotEmpty() }
                    }
                    val songRecs = runCatching { database.recentSongs(limit = 10).first() }.getOrDefault(emptyList()).filter { !it.id.startsWith("JS:") }.shuffled(homeRandom).take(2).mapNotNull { song ->
                        val endpoint = YouTube.next(WatchEndpoint(videoId = song.id)).getOrNull()?.relatedEndpoint ?: return@mapNotNull null
                        val page = YouTube.related(endpoint).getOrNull() ?: return@mapNotNull null
                        SimilarRecommendation(title = song, items = (page.songs.shuffled(homeRandom).take(8) + page.albums.shuffled(homeRandom).take(4) + page.artists.shuffled(homeRandom).take(4) + page.playlists.shuffled(homeRandom).take(4)).distinctBy { it.id }.shuffled(homeRandom).take(10))
                    }
                    similarRecommendations.value = (artistRecs + songRecs).shuffled(homeRandom).takeIf { it.isNotEmpty() }
                }

                launch(Dispatchers.IO) {
                    val enableJioSaavn = context.dataStore.get(com.darkxvenom.airbeats.constants.EnableJioSaavnKey, true)
                    val jioSection = if (enableJioSaavn) {
                        com.darkxvenom.airbeats.jiosaavn.JioSaavnApi.getTrendingSongs().getOrNull()?.let { songs ->
                            HomePage.Section(
                                title = "Trending on JioSaavn (320k)",
                                label = "JioSaavn",
                                thumbnail = null,
                                endpoint = null,
                                items = songs
                            )
                        }
                    } else null

                    YouTube.home().onSuccess { page ->
                        homePage.value = if (jioSection != null) {
                            page.copy(sections = listOf(jioSection) + page.sections)
                        } else {
                            page
                        }
                    }.onFailure {
                        if (jioSection != null) {
                            homePage.value = HomePage(chips = null, sections = listOf(jioSection))
                        }
                        reportException(it)
                    }
                }

                launch(Dispatchers.IO) {
                    YouTube.explore().onSuccess { explorePage.value = it }.onFailure { reportException(it) }
                }
            }
        }

        allLocalItems.value = (quickPicks.value.orEmpty() + forgottenFavorites.value.orEmpty() + keepListening.value.orEmpty()).filter { it is Song || it is Album }
        allYtItems.value = similarRecommendations.value?.flatMap { it.items }.orEmpty() + homePage.value?.sections?.flatMap { it.items }.orEmpty() + explorePage.value?.newReleaseAlbums.orEmpty()
        isLoading.value = false
    }

    fun refresh() {
        if (isRefreshing.value) return
        loadJob?.cancel()
        // Set this before launching. Setting it inside the coroutine leaves a
        // race where repeated UI events can each cancel and restart Home.
        isRefreshing.value = true
        loadJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                load()
            } finally {
                isRefreshing.value = false
            }
        }
    }

    /** Refresh the personalised YouTube Music home feed exactly once per login/logout. */
    fun onAccountChanged(cookie: String) {
        val fingerprint = cookie.hashCode()
        if (accountFingerprint == fingerprint) return
        val isFirst = (accountFingerprint == null)
        accountFingerprint = fingerprint
        // The application collector is asynchronous; set the client now so the
        // refresh cannot accidentally request the anonymous home feed.
        YouTube.cookie = cookie
        if (!isFirst) {
            loadJob?.cancel()
            loadJob = viewModelScope.launch(Dispatchers.IO) {
                load()
            }
        }
    }

    init {
        loadJob = viewModelScope.launch(Dispatchers.IO) {
            load()
        }
    }
}
