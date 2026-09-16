package com.darkxvenom.airbeats.ui.component

import android.os.Build
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.request.ImageRequest
import com.darkxvenom.airbeats.ui.utils.highQualityThumbnail

@Composable
fun BlurredBackground(
    model: Any?,
    modifier: Modifier = Modifier,
    blurRadius: Dp = 90.dp
) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && model != null) {
        val context = LocalContext.current
        var isLoaded by remember(model) { mutableStateOf(false) }
        val alpha by animateFloatAsState(
            targetValue = if (isLoaded) 1f else 0f,
            animationSpec = tween(500),
            label = "blurred_bg_alpha"
        )

        Box(modifier = modifier.fillMaxSize()) {
            LibraryMeshBackground()

            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(model)
                    .crossfade(true)
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                onState = { state ->
                    if (state is AsyncImagePainter.State.Success) {
                        isLoaded = true
                    }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { this.alpha = alpha }
                    .blur(blurRadius)
            )
        }
    } else {
        LibraryMeshBackground(modifier = modifier)
    }
}

/**
 * Renders the exact background from the Library screen (SimpMusicMeshBackground)
 * used when no song is playing.
 */
@Composable
fun LibraryMeshBackground(
    modifier: Modifier = Modifier
) {
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val surfaceColor = if (isDark) Color(0xFF050505) else Color(0xFFF9F9F9)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(surfaceColor)
    ) {
        val color1 = MaterialTheme.colorScheme.primary
        val color2 = MaterialTheme.colorScheme.secondary
        val color3 = MaterialTheme.colorScheme.tertiary
        val color4 = MaterialTheme.colorScheme.primaryContainer
        val color5 = MaterialTheme.colorScheme.secondaryContainer

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxSize(0.7f)
                .drawWithCache {
                    val width = size.width
                    val height = size.height

                    val brush1 = Brush.radialGradient(
                        colors = listOf(color1.copy(alpha = 0.38f), color1.copy(alpha = 0.24f), color1.copy(alpha = 0.14f), color1.copy(alpha = 0.06f), Color.Transparent),
                        center = Offset(width * 0.15f, height * 0.1f),
                        radius = width * 0.55f,
                    )
                    val brush2 = Brush.radialGradient(
                        colors = listOf(color2.copy(alpha = 0.34f), color2.copy(alpha = 0.2f), color2.copy(alpha = 0.11f), color2.copy(alpha = 0.05f), Color.Transparent),
                        center = Offset(width * 0.85f, height * 0.2f),
                        radius = width * 0.65f,
                    )
                    val brush3 = Brush.radialGradient(
                        colors = listOf(color3.copy(alpha = 0.3f), color3.copy(alpha = 0.17f), color3.copy(alpha = 0.09f), color3.copy(alpha = 0.04f), Color.Transparent),
                        center = Offset(width * 0.3f, height * 0.45f),
                        radius = width * 0.6f,
                    )
                    val brush4 = Brush.radialGradient(
                        colors = listOf(color4.copy(alpha = 0.26f), color4.copy(alpha = 0.14f), color4.copy(alpha = 0.08f), color4.copy(alpha = 0.03f), Color.Transparent),
                        center = Offset(width * 0.7f, height * 0.5f),
                        radius = width * 0.7f,
                    )
                    val brush5 = Brush.radialGradient(
                        colors = listOf(color5.copy(alpha = 0.22f), color5.copy(alpha = 0.12f), color5.copy(alpha = 0.06f), color5.copy(alpha = 0.02f), Color.Transparent),
                        center = Offset(width * 0.5f, height * 0.75f),
                        radius = width * 0.8f,
                    )
                    val overlayBrush = Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Color.Transparent, surfaceColor.copy(alpha = 0.22f), surfaceColor.copy(alpha = 0.55f), surfaceColor),
                        startY = height * 0.4f,
                        endY = height,
                    )

                    onDrawBehind {
                        drawRect(brush1)
                        drawRect(brush2)
                        drawRect(brush3)
                        drawRect(brush4)
                        drawRect(brush5)
                        drawRect(overlayBrush)
                    }
                }
        )
    }
}

/**
 * Adaptive background for all main screens (Home, Explore, Settings, About, Appearance, Stats, etc.):
 * - Base layer: Always displays the Library screen mesh background (no black void on startup or while fetching).
 * - Below Android 12 (Android 11, 10, etc.): Always displays the Library mesh background. Never loads thumbnail or uses fluid background.
 * - Android 12+: Once the song thumbnail successfully loads, smoothly fades in the blurred thumbnail and gradient overlay.
 */
@Composable
fun ScreenAdaptiveBackground(
    artworkUrl: String?,
    modifier: Modifier = Modifier,
) {
    val isSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val validArtworkUrl = artworkUrl?.takeIf { it.isNotBlank() }

    Box(modifier = modifier.fillMaxSize()) {
        // Base layer: Always render the Library mesh background immediately
        LibraryMeshBackground()

        if (isSupported) {
            val context = LocalContext.current
            var lastLoadedUrl by remember { mutableStateOf<String?>(null) }
            var isCurrentLoaded by remember { mutableStateOf(false) }

            LaunchedEffect(validArtworkUrl) {
                if (validArtworkUrl == null) {
                    isCurrentLoaded = false
                }
            }

            val targetAlpha = if (validArtworkUrl != null && isCurrentLoaded) 1f else 0f
            val thumbnailAlpha by animateFloatAsState(
                targetValue = targetAlpha,
                animationSpec = tween(600),
                label = "ScreenAdaptiveBackgroundThumbnailAlpha"
            )

            val displayModel = validArtworkUrl ?: lastLoadedUrl

            if (thumbnailAlpha > 0f || validArtworkUrl != null) {
                val isDarkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f
                val surfaceColor = MaterialTheme.colorScheme.surface
                val backgroundColor = MaterialTheme.colorScheme.background
                val overlayBrush = remember(isDarkTheme, surfaceColor, backgroundColor) {
                    if (isDarkTheme) {
                        Brush.verticalGradient(
                            listOf(
                                Color.Black.copy(alpha = 0.2f),
                                Color.Black.copy(alpha = 0.5f),
                                Color.Black.copy(alpha = 0.85f)
                            )
                        )
                    } else {
                        Brush.verticalGradient(
                            listOf(
                                surfaceColor.copy(alpha = 0.25f),
                                surfaceColor.copy(alpha = 0.5f),
                                backgroundColor.copy(alpha = 0.85f)
                            )
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { this.alpha = thumbnailAlpha }
                ) {
                    if (displayModel != null) {
                        AsyncImage(
                            model = ImageRequest.Builder(context)
                                .data(displayModel.highQualityThumbnail())
                                .crossfade(true)
                                .build(),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            onState = { state ->
                                if (state is AsyncImagePainter.State.Success) {
                                    isCurrentLoaded = true
                                    lastLoadedUrl = validArtworkUrl
                                }
                            },
                            modifier = Modifier
                                .fillMaxSize()
                                .blur(90.dp)
                        )
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(overlayBrush)
                    )
                }
            }
        }
    }
}
