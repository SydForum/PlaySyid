package com.darkxvenom.airbeats.ui.component

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.PointerEventPass
import com.darkxvenom.airbeats.ui.screens.Screens
import kotlin.math.abs

/**
 * Detects horizontal swipes across top-level screens (Home, Search, Explore, Library)
 * to allow fluid tab switching.
 */
fun Modifier.tabSwipeGesture(
    enabled: Boolean,
    currentRoute: String?,
    navigationItems: List<Screens>,
    onNavigateToRoute: (String) -> Unit,
    edgeExcludePx: Float = 48f,
): Modifier {
    if (!enabled || navigationItems.isEmpty()) return this

    return this.pointerInput(currentRoute, enabled, navigationItems) {
        awaitEachGesture {
            val down = awaitFirstDown(pass = PointerEventPass.Initial, requireUnconsumed = false)
            // If touch starts near edge, let back gesture or system edge gesture handle it
            if (down.position.x <= edgeExcludePx) return@awaitEachGesture

            var totalDragX = 0f
            var totalDragY = 0f
            var hasTriggered = false
            var isHorizontalClaimed = false

            while (true) {
                val event = awaitPointerEvent(pass = PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break

                if (!change.pressed) break

                val positionChange = change.positionChange()
                totalDragX += positionChange.x
                totalDragY += positionChange.y

                if (!isHorizontalClaimed) {
                    if (abs(totalDragX) > 24f && abs(totalDragX) > abs(totalDragY) * 1.25f) {
                        isHorizontalClaimed = true
                        change.consume()
                    } else if (abs(totalDragY) > 28f && abs(totalDragY) > abs(totalDragX)) {
                        // Vertical scroll dominant -> let child list handle it
                        break
                    }
                }

                if (isHorizontalClaimed) {
                    change.consume()

                    if (!hasTriggered && abs(totalDragX) > 55f) {
                        hasTriggered = true
                        val currentIndex = navigationItems.indexOfFirst { screen ->
                            screen.route == currentRoute || 
                            (currentRoute?.startsWith("search") == true && screen.route.startsWith("search"))
                        }

                        if (currentIndex != -1) {
                            if (totalDragX < 0 && currentIndex < navigationItems.size - 1) {
                                // Swiped Left -> Move forward to next tab
                                onNavigateToRoute(navigationItems[currentIndex + 1].route)
                            } else if (totalDragX > 0 && currentIndex > 0) {
                                // Swiped Right -> Move backward to previous tab
                                onNavigateToRoute(navigationItems[currentIndex - 1].route)
                            }
                        }
                    }
                }
            }
        }
    }
}
