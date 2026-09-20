package com.darkxvenom.airbeats.data.repository

import com.darkxvenom.airbeats.db.MusicDatabase
import com.darkxvenom.airbeats.db.entities.AlbumEntity
import com.darkxvenom.airbeats.db.entities.ArtistEntity
import com.darkxvenom.airbeats.db.entities.Event
import com.darkxvenom.airbeats.db.entities.SongAlbumMap
import com.darkxvenom.airbeats.db.entities.SongArtistMap
import com.darkxvenom.airbeats.db.entities.SongEntity
import com.darkxvenom.airbeats.service.ScrobbleDebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.time.LocalDateTime
import javax.inject.Inject
import javax.inject.Singleton

data class NowPlayingTrack(
    val title: String,
    val artist: String,
    val album: String? = null,
    val thumbnailUrl: String? = null,
    val packageName: String? = null,
    val songId: String? = null,
    val isPlaying: Boolean = true,
    val timestamp: Long = System.currentTimeMillis(),
)

@Singleton
class ScrobbleRepository @Inject constructor(
    private val database: MusicDatabase,
    private val debugLog: ScrobbleDebugLog,
) {
    @Volatile private var lastNowPlayingKey: String? = null

    private val _nowPlaying = MutableStateFlow<NowPlayingTrack?>(null)
    val nowPlaying: StateFlow<NowPlayingTrack?> = _nowPlaying.asStateFlow()

    private val _scrobbleEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val scrobbleEvents: SharedFlow<Unit> = _scrobbleEvents.asSharedFlow()

    sealed interface Result {
        data object Success : Result
        data object NoSessionKey : Result
        data class Failed(
            val message: String,
            val retryable: Boolean = false,
        ) : Result
    }

    private fun cleanArtist(raw: String): String {
        val trimmed = raw.trim()
        val suffixes = listOf(" - Topic", " Topic")
        for (suffix in suffixes) {
            if (trimmed.endsWith(suffix, ignoreCase = true)) {
                return trimmed.substring(0, trimmed.length - suffix.length).trim()
            }
        }
        return trimmed
    }

    suspend fun updateNowPlaying(
        artist: String,
        track: String,
        album: String?,
        packageName: String? = null,
    ): Result {
        val safeArtist = cleanArtist(artist.trim().ifEmpty { "Unknown Artist" })
        val safeTrack = track.trim().ifEmpty { "Unknown Track" }
        val key = "${safeArtist.lowercase()}|${safeTrack.lowercase()}"

        val (matchedSong, matchedArtist) = withContext(Dispatchers.IO) {
            val song = database.findSongByTitleAndArtist(safeTrack, safeArtist)
                ?: database.findSongByTitle(safeTrack)
            val art = if (song == null) database.artistByName(safeArtist) else null
            song to art
        }

        val displayTitle = matchedSong?.title ?: safeTrack
        val displayArtist = matchedSong?.artists?.firstOrNull()?.name ?: matchedArtist?.name ?: safeArtist
        val displayAlbum = matchedSong?.album?.title ?: album?.trim()
        val thumb = matchedSong?.thumbnailUrl?.takeIf { it.isNotBlank() }
            ?: matchedArtist?.thumbnailUrl?.takeIf { it.isNotBlank() }

        _nowPlaying.value = NowPlayingTrack(
            title = displayTitle,
            artist = displayArtist,
            album = displayAlbum,
            thumbnailUrl = thumb,
            packageName = packageName,
            songId = matchedSong?.id,
            isPlaying = true,
        )

        if (key != lastNowPlayingKey) {
            lastNowPlayingKey = key
            val matchNote = if (matchedSong != null) " (matched: ${matchedSong.id})" else ""
            debugLog.log("Now Playing: \"$displayTitle\" by $displayArtist$matchNote")
        }
        return Result.Success
    }

    fun clearNowPlaying() {
        if (_nowPlaying.value != null) {
            debugLog.log("Playback paused/stopped — cleared now playing")
        }
        lastNowPlayingKey = null
        _nowPlaying.value = null
    }

    suspend fun scrobble(
        artist: String,
        track: String,
        album: String?,
        timestampSec: Long,
        durationMs: Long = 0L,
        playTimeMs: Long = 0L,
    ): Result {
        return runCatching {
            val safeArtist = cleanArtist(artist.trim().ifEmpty { "Unknown Artist" })
            val safeTrack = track.trim().ifEmpty { "Unknown Track" }
            val actualDurationSec = if (durationMs > 0L) (durationMs / 1000L).toInt() else 180
            val actualPlayTimeMs = if (playTimeMs > 0L) playTimeMs else if (durationMs > 0L) durationMs else 180_000L

            withContext(Dispatchers.IO) {
                // 1. Check if the song already exists in AirBeats
                val matchedSong = database.findSongByTitleAndArtist(safeTrack, safeArtist)
                    ?: database.findSongByTitle(safeTrack)

                val finalSongId: String
                if (matchedSong != null) {
                    finalSongId = matchedSong.id
                    debugLog.log("Matched existing AirBeats track \"${matchedSong.title}\" ($finalSongId)")
                } else {
                    // Not in database: generate deterministic IDs
                    finalSongId = "scrobble_" + java.lang.Integer.toHexString("$safeArtist|$safeTrack".hashCode())

                    // Check if artist exists in AirBeats
                    val existingArtist = database.artistByName(safeArtist)
                    val finalArtistId = existingArtist?.id
                        ?: ("artist_" + java.lang.Integer.toHexString(safeArtist.lowercase().hashCode()))

                    val existing = database.getSongById(finalSongId)
                    if (existing == null) {
                        database.insert(
                            SongEntity(
                                id = finalSongId,
                                title = safeTrack,
                                duration = actualDurationSec,
                                albumName = album,
                                thumbnailUrl = existingArtist?.thumbnailUrl ?: "",
                                inLibrary = null,
                            )
                        )
                        if (existingArtist == null) {
                            database.insert(
                                ArtistEntity(
                                    id = finalArtistId,
                                    name = safeArtist,
                                    thumbnailUrl = "",
                                )
                            )
                        }
                        database.insert(
                            SongArtistMap(
                                songId = finalSongId,
                                artistId = finalArtistId,
                                position = 0,
                            )
                        )
                        if (!album.isNullOrBlank()) {
                            val existingAlbum = database.albumByName(album.trim())
                            val finalAlbumId = existingAlbum?.id
                                ?: ("album_" + java.lang.Integer.toHexString(album.lowercase().hashCode()))
                            if (existingAlbum == null) {
                                database.insert(
                                    AlbumEntity(
                                        id = finalAlbumId,
                                        title = album.trim(),
                                        songCount = 1,
                                        duration = actualDurationSec,
                                        thumbnailUrl = "",
                                    )
                                )
                            }
                            database.insert(
                                SongAlbumMap(
                                    songId = finalSongId,
                                    albumId = finalAlbumId,
                                    index = 0,
                                )
                            )
                        }
                    }
                }

                // Record listening history event
                database.insert(
                    Event(
                        songId = finalSongId,
                        timestamp = LocalDateTime.now(),
                        playTime = actualPlayTimeMs,
                    )
                )
                database.incrementTotalPlayTime(finalSongId, actualPlayTimeMs)
                try {
                    database.incrementPlayCount(finalSongId)
                } catch (e: Exception) {
                    Timber.w(e, "incrementPlayCount failed for $finalSongId")
                }
            }

            _scrobbleEvents.tryEmit(Unit)
            debugLog.log("Scrobbled to history: \"$safeTrack\" by $safeArtist (${actualPlayTimeMs / 1000}s play time)")
            Result.Success
        }.getOrElse { error ->
            Timber.e(error, "Failed to scrobble track $track")
            debugLog.log("Scrobble database insert failed: ${error.message}")
            Result.Failed(error.message ?: "Failed to save scrobble")
        }
    }
}
