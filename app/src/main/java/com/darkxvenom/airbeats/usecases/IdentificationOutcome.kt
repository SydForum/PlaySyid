package com.darkxvenom.airbeats.usecases

import com.darkxvenom.airbeats.providers.ProviderSong
import com.darkxvenom.airbeats.songs.IdentifiedSong

sealed class IdentificationOutcome {
    data class Success(
        val song: IdentifiedSong,
        val airBeatsMatch: ProviderSong?,
        val candidates: List<ProviderSong> = emptyList()
    ) : IdentificationOutcome()

    data object NoMusicFound : IdentificationOutcome()
    data object NoAudio : IdentificationOutcome()
    data class UnsupportedMedia(val reason: String) : IdentificationOutcome()
    data class IsUrlOnly(val url: String) : IdentificationOutcome()
    data object NetworkError : IdentificationOutcome()
    data class Error(val message: String) : IdentificationOutcome()
}
