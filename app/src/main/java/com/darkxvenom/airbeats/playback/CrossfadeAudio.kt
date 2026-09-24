package com.darkxvenom.airbeats.playback

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.darkxvenom.airbeats.db.MusicDatabase
import com.darkxvenom.airbeats.extensions.metadata
import timber.log.Timber
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

import com.darkxvenom.airbeats.playback.automix.CrossfadeMode
import com.darkxvenom.airbeats.playback.automix.TrackAnalyzer
import com.darkxvenom.airbeats.playback.automix.TransitionTrackInfo
import com.darkxvenom.airbeats.playback.automix.planTransition

/**
 * Audio Crossfade Engine:
 *  - Equal-power volume curve (sin/cos) maintaining steady acoustic energy
 *  - Gapless album skip (preserves seamless album track transitions)
 *  - Overlap secondary ExoPlayer for priming and smooth dual-decoder transitions
 *  - Automix smart transition planner with beat-aligned fades and cue-point drops
 *  - Instant position discontinuity recovery without muted volume or hung state
 */
internal class CrossfadeAudio(
    private val player: ExoPlayer,
    private val database: MusicDatabase,
    private val crossfadeDurationMs: MutableStateFlow<Int>,
    private val playbackFadeFactor: MutableStateFlow<Float>,
    private val playerVolume: MutableStateFlow<Float>,
    private val audioFocusVolumeFactor: MutableStateFlow<Float>,
    private val audioNormalizationEnabled: MutableStateFlow<Boolean>,
    private val maxSafeGainFactor: Float = 1.414f,
    private val overlapPlayerFactory: () -> ExoPlayer,
    private val automixEnabled: MutableStateFlow<Boolean> = MutableStateFlow(false),
    private val trackAnalyzer: TrackAnalyzer? = null,
    private val onCrossfadeStart: (MediaItem) -> Unit = {},
) {
    // ── Loop State ────────────────────────────────────────────────────────────

    private var loopJob: Job? = null

    // ── Overlap Player State ──────────────────────────────────────────────────

    private var overlapPlayer: ExoPlayer? = null
    private var overlapPrimedIndex: Int = C.INDEX_UNSET
    private var overlapPrimedMediaId: String? = null
    private var crossfadeActive = false
    private var crossfadeTargetIndex: Int = C.INDEX_UNSET
    private var crossfadeTargetMediaId: String? = null
    private var crossfadeStartElapsedMs: Long = 0L
    private var crossfadeActiveDurationMs: Int = 0
    private var crossfadeTargetCueTimeMs: Long = 0L
    private var overlapNormalizeFactor: Float = 1f

    // ── Public API ────────────────────────────────────────────────────────────

    fun isCrossfading(): Boolean = crossfadeActive

    fun start(scope: CoroutineScope) {
        if (loopJob?.isActive == true) return
        loopJob = scope.launch { runLoop() }
    }

    fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        handleMediaItemTransition(mediaItem, reason)
    }

    fun onPlaybackStateChanged(@Player.State playbackState: Int) {
        if (playbackState == Player.STATE_IDLE || playbackState == Player.STATE_ENDED) {
            stop(resetMainFade = true)
        }
    }

    fun onPositionDiscontinuity(reason: Int) {
        if (reason == Player.DISCONTINUITY_REASON_SEEK || reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT) {
            stopOverlapCrossfade(resetMainFade = true)
        }
    }

    fun stop(resetMainFade: Boolean) {
        stopOverlapCrossfade(resetMainFade = resetMainFade)
    }

    fun release() {
        loopJob?.cancel()
        loopJob = null
        stopOverlapCrossfade(resetMainFade = true)
        runCatching { overlapPlayer?.release() }
        overlapPlayer = null
    }

    // ── Main Loop ─────────────────────────────────────────────────────────────

    private suspend fun runLoop() {
        while (kotlin.coroutines.coroutineContext.isActive) {
            val isSmart = automixEnabled.value && trackAnalyzer != null
            val rawFadeMs = crossfadeDurationMs.value

            if (!isSmart && rawFadeMs <= 0) {
                stopOverlapCrossfade(resetMainFade = true)
                delay(250)
                continue
            }

            if (!player.playWhenReady || !player.isPlaying) {
                stopOverlapCrossfade(resetMainFade = true)
                delay(150)
                continue
            }

            if (player.playbackState != Player.STATE_READY) {
                stopOverlapCrossfade(resetMainFade = true)
                delay(150)
                continue
            }

            val durationMs = player.duration
            val positionMs = player.currentPosition.coerceAtLeast(0L)
            val nextIndex = player.nextMediaItemIndex

            // No crossfade in repeat-one
            if (player.repeatMode == Player.REPEAT_MODE_ONE) {
                stopOverlapCrossfade(resetMainFade = true)
                delay(150)
                continue
            }

            if (nextIndex == C.INDEX_UNSET || durationMs <= 0 || durationMs == C.TIME_UNSET) {
                stopOverlapCrossfade(resetMainFade = true)
                delay(150)
                continue
            }

            val currentItem = runCatching { player.getMediaItemAt(player.currentMediaItemIndex) }.getOrNull()
            val nextItem = if (nextIndex != C.INDEX_UNSET) runCatching { player.getMediaItemAt(nextIndex) }.getOrNull() else null

            // Gapless album skip: don't crossfade if both songs are from same album
            if (!crossfadeActive && currentItem != null && nextItem != null && isGaplessAlbumTransition(currentItem, nextItem)) {
                unprimeOverlap()
                delay(200)
                continue
            }

            var effectiveFadeMs = if (rawFadeMs > 0) rawFadeMs else 6000
            var incomingCueMs = 0L
            var plannedStartMs: Long? = null

            if (isSmart && currentItem != null && nextItem != null) {
                trackAnalyzer.request(
                    currentItem.mediaId,
                    currentItem.localConfiguration?.uri,
                    durationMs / 1000.0
                )
                val nextDurationSec = (nextItem.metadata?.duration ?: 180).toDouble()
                trackAnalyzer.request(
                    nextItem.mediaId,
                    nextItem.localConfiguration?.uri,
                    nextDurationSec
                )

                val currentAnalysis = trackAnalyzer.analysisFor(currentItem.mediaId)
                val nextAnalysis = trackAnalyzer.analysisFor(nextItem.mediaId)

                val plan = planTransition(
                    analysis = currentAnalysis,
                    nextAnalysis = nextAnalysis,
                    currentTrack = TransitionTrackInfo(id = currentItem.mediaId, durationMs = durationMs),
                    nextTrack = TransitionTrackInfo(id = nextItem.mediaId, durationMs = (nextDurationSec * 1000).toLong()),
                    currentTime = positionMs / 1000.0,
                    duration = durationMs / 1000.0,
                    fadeSeconds = (effectiveFadeMs / 1000.0).coerceIn(4.0, 12.0),
                    mode = CrossfadeMode.SMART,
                )

                if (!plan.blocked) {
                    effectiveFadeMs = plan.fadeMs.toInt().coerceIn(1000, 15000)
                    incomingCueMs = (plan.incomingCueTime * 1000.0).toLong().coerceAtLeast(0L)
                    plannedStartMs = (plan.transitionStart * 1000.0).toLong()
                }
            }

            // Do not crossfade a song shorter than requested overlap + 1s
            if (durationMs <= effectiveFadeMs.toLong() + 1000L) {
                stopOverlapCrossfade(resetMainFade = true)
                delay(250)
                continue
            }

            val remainingMs = (durationMs - positionMs).coerceAtLeast(0L)

            // If crossfade is already running, update volumes and check completion
            if (crossfadeActive) {
                val targetId = crossfadeTargetMediaId
                val currentId = player.currentMediaItem?.mediaId
                val onTarget = !targetId.isNullOrBlank() && targetId == currentId

                val tooFarFromEnd = !onTarget && plannedStartMs == null && remainingMs > effectiveFadeMs.toLong() + 3000L
                val nextChanged = !onTarget && crossfadeTargetIndex != C.INDEX_UNSET && nextIndex != crossfadeTargetIndex

                if (tooFarFromEnd || nextChanged) {
                    stopOverlapCrossfade(resetMainFade = true)
                    delay(50)
                    continue
                }

                updateVolumes()
                delay(40)
                continue
            }

            // Preload window (start priming secondary decoder early)
            val preloadWindowMs = effectiveFadeMs.toLong() + 2500L
            val isTimeNearTransition = if (plannedStartMs != null) {
                positionMs >= plannedStartMs - 3000L
            } else {
                remainingMs in 1L..preloadWindowMs
            }

            if (isTimeNearTransition) {
                primeOverlapForNext(nextIndex, incomingCueMs)
            } else {
                unprimeOverlap()
            }

            val shouldStartCrossfade = if (plannedStartMs != null) {
                positionMs >= plannedStartMs
            } else {
                remainingMs in 1L..effectiveFadeMs.toLong()
            }

            // Start crossfade when transition condition is satisfied and overlap is ready
            if (overlapPrimedIndex == nextIndex && shouldStartCrossfade) {
                val overlap = overlapPlayer
                if (overlap != null && overlap.playbackState == Player.STATE_READY) {
                    beginOverlapCrossfade(
                        fadeMs = effectiveFadeMs,
                        remainingMs = remainingMs,
                        cueTimeMs = incomingCueMs
                    )
                }
                delay(40)
                continue
            }

            if (playbackFadeFactor.value != 1f) playbackFadeFactor.value = 1f
            delay(80)
        }
    }

    // ── Gapless Album Transition Detection ────────────────────────────────────

    private fun isGaplessAlbumTransition(current: MediaItem, target: MediaItem): Boolean {
        val albumA = current.metadata?.album?.id?.takeIf { it.isNotBlank() }
            ?: current.metadata?.album?.title?.takeIf { it.isNotBlank() }
            ?: current.mediaMetadata.albumTitle?.toString()?.takeIf { it.isNotBlank() }

        val albumB = target.metadata?.album?.id?.takeIf { it.isNotBlank() }
            ?: target.metadata?.album?.title?.takeIf { it.isNotBlank() }
            ?: target.mediaMetadata.albumTitle?.toString()?.takeIf { it.isNotBlank() }

        return albumA != null && albumA == albumB
    }

    // ── Overlap Player Management ─────────────────────────────────────────────

    private suspend fun primeOverlapForNext(nextIndex: Int, cueTimeMs: Long = 0L) {
        val nextItem = runCatching { player.getMediaItemAt(nextIndex) }.getOrNull() ?: return
        val nextMediaId = nextItem.mediaId

        if (overlapPrimedIndex == nextIndex && overlapPrimedMediaId == nextMediaId) return

        stopOverlapCrossfade(resetMainFade = false)

        val overlap = ensureOverlapPlayer()
        overlap.clearMediaItems()
        overlap.setMediaItem(nextItem)
        if (cueTimeMs > 0L) {
            overlap.seekTo(cueTimeMs)
        }
        overlap.volume = 0f
        overlap.prepare()
        overlap.playWhenReady = true

        overlapNormalizeFactor = fetchNormalizeFactorForMediaId(nextMediaId)
        overlapPrimedIndex = nextIndex
        overlapPrimedMediaId = nextMediaId
    }

    private fun unprimeOverlap() {
        if (crossfadeActive) return
        if (overlapPrimedIndex == C.INDEX_UNSET && overlapPrimedMediaId == null) return
        stopOverlapCrossfade(resetMainFade = false)
    }

    private fun beginOverlapCrossfade(fadeMs: Int, remainingMs: Long, cueTimeMs: Long) {
        val overlap = overlapPlayer ?: return

        val targetIndex = overlapPrimedIndex
        if (targetIndex != C.INDEX_UNSET && targetIndex < player.mediaItemCount) {
            onCrossfadeStart(player.getMediaItemAt(targetIndex))
        }

        crossfadeActive = true
        crossfadeStartElapsedMs = android.os.SystemClock.elapsedRealtime()
        crossfadeActiveDurationMs = min(fadeMs.toLong(), remainingMs).toInt().coerceAtLeast(1000)
        crossfadeTargetIndex = overlapPrimedIndex
        crossfadeTargetMediaId = overlapPrimedMediaId
        crossfadeTargetCueTimeMs = cueTimeMs
        overlap.playWhenReady = true
    }

    // ── Equal-Power Volume Updating ───────────────────────────────────────────

    private fun updateVolumes() {
        val overlap = overlapPlayer ?: run {
            stopOverlapCrossfade(resetMainFade = true)
            return
        }

        val baseOverlapVolume =
            (playerVolume.value * overlapNormalizeFactor * audioFocusVolumeFactor.value)
                .coerceIn(0f, 1f)

        val denom = crossfadeActiveDurationMs.toLong().coerceAtLeast(1L)
        val elapsed = (android.os.SystemClock.elapsedRealtime() - crossfadeStartElapsedMs).coerceAtLeast(0L)
        val t = (elapsed.toFloat() / denom.toFloat()).coerceIn(0f, 1f)

        // Equal-power sin/cos crossfade curve
        val radians = t.toDouble() * (PI / 2.0)
        playbackFadeFactor.value = cos(radians).toFloat().coerceIn(0f, 1f)
        overlap.volume = (baseOverlapVolume * sin(radians).toFloat()).coerceIn(0f, maxSafeGainFactor)

        if (t >= 1f) {
            // Overlap phase completed: seamlessly transfer playback to the main player
            val targetIndex = crossfadeTargetIndex
            val overlapPos = overlap.currentPosition.coerceAtLeast(0L)
            val seekTargetPos = if (overlapPos > 0L) overlapPos else (crossfadeTargetCueTimeMs + crossfadeActiveDurationMs)

            if (targetIndex != C.INDEX_UNSET && targetIndex < player.mediaItemCount) {
                player.seekTo(targetIndex, seekTargetPos)
                player.playWhenReady = true
            }
            stopOverlapCrossfade(resetMainFade = true)
        }
    }

    // ── MediaItem Transition ──────────────────────────────────────────────────

    private fun handleMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        if (!crossfadeActive) {
            playbackFadeFactor.value = 1f
            return
        }

        val targetId = crossfadeTargetMediaId
        val newId = mediaItem?.mediaId

        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO &&
            !targetId.isNullOrBlank() && targetId == newId
        ) {
            // Main player naturally reached the new song: align to overlap's current position
            val overlapPos = overlapPlayer?.currentPosition?.coerceAtLeast(0L) ?: 0L
            if (overlapPos > 200L) {
                player.seekTo(overlapPos)
            }
        }

        stopOverlapCrossfade(resetMainFade = true)
    }

    // ── Stop / Reset ──────────────────────────────────────────────────────────

    private fun stopOverlapCrossfade(resetMainFade: Boolean) {
        crossfadeActive = false
        crossfadeTargetIndex = C.INDEX_UNSET
        crossfadeTargetMediaId = null
        crossfadeActiveDurationMs = 0
        crossfadeTargetCueTimeMs = 0L
        overlapNormalizeFactor = 1f
        overlapPrimedIndex = C.INDEX_UNSET
        overlapPrimedMediaId = null

        overlapPlayer?.let { overlap ->
            runCatching {
                overlap.volume = 0f
                overlap.stop()
                overlap.clearMediaItems()
            }
        }

        if (resetMainFade) {
            playbackFadeFactor.value = 1f
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun ensureOverlapPlayer(): ExoPlayer {
        val existing = overlapPlayer
        if (existing != null) return existing
        return overlapPlayerFactory().also { playerInstance ->
            playerInstance.addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    Timber.w(error, "Crossfade overlap decoder error, stopping overlap")
                    stopOverlapCrossfade(resetMainFade = true)
                }
            })
            overlapPlayer = playerInstance
        }
    }

    private suspend fun fetchNormalizeFactorForMediaId(mediaId: String): Float {
        if (!audioNormalizationEnabled.value) return 1f

        val format = withContext(Dispatchers.IO) {
            database.format(mediaId).first()
        }

        val loudness = format?.loudnessDb ?: return 1f
        var factor = 10f.pow((-loudness.toFloat()) / 20f)
        if (factor > 1f) factor = min(factor, maxSafeGainFactor)
        return factor
    }
}
