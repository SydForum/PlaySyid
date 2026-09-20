package com.darkxvenom.airbeats.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import coil.compose.AsyncImage
import com.darkxvenom.airbeats.LocalDatabase
import com.darkxvenom.airbeats.LocalPlayerAwareWindowInsets
import com.darkxvenom.airbeats.LocalPlayerConnection
import com.darkxvenom.airbeats.R
import com.darkxvenom.airbeats.constants.HistorySource
import com.darkxvenom.airbeats.constants.InnerTubeCookieKey
import com.darkxvenom.airbeats.db.entities.EventWithSong
import com.darkxvenom.airbeats.extensions.metadata
import com.darkxvenom.airbeats.extensions.toMediaItem
import com.darkxvenom.airbeats.extensions.togglePlayPause
import com.darkxvenom.airbeats.innertube.utils.parseCookieString
import com.darkxvenom.airbeats.models.toMediaMetadata
import com.darkxvenom.airbeats.playback.queues.ListQueue
import com.darkxvenom.airbeats.playback.queues.YouTubeQueue
import com.darkxvenom.airbeats.ui.component.HideOnScrollFAB
import com.darkxvenom.airbeats.ui.component.LocalMenuState
import com.darkxvenom.airbeats.ui.component.NavigationTitle
import com.darkxvenom.airbeats.ui.component.YouTubeListItem
import com.darkxvenom.airbeats.ui.menu.SelectionMediaMetadataMenu
import com.darkxvenom.airbeats.ui.menu.SongMenu
import com.darkxvenom.airbeats.ui.menu.YouTubeSongMenu
import com.darkxvenom.airbeats.ui.utils.backToMain
import com.darkxvenom.airbeats.utils.makeTimeString
import com.darkxvenom.airbeats.utils.rememberPreference
import com.darkxvenom.airbeats.viewmodels.DateAgo
import com.darkxvenom.airbeats.viewmodels.HistoryCategory
import com.darkxvenom.airbeats.viewmodels.HistoryViewModel
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

private fun formatEventTimeString(timestamp: LocalDateTime): String {
    val date = timestamp.toLocalDate()
    val today = LocalDate.now()
    val timeFormatter = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)
    return when {
        date == today -> timestamp.format(timeFormatter)
        date == today.minusDays(1) -> timestamp.format(timeFormatter)
        else -> {
            val dayFormatter = DateTimeFormatter.ofPattern("EEE, d MMM", Locale.ENGLISH)
            timestamp.format(dayFormatter)
        }
    }
}

/**
 * Ambient, flowing warm wave curves background with gentle undulating animation.
 */
@Composable
fun FluidWavesBackground(
    modifier: Modifier = Modifier,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "fluid_waves_bg")
    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 16000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "phase"
    )

    Canvas(modifier = modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height

        // Deep warm obsidian base
        drawRect(color = Color(0xFF0D0B0A))

        val rad1 = Math.toRadians(phase.toDouble()).toFloat()
        val rad2 = Math.toRadians((phase + 120f).toDouble()).toFloat()
        val rad3 = Math.toRadians((phase + 240f).toDouble()).toFloat()

        val shift1X = sin(rad1) * 25f
        val shift1Y = cos(rad1) * 20f
        val shift2X = cos(rad2) * 22f
        val shift2Y = sin(rad2) * 22f
        val shift3X = sin(rad3) * 18f
        val shift3Y = cos(rad3) * 16f

        // Warm radial glows in upper right and mid-left
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color(0xFFEA580C).copy(alpha = 0.28f),
                    Color(0xFF9A3412).copy(alpha = 0.12f),
                    Color(0xFF78350F).copy(alpha = 0.04f),
                    Color.Transparent,
                ),
                center = Offset(w * 0.88f + shift1X, h * 0.10f + shift1Y),
                radius = w * 0.85f,
            )
        )

        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color(0xFFC2410C).copy(alpha = 0.15f),
                    Color(0xFF78350F).copy(alpha = 0.05f),
                    Color.Transparent,
                ),
                center = Offset(w * 0.15f + shift2X, h * 0.26f + shift2Y),
                radius = w * 0.55f,
            )
        )

        // Wave Ribbon 1 (Upper main sweeping ribbon)
        val path1 = Path().apply {
            moveTo(w * 1.25f, -h * 0.05f)
            cubicTo(
                w * 0.90f + shift1X, h * 0.08f + shift1Y,
                w * 0.65f + shift2X, h * 0.20f + shift2Y,
                -w * 0.15f, h * 0.32f + shift3Y
            )
            lineTo(-w * 0.15f, -h * 0.05f)
            close()
        }
        drawPath(
            path = path1,
            brush = Brush.verticalGradient(
                colors = listOf(
                    Color(0xFFEA580C).copy(alpha = 0.08f),
                    Color(0xFF78350F).copy(alpha = 0.02f),
                    Color.Transparent,
                )
            )
        )

        val strokePath1 = Path().apply {
            moveTo(w * 1.25f, -h * 0.05f)
            cubicTo(
                w * 0.90f + shift1X, h * 0.08f + shift1Y,
                w * 0.65f + shift2X, h * 0.20f + shift2Y,
                -w * 0.15f, h * 0.32f + shift3Y
            )
        }
        drawPath(
            path = strokePath1,
            brush = Brush.linearGradient(
                colors = listOf(
                    Color(0xFFF97316).copy(alpha = 0.50f),
                    Color(0xFFEA580C).copy(alpha = 0.35f),
                    Color(0xFFB45309).copy(alpha = 0.18f),
                    Color.Transparent,
                ),
                start = Offset(w, 0f),
                end = Offset(0f, h * 0.35f),
            ),
            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
        )

        // Wave Ribbon 2 (Middle sweeping ribbon)
        val strokePath2 = Path().apply {
            moveTo(w * 1.15f, h * 0.06f)
            cubicTo(
                w * 0.82f + shift2X, h * 0.18f + shift1Y,
                w * 0.45f + shift3X, h * 0.26f + shift2Y,
                -w * 0.10f, h * 0.24f + shift1Y
            )
        }
        drawPath(
            path = strokePath2,
            brush = Brush.linearGradient(
                colors = listOf(
                    Color(0xFFFB923C).copy(alpha = 0.38f),
                    Color(0xFFD97706).copy(alpha = 0.22f),
                    Color(0xFF78350F).copy(alpha = 0.08f),
                    Color.Transparent,
                ),
                start = Offset(w, h * 0.05f),
                end = Offset(0f, h * 0.30f),
            ),
            style = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round)
        )

        // Wave Ribbon 3 (Lower delicate ribbon)
        val strokePath3 = Path().apply {
            moveTo(w * 1.10f, h * 0.15f)
            cubicTo(
                w * 0.75f + shift3X, h * 0.26f + shift3Y,
                w * 0.35f + shift1X, h * 0.32f + shift1Y,
                -w * 0.05f, h * 0.38f + shift2Y
            )
        }
        drawPath(
            path = strokePath3,
            brush = Brush.linearGradient(
                colors = listOf(
                    Color(0xFFEA580C).copy(alpha = 0.28f),
                    Color(0xFF9A3412).copy(alpha = 0.12f),
                    Color.Transparent,
                )
            ),
            style = Stroke(width = 1.2.dp.toPx(), cap = StrokeCap.Round)
        )
    }
}

/**
 * "Keep vibing!" card with matching flowing waves canvas effect and quick stats jump.
 */
@Composable
fun KeepVibingWavesCard(
    navController: NavController,
    modifier: Modifier = Modifier,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "card_waves_anim")
    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 10000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "card_phase"
    )

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF1C1410)
        ),
        border = BorderStroke(1.dp, Color(0xFFE55D26).copy(alpha = 0.28f))
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            Canvas(modifier = Modifier.matchParentSize()) {
                val w = size.width
                val h = size.height

                val rad = Math.toRadians(phase.toDouble()).toFloat()
                val shiftX = sin(rad) * 15f
                val shiftY = cos(rad) * 10f

                // Left glow behind soundwave circle
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color(0xFFE55D26).copy(alpha = 0.35f),
                            Color(0xFF9A3412).copy(alpha = 0.15f),
                            Color.Transparent,
                        ),
                        center = Offset(w * 0.12f, h * 0.5f),
                        radius = w * 0.45f,
                    )
                )

                // Flowing waves across card
                val cardPath1 = Path().apply {
                    moveTo(-w * 0.1f, h * 0.95f)
                    cubicTo(
                        w * 0.35f + shiftX, h * 0.70f + shiftY,
                        w * 0.65f - shiftX, h * 0.30f - shiftY,
                        w * 1.1f, h * 0.10f
                    )
                    lineTo(w * 1.1f, h)
                    lineTo(-w * 0.1f, h)
                    close()
                }
                drawPath(
                    path = cardPath1,
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFFE55D26).copy(alpha = 0.18f),
                            Color(0xFF78350F).copy(alpha = 0.05f),
                            Color.Transparent,
                        )
                    )
                )

                val cardStroke1 = Path().apply {
                    moveTo(-w * 0.1f, h * 0.95f)
                    cubicTo(
                        w * 0.35f + shiftX, h * 0.70f + shiftY,
                        w * 0.65f - shiftX, h * 0.30f - shiftY,
                        w * 1.1f, h * 0.10f
                    )
                }
                drawPath(
                    path = cardStroke1,
                    brush = Brush.horizontalGradient(
                        colors = listOf(
                            Color(0xFFF97316).copy(alpha = 0.55f),
                            Color(0xFFEA580C).copy(alpha = 0.40f),
                            Color(0xFFB45309).copy(alpha = 0.20f),
                        )
                    ),
                    style = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round)
                )

                val cardStroke2 = Path().apply {
                    moveTo(w * 0.15f, h * 1.1f)
                    cubicTo(
                        w * 0.50f + shiftX, h * 0.65f + shiftY,
                        w * 0.80f + shiftY, h * 0.45f + shiftX,
                        w * 1.15f, h * 0.40f
                    )
                }
                drawPath(
                    path = cardStroke2,
                    brush = Brush.horizontalGradient(
                        colors = listOf(
                            Color(0xFFFB923C).copy(alpha = 0.35f),
                            Color(0xFFD97706).copy(alpha = 0.20f),
                            Color.Transparent,
                        )
                    ),
                    style = Stroke(width = 1.4.dp.toPx(), cap = StrokeCap.Round)
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Soundwave / Equalizer glowing container
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.radialGradient(
                                colors = listOf(
                                    Color(0xFFE54D2E),
                                    Color(0xFF992E15),
                                )
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(R.drawable.graphic_eq),
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(22.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Keep vibing!",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Your recent listening activity",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.7f),
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = Color.White.copy(alpha = 0.14f),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.22f)),
                    modifier = Modifier.clickable { navController.navigate("stats") }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "View Stats",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White,
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            painter = painterResource(R.drawable.arrow_forward),
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Filter Chips row: All, Songs, Albums, Artists
 */
@Composable
fun HistoryCategoryChips(
    selectedCategory: HistoryCategory,
    onSelect: (HistoryCategory) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        val categories = listOf(
            Triple(HistoryCategory.ALL, "All", R.drawable.history),
            Triple(HistoryCategory.SONGS, "Songs", R.drawable.music_note),
            Triple(HistoryCategory.ALBUMS, "Albums", R.drawable.album),
            Triple(HistoryCategory.ARTISTS, "Artists", R.drawable.artist),
        )

        categories.forEach { (category, title, iconRes) ->
            val isSelected = selectedCategory == category
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = if (isSelected) Color(0xFFE54D2E) else Color.White.copy(alpha = 0.08f),
                border = if (isSelected) null else BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(20.dp))
                    .clickable { onSelect(category) }
            ) {
                Row(
                    modifier = Modifier.padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        painter = painterResource(iconRes),
                        contentDescription = null,
                        tint = if (isSelected) Color.White else Color.White.copy(alpha = 0.7f),
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        text = title,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        color = if (isSelected) Color.White else Color.White.copy(alpha = 0.7f),
                    )
                }
            }
        }
    }
}

/**
 * Single song item row in History: supports the highlighted active card ("Baby Girl")
 * as well as the sleek standard history items.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HistorySongRow(
    event: EventWithSong,
    isHighlighted: Boolean,
    isPlaying: Boolean,
    isCurrentTrack: Boolean,
    isSelected: Boolean,
    isSelectionMode: Boolean,
    onToggleLike: () -> Unit,
    onItemClick: () -> Unit,
    onItemLongClick: () -> Unit,
    onMenuClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val durationSec = event.song.duration
    val durationString = if (durationSec > 0) makeTimeString(durationSec * 1000L) else "3:00"
    val timeString = formatEventTimeString(event.event.timestamp)
    val artistsString = event.song.artists.joinToString { it.name }.ifEmpty { "Unknown Artist" }
    val isLiked = event.song.song.liked

    if (isHighlighted) {
        // High-profile active card (like "Baby Girl" in the screenshot)
        Card(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp)
                .clip(RoundedCornerShape(16.dp))
                .combinedClickable(
                    onClick = onItemClick,
                    onLongClick = onItemLongClick
                ),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = Color(0xFF241914)
            ),
            border = BorderStroke(1.dp, Color(0xFFE55D26).copy(alpha = 0.45f))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Album Art with pause/play overlay
                Box(
                    modifier = Modifier
                        .size(54.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF33231D))
                ) {
                    if (!event.song.thumbnailUrl.isNullOrBlank()) {
                        AsyncImage(
                            model = event.song.thumbnailUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.music_note),
                                contentDescription = null,
                                tint = Color(0xFFE55D26),
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }

                    // Center Pause / Play indicator
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.45f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(if (isPlaying) R.drawable.pause else R.drawable.play),
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = event.song.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = artistsString,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFFD4C7C2),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(3.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            painter = painterResource(R.drawable.album),
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.55f),
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "$durationString • $timeString",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.6f)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Heart / Like button (filled red if liked)
                IconButton(onClick = onToggleLike) {
                    Icon(
                        painter = painterResource(if (isLiked) R.drawable.favorite else R.drawable.favorite_border),
                        contentDescription = null,
                        tint = if (isLiked) Color(0xFFEF4444) else Color.White.copy(alpha = 0.5f),
                        modifier = Modifier.size(20.dp)
                    )
                }

                // Three-dots menu button
                IconButton(onClick = onMenuClick) {
                    Icon(
                        painter = painterResource(R.drawable.more_vert),
                        contentDescription = null,
                        tint = Color(0xFFD4C7C2),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    } else {
        // Standard sleek history item row
        Row(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(if (isSelected && isSelectionMode) Color.White.copy(alpha = 0.12f) else Color.Transparent)
                .combinedClickable(
                    onClick = onItemClick,
                    onLongClick = onItemLongClick
                )
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Rounded Album Art
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF241B17))
            ) {
                if (!event.song.thumbnailUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = event.song.thumbnailUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.music_note),
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.5f),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = event.song.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = artistsString,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF9E8E88),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painter = painterResource(R.drawable.play),
                        contentDescription = null,
                        tint = Color(0xFF9E8E88),
                        modifier = Modifier.size(11.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "$durationString • $timeString",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF9E8E88)
                    )
                }
            }

            Spacer(modifier = Modifier.width(6.dp))

            IconButton(onClick = onToggleLike) {
                Icon(
                    painter = painterResource(if (isLiked) R.drawable.favorite else R.drawable.favorite_border),
                    contentDescription = null,
                    tint = if (isLiked) Color(0xFFEF4444) else Color(0xFF9E8E88),
                    modifier = Modifier.size(20.dp)
                )
            }

            IconButton(onClick = onMenuClick) {
                Icon(
                    painter = painterResource(R.drawable.more_vert),
                    contentDescription = null,
                    tint = Color(0xFF9E8E88),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HistoryScreen(
    navController: NavController,
    viewModel: HistoryViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val menuState = LocalMenuState.current
    val haptic = LocalHapticFeedback.current
    val playerConnection = LocalPlayerConnection.current ?: return
    val isPlaying by playerConnection.isPlaying.collectAsState()
    val mediaMetadata by playerConnection.mediaMetadata.collectAsState()

    var selection by remember { mutableStateOf(false) }
    var isSearching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue()) }
    val focusRequester = remember { FocusRequester() }

    var showClearTodayDialog by remember { mutableStateOf(false) }
    var showClearAllDialog by remember { mutableStateOf(false) }
    var showTopMenu by remember { mutableStateOf(false) }

    val historySource by viewModel.historySource.collectAsState()
    val selectedCategory by viewModel.selectedCategory.collectAsState()
    val events by viewModel.events.collectAsState()
    val historyPage by viewModel.historyPage

    val innerTubeCookie by rememberPreference(InnerTubeCookieKey, "")
    val isLoggedIn = remember(innerTubeCookie) {
        "SAPISID" in parseCookieString(innerTubeCookie)
    }

    LaunchedEffect(isSearching) {
        if (isSearching) {
            focusRequester.requestFocus()
        }
    }

    if (isSearching) {
        BackHandler {
            isSearching = false
            query = TextFieldValue()
        }
    } else if (selection) {
        BackHandler {
            selection = false
        }
    }

    fun dateAgoToString(dateAgo: DateAgo): String {
        return when (dateAgo) {
            DateAgo.Today -> context.getString(R.string.today)
            DateAgo.Yesterday -> context.getString(R.string.yesterday)
            DateAgo.ThisWeek -> "Earlier This Week"
            DateAgo.LastWeek -> context.getString(R.string.last_week)
            is DateAgo.Other -> dateAgo.date.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault()))
        }
    }

    class WrappedHistoryItem(val item: EventWithSong) {
        var isSelected by mutableStateOf(false)
    }

    val filteredEvents = remember(events, query, selectedCategory) {
        val base = when (selectedCategory) {
            HistoryCategory.ALL, HistoryCategory.SONGS -> events
            HistoryCategory.ALBUMS -> events.mapValues { (_, songList) ->
                songList.filter { it.song.album != null }.distinctBy { it.song.album?.id }
            }.filterValues { it.isNotEmpty() }
            HistoryCategory.ARTISTS -> events.mapValues { (_, songList) ->
                songList.filter { it.song.artists.isNotEmpty() }.distinctBy { it.song.artists.firstOrNull()?.id }
            }.filterValues { it.isNotEmpty() }
        }

        if (query.text.isEmpty()) {
            base
        } else {
            base.mapValues { (_, songs) ->
                songs.filter { event ->
                    event.song.song.title.contains(query.text, ignoreCase = true) ||
                            event.song.artists.any { it.name.contains(query.text, ignoreCase = true) } ||
                            (event.song.album?.title?.contains(query.text, ignoreCase = true) == true)
                }
            }.filterValues { it.isNotEmpty() }
        }
    }

    val filteredRemoteContent = remember(historyPage, query) {
        if (query.text.isEmpty()) {
            historyPage?.sections
        } else {
            historyPage?.sections?.map { section ->
                section.copy(
                    songs = section.songs.filter { song ->
                        song.title.contains(query.text, ignoreCase = true) ||
                                song.artists.any { it.name.contains(query.text, ignoreCase = true) }
                    }
                )
            }?.filter { it.songs.isNotEmpty() }
        }
    }

    val wrappedItemsMap = remember(filteredEvents) {
        filteredEvents.mapValues { (_, events) ->
            events.map { WrappedHistoryItem(it) }.toMutableStateList()
        }
    }

    val allWrappedItems = remember(wrappedItemsMap) {
        wrappedItemsMap.values.flatten()
    }

    val lazyListState = rememberLazyListState()

    // Confirm Clear Today Dialog
    if (showClearTodayDialog) {
        AlertDialog(
            onDismissRequest = { showClearTodayDialog = false },
            title = { Text("Clear Today's History?", fontWeight = FontWeight.Bold) },
            text = { Text("This will remove listening events from today. Your total play statistics will remain preserved.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clearToday()
                        showClearTodayDialog = false
                    }
                ) {
                    Text("Clear", color = Color(0xFFEF4444), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearTodayDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Confirm Clear All Dialog
    if (showClearAllDialog) {
        AlertDialog(
            onDismissRequest = { showClearAllDialog = false },
            title = { Text("Clear All History?", fontWeight = FontWeight.Bold) },
            text = { Text("Are you sure you want to clear your entire listening history? This cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clearAll()
                        showClearAllDialog = false
                    }
                ) {
                    Text("Clear All", color = Color(0xFFEF4444), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearAllDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    Box(Modifier.fillMaxSize()) {
        // Fluid Waves Background
        FluidWavesBackground()

        LazyColumn(
            state = lazyListState,
            contentPadding = LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)
                .asPaddingValues(),
            modifier = Modifier.windowInsetsPadding(
                LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Top)
            )
        ) {
            // Header Section
            item(key = "header_section") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "YOUR MUSIC JOURNEY",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    letterSpacing = 2.sp,
                                    fontWeight = FontWeight.Bold
                                ),
                                color = Color(0xFFE56A32),
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = stringResource(R.string.history),
                                style = MaterialTheme.typography.headlineLarge,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                            )
                        }

                        // Search & Menu action buttons
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Surface(
                                shape = CircleShape,
                                color = Color.White.copy(alpha = 0.08f),
                                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .clickable { isSearching = true }
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        painter = painterResource(R.drawable.search),
                                        contentDescription = "Search",
                                        tint = Color.White,
                                        modifier = Modifier.size(19.dp)
                                    )
                                }
                            }

                            Box {
                                Surface(
                                    shape = CircleShape,
                                    color = Color.White.copy(alpha = 0.08f),
                                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(CircleShape)
                                        .clickable { showTopMenu = true }
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            painter = painterResource(R.drawable.more_vert),
                                            contentDescription = "Menu",
                                            tint = Color.White,
                                            modifier = Modifier.size(19.dp)
                                        )
                                    }
                                }

                                DropdownMenu(
                                    expanded = showTopMenu,
                                    onDismissRequest = { showTopMenu = false }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("View Stats") },
                                        onClick = {
                                            showTopMenu = false
                                            navController.navigate("stats")
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Clear All History", color = Color(0xFFEF4444)) },
                                        onClick = {
                                            showTopMenu = false
                                            showClearAllDialog = true
                                        }
                                    )
                                    if (isLoggedIn) {
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    if (historySource == HistorySource.LOCAL) "Switch to Remote History"
                                                    else "Switch to Local History"
                                                )
                                            },
                                            onClick = {
                                                showTopMenu = false
                                                val next = if (historySource == HistorySource.LOCAL) HistorySource.REMOTE else HistorySource.LOCAL
                                                viewModel.historySource.value = next
                                                if (next == HistorySource.REMOTE) viewModel.fetchRemoteHistory()
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "All the music you've listened to, in one place.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.65f)
                    )
                }
            }

            // Category Filter Chips (All, Songs, Albums, Artists)
            item(key = "category_chips") {
                HistoryCategoryChips(
                    selectedCategory = selectedCategory,
                    onSelect = { viewModel.setCategory(it) }
                )
            }

            // "Keep vibing!" Waves Card
            item(key = "keep_vibing_card") {
                KeepVibingWavesCard(navController = navController)
            }

            // Remote Content Handling
            if (historySource == HistorySource.REMOTE && isLoggedIn) {
                filteredRemoteContent?.forEach { section ->
                    stickyHeader {
                        NavigationTitle(
                            title = section.title,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFF0D0B0A).copy(alpha = 0.95f))
                        )
                    }

                    items(
                        items = section.songs,
                        key = { "${section.title}_${it.id}_${section.songs.indexOf(it)}" }
                    ) { song ->
                        YouTubeListItem(
                            item = song,
                            isActive = song.id == mediaMetadata?.id,
                            isPlaying = isPlaying,
                            trailingContent = {
                                IconButton(
                                    onClick = {
                                        menuState.show {
                                            YouTubeSongMenu(
                                                song = song,
                                                navController = navController,
                                                onDismiss = menuState::dismiss
                                            )
                                        }
                                    }
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.more_vert),
                                        contentDescription = null
                                    )
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .combinedClickable(
                                    onClick = {
                                        if (song.id == mediaMetadata?.id) {
                                            playerConnection.player.togglePlayPause()
                                        } else {
                                            playerConnection.playQueue(
                                                YouTubeQueue.radio(song.toMediaMetadata())
                                            )
                                        }
                                    },
                                    onLongClick = {
                                        menuState.show {
                                            YouTubeSongMenu(
                                                song = song,
                                                navController = navController,
                                                onDismiss = menuState::dismiss
                                            )
                                        }
                                    }
                                )
                                .animateItem()
                        )
                    }
                }
            } else {
                // Local Grouped History
                filteredEvents.forEach { (dateAgo, eventList) ->
                    stickyHeader {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFF0D0B0A).copy(alpha = 0.92f))
                                .padding(horizontal = 20.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = dateAgoToString(dateAgo),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                            )

                            if (dateAgo == DateAgo.Today) {
                                // "Clear" pill button for Today
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = Color.White.copy(alpha = 0.08f),
                                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
                                    modifier = Modifier.clickable { showClearTodayDialog = true }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            painter = painterResource(R.drawable.delete),
                                            contentDescription = "Clear",
                                            tint = Color.White.copy(alpha = 0.8f),
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = "Clear",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Medium,
                                            color = Color.White.copy(alpha = 0.85f)
                                        )
                                    }
                                }
                            } else if (dateAgo == DateAgo.ThisWeek) {
                                Text(
                                    text = "See all >",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.White.copy(alpha = 0.6f),
                                    modifier = Modifier.clickable {
                                        // Scroll to top or refresh
                                    }
                                )
                            }
                        }
                    }

                    val currentDateWrappedItems = wrappedItemsMap[dateAgo] ?: emptyList()

                    itemsIndexed(
                        items = currentDateWrappedItems,
                        key = { index, wrappedItem -> "${dateAgo}_${wrappedItem.item.event.id}_$index" }
                    ) { index, wrappedItem ->
                        val event = wrappedItem.item
                        val isCurrentActive = event.song.id == mediaMetadata?.id
                        val isHighlighted = isCurrentActive || (dateAgo == DateAgo.Today && index == 0 && isPlaying)

                        HistorySongRow(
                            event = event,
                            isHighlighted = isHighlighted,
                            isPlaying = isPlaying,
                            isCurrentTrack = isCurrentActive,
                            isSelected = wrappedItem.isSelected && selection,
                            isSelectionMode = selection,
                            onToggleLike = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                viewModel.toggleLike(event.song)
                            },
                            onItemClick = {
                                if (!selection) {
                                    if (event.song.id == mediaMetadata?.id) {
                                        playerConnection.player.togglePlayPause()
                                    } else {
                                        playerConnection.playQueue(
                                            ListQueue(
                                                title = dateAgoToString(dateAgo),
                                                items = currentDateWrappedItems.map { it.item.song.toMediaItem() },
                                                startIndex = index
                                            )
                                        )
                                    }
                                } else {
                                    wrappedItem.isSelected = !wrappedItem.isSelected
                                }
                            },
                            onItemLongClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                if (!selection) {
                                    selection = true
                                    allWrappedItems.forEach { it.isSelected = false }
                                    wrappedItem.isSelected = true
                                }
                            },
                            onMenuClick = {
                                if (!selection) {
                                    menuState.show {
                                        SongMenu(
                                            originalSong = event.song,
                                            event = event.event,
                                            navController = navController,
                                            onDismiss = menuState::dismiss
                                        )
                                    }
                                }
                            },
                            modifier = Modifier.animateItem()
                        )
                    }
                }
            }
        }

        HideOnScrollFAB(
            visible = if (historySource == HistorySource.REMOTE) {
                filteredRemoteContent?.any { it.songs.isNotEmpty() } == true
            } else {
                allWrappedItems.isNotEmpty()
            },
            lazyListState = lazyListState,
            icon = R.drawable.shuffle,
            onClick = {
                if (historySource == HistorySource.REMOTE && historyPage != null) {
                    val songs = filteredRemoteContent?.flatMap { it.songs } ?: emptyList()
                    if (songs.isNotEmpty()) {
                        playerConnection.playQueue(
                            ListQueue(
                                title = context.getString(R.string.history),
                                items = songs.map { it.toMediaItem() }.shuffled()
                            )
                        )
                    }
                } else {
                    playerConnection.playQueue(
                        ListQueue(
                            title = context.getString(R.string.history),
                            items = allWrappedItems.map { it.item.song.toMediaItem() }.shuffled()
                        )
                    )
                }
            }
        )
    }

    // TopAppBar when in searching or multi-selection mode
    if (selection || isSearching) {
        TopAppBar(
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = Color(0xFF0D0B0A).copy(alpha = 0.95f)
            ),
            title = {
                if (selection) {
                    val count = allWrappedItems.count { it.isSelected }
                    Text(
                        text = pluralStringResource(R.plurals.n_song, count, count),
                        style = MaterialTheme.typography.titleLarge,
                        color = Color.White
                    )
                } else if (isSearching) {
                    TextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = {
                            Text(
                                text = stringResource(R.string.search),
                                style = MaterialTheme.typography.titleLarge,
                                color = Color.White.copy(alpha = 0.5f)
                            )
                        },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.titleLarge.copy(color = Color.White),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            disabledIndicatorColor = Color.Transparent,
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester)
                    )
                }
            },
            navigationIcon = {
                IconButton(
                    onClick = {
                        when {
                            isSearching -> {
                                isSearching = false
                                query = TextFieldValue()
                            }
                            selection -> {
                                selection = false
                            }
                            else -> {
                                navController.navigateUp()
                            }
                        }
                    }
                ) {
                    Icon(
                        painter = painterResource(
                            if (selection) R.drawable.close else R.drawable.arrow_back
                        ),
                        contentDescription = null,
                        tint = Color.White
                    )
                }
            },
            actions = {
                if (selection) {
                    val count = allWrappedItems.count { it.isSelected }
                    IconButton(
                        onClick = {
                            if (count == allWrappedItems.size) {
                                allWrappedItems.forEach { it.isSelected = false }
                            } else {
                                allWrappedItems.forEach { it.isSelected = true }
                            }
                        }
                    ) {
                        Icon(
                            painter = painterResource(
                                if (count == allWrappedItems.size) R.drawable.deselect else R.drawable.select_all
                            ),
                            contentDescription = null,
                            tint = Color.White
                        )
                    }
                    IconButton(
                        onClick = {
                            menuState.show {
                                SelectionMediaMetadataMenu(
                                    songSelection = allWrappedItems
                                        .filter { it.isSelected }
                                        .map { it.item.song.toMediaItem().metadata!! },
                                    onDismiss = menuState::dismiss,
                                    clearAction = { selection = false },
                                    currentItems = emptyList()
                                )
                            }
                        }
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.more_vert),
                            contentDescription = null,
                            tint = Color.White
                        )
                    }
                }
            }
        )
    }
}
