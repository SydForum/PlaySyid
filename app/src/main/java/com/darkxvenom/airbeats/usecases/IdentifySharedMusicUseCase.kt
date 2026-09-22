package com.darkxvenom.airbeats.usecases

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.util.Log
import com.darkxvenom.airbeats.media.LinkMediaResolver
import com.darkxvenom.airbeats.media.MediaInspector
import com.darkxvenom.airbeats.media.TemporaryMediaManager
import com.darkxvenom.airbeats.providers.ProviderSearchManager
import com.darkxvenom.airbeats.recognition.AudioExtractor
import com.darkxvenom.airbeats.recognition.AudioSegmentSelector
import com.darkxvenom.airbeats.recognition.AudioSource
import com.darkxvenom.airbeats.recognition.CompositeRecognitionEngine
import com.darkxvenom.airbeats.recognition.MusicRecognitionEngine
import com.darkxvenom.airbeats.recognition.RecognitionCache
import com.darkxvenom.airbeats.share.SharedContent
import com.darkxvenom.airbeats.share.SharedContentType
import com.darkxvenom.airbeats.songs.IdentifiedSong
import com.darkxvenom.airbeats.songs.SongMetadataNormalizer
import com.darkxvenom.airbeats.utils.GlobalLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.io.File

enum class IdentificationStep {
    VALIDATING,
    RESOLVING_LINK,
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
    private val linkMediaResolver: LinkMediaResolver = LinkMediaResolver(context, tempManager),
    private val recognitionEngine: MusicRecognitionEngine = CompositeRecognitionEngine(context),
    private val providerSearchManager: ProviderSearchManager = ProviderSearchManager(context)
) {

    companion object {
        private const val TAG = "IdentifyMusicUseCase"
    }

    fun execute(content: SharedContent): Flow<Pair<IdentificationStep, IdentificationOutcome?>> = flow {
        emit(Pair(IdentificationStep.VALIDATING, null))
        GlobalLog.append(Log.INFO, TAG, "=== Starting Music Identification Pipeline ===")
        GlobalLog.append(Log.INFO, TAG, "Input type: ${content.type}, text: '${content.text.orEmpty().take(120)}', uri: ${content.uri}")

        // 1. Check network connectivity
        if (!isNetworkConnected(context)) {
            GlobalLog.append(Log.ERROR, TAG, "Network check failed: No active internet connection")
            emit(Pair(IdentificationStep.COMPLETE, IdentificationOutcome.NetworkError))
            return@flow
        }
        GlobalLog.append(Log.INFO, TAG, "Network check: Connected")

        var downloadedMediaFile: File? = null
        val targetUri: Uri

        // 2. Resolve media URI or download audio from shared link
        if (content.type == SharedContentType.URL) {
            val url = content.text.orEmpty().trim()
            if (url.isBlank()) {
                GlobalLog.append(Log.ERROR, TAG, "Validation error: Empty URL shared")
                emit(Pair(IdentificationStep.COMPLETE, IdentificationOutcome.UnsupportedMedia("Empty URL shared")))
                return@flow
            }
            emit(Pair(IdentificationStep.RESOLVING_LINK, null))
            GlobalLog.append(Log.INFO, TAG, "Resolving media for URL: $url")
            val resolvedFile = withContext(Dispatchers.IO) {
                linkMediaResolver.resolveMedia(url)
            }
            if (resolvedFile == null || !resolvedFile.exists()) {
                Log.w(TAG, "LinkMediaResolver could not download media from $url, prompting user")
                GlobalLog.append(Log.WARN, TAG, "LinkMediaResolver could not download media from $url")
                emit(Pair(IdentificationStep.COMPLETE, IdentificationOutcome.IsUrlOnly(url)))
                return@flow
            }
            GlobalLog.append(Log.INFO, TAG, "Media resolved: ${resolvedFile.name} (${resolvedFile.length()} bytes)")
            downloadedMediaFile = resolvedFile
            targetUri = Uri.fromFile(resolvedFile)
        } else {
            val uri = content.uri
            if (uri == null) {
                GlobalLog.append(Log.ERROR, TAG, "Validation error: No media URI provided")
                emit(Pair(IdentificationStep.COMPLETE, IdentificationOutcome.UnsupportedMedia("No media URI provided")))
                return@flow
            }
            GlobalLog.append(Log.INFO, TAG, "Local media URI received: $uri")
            targetUri = uri
        }

        // 3. Inspect Media
        emit(Pair(IdentificationStep.ANALYZING_MEDIA, null))
        GlobalLog.append(Log.INFO, TAG, "Analyzing media metadata via MediaInspector...")
        val mediaInfo = withContext(Dispatchers.IO) {
            MediaInspector.inspect(context, targetUri)
        }
        GlobalLog.append(Log.INFO, TAG, "Media metadata: duration=${mediaInfo.durationMs}ms, hasAudio=${mediaInfo.hasAudio}, audioMime=${mediaInfo.audioMime}")

        if (!mediaInfo.hasAudio) {
            GlobalLog.append(Log.WARN, TAG, "No audio track detected in media file")
            emit(Pair(IdentificationStep.COMPLETE, IdentificationOutcome.NoAudio))
            return@flow
        }

        var currentAudioSource: AudioSource? = null

        try {
            // 4. Extract Audio Segment (focused on the first 30s)
            emit(Pair(IdentificationStep.EXTRACTING_AUDIO, null))
            val window1 = AudioSegmentSelector.selectSegment(mediaInfo.durationMs, candidateIndex = 0)
            GlobalLog.append(Log.INFO, TAG, "Extracting audio segment 0: ${window1.startMs}ms - ${window1.startMs + window1.durationMs}ms")
            currentAudioSource = audioExtractor.extractSegment(
                uri = targetUri,
                mediaInfo = mediaInfo,
                startMs = window1.startMs,
                durationMs = window1.durationMs
            )
            GlobalLog.append(Log.INFO, TAG, "Segment 0 extracted: ${currentAudioSource.file.name} (${currentAudioSource.file.length()} bytes)")

            // Check local recognition cache
            val hash = RecognitionCache.computeHash(currentAudioSource.file)
            val cachedSong = RecognitionCache.get(hash)

            val identifiedSong: IdentifiedSong = if (cachedSong != null) {
                GlobalLog.append(Log.INFO, TAG, "Cache hit for audio hash $hash: '${cachedSong.title}' by '${cachedSong.artist}'")
                cachedSong
            } else {
                emit(Pair(IdentificationStep.IDENTIFYING, null))
                GlobalLog.append(Log.INFO, TAG, "Recognizing candidate 0 with Shazam...")
                var recResult = recognitionEngine.recognize(currentAudioSource)

                // If candidate 0 returned no match, try candidate 1 (skipping intro speech / sound effects)
                if ((!recResult.success || recResult.title.isNullOrBlank()) && mediaInfo.durationMs > 8_000L) {
                    GlobalLog.append(Log.INFO, TAG, "Candidate 0 returned no match. Trying candidate 1 (middle segment)...")
                    tempManager.cleanup(currentAudioSource.file)
                    val window2 = AudioSegmentSelector.selectSegment(mediaInfo.durationMs, candidateIndex = 1)
                    currentAudioSource = audioExtractor.extractSegment(
                        uri = targetUri,
                        mediaInfo = mediaInfo,
                        startMs = window2.startMs,
                        durationMs = window2.durationMs
                    )
                    GlobalLog.append(Log.INFO, TAG, "Segment 1 extracted: ${currentAudioSource.file.name} (${currentAudioSource.file.length()} bytes)")
                    recResult = recognitionEngine.recognize(currentAudioSource)
                }

                // If candidate 1 returned no match, try candidate 2 (catches late drops / sound-only beats)
                if ((!recResult.success || recResult.title.isNullOrBlank()) && mediaInfo.durationMs > 14_000L) {
                    GlobalLog.append(Log.INFO, TAG, "Candidate 1 returned no match. Trying candidate 2 (late segment)...")
                    tempManager.cleanup(currentAudioSource.file)
                    val window3 = AudioSegmentSelector.selectSegment(mediaInfo.durationMs, candidateIndex = 2)
                    currentAudioSource = audioExtractor.extractSegment(
                        uri = targetUri,
                        mediaInfo = mediaInfo,
                        startMs = window3.startMs,
                        durationMs = window3.durationMs
                    )
                    GlobalLog.append(Log.INFO, TAG, "Segment 2 extracted: ${currentAudioSource.file.name} (${currentAudioSource.file.length()} bytes)")
                    recResult = recognitionEngine.recognize(currentAudioSource)
                }

                if (!recResult.success || recResult.title.isNullOrBlank()) {
                    GlobalLog.append(Log.WARN, TAG, "Recognition completed: No music matched")
                    emit(Pair(IdentificationStep.COMPLETE, IdentificationOutcome.NoMusicFound))
                    return@flow
                }

                val normalized = SongMetadataNormalizer.normalize(recResult)
                GlobalLog.append(Log.INFO, TAG, "Song recognized: '${normalized.title}' by '${normalized.artist}'")
                RecognitionCache.put(hash, normalized)
                normalized
            }

            // 5. Search existing AirBeats providers
            emit(Pair(IdentificationStep.SEARCHING_AIRBEATS, null))
            GlobalLog.append(Log.INFO, TAG, "Searching AirBeats library for '${identifiedSong.title}' - '${identifiedSong.artist}'...")
            val candidates = providerSearchManager.searchProviders(identifiedSong)
            val bestMatch = candidates.firstOrNull()
            GlobalLog.append(Log.INFO, TAG, "AirBeats search completed: ${candidates.size} results found (Best: ${bestMatch?.title ?: "None"})")

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
            GlobalLog.append(Log.ERROR, TAG, "Identification pipeline error: ${e.message}")
            emit(Pair(IdentificationStep.COMPLETE, IdentificationOutcome.Error(e.localizedMessage ?: "Processing error")))
        } finally {
            // Clean up temporary audio & downloaded files to protect user storage and privacy
            tempManager.cleanup(currentAudioSource?.file)
            tempManager.cleanup(downloadedMediaFile)
            tempManager.cleanupAll()
            GlobalLog.append(Log.INFO, TAG, "Identification pipeline finished (temp files cleaned)")
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
