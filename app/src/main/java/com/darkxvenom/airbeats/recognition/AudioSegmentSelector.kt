package com.darkxvenom.airbeats.recognition

object AudioSegmentSelector {

    data class SelectedWindow(
        val startMs: Long,
        val durationMs: Long
    )

    private const val MAX_DETECTION_WINDOW_MS = 30_000L // User requirement: detect first 30s
    private const val DEFAULT_SEGMENT_DURATION_MS = 15_000L

    fun selectSegment(totalDurationMs: Long, candidateIndex: Int = 0): SelectedWindow {
        val boundedTotal = if (totalDurationMs > 0) minOf(totalDurationMs, MAX_DETECTION_WINDOW_MS) else MAX_DETECTION_WINDOW_MS

        if (boundedTotal <= DEFAULT_SEGMENT_DURATION_MS) {
            return SelectedWindow(startMs = 0L, durationMs = boundedTotal)
        }

        // Generate candidate windows strictly within the first 30 seconds
        val windows = listOf(
            // Segment 1: from 0s up to 15s (or up to 20s if available)
            SelectedWindow(
                startMs = 0L,
                durationMs = minOf(20_000L, boundedTotal)
            ),
            // Segment 2: from 10s to 25s (often clearer music past intro speech)
            SelectedWindow(
                startMs = 10_000L,
                durationMs = minOf(DEFAULT_SEGMENT_DURATION_MS, (boundedTotal - 10_000L).coerceAtLeast(5_000L))
            ),
            // Segment 3: from 15s to 30s
            SelectedWindow(
                startMs = 15_000L,
                durationMs = minOf(DEFAULT_SEGMENT_DURATION_MS, (boundedTotal - 15_000L).coerceAtLeast(5_000L))
            )
        )

        return windows.getOrElse(candidateIndex % windows.size) { windows[0] }
    }
}
