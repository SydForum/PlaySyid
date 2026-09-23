package com.darkxvenom.airbeats.ui.component

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import com.darkxvenom.airbeats.ui.screens.Screens
import kotlin.math.abs

/**
 * Detects horizontal swipes across top-level screens (Home, Explore, Library)
 * to allow fluid tab switching.
 */
fun Modifier.tabSwipeGesture(
    enabled: Boolean,
    currentRoute: String?,
    onNavigateToRoute: (String) -> Unit,
    edgeExcludePx: Float = 60f,
): Modifier {
    if (!enabled) return this

    return this.pointerInput(currentRoute, enabled) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            // If touch starts near edge, let back gesture or system edge gesture handle it
            if (down.position.x <= edgeExcludePx) return@awaitEachGesture

            var totalDragX = 0f
            var totalDragY = 0f
            var hasTriggered = false

            do {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: break

                if (change.pressed) {
                    val positionChange = change.positionChange()
                    totalDragX += positionChange.x
                    totalDragY += positionChange.y

                    // Only trigger if horizontal movement dominates vertical movement
                    if (!hasTriggered && abs(totalDragX) > 120f && abs(totalDragX) > abs(totalDragY) * 2f) {
                        hasTriggered = true
                        if (totalDragX < 0) {
                            // Swiped Left -> Move forward
                            when (currentRoute) {
                                Screens.Home.route -> onNavigateToRoute(Screens.Explore.route)
                                Screens.Explore.route -> onNavigateToRoute(Screens.Library.route)
                            }
                        } else {
                            // Swiped Right -> Move backward
                            when (currentRoute) {
                                Screens.Library.route -> onNavigateToRoute(Screens.Explore.route)
                                Screens.Explore.route -> onNavigateToRoute(Screens.Home.route)
                            }
                        }
                    }
                }
            } while (event.changes.any { it.pressed })
        }
    }
}
