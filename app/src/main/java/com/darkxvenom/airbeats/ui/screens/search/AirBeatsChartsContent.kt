package com.darkxvenom.airbeats.ui.screens.search

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.darkxvenom.airbeats.LocalPlayerConnection
import com.darkxvenom.airbeats.R
import com.darkxvenom.airbeats.constants.ListItemHeight
import com.darkxvenom.airbeats.extensions.togglePlayPause
import com.darkxvenom.airbeats.innertube.models.AlbumItem
import com.darkxvenom.airbeats.innertube.models.ArtistItem
import com.darkxvenom.airbeats.innertube.models.PlaylistItem
import com.darkxvenom.airbeats.innertube.models.SongItem
import com.darkxvenom.airbeats.innertube.models.WatchEndpoint
import com.darkxvenom.airbeats.innertube.models.YTItem
import com.darkxvenom.airbeats.innertube.pages.ChartsPage
import com.darkxvenom.airbeats.models.toMediaMetadata
import com.darkxvenom.airbeats.playback.queues.YouTubeQueue
import com.darkxvenom.airbeats.ui.component.LocalMenuState
import com.darkxvenom.airbeats.ui.component.NavigationTitle
import com.darkxvenom.airbeats.ui.component.YouTubeGridItem
import com.darkxvenom.airbeats.ui.component.YouTubeListItem
import com.darkxvenom.airbeats.ui.component.shimmer.GridItemPlaceHolder
import com.darkxvenom.airbeats.ui.component.shimmer.ShimmerHost
import com.darkxvenom.airbeats.ui.component.shimmer.TextPlaceholder
import com.darkxvenom.airbeats.ui.menu.YouTubeAlbumMenu
import com.darkxvenom.airbeats.ui.menu.YouTubeArtistMenu
import com.darkxvenom.airbeats.ui.menu.YouTubePlaylistMenu
import com.darkxvenom.airbeats.ui.menu.YouTubeSongMenu
import com.darkxvenom.airbeats.ui.utils.SnapLayoutInfoProvider
import com.darkxvenom.airbeats.viewmodels.ChartsViewModel
import timber.log.Timber

private fun chartItemShape(index: Int, count: Int, radius: Dp = 12.dp): Shape {
    return when {
        count == 1 -> RoundedCornerShape(radius)
        index % 4 == 0 -> RoundedCornerShape(topStart = radius, topEnd = radius)
        index % 4 == 3 || index == count - 1 -> RoundedCornerShape(bottomStart = radius, bottomEnd = radius)
        else -> RectangleShape
    }
}

@OptIn(ExperimentalFoundationApi::class)
fun LazyListScope.airbeatsChartsItems(
    navController: NavController,
    viewModel: ChartsViewModel,
) {
    item(key = "airbeats_charts_core") {
        AirBeatsChartsEmbedView(
            navController = navController,
            viewModel = viewModel
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AirBeatsChartsEmbedView(
    navController: NavController,
    viewModel: ChartsViewModel = hiltViewModel(),
    modifier: Modifier = Modifier,
) {
    val menuState = LocalMenuState.current
    val haptic = LocalHapticFeedback.current
    val playerConnection = LocalPlayerConnection.current
    val isPlaying by playerConnection?.isPlaying?.collectAsState() ?: remember { androidx.compose.runtime.mutableStateOf(false) }
    val mediaMetadata by playerConnection?.mediaMetadata?.collectAsState() ?: remember { androidx.compose.runtime.mutableStateOf(null) }

    val chartsPage by viewModel.chartsPage.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        Timber.tag("AirBeatsCharts").d("AirBeatsChartsEmbedView displayed, requesting loadCharts()")
        viewModel.loadCharts()
    }

    Column(modifier = modifier.fillMaxWidth()) {
        if (isLoading && chartsPage == null) {
            // Modern Shimmer Loader
            ShimmerHost(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    TextPlaceholder(
                        height = 32.dp,
                        modifier = Modifier
                            .padding(12.dp)
                            .fillMaxWidth(0.5f)
                    )
                    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                        val itemWidthFactor = if (maxWidth * 0.475f >= 320.dp) 0.475f else 0.88f
                        val itemWidth = maxWidth * itemWidthFactor

                        LazyHorizontalGrid(
                            rows = GridCells.Fixed(4),
                            contentPadding = PaddingValues(start = 4.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(ListItemHeight * 4)
                        ) {
                            items(4) {
                                Row(
                                    modifier = Modifier
                                        .width(itemWidth)
                                        .padding(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(ListItemHeight - 16.dp)
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(MaterialTheme.colorScheme.onSurface)
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column(
                                        modifier = Modifier.fillMaxHeight(),
                                        verticalArrangement = Arrangement.Center
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .height(16.dp)
                                                .width(120.dp)
                                                .background(MaterialTheme.colorScheme.onSurface)
                                        )
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Box(
                                            modifier = Modifier
                                                .height(12.dp)
                                                .width(80.dp)
                                                .background(MaterialTheme.colorScheme.onSurface)
                                        )
                                    }
                                }
                            }
                        }
                    }
                    TextPlaceholder(
                        height = 32.dp,
                        modifier = Modifier
                            .padding(vertical = 12.dp, horizontal = 12.dp)
                            .width(220.dp)
                    )
                    Row(modifier = Modifier.fillMaxWidth()) {
                        repeat(2) {
                            GridItemPlaceHolder()
                        }
                    }
                }
            }
        } else if (error != null && (chartsPage == null || chartsPage?.sections.isNullOrEmpty())) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Unable to load AirBeats Charts",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = error ?: "Network error",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(onClick = { viewModel.retry() }) {
                    Text("Retry")
                }
            }
        } else {
            val sections = chartsPage?.sections.orEmpty().filter { it.items.isNotEmpty() }

            if (sections.isEmpty() && !isLoading) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "No charts available",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Could not find chart content. Tap retry to check again.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(onClick = { viewModel.retry() }) {
                        Text("Retry")
                    }
                }
            } else {
                sections.forEach { section ->
                    val isSongSection = (section.chartType == ChartsPage.ChartType.TRENDING || section.title.equals("Trending", ignoreCase = true)) &&
                            section.items.all { it is SongItem }

                    if (isSongSection) {
                        val title = section.title.ifEmpty { "Trending Songs" }
                        NavigationTitle(
                            title = title,
                            modifier = Modifier.padding(top = 8.dp)
                        )

                        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                            val horizontalLazyGridItemWidthFactor = if (maxWidth * 0.475f >= 320.dp) 0.475f else 0.88f
                            val horizontalLazyGridItemWidth = maxWidth * horizontalLazyGridItemWidthFactor

                            val lazyGridState = rememberLazyGridState()
                            val snapLayoutInfoProvider = remember(lazyGridState) {
                                SnapLayoutInfoProvider(
                                    lazyGridState = lazyGridState,
                                    positionInLayout = { layoutSize, itemSize ->
                                        (layoutSize * horizontalLazyGridItemWidthFactor / 2f - itemSize / 2f)
                                    }
                                )
                            }

                            val songItems = remember(section.items) {
                                section.items.filterIsInstance<SongItem>().distinctBy { it.id }
                            }

                            LazyHorizontalGrid(
                                state = lazyGridState,
                                rows = GridCells.Fixed(4),
                                flingBehavior = rememberSnapFlingBehavior(snapLayoutInfoProvider),
                                contentPadding = WindowInsets.systemBars
                                    .only(WindowInsetsSides.Horizontal)
                                    .asPaddingValues(),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(ListItemHeight * 4)
                            ) {
                                itemsIndexed(
                                    items = songItems,
                                    key = { _, it -> it.id }
                                ) { index, song ->
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
                                            .width(horizontalLazyGridItemWidth)
                                            .clip(chartItemShape(index, songItems.size))
                                            .combinedClickable(
                                                onClick = {
                                                    Timber.tag("AirBeatsCharts").d("Song clicked: %s (%s)", song.title, song.id)
                                                    if (song.id == mediaMetadata?.id) {
                                                        playerConnection?.togglePlayPause()
                                                    } else {
                                                        playerConnection?.playQueue(
                                                            YouTubeQueue(
                                                                endpoint = WatchEndpoint(videoId = song.id),
                                                                preloadItem = song.toMediaMetadata()
                                                            )
                                                        )
                                                    }
                                                },
                                                onLongClick = {
                                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                    menuState.show {
                                                        YouTubeSongMenu(
                                                            song = song,
                                                            navController = navController,
                                                            onDismiss = menuState::dismiss
                                                        )
                                                    }
                                                }
                                            )
                                    )
                                }
                            }
                        }
                    } else {
                        // Horizontal Card Row (Music Videos, Playlists, Artists, Genres)
                        val items = remember(section.items) {
                            section.items.distinctBy { it.id }
                        }

                        if (items.isNotEmpty()) {
                            NavigationTitle(
                                title = section.title.ifEmpty { "AirBeats Charts" },
                                modifier = Modifier.padding(top = 12.dp)
                            )

                            LazyRow(
                                contentPadding = WindowInsets.systemBars
                                    .only(WindowInsetsSides.Horizontal)
                                    .asPaddingValues(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                items(
                                    items = items,
                                    key = { it.id }
                                ) { item ->
                                    YouTubeGridItem(
                                        item = item,
                                        isActive = when (item) {
                                            is SongItem -> item.id == mediaMetadata?.id
                                            is AlbumItem -> item.id == mediaMetadata?.album?.id
                                            else -> false
                                        },
                                        isPlaying = isPlaying,
                                        coroutineScope = coroutineScope,
                                        modifier = Modifier.combinedClickable(
                                            onClick = {
                                                Timber.tag("AirBeatsCharts").d("Chart item clicked: %s (%s)", item.title, item::class.simpleName)
                                                when (item) {
                                                    is SongItem -> {
                                                        if (item.id == mediaMetadata?.id) {
                                                            playerConnection?.togglePlayPause()
                                                        } else {
                                                            playerConnection?.playQueue(
                                                                YouTubeQueue(
                                                                    endpoint = WatchEndpoint(videoId = item.id),
                                                                    preloadItem = item.toMediaMetadata()
                                                                )
                                                            )
                                                        }
                                                    }
                                                    is PlaylistItem -> navController.navigate("online_playlist/${item.id}")
                                                    is ArtistItem -> navController.navigate("artist/${item.id}")
                                                    is AlbumItem -> navController.navigate("album/${item.id}")
                                                }
                                            },
                                            onLongClick = {
                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                when (item) {
                                                    is SongItem -> menuState.show {
                                                        YouTubeSongMenu(
                                                            song = item,
                                                            navController = navController,
                                                            onDismiss = menuState::dismiss
                                                        )
                                                    }
                                                    is PlaylistItem -> menuState.show {
                                                        YouTubePlaylistMenu(
                                                            playlist = item,
                                                            coroutineScope = coroutineScope,
                                                            onDismiss = menuState::dismiss
                                                        )
                                                    }
                                                    is ArtistItem -> menuState.show {
                                                        YouTubeArtistMenu(
                                                            artist = item,
                                                            onDismiss = menuState::dismiss
                                                        )
                                                    }
                                                    is AlbumItem -> menuState.show {
                                                        YouTubeAlbumMenu(
                                                            albumItem = item,
                                                            navController = navController,
                                                            onDismiss = menuState::dismiss
                                                        )
                                                    }
                                                }
                                            }
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
