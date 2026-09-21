package com.darkxvenom.airbeats.ui.screens.settings

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.darkxvenom.airbeats.R
import com.darkxvenom.airbeats.data.local.ScrobblerPreferences
import com.darkxvenom.airbeats.data.local.ScrobblerSettings
import com.darkxvenom.airbeats.ui.component.PreferenceEntry
import com.darkxvenom.airbeats.ui.component.SettingsGeneralCategory
import com.darkxvenom.airbeats.ui.component.SettingsPage
import com.darkxvenom.airbeats.ui.component.SwitchPreference
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.roundToInt

@HiltViewModel
class ScrobblerSettingsViewModel @Inject constructor(
    private val scrobblerPreferences: ScrobblerPreferences,
) : ViewModel() {
    val settings: StateFlow<ScrobblerSettings> = scrobblerPreferences.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ScrobblerSettings())

    fun setEnabled(enabled: Boolean) {
        viewModelScope.launch { scrobblerPreferences.setEnabled(enabled) }
    }

    fun setSubmitNowPlaying(enabled: Boolean) {
        viewModelScope.launch { scrobblerPreferences.setSubmitNowPlaying(enabled) }
    }

    fun setScrobblePercent(percent: Int) {
        viewModelScope.launch { scrobblerPreferences.setScrobblePercent(percent) }
    }
}

private fun isNotificationAccessGranted(context: Context): Boolean {
    val enabledListeners = NotificationManagerCompat.getEnabledListenerPackages(context)
    return context.packageName in enabledListeners
}

private fun openNotificationAccessSettings(context: Context) {
    runCatching {
        context.startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScrobblerSettingsScreen(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
    viewModel: ScrobblerSettingsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsState()

    SettingsPage(
        title = "Scrobbler",
        navController = navController,
        scrollBehavior = scrollBehavior,
    ) {
        SettingsGeneralCategory(
            title = "Scrobbler",
            items = listOf(
                {
                    SwitchPreference(
                        title = { Text("Scrobble") },
                        description = if (settings.enabled) {
                            if (!isNotificationAccessGranted(context)) {
                                "Permission needed: tap to grant Notification Listener access"
                            } else {
                                "Watching ${settings.selectedPackages.size} app(s)"
                            }
                        } else {
                            "Detect and submit plays from other apps"
                        },
                        icon = { Icon(painterResource(R.drawable.graphic_eq), null) },
                        checked = settings.enabled,
                        onCheckedChange = { enabled ->
                            if (enabled && !isNotificationAccessGranted(context)) {
                                openNotificationAccessSettings(context)
                            }
                            viewModel.setEnabled(enabled)
                        },
                    )
                },
                {
                    PreferenceEntry(
                        title = { Text("Choose apps") },
                        description = if (settings.selectedPackages.isEmpty()) "None selected yet" else "${settings.selectedPackages.size} app(s) selected",
                        icon = { Icon(painterResource(R.drawable.music_note), null) },
                        onClick = { navController.navigate("settings/scrobbler/apps") },
                    )
                },
                {
                    SwitchPreference(
                        title = { Text("Submit \"Now Playing\"") },
                        description = "Show currently playing track immediately on status",
                        icon = { Icon(painterResource(R.drawable.play), null) },
                        checked = settings.submitNowPlaying,
                        onCheckedChange = viewModel::setSubmitNowPlaying,
                    )
                },
                {
                    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "Scrobble delay",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = "${settings.scrobblePercent}%",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Text(
                            text = "Plays must reach ${settings.scrobblePercent}% (max 4 mins) before registering",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp, bottom = 4.dp),
                        )
                        Slider(
                            value = settings.scrobblePercent.toFloat(),
                            onValueChange = { viewModel.setScrobblePercent(it.roundToInt()) },
                            valueRange = 25f..90f,
                            steps = 12,
                        )
                    }
                }
            )
        )
    }
}
