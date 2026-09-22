package com.darkxvenom.airbeats.ui.sharedmusic

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.darkxvenom.airbeats.LocalPlayerConnection
import com.darkxvenom.airbeats.db.MusicDatabase
import com.darkxvenom.airbeats.playback.MusicService
import com.darkxvenom.airbeats.playback.PlayerConnection
import com.darkxvenom.airbeats.share.ShareIntentParser
import com.darkxvenom.airbeats.share.SharedContent
import com.darkxvenom.airbeats.ui.theme.AirBeatsTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class SharedMusicIdentificationActivity : ComponentActivity() {

    private val viewModel: SharedMusicViewModel by viewModels()

    @Inject
    lateinit var database: MusicDatabase

    private var playerConnection by mutableStateOf<PlayerConnection?>(null)
    private var isServiceBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            if (service is MusicService.MusicBinder) {
                playerConnection = PlayerConnection(
                    this@SharedMusicIdentificationActivity,
                    service,
                    database,
                    lifecycleScope
                )
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            playerConnection?.dispose()
            playerConnection = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Bind or reuse existing player connection
        playerConnection = PlayerConnection.instance
        if (playerConnection == null) {
            startService(Intent(this, MusicService::class.java))
            isServiceBound = bindService(
                Intent(this, MusicService::class.java),
                serviceConnection,
                Context.BIND_AUTO_CREATE
            )
        }

        val sharedContents = ShareIntentParser.parse(this, intent)
        val initialContent: SharedContent? = sharedContents.firstOrNull()

        if (initialContent != null) {
            viewModel.processSharedContent(initialContent)
        }

        setContent {
            AirBeatsTheme {
                CompositionLocalProvider(
                    LocalPlayerConnection provides (playerConnection ?: PlayerConnection.instance)
                ) {
                    SharedMusicScreen(
                        viewModel = viewModel,
                        sharedContent = initialContent,
                        onPlay = { providerSong ->
                            val pc = playerConnection ?: PlayerConnection.instance
                            if (pc != null) {
                                viewModel.playSong(providerSong, pc)
                                val mainIntent = Intent(this@SharedMusicIdentificationActivity, com.darkxvenom.airbeats.MainActivity::class.java).apply {
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                                }
                                startActivity(mainIntent)
                                finish()
                            }
                        },
                        onClose = { finish() }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val sharedContents = ShareIntentParser.parse(this, intent)
        val initialContent: SharedContent? = sharedContents.firstOrNull()
        if (initialContent != null) {
            viewModel.processSharedContent(initialContent)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isServiceBound) {
            unbindService(serviceConnection)
            isServiceBound = false
        }
    }
}
