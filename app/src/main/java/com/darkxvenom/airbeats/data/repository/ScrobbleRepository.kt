package com.darkxvenom.airbeats.data.repository

import com.darkxvenom.airbeats.db.MusicDatabase
import com.darkxvenom.airbeats.db.entities.ArtistEntity
import com.darkxvenom.airbeats.db.entities.Event
import com.darkxvenom.airbeats.db.entities.SongArtistMap
import com.darkxvenom.airbeats.db.entities.SongEntity
import com.darkxvenom.airbeats.service.ScrobbleDebugLog
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.firstOrNull
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

    suspend fun scrobble(artist: String, track: String, album: String?, timestampSec: Long): Result {
        return runCatching {
            val safeArtist = artist.trim().ifEmpty { "Unknown Artist" }
            val safeTrack = track.trim().ifEmpty { "Unknown Track" }
            val songId = "scrobble_" + java.lang.Integer.toHexString("$safeArtist|$safeTrack".hashCode())
            val artistId = "artist_" + java.lang.Integer.toHexString(safeArtist.lowercase().hashCode())

            // Ensure song exists in local DB
            val existing = try {
                database.song(songId).firstOrNull()
            } catch (_: Exception) {
                null
            }

            database.query {
                if (existing == null) {
                    insert(
                        SongEntity(
                            id = songId,
                            title = safeTrack,
                            duration = 180,
                            albumName = album,
                            inLibrary = null,
                        )
                    )
                    insert(
                        ArtistEntity(
                            id = artistId,
                            name = safeArtist,
                        )
                    )
                    insert(
                        SongArtistMap(
                            songId = songId,
                            artistId = artistId,
                            position = 0,
                        )
                    )
                }

                // Record listening history event
                insert(
                    Event(
                        songId = songId,
                        timestamp = LocalDateTime.now(),
                        playTime = 30_000L,
                    )
                )
                incrementTotalPlayTime(songId, 30_000L)
            }

            _scrobbleEvents.tryEmit(Unit)
            debugLog.log("Scrobbled to history: \"$safeTrack\" by $safeArtist")
            Result.Success
        }.getOrElse { error ->
            Timber.e(error, "Failed to scrobble track $track")
            debugLog.log("Scrobble database insert failed: ${error.message}")
            Result.Failed(error.message ?: "Failed to save scrobble")
        }
    }
}
