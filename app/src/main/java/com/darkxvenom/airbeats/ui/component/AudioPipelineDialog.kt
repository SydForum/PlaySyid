package com.darkxvenom.airbeats.ui.component

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.darkxvenom.airbeats.R
import com.darkxvenom.airbeats.db.entities.FormatEntity
import com.darkxvenom.airbeats.models.MediaMetadata

/**
 * Reusable Quality Tag Pill displayed just below the seekbar.
 * Shows "High Quality" when playing from JioSaavn 320kbps,
 * or standard codec format (e.g. WEBM, OPUS, AAC, Dolby Atmos) when playing from YouTube.
 * Tapping opens the Audio Pipeline dialog.
 */
@Composable
fun AudioQualityTag(
    currentFormat: FormatEntity?,
    mediaMetadata: MediaMetadata?,
    tint: Color = Color.White,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val isJioSaavn = mediaMetadata?.id?.startsWith("JS:") == true ||
        currentFormat?.playbackUrl?.contains("saavn", ignoreCase = true) == true ||
        currentFormat?.playbackUrl?.contains("jio", ignoreCase = true) == true

    val isDolbyAtmos = currentFormat?.mimeType?.let {
        it.contains("eac3", ignoreCase = true) || it.contains("dolby", ignoreCase = true)
    } == true

    val rawCodec = currentFormat?.mimeType
        ?.substringAfter("/", missingDelimiterValue = "")
        ?.substringBefore(";")
        ?.uppercase() ?: ""

    val label = when {
        isJioSaavn -> "High Quality"
        isDolbyAtmos -> "Dolby Atmos"
        rawCodec.contains("FLAC") || rawCodec.contains("ALAC") -> "Lossless"
        rawCodec.contains("OPUS") -> "OPUS"
        rawCodec.contains("AAC") || rawCodec.contains("MP4A") -> "AAC"
        rawCodec.contains("WEBM") -> "WEBM"
        rawCodec.isNotBlank() -> rawCodec
        else -> if (mediaMetadata != null) "High Quality" else "AAC"
    }

    Surface(
        shape = RoundedCornerShape(6.dp),
        color = tint.copy(alpha = 0.14f),
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        ) {
            Icon(
                painter = painterResource(if (isDolbyAtmos) R.drawable.ic_dolby_atmos else R.drawable.graphic_eq),
                contentDescription = null,
                modifier = Modifier.size(if (isDolbyAtmos) 16.dp else 14.dp),
                tint = tint.copy(alpha = 0.85f),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 11.sp,
                ),
                color = tint.copy(alpha = 0.9f),
            )
        }
    }
}

/**
 * Audio Pipeline inspection bottom sheet matching the exact connected timeline flow:
 * Track Info -> Decoder -> Resampler -> DSP -> Output Device.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioPipelineDialog(
    currentFormat: FormatEntity?,
    mediaMetadata: MediaMetadata?,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val isJioSaavn = mediaMetadata?.id?.startsWith("JS:") == true ||
        currentFormat?.playbackUrl?.contains("saavn", ignoreCase = true) == true ||
        currentFormat?.playbackUrl?.contains("jio", ignoreCase = true) == true

    val rawMime = currentFormat?.mimeType.orEmpty()
    val isDolbyAtmos = rawMime.contains("eac3", ignoreCase = true) || rawMime.contains("dolby", ignoreCase = true)
    val codecUpper = rawMime.substringAfter("/").substringBefore(";").uppercase()

    val sourceName = if (isJioSaavn) "JioSaavn" else "YouTube"
    val formatName = when {
        isJioSaavn -> "AAC"
        isDolbyAtmos -> "E-AC-3 (Dolby Atmos)"
        codecUpper.contains("OPUS") -> "Opus"
        codecUpper.contains("WEBM") -> "WebM (Opus)"
        codecUpper.contains("AAC") || codecUpper.contains("MP4A") -> "AAC"
        codecUpper.contains("FLAC") -> "FLAC"
        codecUpper.isNotBlank() -> codecUpper
        else -> "AAC"
    }

    val sampleRate = currentFormat?.sampleRate ?: 44100
    val bitrateKbps = if (isJioSaavn) 320 else currentFormat?.bitrate?.let { it / 1000 } ?: 340
    val bitDepth = if (codecUpper.contains("FLAC") || codecUpper.contains("ALAC")) "24-bit" else "—"

    val decoderName = when {
        formatName.contains("Opus", ignoreCase = true) -> "c2.android.opus.decoder"
        formatName.contains("FLAC", ignoreCase = true) -> "c2.android.flac.decoder"
        else -> "c2.android.aac.decoder"
    }

    val outputDeviceName = getActiveAudioDevice(context)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF141414),
        contentColor = Color.White,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(vertical = 10.dp)
                    .width(36.dp)
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.25f))
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = "Audio Pipeline",
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 22.sp
                ),
                color = Color.White,
                modifier = Modifier.padding(bottom = 20.dp)
            )

            // Pipeline stage 1: Track Info
            PipelineStep(
                iconRes = R.drawable.info,
                title = "Track Info",
                isFirst = true,
                isLast = false,
                items = listOf(
                    "Source" to sourceName,
                    "Format" to formatName,
                    "Bit Depth" to bitDepth,
                    "Sample Rate" to "$sampleRate Hz",
                    "Bitrate" to "$bitrateKbps kbps",
                    "Channels" to "Stereo"
                )
            )

            // Pipeline stage 2: Decoder
            PipelineStep(
                iconRes = R.drawable.deployed_code_update,
                title = "Decoder",
                isFirst = false,
                isLast = false,
                items = listOf(
                    "Decoder Name" to decoderName
                )
            )

            // Pipeline stage 3: Resampler
            PipelineStep(
                iconRes = R.drawable.tune,
                title = "Resampler",
                isFirst = false,
                isLast = false,
                items = listOf(
                    "I/O Rate" to "$sampleRate Hz → $sampleRate Hz",
                    "Type" to "None",
                    "Cutoff" to "—",
                    "Quality" to "Passthrough"
                )
            )

            // Pipeline stage 4: DSP
            PipelineStep(
                iconRes = R.drawable.graphic_eq,
                title = "DSP",
                isFirst = false,
                isLast = false,
                items = listOf(
                    "PCM Format" to "16-bit PCM",
                    "Sample Rate" to "$sampleRate Hz",
                    "EQ Preset" to "Flat",
                    "Stereo Expand" to "100%",
                    "Buffers" to "2x (500ms, ${sampleRate / 2} frames)",
                    "Output API" to "AudioTrack"
                )
            )

            // Pipeline stage 5: Output Device
            PipelineStep(
                iconRes = R.drawable.volume_up,
                title = "Output Device",
                isFirst = false,
                isLast = true,
                items = listOf(
                    "Device Name" to outputDeviceName,
                    "Bit Depth" to "In: 16-bit Out: 16-bit"
                )
            )
        }
    }
}

@Composable
private fun PipelineStep(
    iconRes: Int,
    title: String,
    isFirst: Boolean,
    isLast: Boolean,
    items: List<Pair<String, String>>,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
    ) {
        // Connected Timeline Column
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.width(32.dp)
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(20.dp)
            )
            if (!isLast) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .width(1.5.dp)
                        .background(Color.White.copy(alpha = 0.25f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "↓",
                        fontSize = 11.sp,
                        color = Color.White.copy(alpha = 0.45f)
                    )
                }
            }
        }

        Spacer(Modifier.width(14.dp))

        // Content Column
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(bottom = if (isLast) 0.dp else 18.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                ),
                color = Color.White
            )
            Spacer(Modifier.height(4.dp))
            items.forEach { (key, value) ->
                Row(
                    modifier = Modifier.padding(vertical = 1.dp)
                ) {
                    Text(
                        text = "$key: ",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        ),
                        color = Color.White.copy(alpha = 0.9f)
                    )
                    Text(
                        text = value,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = 13.sp
                        ),
                        color = Color.White.copy(alpha = 0.7f)
                    )
                }
            }
        }
    }
}

/**
 * Resolves the currently active audio output device name (USB, Bluetooth, Wired, or Speaker).
 */
private fun getActiveAudioDevice(context: Context): String {
    val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return "Speaker"
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        for (device in devices) {
            when (device.type) {
                AudioDeviceInfo.TYPE_USB_DEVICE,
                AudioDeviceInfo.TYPE_USB_HEADSET,
                AudioDeviceInfo.TYPE_USB_ACCESSORY -> {
                    val name = device.productName.toString().trim()
                    return if (name.isNotBlank()) "USB-Audio - $name" else "USB-Audio"
                }
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                AudioDeviceInfo.TYPE_BLE_HEADSET,
                AudioDeviceInfo.TYPE_BLE_SPEAKER -> {
                    val name = device.productName.toString().trim()
                    return if (name.isNotBlank()) "Bluetooth - $name" else "Bluetooth Audio"
                }
                AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> {
                    return "Wired Headphones"
                }
            }
        }
    }
    return "Speaker"
}
