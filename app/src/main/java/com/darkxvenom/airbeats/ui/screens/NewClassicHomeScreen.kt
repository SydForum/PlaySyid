package com.darkxvenom.airbeats.ui.screens

import android.annotation.SuppressLint
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import androidx.navigation.compose.currentBackStackEntryAsState
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.darkxvenom.airbeats.LocalDatabase
import com.darkxvenom.airbeats.LocalPlayerAwareWindowInsets
import com.darkxvenom.airbeats.LocalPlayerConnection
import com.darkxvenom.airbeats.R
import com.darkxvenom.airbeats.constants.InnerTubeCookieKey
import com.darkxvenom.airbeats.db.entities.LocalItem
import com.darkxvenom.airbeats.db.entities.Song
import com.darkxvenom.airbeats.extensions.toMediaItem
import com.darkxvenom.airbeats.innertube.models.SongItem
import com.darkxvenom.airbeats.innertube.models.AlbumItem
import com.darkxvenom.airbeats.innertube.models.ArtistItem
import com.darkxvenom.airbeats.innertube.models.PlaylistItem
import com.darkxvenom.airbeats.innertube.models.YTItem
import com.darkxvenom.airbeats.models.toMediaMetadata
import com.darkxvenom.airbeats.playback.queues.ListQueue
import com.darkxvenom.airbeats.playback.queues.YouTubeAlbumRadio
import com.darkxvenom.airbeats.playback.queues.YouTubeQueue
import com.darkxvenom.airbeats.ui.component.LocalMenuState
import com.darkxvenom.airbeats.ui.menu.SongMenu
import com.darkxvenom.airbeats.ui.menu.YouTubeSongMenu
import com.darkxvenom.airbeats.ui.utils.highQualityThumbnail
import com.darkxvenom.airbeats.utils.rememberPreference
import com.darkxvenom.airbeats.viewmodels.HeroPlaylistData
import com.darkxvenom.airbeats.viewmodels.HomeViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private val NewClassicDarkBg = Color.Black // 100% Pure OLED Black
private val NewClassicSurface = Color(0xFF141414)
private val NewClassicTextPrimary = Color(0xFFFFFFFF)
private val NewClassicTextSecondary = Color(0xFF909AA8)
private val NewClassicSeeAllBg = Color(0xFF1C1C1C)

@SuppressLint("UnusedBoxWithConstraintsScope")
@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalFoundationApi::class,
    ExperimentalMaterial3ExpressiveApi::class
)
@Composable
fun NewClassicHomeScreen(
    navController: NavController,
    onSearchClick: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val menuState = LocalMenuState.current
    val database = LocalDatabase.current
    val playerConnection = LocalPlayerConnection.current ?: return
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    val heroPlaylist by viewModel.heroPlaylist.collectAsState()
    val quickPicks by viewModel.quickPicks.collectAsState()
    val forgottenFavorites by viewModel.forgottenFavorites.collectAsState()
    val keepListening by viewModel.keepListening.collectAsState()
    val aiRecommendedPlaylist by viewModel.aiRecommendedPlaylist.collectAsState()
    val similarRecommendations by viewModel.similarRecommendations.collectAsState()
    val homePage by viewModel.homePage.collectAsState()
    val explorePage by viewModel.explorePage.collectAsState()

    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val pullRefreshState = rememberPullToRefreshState()

    val innerTubeCookie by rememberPreference(InnerTubeCookieKey, "")
    LaunchedEffect(innerTubeCookie) {
        viewModel.onAccountChanged(innerTubeCookie)
    }

    val listState = rememberLazyListState()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val scrollToTop = backStackEntry?.savedStateHandle?.getStateFlow("scrollToTop", false)?.collectAsState()

    LaunchedEffect(scrollToTop?.value) {
        if (scrollToTop?.value == true) {
            listState.animateScrollToItem(0)
            backStackEntry?.savedStateHandle?.set("scrollToTop", false)
        }
    }

    val effectiveHero: HeroPlaylistData = remember(heroPlaylist, quickPicks) {
        heroPlaylist ?: quickPicks?.firstOrNull()?.let { firstSong ->
            val topArtist = firstSong.artists.firstOrNull()?.name ?: "Top Hits"
            HeroPlaylistData(
                title = firstSong.song.title.ifBlank { "$topArtist Soundtracks" },
                subtitle = firstSong.artists.joinToString { it.name }.ifEmpty { "Based on your last listening habits and artists..." },
                tag = "RECOMMENDED",
                thumbnailUrl = firstSong.thumbnailUrl,
                songs = quickPicks.orEmpty(),
                playlistId = null
            )
        } ?: HeroPlaylistData(
            title = "90s HipHop Soundtracks",
            subtitle = "Based on your last listening habits and artists...",
            tag = "HIPHOP",
            thumbnailUrl = "https://images.unsplash.com/photo-1511671782779-c97d3d27a1d4?w=800",
            songs = emptyList(),
            playlistId = null
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(NewClassicDarkBg)
            .pullToRefresh(
                isRefreshing = isRefreshing,
                state = pullRefreshState,
                onRefresh = viewModel::refresh
            )
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .background(NewClassicDarkBg),
            contentPadding = PaddingValues(
                bottom = LocalPlayerAwareWindowInsets.current.asPaddingValues().calculateBottomPadding() + 110.dp
            )
        ) {
            item(key = "hero_section") {
                NewClassicHeroSection(
                    heroData = effectiveHero,
                    onNotificationClick = { navController.navigate("history") },
                    onSettingsClick = { navController.navigate("settings") },
                    onPlayNowClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        if (effectiveHero.songs.isNotEmpty()) {
                            playerConnection.playQueue(
                                ListQueue(
                                    title = effectiveHero.title,
                                    items = effectiveHero.songs.map { it.toMediaItem() }
                                )
                            )
                        } else if (!effectiveHero.playlistId.isNullOrBlank()) {
                            playerConnection.playQueue(
                                YouTubeAlbumRadio(effectiveHero.playlistId)
                            )
                        } else {
                            quickPicks?.firstOrNull()?.let { song ->
                                playerConnection.playQueue(YouTubeQueue.radio(song.toMediaMetadata()))
                            }
                        }
                    }
                )
            }

            val trendingSongs = quickPicks.orEmpty().ifEmpty {
                homePage?.sections?.firstOrNull()?.items?.filterIsInstance<SongItem>().orEmpty().map { item ->
                    Song(
                        song = item.toMediaMetadata().toSongEntity(),
                        artists = item.artists.map { a ->
                            com.darkxvenom.airbeats.db.entities.ArtistEntity(id = a.id ?: "", name = a.name)
                        },
                        album = item.album?.let { a ->
                            com.darkxvenom.airbeats.db.entities.AlbumEntity(id = a.id, title = a.name, songCount = 0, duration = 0)
                        }
                    )
                }
            }

            if (trendingSongs.isNotEmpty()) {
                item(key = "section_trending") {
                    NewClassicSectionHeader(
                        title = "On Trending",
                        onSeeAllClick = {
                            navController.navigate("explore")
                        }
                    )
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(trendingSongs, key = { it.id }) { song ->
                            NewClassicSongCard(
                                title = song.title,
                                subtitle = song.artists.joinToString { it.name }.ifEmpty { "AirBeats" },
                                thumbnailUrl = song.thumbnailUrl,
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    playerConnection.playQueue(YouTubeQueue.radio(song.toMediaMetadata()))
                                },
                                onLongClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    menuState.show {
                                        SongMenu(
                                            originalSong = song,
                                            navController = navController,
                                            onDismiss = menuState::dismiss
                                        )
                                    }
                                }
                            )
                        }
                    }
                }
            }

            if (aiRecommendedPlaylist != null && aiRecommendedPlaylist!!.second.isNotEmpty()) {
                val (playlist, songs) = aiRecommendedPlaylist!!
                item(key = "section_ai_recommended") {
                    Spacer(Modifier.height(24.dp))
                    NewClassicSectionHeader(
                        title = "Recommended by AI",
                        onSeeAllClick = {
                            navController.navigate("local_playlist/${playlist.id}")
                        }
                    )
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(songs, key = { it.id }) { song ->
                            NewClassicSongCard(
                                title = song.title,
                                subtitle = song.artists.joinToString { it.name },
                                thumbnailUrl = song.thumbnailUrl,
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    playerConnection.playQueue(
                                        ListQueue(
                                            title = "Recommended by AI",
                                            items = songs.map { it.toMediaItem() },
                                            startIndex = songs.indexOf(song).coerceAtLeast(0)
                                        )
                                    )
                                },
                                onLongClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    menuState.show {
                                        SongMenu(
                                            originalSong = song,
                                            navController = navController,
                                            onDismiss = menuState::dismiss
                                        )
                                    }
                                }
                            )
                        }
                    }
                }
            } else if (!similarRecommendations.isNullOrEmpty()) {
                similarRecommendations?.firstOrNull()?.let { rec ->
                    item(key = "section_similar_${rec.title}") {
                        Spacer(Modifier.height(24.dp))
                        val headerTitle = when (val title = rec.title) {
                            is Song -> "Similar to ${title.title}"
                            is com.darkxvenom.airbeats.db.entities.Artist -> "More from ${title.title}"
                            else -> "Recommended For You"
                        }
                        NewClassicSectionHeader(
                            title = headerTitle,
                            onSeeAllClick = { navController.navigate("explore") }
                        )
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            items(rec.items, key = { it.id }) { item ->
                                val thumb = when (item) {
                                    is SongItem -> item.thumbnail
                                    else -> item.thumbnail
                                }
                                val sub = when (item) {
                                    is SongItem -> item.artists.joinToString { it.name }
                                    else -> ""
                                }
                                NewClassicSongCard(
                                    title = item.title,
                                    subtitle = sub,
                                    thumbnailUrl = thumb,
                                    onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        when (item) {
                                            is SongItem -> playerConnection.playQueue(YouTubeQueue.radio(item.toMediaMetadata()))
                                            is AlbumItem -> playerConnection.playQueue(YouTubeAlbumRadio(item.playlistId))
                                            is ArtistItem -> item.radioEndpoint?.let { playerConnection.playQueue(YouTubeQueue(it)) }
                                            is PlaylistItem -> item.playEndpoint?.let { playerConnection.playQueue(YouTubeQueue(it)) }
                                        }
                                    },
                                    onLongClick = {
                                        if (item is SongItem) {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            menuState.show {
                                                YouTubeSongMenu(
                                                    song = item,
                                                    navController = navController,
                                                    onDismiss = menuState::dismiss
                                                )
                                            }
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }

            val favoritesList = forgottenFavorites.orEmpty().ifEmpty {
                keepListening.orEmpty().filterIsInstance<Song>()
            }
            if (favoritesList.isNotEmpty()) {
                item(key = "section_favorites") {
                    Spacer(Modifier.height(24.dp))
                    NewClassicSectionHeader(
                        title = "Access Your Favourites",
                        onSeeAllClick = {
                            navController.navigate("auto_playlist/liked")
                        }
                    )
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(favoritesList, key = { it.id }) { song ->
                            NewClassicSongCard(
                                title = song.title,
                                subtitle = song.artists.joinToString { it.name }.ifEmpty { "AirBeats" },
                                thumbnailUrl = song.thumbnailUrl,
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    playerConnection.playQueue(YouTubeQueue.radio(song.toMediaMetadata()))
                                },
                                onLongClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    menuState.show {
                                        SongMenu(
                                            originalSong = song,
                                            navController = navController,
                                            onDismiss = menuState::dismiss
                                        )
                                    }
                                }
                            )
                        }
                    }
                }
            }

            item(key = "bottom_spacer") {
                Spacer(Modifier.height(40.dp))
            }
        }

        PullToRefreshDefaults.LoadingIndicator(
            isRefreshing = isRefreshing,
            state = pullRefreshState,
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(LocalPlayerAwareWindowInsets.current.asPaddingValues()),
        )
    }
}

@Composable
private fun NewClassicHeroSection(
    heroData: HeroPlaylistData,
    onNotificationClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onPlayNowClick: () -> Unit,
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val statusBarHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val heroHeight = (configuration.screenHeightDp.dp * 0.58f).coerceAtLeast(490.dp) + statusBarHeight

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(heroHeight)
            .clipToBounds()
            .background(Color.Black)
    ) {
        // Crisp high-resolution hero image with immersive zoom
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(heroData.thumbnailUrl)
                .crossfade(true)
                .build(),
            contentDescription = heroData.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .scale(1.15f)
        )

        // Continuous organic gradient blend dissolving seamlessly into pure OLED black
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0.0f to Color.Black.copy(alpha = 0.60f),
                            0.12f to Color.Black.copy(alpha = 0.20f),
                            0.24f to Color.Transparent,
                            0.38f to Color.Transparent,
                            0.50f to Color.Black.copy(alpha = 0.15f),
                            0.62f to Color.Black.copy(alpha = 0.38f),
                            0.74f to Color.Black.copy(alpha = 0.68f),
                            0.86f to Color.Black.copy(alpha = 0.92f),
                            0.94f to Color.Black,
                            1.0f to Color.Black
                        )
                    )
                )
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = statusBarHeight)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                IconButton(
                    onClick = onNotificationClick,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.08f))
                ) {
                    Icon(
                        painter = painterResource(R.drawable.notification_on),
                        contentDescription = "Notifications",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }

                IconButton(
                    onClick = onSettingsClick,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.08f))
                ) {
                    Icon(
                        painter = painterResource(R.drawable.settings),
                        contentDescription = "Settings",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }


            Spacer(modifier = Modifier.weight(1f))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp)
            ) {
                Text(
                    text = heroData.tag.uppercase(),
                    style = MaterialTheme.typography.labelMedium.copy(
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 1.6.sp
                    )
                )

                Spacer(Modifier.height(6.dp))

                Text(
                    text = heroData.title,
                    style = MaterialTheme.typography.headlineMedium.copy(
                        color = Color.White,
                        fontWeight = FontWeight.ExtraBold,
                        lineHeight = 32.sp
                    ),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(Modifier.height(4.dp))

                Text(
                    text = heroData.subtitle,
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = Color.White.copy(alpha = 0.72f),
                        fontSize = 12.5.sp
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(Modifier.height(16.dp))

                val interactionSource = remember { MutableInteractionSource() }
                val isPressed by interactionSource.collectIsPressedAsState()
                val buttonScale by animateFloatAsState(
                    targetValue = if (isPressed) 0.93f else 1.0f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessMedium
                    ),
                    label = "playNowScale"
                )

                Button(
                    onClick = onPlayNowClick,
                    interactionSource = interactionSource,
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    contentPadding = PaddingValues(horizontal = 28.dp, vertical = 11.dp),
                    modifier = Modifier
                        .scale(buttonScale)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.play),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "PLAY NOW",
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontWeight = FontWeight.Black,
                                letterSpacing = 0.8.sp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        )
                    }
                }
            }
        }
    }
}


@Composable
private fun NewClassicSectionHeader(
    title: String,
    onSeeAllClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium.copy(
                color = NewClassicTextPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp
            )
        )

        Box(
            modifier = Modifier
                .clip(CircleShape)
                .background(NewClassicSeeAllBg)
                .clickable(onClick = onSeeAllClick)
                .padding(horizontal = 14.dp, vertical = 5.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "SEE ALL",
                style = MaterialTheme.typography.labelSmall.copy(
                    color = Color.White.copy(alpha = 0.75f),
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp,
                    fontSize = 10.5.sp
                )
            )
        }
    }
}

@Composable
private fun NewClassicSongCard(
    title: String,
    subtitle: String,
    thumbnailUrl: String?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.94f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "cardScale"
    )

    Column(
        modifier = modifier
            .width(140.dp)
            .scale(scale)
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick
            )
    ) {
        Box(
            modifier = Modifier
                .size(140.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(NewClassicSurface)
        ) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(thumbnailUrl)
                    .crossfade(true)
                    .build(),
                contentDescription = title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }

        Spacer(Modifier.height(8.dp))

        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium.copy(
                color = NewClassicTextPrimary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.5.sp
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        Spacer(Modifier.height(2.dp))

        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall.copy(
                color = NewClassicTextSecondary,
                fontSize = 12.sp
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
