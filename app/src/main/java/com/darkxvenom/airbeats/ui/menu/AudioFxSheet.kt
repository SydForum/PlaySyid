package com.darkxvenom.airbeats.ui.menu

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.darkxvenom.airbeats.R
import com.darkxvenom.airbeats.constants.LiquidGlassKey
import com.darkxvenom.airbeats.LocalPlayerConnection
import com.darkxvenom.airbeats.playback.AudioVisualizerStats
import com.darkxvenom.airbeats.ui.component.LocalBackdrop
import com.darkxvenom.airbeats.ui.component.drawBackdropCustomShape
import com.darkxvenom.airbeats.ui.component.isFrostedGlassUiEnabled
import com.darkxvenom.airbeats.utils.rememberPreference
import kotlin.math.cos
import kotlin.math.sin

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InAppAudioFxSheet(onDismiss: () -> Unit) {
    val playerConnection = LocalPlayerConnection.current ?: return
    val boostPercent by playerConnection.service.audioBoostPercent.collectAsState()
    val boostEnabled by playerConnection.service.audioBoostEnabled.collectAsState()
    val stats by playerConnection.service.visualizerManager.stats.collectAsState()

    val (enableLiquidGlass) = rememberPreference(LiquidGlassKey, false)
    val isFrosted = isFrostedGlassUiEnabled()
    val backdrop = LocalBackdrop.current
    val layer = rememberGraphicsLayer()
    val luminanceAnimation = remember { Animatable(0.3f) }
    val isDark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val sheetShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = if (enableLiquidGlass && !isFrosted && backdrop != null) {
            Color.Transparent
        } else if (isFrosted) {
            if (isDark) Color(0xFF131417).copy(alpha = 0.94f) else MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)
        } else {
            Color(0xFF131417)
        },
        shape = sheetShape,
        modifier = Modifier.then(
            if (enableLiquidGlass && !isFrosted && backdrop != null) {
                Modifier.drawBackdropCustomShape(backdrop = backdrop, layer = layer, luminanceAnimation = luminanceAnimation.value, shape = sheetShape)
            } else if (isFrosted) {
                Modifier.border(
                    BorderStroke(1.dp, Color.White.copy(alpha = 0.10f)),
                    sheetShape
                )
            } else {
                Modifier
            }
        ),
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(top = 10.dp, bottom = 8.dp)
                    .width(36.dp)
                    .height(4.dp)
                    .background(
                        color = Color(0xFF383A42),
                        shape = RoundedCornerShape(50)
                    )
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 20.dp)
        ) {
            // Header Row: Audio FX + PROFESSIONAL EQUALIZER and Reset Pill
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp, bottom = 22.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Audio FX",
                        style = MaterialTheme.typography.headlineMedium.copy(
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 25.sp,
                            color = Color.White
                        )
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "PROFESSIONAL EQUALIZER",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            letterSpacing = 1.6.sp,
                            color = Color(0xFF6E7280)
                        )
                    )
                }

                // Reset Pill Button
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(Color(0xFF28131C))
                        .clickable {
                            playerConnection.service.resetAudioFx()
                        }
                        .padding(horizontal = 18.dp, vertical = 7.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Reset",
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = Color(0xFFFF2A6D)
                        )
                    )
                }
            }

            // Three Reactive Circular Visualizer Dials
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                AudioVisualizerDial(
                    title = "BASS",
                    dbValue = stats.bassDb,
                    energy = stats.bass,
                    isPeak = stats.isBassPeak,
                    accentDefaultColor = Color(0xFFFF2A6D),
                    modifier = Modifier.weight(1f)
                )

                Spacer(modifier = Modifier.width(12.dp))

                AudioVisualizerDial(
                    title = "MID",
                    dbValue = stats.midDb,
                    energy = stats.mid,
                    isPeak = false,
                    accentDefaultColor = Color(0xFFFFFFFF),
                    modifier = Modifier.weight(1f)
                )

                Spacer(modifier = Modifier.width(12.dp))

                AudioVisualizerDial(
                    title = "TREBLE",
                    dbValue = stats.trebleDb,
                    energy = stats.treble,
                    isPeak = false,
                    accentDefaultColor = Color(0xFF00E5FF),
                    modifier = Modifier.weight(1f)
                )
            }

            // Master Boost Card
            MasterBoostCard(
                boostPercent = boostPercent,
                boostEnabled = boostEnabled,
                onBoostPercentChange = { percent ->
                    playerConnection.service.setAudioBoostPercent(percent)
                    if (!boostEnabled && percent > 100) {
                        playerConnection.service.setAudioBoostEnabled(true)
                    }
                }
            )
        }
    }
}

/**
 * Circular visualizer dial matching screenshot with dynamic White -> Green -> Glowing Red progression.
 */
@Composable
private fun AudioVisualizerDial(
    title: String,
    dbValue: Int,
    energy: Float,
    isPeak: Boolean,
    accentDefaultColor: Color,
    modifier: Modifier = Modifier
) {
    // Dynamic color transition based on live playing energy
    // White (< 0.35) -> Green (0.35 .. 0.70) -> Red (0.70+)
    val targetDynamicColor = when {
        isPeak || energy > 0.70f -> Color(0xFFFF1744) // Neon Peak Red
        energy > 0.35f -> {
            val t = ((energy - 0.35f) / 0.35f).coerceIn(0f, 1f)
            lerp(Color(0xFF00E676), Color(0xFFFF1744), t)
        }
        energy > 0.05f -> {
            val t = ((energy - 0.05f) / 0.30f).coerceIn(0f, 1f)
            lerp(Color.White, Color(0xFF00E676), t)
        }
        else -> accentDefaultColor
    }

    val animatedColor by animateColorAsState(
        targetValue = targetDynamicColor,
        animationSpec = tween(120, easing = FastOutSlowInEasing),
        label = "dialColor"
    )

    val animatedSweep by animateFloatAsState(
        targetValue = (energy * 360f).coerceIn(0f, 360f),
        animationSpec = tween(80),
        label = "dialSweep"
    )

    val animatedScale by animateFloatAsState(
        targetValue = if (isPeak) 1.04f else 1.0f,
        animationSpec = tween(100),
        label = "dialPulse"
    )

    Box(
        modifier = modifier
            .height(116.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(Color(0xFF17191E))
            .border(
                BorderStroke(
                    1.dp,
                    if (isPeak) animatedColor.copy(alpha = 0.5f) else Color(0xFF22252C)
                ),
                RoundedCornerShape(22.dp)
            ),
        contentAlignment = Alignment.Center
    ) {
        // Canvas for circular tracks and indicator pill
        Canvas(modifier = Modifier.size(86.dp)) {
            val strokeWidth = 5.dp.toPx()
            val radius = (size.minDimension - strokeWidth) / 2
            val center = Offset(size.width / 2, size.height / 2)

            // Inner dark circle
            drawCircle(
                color = Color(0xFF141519),
                radius = radius - strokeWidth / 2,
                center = center
            )

            // Outer inactive track ring
            drawCircle(
                color = Color(0xFF22252C),
                radius = radius,
                center = center,
                style = Stroke(width = strokeWidth)
            )

            // Active reactive arc
            if (animatedSweep > 5f) {
                drawArc(
                    color = animatedColor,
                    startAngle = -90f,
                    sweepAngle = animatedSweep,
                    useCenter = false,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
                    topLeft = Offset(strokeWidth / 2, strokeWidth / 2),
                    size = Size(size.width - strokeWidth, size.height - strokeWidth)
                )
            }

            // Top indicator pill/dot (as in screenshot)
            val pillAngleRad = Math.toRadians(-90.0).toFloat()
            val pillX = center.x + radius * cos(pillAngleRad)
            val pillY = center.y + radius * sin(pillAngleRad)

            drawCircle(
                color = animatedColor,
                radius = 4.dp.toPx(),
                center = Offset(pillX, pillY)
            )

            if (isPeak) {
                // Peak glow aura
                drawCircle(
                    color = animatedColor.copy(alpha = 0.35f),
                    radius = 8.dp.toPx(),
                    center = Offset(pillX, pillY)
                )
            }
        }

        // Center content: dB Value and Title Label
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "$dbValue",
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 22.sp,
                    color = Color.White
                )
            )
            Spacer(modifier = Modifier.height(1.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.sp,
                    letterSpacing = 1.2.sp,
                    color = Color(0xFF6E7280)
                )
            )
        }
    }
}

/**
 * Master Boost Card with speaker badge, live percentage, segmented slider, and power labels.
 */
@Composable
private fun MasterBoostCard(
    boostPercent: Int,
    boostEnabled: Boolean,
    onBoostPercentChange: (Int) -> Unit
) {
    val displayPercent = if (boostEnabled) boostPercent else 100

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(Color(0xFF17191E))
            .border(BorderStroke(1.dp, Color(0xFF22252C)), RoundedCornerShape(22.dp))
            .padding(20.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Top row: Speaker Icon + Titles and Percentage Display
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Speaker Icon badge container
                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color(0xFF28131C)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.volume_up),
                            contentDescription = "Master Boost",
                            tint = Color(0xFFFF2A6D),
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(14.dp))

                    Column {
                        Text(
                            text = "Master Boost",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 17.sp,
                                color = Color.White
                            )
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "200% OUTPUT POWER",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 11.sp,
                                letterSpacing = 1.sp,
                                color = Color(0xFF6E7280)
                            )
                        )
                    }
                }

                // Right side: 200% VOLUME BOOST
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "${displayPercent}%",
                        style = MaterialTheme.typography.headlineSmall.copy(
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 24.sp,
                            color = Color(0xFFFF2A6D)
                        )
                    )
                    Text(
                        text = "VOLUME BOOST",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp,
                            letterSpacing = 1.2.sp,
                            color = Color(0xFFFF2A6D)
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(22.dp))

            // Segmented interactive slider bar
            SegmentedBoostSlider(
                currentPercent = displayPercent,
                onPercentChange = onBoostPercentChange
            )

            Spacer(modifier = Modifier.height(14.dp))

            // Bottom labels: NORMAL (100%) and ULTRA BOOST (200%)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "NORMAL (100%)",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 11.sp,
                        color = Color(0xFF6E7280)
                    )
                )
                Text(
                    text = "ULTRA BOOST (200%)",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 11.sp,
                        color = Color(0xFF6E7280)
                    )
                )
            }
        }
    }
}

/**
 * Custom segmented slider bar matching the screenshot with smooth interactive drag/tap.
 */
@Composable
private fun SegmentedBoostSlider(
    currentPercent: Int,
    onPercentChange: (Int) -> Unit
) {
    var componentWidth by remember { mutableFloatStateOf(1f) }
    val progressFraction = ((currentPercent - 100) / 100f).coerceIn(0f, 1f)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(14.dp)
            .onSizeChanged { componentWidth = it.width.toFloat().coerceAtLeast(1f) }
            .clip(RoundedCornerShape(50))
            .background(Color(0xFF22242B))
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    val frac = (offset.x / componentWidth).coerceIn(0f, 1f)
                    val newPercent = 100 + (frac * 100).toInt()
                    onPercentChange(newPercent)
                }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures { change, _ ->
                    val frac = (change.position.x / componentWidth).coerceIn(0f, 1f)
                    val newPercent = 100 + (frac * 100).toInt()
                    onPercentChange(newPercent)
                }
            }
    ) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val width = size.width
            val height = size.height
            val activeWidth = width * progressFraction

            // Active bar fill with neon pink and subtle gradient
            if (activeWidth > 0) {
                drawRoundRect(
                    brush = Brush.horizontalGradient(
                        colors = listOf(
                            Color(0xFFFF2A6D),
                            Color(0xFFFF3377)
                        ),
                        endX = activeWidth
                    ),
                    topLeft = Offset.Zero,
                    size = Size(activeWidth, height),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(height / 2, height / 2)
                )
            }

            // Segment vertical tick notches (10 segments across the track)
            val segments = 10
            for (i in 1 until segments) {
                val segX = width * (i.toFloat() / segments)
                drawLine(
                    color = Color(0xFF17191E), // Match card background to create clean segment gaps
                    start = Offset(segX, 0f),
                    end = Offset(segX, height),
                    strokeWidth = 2.dp.toPx()
                )
            }
        }
    }
}
