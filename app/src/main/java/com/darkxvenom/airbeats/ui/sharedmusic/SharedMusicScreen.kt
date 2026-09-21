package com.darkxvenom.airbeats.ui.sharedmusic

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.MusicOff
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.darkxvenom.airbeats.LocalPlayerConnection
import com.darkxvenom.airbeats.R
import com.darkxvenom.airbeats.providers.ProviderSong
import com.darkxvenom.airbeats.share.SharedContent
import com.darkxvenom.airbeats.usecases.IdentificationStep

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SharedMusicScreen(
    viewModel: SharedMusicViewModel,
    sharedContent: SharedContent?,
    onPlay: (ProviderSong) -> Unit,
    onClose: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.identify_music),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        viewModel.cancel()
                        onClose()
                    }) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(R.string.close)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.surface
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 24.dp),
            contentAlignment = Alignment.Center
        ) {
            AnimatedContent(
                targetState = uiState,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "SharedMusicUiStateTransition"
            ) { state ->
                when (state) {
                    is SharedMusicUiState.Idle, is SharedMusicUiState.Processing -> {
                        val step = (state as? SharedMusicUiState.Processing)?.step ?: IdentificationStep.VALIDATING
                        ProcessingContent(step = step, onCancel = {
                            viewModel.cancel()
                            onClose()
                        })
                    }

                    is SharedMusicUiState.Success -> {
                        SuccessContent(
                            state = state,
                            onPlay = onPlay
                        )
                    }

                    is SharedMusicUiState.NoMusicFound -> {
                        StatusErrorContent(
                            icon = Icons.Default.MusicOff,
                            title = stringResource(R.string.no_music_found_title),
                            description = stringResource(R.string.no_music_found_desc),
                            primaryButtonText = stringResource(R.string.close),
                            onPrimaryClick = onClose,
                            secondaryButtonText = if (sharedContent != null) stringResource(R.string.retry) else null,
                            onSecondaryClick = {
                                if (sharedContent != null) viewModel.processSharedContent(sharedContent)
                            }
                        )
                    }

                    is SharedMusicUiState.NoAudio -> {
                        StatusErrorContent(
                            icon = Icons.Default.VolumeOff,
                            title = stringResource(R.string.no_audio_found),
                            description = stringResource(R.string.no_music_found_desc),
                            primaryButtonText = stringResource(R.string.close),
                            onPrimaryClick = onClose
                        )
                    }

                    is SharedMusicUiState.UrlShared -> {
                        StatusErrorContent(
                            icon = Icons.Default.Link,
                            title = stringResource(R.string.url_share_title),
                            description = stringResource(R.string.url_share_desc),
                            primaryButtonText = stringResource(R.string.close),
                            onPrimaryClick = onClose,
                            secondaryButtonText = if (sharedContent != null) stringResource(R.string.retry) else null,
                            onSecondaryClick = {
                                if (sharedContent != null) viewModel.processSharedContent(sharedContent)
                            }
                        )
                    }

                    is SharedMusicUiState.UnsupportedMedia -> {
                        StatusErrorContent(
                            icon = Icons.Default.ErrorOutline,
                            title = stringResource(R.string.unsupported_media),
                            description = state.message,
                            primaryButtonText = stringResource(R.string.close),
                            onPrimaryClick = onClose
                        )
                    }

                    is SharedMusicUiState.NetworkError -> {
                        StatusErrorContent(
                            icon = Icons.Default.WifiOff,
                            title = stringResource(R.string.network_error_identify_title),
                            description = stringResource(R.string.network_error_identify_desc),
                            primaryButtonText = stringResource(R.string.retry),
                            onPrimaryClick = {
                                if (sharedContent != null) viewModel.processSharedContent(sharedContent)
                            },
                            secondaryButtonText = stringResource(R.string.close),
                            onSecondaryClick = onClose
                        )
                    }

                    is SharedMusicUiState.Error -> {
                        StatusErrorContent(
                            icon = Icons.Default.ErrorOutline,
                            title = stringResource(R.string.error),
                            description = state.message,
                            primaryButtonText = stringResource(R.string.retry),
                            onPrimaryClick = {
                                if (sharedContent != null) viewModel.processSharedContent(sharedContent)
                            },
                            secondaryButtonText = stringResource(R.string.close),
                            onSecondaryClick = onClose
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProcessingContent(
    step: IdentificationStep,
    onCancel: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            modifier = Modifier
                .size(100.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(80.dp),
                strokeWidth = 4.dp,
                color = MaterialTheme.colorScheme.primary
            )
            Icon(
                imageVector = Icons.Default.MusicNote,
                contentDescription = null,
                modifier = Modifier.size(36.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }

        Spacer(modifier = Modifier.height(28.dp))

        Text(
            text = stringResource(R.string.identifying_music),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(modifier = Modifier.height(8.dp))

        val stepText = when (step) {
            IdentificationStep.VALIDATING -> stringResource(R.string.analyzing_media)
            IdentificationStep.RESOLVING_LINK -> stringResource(R.string.resolving_link)
            IdentificationStep.ANALYZING_MEDIA -> stringResource(R.string.analyzing_media)
            IdentificationStep.EXTRACTING_AUDIO -> stringResource(R.string.extracting_audio)
            IdentificationStep.IDENTIFYING -> stringResource(R.string.recognizing_song)
            IdentificationStep.SEARCHING_AIRBEATS -> stringResource(R.string.searching_airbeats)
            IdentificationStep.COMPLETE -> stringResource(R.string.music_found)
        }

        Text(
            text = stepText,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(32.dp))

        OutlinedButton(onClick = onCancel) {
            Text(stringResource(R.string.cancel))
        }
    }
}

@Composable
private fun SuccessContent(
    state: SharedMusicUiState.Success,
    onPlay: (ProviderSong) -> Unit
) {
    val scrollState = rememberScrollState()

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(scrollState)
            .padding(vertical = 24.dp)
    ) {
        // Album Artwork
        val artUrl = state.identifiedSong.albumArtUrl ?: state.airBeatsMatch?.thumbnailUrl
        Card(
            shape = RoundedCornerShape(20.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
            modifier = Modifier.size(220.dp)
        ) {
            if (!artUrl.isNullOrBlank()) {
                AsyncImage(
                    model = artUrl,
                    contentDescription = state.identifiedSong.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.MusicNote,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Identification Badge
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = stringResource(R.string.music_found),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Song Title
        Text(
            text = state.identifiedSong.title,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )

        // Artist Name
        if (!state.identifiedSong.artist.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = state.identifiedSong.artist,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        // Album Name
        if (!state.identifiedSong.album.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = state.identifiedSong.album,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(modifier = Modifier.height(28.dp))

        // Play Button
        val matchToPlay = state.airBeatsMatch ?: state.candidates.firstOrNull()
        if (matchToPlay != null) {
            Button(
                onClick = { onPlay(matchToPlay) },
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    stringResource(R.string.play_in_airbeats),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // Alternative candidates list if multiple songs were matched
        if (state.candidates.size > 1) {
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = "Alternative Matches",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.Start)
            )
            Spacer(modifier = Modifier.height(8.dp))

            state.candidates.take(3).forEach { candidate ->
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clickable { onPlay(candidate) }
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(12.dp)
                    ) {
                        AsyncImage(
                            model = candidate.thumbnailUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(8.dp))
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = candidate.title,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = candidate.artists.joinToString(", "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        IconButton(onClick = { onPlay(candidate) }) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusErrorContent(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    description: String,
    primaryButtonText: String,
    onPrimaryClick: () -> Unit,
    secondaryButtonText: String? = null,
    onSecondaryClick: (() -> Unit)? = null
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Box(
            modifier = Modifier
                .size(80.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.error
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = onPrimaryClick,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(primaryButtonText)
        }

        if (secondaryButtonText != null && onSecondaryClick != null) {
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedButton(
                onClick = onSecondaryClick,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(secondaryButtonText)
            }
        }
    }
}
