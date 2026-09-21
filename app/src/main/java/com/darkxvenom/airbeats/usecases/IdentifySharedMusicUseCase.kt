package com.darkxvenom.airbeats.usecases

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.darkxvenom.airbeats.media.MediaInspector
import com.darkxvenom.airbeats.media.TemporaryMediaManager
import com.darkxvenom.airbeats.providers.ProviderSearchManager
import com.darkxvenom.airbeats.recognition.AudDRecognitionEngine
import com.darkxvenom.airbeats.recognition.AudioExtractor
import com.darkxvenom.airbeats.recognition.AudioSegmentSelector
import com.darkxvenom.airbeats.recognition.AudioSource
import com.darkxvenom.airbeats.recognition.MusicRecognitionEngine
import com.darkxvenom.airbeats.recognition.RecognitionCache
import com.darkxvenom.airbeats.recognition.RecognitionResult
import com.darkxvenom.airbeats.share.SharedContent
import com.darkxvenom.airbeats.share.SharedContentType
import com.darkxvenom.airbeats.songs.IdentifiedSong
import com.darkxvenom.airbeats.songs.SongMetadataNormalizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

enum class IdentificationStep {
    VALIDATING,
    ANALYZING_MEDIA,
    EXTRACTING_AUDIO,
    IDENTIFYING,
    SEARCHING_AIRBEATS,
    COMPLETE
}

class IdentifySharedMusicUseCase(
    private val context: Context,
    private val tempManager: TemporaryMediaManager = TemporaryMediaManager(context),
    private val audioExtractor: AudioExtractor = AudioExtractor(context, tempManager),
    private val recognitionEngine: MusicRecognitionEngine = AudDRecognitionEngine(context),
    private val providerSearchManager: ProviderSearchManager = ProviderSearchManager(context)
) {

    companion object {
        private const val TAG = "IdentifyMusicUseCase"
    }

    fun execute(content: SharedContent): Flow<Pair<IdentificationStep, IdentificationOutcome?>> = flow {
        emit(Pair(IdentificationStep.VALIDATING, null))

        // 1. Validate content type
        if (content.type == SharedContentType.URL) {
            emit(Pair(IdentificationStep.COMPLETE, IdentificationOutcome.IsUrlOnly(content.text.orEmpty())))
            return@flow
        }

        val uri = content.uri
        if (uri == null) {
            emit(Pair(IdentificationStep.COMPLETE, IdentificationOutcome.UnsupportedMedia("No media URI provided")))
            return@flow
        }

        // 2. Check network connectivity
        if (!isNetworkConnected(context)) {
            emit(Pair(IdentificationStep.COMPLETE, IdentificationOutcome.NetworkError))
            return@flow
        }

        // 3. Inspect Media
        emit(Pair(IdentificationStep.ANALYZING_MEDIA, null))
        val mediaInfo = withContext(Dispatchers.IO) {
            MediaInspector.inspect(context, uri)
        }

        if (!mediaInfo.hasAudio) {
            emit(Pair(IdentificationStep.COMPLETE, IdentificationOutcome.NoAudio))
            return@flow
        }

        var currentAudioSource: AudioSource? = null

        try {
            // 4. Extract Audio Segment (focused on the first 30s as requested)
            emit(Pair(IdentificationStep.EXTRACTING_AUDIO, null))
            val window1 = AudioSegmentSelector.selectSegment(mediaInfo.durationMs, candidateIndex = 0)
            currentAudioSource = audioExtractor.extractSegment(
                uri = uri,
                mediaInfo = mediaInfo,
                startMs = window1.startMs,
                durationMs = window1.durationMs
            )

            // Check local recognition cache
            val hash = RecognitionCache.computeHash(currentAudioSource.file)
            val cachedSong = RecognitionCache.get(hash)

            val identifiedSong: IdentifiedSong = if (cachedSong != null) {
                cachedSong
            } else {
                emit(Pair(IdentificationStep.IDENTIFYING, null))
                var recResult = recognitionEngine.recognize(currentAudioSource)

                // If candidate 1 returned no match and media is longer than 15s, try candidate 2 within the first 30s
                if (!recResult.success && mediaInfo.durationMs > 15_000L) {
                    tempManager.cleanup(currentAudioSource.file)
                    val window2 = AudioSegmentSelector.selectSegment(mediaInfo.durationMs, candidateIndex = 1)
                    currentAudioSource = audioExtractor.extractSegment(
                        uri = uri,
                        mediaInfo = mediaInfo,
                        startMs = window2.startMs,
                        durationMs = window2.durationMs
                    )
                    recResult = recognitionEngine.recognize(currentAudioSource)
                }

                if (!recResult.success || recResult.title.isNullOrBlank()) {
                    emit(Pair(IdentificationStep.COMPLETE, IdentificationOutcome.NoMusicFound))
                    return@flow
                }

                val normalized = SongMetadataNormalizer.normalize(recResult)
                RecognitionCache.put(hash, normalized)
                normalized
            }

            // 5. Search existing AirBeats providers
            emit(Pair(IdentificationStep.SEARCHING_AIRBEATS, null))
            val candidates = providerSearchManager.searchProviders(identifiedSong)
            val bestMatch = candidates.firstOrNull()

            emit(
                Pair(
                    IdentificationStep.COMPLETE,
                    IdentificationOutcome.Success(
                        song = identifiedSong,
                        airBeatsMatch = bestMatch,
                        candidates = candidates
                    )
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Identification pipeline failed", e)
            emit(Pair(IdentificationStep.COMPLETE, IdentificationOutcome.Error(e.localizedMessage ?: "Processing error")))
        } finally {
            // Clean up temporary audio files to protect user storage and privacy
            tempManager.cleanup(currentAudioSource?.file)
            tempManager.cleanupAll()
        }
    }

    private fun isNetworkConnected(context: Context): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return true
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (_: Exception) {
            true
        }
    }
}
