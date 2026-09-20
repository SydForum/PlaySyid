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
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.time.LocalDateTime
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ScrobbleRepository @Inject constructor(
    private val database: MusicDatabase,
    private val debugLog: ScrobbleDebugLog,
) {
    @Volatile private var lastNowPlayingKey: String? = null

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

    suspend fun updateNowPlaying(artist: String, track: String, album: String?): Result {
        val key = "${artist.lowercase().trim()}|${track.lowercase().trim()}"
        if (key == lastNowPlayingKey) {
            return Result.Success
        }
        lastNowPlayingKey = key
        debugLog.log("Now Playing: \"$track\" by $artist")
        return Result.Success
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
            val safeArtist = artist.trim().ifEmpty { "Unknown Artist" }
            val safeTrack = track.trim().ifEmpty { "Unknown Track" }
            val songId = "scrobble_" + java.lang.Integer.toHexString("$safeArtist|$safeTrack".hashCode())
            val artistId = "artist_" + java.lang.Integer.toHexString(safeArtist.lowercase().hashCode())
            val actualDurationSec = if (durationMs > 0L) (durationMs / 1000L).toInt() else 180
            val actualPlayTimeMs = if (playTimeMs > 0L) playTimeMs else if (durationMs > 0L) durationMs else 180_000L

            withContext(Dispatchers.IO) {
                val existing = database.getSongById(songId)
                if (existing == null) {
                    database.insert(
                        SongEntity(
                            id = songId,
                            title = safeTrack,
                            duration = actualDurationSec,
                            albumName = album,
                            thumbnailUrl = "",
                            inLibrary = null,
                        )
                    )
                    database.insert(
                        ArtistEntity(
                            id = artistId,
                            name = safeArtist,
                            thumbnailUrl = "",
                        )
                    )
                    database.insert(
                        SongArtistMap(
                            songId = songId,
                            artistId = artistId,
                            position = 0,
                        )
                    )
                    if (!album.isNullOrBlank()) {
                        val albumId = "album_" + java.lang.Integer.toHexString(album.lowercase().hashCode())
                        database.insert(
                            AlbumEntity(
                                id = albumId,
                                title = album,
                                songCount = 1,
                                duration = actualDurationSec,
                                thumbnailUrl = "",
                            )
                        )
                        database.insert(
                            SongAlbumMap(
                                songId = songId,
                                albumId = albumId,
                                index = 0,
                            )
                        )
                    }
                }

                // Record listening history event
                database.insert(
                    Event(
                        songId = songId,
                        timestamp = LocalDateTime.now(),
                        playTime = actualPlayTimeMs,
                    )
                )
                database.incrementTotalPlayTime(songId, actualPlayTimeMs)
                try {
                    database.incrementPlayCount(songId)
                } catch (e: Exception) {
                    Timber.w(e, "incrementPlayCount failed for $songId")
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
