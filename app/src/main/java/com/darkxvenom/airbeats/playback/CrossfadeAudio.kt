package com.darkxvenom.airbeats.playback

import androidx.media3.common.C
import androidx.media3.common.MediaItem
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
import kotlin.math.PI
import kotlin.math.abs
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
 *  - Equal-power volume curve (sin/cos) to maintain constant perceptual loudness
 *  - Gapless album skip (preserves seamless album track transitions)
 *  - Buffer verification before starting crossfade
 *  - Overlap secondary ExoPlayer for priming and smooth handoff
 *  - Automix smart transition planner with beat-aligned fades and cue-point drops
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
    private var overlapNormalizeFactor: Float = 1f

    // ── Handoff State ─────────────────────────────────────────────────────────

    private var handoffActive = false
    private var handoffStartElapsedMs: Long = 0L
    private var handoffDurationMs: Int = 0
    private var handoffTargetPositionMs: Long = 0L
    private var handoffLastSyncSeekElapsedMs: Long = 0L
    private var handoffSeekIssued = false
    private var handoffRampStarted = false

    private val handoffTimeoutMs = 4000L

    // ── Buffer Requirement ────────────────────────────────────────────────────

    private fun requiredStartBufferMs(fadeMs: Int): Long =
        (fadeMs.toLong() + 2_000L).coerceIn(3_000L, 10_000L)

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

            if (!player.playWhenReady) {
                stopOverlapCrossfade(resetMainFade = true)
                delay(150)
                continue
            }

            // During handoff, only update volumes
            if (handoffActive) {
                updateVolumes()
                delay(50)
                continue
            }

            if (!crossfadeActive && (player.playbackState != Player.STATE_READY || !player.isPlaying)) {
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

            if (!crossfadeActive && (nextIndex == C.INDEX_UNSET || durationMs <= 0 || durationMs == C.TIME_UNSET)) {
                stopOverlapCrossfade(resetMainFade = true)
                delay(150)
                continue
            }

            val currentItem = runCatching { player.getMediaItemAt(player.currentMediaItemIndex) }.getOrNull()
            val nextItem = if (nextIndex != C.INDEX_UNSET) runCatching { player.getMediaItemAt(nextIndex) }.getOrNull() else null

            // Gapless album skip: don't crossfade if both songs are from same album
            if (!crossfadeActive && currentItem != null && nextItem != null && isGaplessAlbumTransition(currentItem, nextItem)) {
                unprimeOverlap()
                delay(150)
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
                    effectiveFadeMs = plan.fadeMs.toInt().coerceIn(1000, 12000)
                    incomingCueMs = (plan.incomingCueTime * 1000.0).toLong().coerceAtLeast(0L)
                    plannedStartMs = (plan.transitionStart * 1000.0).toLong()
                }
            }

            if (crossfadeActive) {
                val targetId = crossfadeTargetMediaId
                val currentId = player.currentMediaItem?.mediaId
                val onTarget = !targetId.isNullOrBlank() && targetId == currentId

                if (!onTarget && (nextIndex == C.INDEX_UNSET || durationMs <= 0 || durationMs == C.TIME_UNSET)) {
                    stopOverlapCrossfade(resetMainFade = true)
                    delay(150)
                    continue
                }

                val remainingMs = (durationMs - positionMs).coerceAtLeast(0L)
                val tooFarFromEnd = !onTarget && plannedStartMs == null && remainingMs > effectiveFadeMs.toLong() + 2000L
                val nextChanged =
                    !onTarget && crossfadeTargetIndex != C.INDEX_UNSET && nextIndex != crossfadeTargetIndex
                if (tooFarFromEnd || nextChanged) {
                    stopOverlapCrossfade(resetMainFade = true)
                    delay(100)
                    continue
                }

                updateVolumes()
                delay(50)
                continue
            }

            val remainingMs = (durationMs - positionMs).coerceAtLeast(0L)
            val preloadWindowMs = effectiveFadeMs.toLong() + 1500L

            val isTimeNearTransition = if (plannedStartMs != null) {
                positionMs >= plannedStartMs - 2000L
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

            // Start crossfade when transition condition is satisfied
            if (overlapPrimedIndex == nextIndex && shouldStartCrossfade) {
                val overlap = overlapPlayer
                if (overlap != null && hasEnoughBuffer(overlap, requiredStartBufferMs(effectiveFadeMs))) {
                    beginOverlapCrossfade(fadeMs = effectiveFadeMs, remainingMs = remainingMs)
                }
                delay(50)
                continue
            }

            if (playbackFadeFactor.value != 1f) playbackFadeFactor.value = 1f
            delay(100)
        }
    }

    // ── Buffer Helpers ────────────────────────────────────────────────────────

    private fun hasEnoughBuffer(targetPlayer: ExoPlayer, minMs: Long): Boolean {
        if (minMs <= 0L) return true
        if (targetPlayer.playbackState != Player.STATE_READY) return false

        val duration = targetPlayer.duration
        val buffered = targetPlayer.totalBufferedDuration.coerceAtLeast(0L)
        if (buffered >= minMs) return true

        return duration != C.TIME_UNSET &&
                targetPlayer.bufferedPosition >= duration - 150L
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
        overlap.prepare()
        overlap.playWhenReady = true
        overlap.volume = 0f

        overlapNormalizeFactor = fetchNormalizeFactorForMediaId(nextMediaId)
        overlapPrimedIndex = nextIndex
        overlapPrimedMediaId = nextMediaId
    }

    private fun unprimeOverlap() {
        if (crossfadeActive) return
        if (overlapPrimedIndex == C.INDEX_UNSET && overlapPrimedMediaId == null) return
        stopOverlapCrossfade(resetMainFade = false)
    }

    private fun beginOverlapCrossfade(fadeMs: Int, remainingMs: Long) {
        if (overlapPlayer == null) return

        val targetIndex = overlapPrimedIndex
        if (targetIndex != C.INDEX_UNSET && targetIndex < player.mediaItemCount) {
            onCrossfadeStart(player.getMediaItemAt(targetIndex))
        }

        crossfadeActive = true
        crossfadeStartElapsedMs = android.os.SystemClock.elapsedRealtime()
        crossfadeActiveDurationMs = min(fadeMs.toLong(), remainingMs).toInt().coerceAtLeast(1)
        crossfadeTargetIndex = overlapPrimedIndex
        crossfadeTargetMediaId = overlapPrimedMediaId
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

        // ── Handoff Phase ─────────────────────────────────────────────────────
        if (handoffActive) {
            val nowElapsedMs = android.os.SystemClock.elapsedRealtime()

            val overlapDead =
                overlap.playbackState == Player.STATE_IDLE || overlap.playbackState == Player.STATE_ENDED
            val handoffElapsed = nowElapsedMs - handoffStartElapsedMs
            val handoffTimedOut = handoffElapsed >= handoffTimeoutMs

            if (overlapDead || handoffTimedOut) {
                completeHandoffFromOverlap()
                return
            }

            // Wait for main player to finish buffering and become ready before ramping
            if (!handoffRampStarted) {
                val isPlayerReady = player.playbackState == Player.STATE_READY &&
                        (player.isPlaying || player.playWhenReady)

                if (!isPlayerReady) {
                    playbackFadeFactor.value = 0f
                    overlap.volume = baseOverlapVolume
                    return
                }

                handoffRampStarted = true
                handoffStartElapsedMs = nowElapsedMs
            }

            val denom = handoffDurationMs.toLong().coerceAtLeast(1L)
            val elapsed = (nowElapsedMs - handoffStartElapsedMs).coerceAtLeast(0L)
            val t = (elapsed.toFloat() / denom.toFloat()).coerceIn(0f, 1f)

            // Smooth equal-power sin/cos ramp during handoff (250ms)
            val radians = t.toDouble() * (PI / 2.0)
            playbackFadeFactor.value = sin(radians).toFloat().coerceIn(0f, 1f)
            overlap.volume = (baseOverlapVolume * cos(radians).toFloat()).coerceIn(0f, 1f)

            if (t >= 1f) completeHandoffFromOverlap()
            return
        }

        // ── Active Crossfade Phase (equal-power sin/cos) ──────────────────────
        val denom = crossfadeActiveDurationMs.toLong().coerceAtLeast(1L)
        val elapsed = (android.os.SystemClock.elapsedRealtime() - crossfadeStartElapsedMs).coerceAtLeast(0L)
        val t = (elapsed.toFloat() / denom.toFloat()).coerceIn(0f, 1f)

        val radians = t.toDouble() * (PI / 2.0)
        playbackFadeFactor.value = cos(radians).toFloat().coerceIn(0f, 1f)

        overlap.volume =
            (baseOverlapVolume * sin(radians).toFloat()).coerceIn(0f, maxSafeGainFactor)

        if (t >= 1f) {
            // Equal-power crossfade completed. Hand over to main player smoothly.
            val targetIndex = crossfadeTargetIndex
            val overlapPos = overlap.currentPosition.coerceAtLeast(0L)
            if (targetIndex != C.INDEX_UNSET && targetIndex < player.mediaItemCount && player.currentMediaItemIndex != targetIndex) {
                player.seekTo(targetIndex, overlapPos)
            }
            beginHandoffFromOverlap()
        }
    }

    // ── MediaItem Transition ──────────────────────────────────────────────────

    private fun handleMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        if (!crossfadeActive) return

        val targetId = crossfadeTargetMediaId
        val newId = mediaItem?.mediaId

        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO &&
            !targetId.isNullOrBlank() && targetId == newId
        ) {
            beginHandoffFromOverlap()
            return
        }

        stopOverlapCrossfade(resetMainFade = true)
    }

    // ── Handoff: Transfer overlap to main player ──────────────────────────────

    private fun beginHandoffFromOverlap() {
        val overlap = overlapPlayer ?: run {
            stopOverlapCrossfade(resetMainFade = true)
            return
        }

        val overlapPositionMs = overlap.currentPosition.coerceAtLeast(0L)
        val currentIndex = player.currentMediaItemIndex
        val targetIndex = crossfadeTargetIndex
        if (targetIndex != C.INDEX_UNSET && currentIndex != targetIndex && targetIndex < player.mediaItemCount) {
            player.seekTo(targetIndex, overlapPositionMs)
        } else if (currentIndex != C.INDEX_UNSET) {
            val mainPos = player.currentPosition.coerceAtLeast(0L)
            if (abs(mainPos - overlapPositionMs) > 250L) {
                player.seekTo(currentIndex, overlapPositionMs)
            }
        }

        handoffActive = true
        handoffSeekIssued = true
        handoffRampStarted = false
        handoffTargetPositionMs = overlapPositionMs
        handoffStartElapsedMs = android.os.SystemClock.elapsedRealtime()
        handoffLastSyncSeekElapsedMs = 0L
        handoffDurationMs = 250
        playbackFadeFactor.value = 0f
    }

    private fun completeHandoffFromOverlap() {
        val overlap = overlapPlayer ?: run {
            stopOverlapCrossfade(resetMainFade = true)
            return
        }

        runCatching {
            overlap.volume = 0f
            overlap.stop()
            overlap.clearMediaItems()
        }

        handoffActive = false
        handoffStartElapsedMs = 0L
        handoffDurationMs = 0
        handoffTargetPositionMs = 0L
        handoffLastSyncSeekElapsedMs = 0L
        handoffSeekIssued = false
        handoffRampStarted = false

        crossfadeActive = false
        crossfadeTargetIndex = C.INDEX_UNSET
        crossfadeTargetMediaId = null
        crossfadeActiveDurationMs = 0
        overlapNormalizeFactor = 1f
        overlapPrimedIndex = C.INDEX_UNSET
        overlapPrimedMediaId = null

        playbackFadeFactor.value = 1f
    }

    // ── Stop / Reset ──────────────────────────────────────────────────────────

    private fun stopOverlapCrossfade(resetMainFade: Boolean) {
        crossfadeActive = false
        crossfadeTargetIndex = C.INDEX_UNSET
        crossfadeTargetMediaId = null
        crossfadeActiveDurationMs = 0
        overlapNormalizeFactor = 1f
        overlapPrimedIndex = C.INDEX_UNSET
        overlapPrimedMediaId = null
        handoffActive = false
        handoffStartElapsedMs = 0L
        handoffDurationMs = 0
        handoffTargetPositionMs = 0L
        handoffLastSyncSeekElapsedMs = 0L
        handoffSeekIssued = false
        handoffRampStarted = false

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
        return overlapPlayerFactory().also { overlapPlayer = it }
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
