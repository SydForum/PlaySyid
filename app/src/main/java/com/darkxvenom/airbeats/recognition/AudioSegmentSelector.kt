package com.darkxvenom.airbeats.recognition

object AudioSegmentSelector {

    data class SelectedWindow(
        val startMs: Long,
        val durationMs: Long
    )

    private const val MAX_DETECTION_WINDOW_MS = 30_000L // Detect within first 30 seconds

    fun selectSegment(totalDurationMs: Long, candidateIndex: Int = 0): SelectedWindow {
        val boundedTotal = if (totalDurationMs > 0) minOf(totalDurationMs, MAX_DETECTION_WINDOW_MS) else MAX_DETECTION_WINDOW_MS

        if (boundedTotal <= 10_000L) {
            return SelectedWindow(startMs = 0L, durationMs = boundedTotal)
        }

        val windows = listOf(
            // Candidate 0: Broad intro window (0s to 22s)
            SelectedWindow(
                startMs = 0L,
                durationMs = minOf(22_000L, boundedTotal)
            ),
            // Candidate 1: Skips initial 6s of talking / intro sound effects (6s to 26s)
            SelectedWindow(
                startMs = 6_000L,
                durationMs = minOf(20_000L, (boundedTotal - 6_000L).coerceAtLeast(6_000L))
            ),
            // Candidate 2: Catches the late music drop / hook (12s to 30s)
            SelectedWindow(
                startMs = 12_000L,
                durationMs = minOf(18_000L, (boundedTotal - 12_000L).coerceAtLeast(6_000L))
            )
        )

        return windows.getOrElse(candidateIndex % windows.size) { windows[0] }
    }
}
