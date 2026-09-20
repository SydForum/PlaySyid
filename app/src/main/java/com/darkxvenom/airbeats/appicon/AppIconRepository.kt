package com.darkxvenom.airbeats.appicon

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import androidx.core.graphics.drawable.toBitmap
import coil.decode.SvgDecoder
import coil.imageLoader
import coil.request.ImageRequest
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import timber.log.Timber
import java.util.concurrent.TimeUnit

object AppIconRepository {

    private const val PREFS_NAME = "airbeats_app_icon_prefs"
    private const val KEY_SELECTED_ICON = "selected_app_icon_id"

    private const val PACKAGE_NAME = "com.darkxvenom.airbeats"

    const val GITHUB_COMMUNITY_ICONS_URL =
        "https://raw.githubusercontent.com/d0x-dev/AirBeats/main/assets/icons/community_icons.json"

    val DEFAULT_ICON = AppIcon(
        id = "default",
        title = "Default Brand",
        subtitle = "Original vibrant AirBeats gradient",
        author = "AirBeats Team",
        aliasName = "$PACKAGE_NAME.launcher.DefaultIcon",
        bgColors = listOf(Color(0xFFFFFFFF), Color(0xFFF2F4F7)),
        fgTint = null, // null means use original multi-color gradient
        isDefault = true
    )

    val BUILT_IN_ICONS: List<AppIcon> = listOf(
        DEFAULT_ICON,
        AppIcon(
            id = "dark",
            title = "AMOLED Dark",
            subtitle = "Pitch black with crisp titanium emblem",
            author = "AirBeats Team",
            aliasName = "$PACKAGE_NAME.launcher.AmoledDark",
            bgColors = listOf(Color(0xFF000000), Color(0xFF0D0D0E)),
            fgTint = Color(0xFFF2F2F5)
        ),
        AppIcon(
            id = "neon",
            title = "Neon Cyber",
            subtitle = "Synthwave twilight with electric cyan",
            author = "AirBeats Team",
            aliasName = "$PACKAGE_NAME.launcher.NeonCyber",
            bgColors = listOf(Color(0xFF090514), Color(0xFF150A2B)),
            fgTint = Color(0xFF00F0FF)
        ),
        AppIcon(
            id = "crimson",
            title = "Crimson Blood",
            subtitle = "Deep velvet shadow with ruby red",
            author = "AirBeats Team",
            aliasName = "$PACKAGE_NAME.launcher.CrimsonRed",
            bgColors = listOf(Color(0xFF180205), Color(0xFF2E050B)),
            fgTint = Color(0xFFFF1744)
        ),
        AppIcon(
            id = "gold",
            title = "Luxury Gold",
            subtitle = "Matte obsidian with polished champagne gold",
            author = "AirBeats Team",
            aliasName = "$PACKAGE_NAME.launcher.LuxuryGold",
            bgColors = listOf(Color(0xFF0E0E10), Color(0xFF1C1A14)),
            fgTint = Color(0xFFFFD700)
        ),
        AppIcon(
            id = "forest",
            title = "Forest Mint",
            subtitle = "Deep emerald night with luminous mint",
            author = "AirBeats Team",
            aliasName = "$PACKAGE_NAME.launcher.ForestMint",
            bgColors = listOf(Color(0xFF04140D), Color(0xFF0A281A)),
            fgTint = Color(0xFF00E676)
        ),
        AppIcon(
            id = "mono",
            title = "Minimalist Mono",
            subtitle = "Industrial slate with pure porcelain white",
            author = "AirBeats Team",
            aliasName = "$PACKAGE_NAME.launcher.MinimalistMono",
            bgColors = listOf(Color(0xFF1A1A1E), Color(0xFF24242B)),
            fgTint = Color(0xFFFFFFFF)
        )
    )

    /**
     * Returns all available icons for the app.
     */
    fun getAvailableIcons(): List<AppIcon> = BUILT_IN_ICONS

    /**
     * Determines which icon is currently active on the device.
     */
    fun getActiveIconId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedId = prefs.getString(KEY_SELECTED_ICON, null)
        if (!savedId.isNullOrEmpty() && BUILT_IN_ICONS.any { it.id == savedId }) {
            return savedId
        }

        // Check PackageManager state if not in prefs
        try {
            val pm = context.packageManager
            for (icon in BUILT_IN_ICONS) {
                val componentName = ComponentName(context, icon.aliasName)
                val state = pm.getComponentEnabledSetting(componentName)
                if (state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
                    return icon.id
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Error checking active icon via PackageManager")
        }

        return DEFAULT_ICON.id
    }

    /**
     * Changes the active launcher app icon.
     * Uses DONT_KILL_APP to prevent killing the current process.
     */
    fun setActiveIcon(context: Context, targetIconId: String): Boolean {
        val targetIcon = BUILT_IN_ICONS.find { it.id == targetIconId } ?: DEFAULT_ICON
        val pm = context.packageManager

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // Android 13+ atomic batch update
                val settings = mutableListOf<PackageManager.ComponentEnabledSetting>()
                for (icon in BUILT_IN_ICONS) {
                    val componentName = ComponentName(context, icon.aliasName)
                    val newState = if (icon.id == targetIcon.id) {
                        PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                    } else {
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                    }
                    settings.add(
                        PackageManager.ComponentEnabledSetting(
                            componentName,
                            newState,
                            PackageManager.DONT_KILL_APP
                        )
                    )
                }
                pm.setComponentEnabledSettings(settings)
            } else {
                // API 26-32: Enable target FIRST, then disable others
                val targetComponent = ComponentName(context, targetIcon.aliasName)
                pm.setComponentEnabledSetting(
                    targetComponent,
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP
                )

                for (icon in BUILT_IN_ICONS) {
                    if (icon.id != targetIcon.id) {
                        val otherComponent = ComponentName(context, icon.aliasName)
                        pm.setComponentEnabledSetting(
                            otherComponent,
                            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                            PackageManager.DONT_KILL_APP
                        )
                    }
                }
            }

            // Save preference
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_SELECTED_ICON, targetIcon.id)
                .apply()

            Timber.i("AirBeats app icon changed to: ${targetIcon.title}")
            return true
        } catch (e: Exception) {
            Timber.e(e, "Failed to change app icon to: ${targetIcon.id}")
            return false
        }
    }

    /**
     * Applies a community SVG icon dynamically to the Android launcher by pinning/updating a high-res home screen shortcut.
     */
    suspend fun applyCommunityIcon(context: Context, icon: AppIcon): Boolean = withContext(Dispatchers.IO) {
        try {
            // Save preference so app remembers selection
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_SELECTED_ICON, icon.id)
                .apply()

            if (!icon.svgUrl.isNullOrBlank()) {
                val loader = context.imageLoader
                val req = ImageRequest.Builder(context)
                    .data(icon.svgUrl)
                    .decoderFactory(SvgDecoder.Factory())
                    .size(256, 256)
                    .allowHardware(false)
                    .build()
                val result = loader.execute(req)
                val bitmap = result.drawable?.toBitmap(256, 256, Bitmap.Config.ARGB_8888)

                if (bitmap != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val shortcutManager = context.getSystemService(ShortcutManager::class.java)
                    if (shortcutManager != null && shortcutManager.isRequestPinShortcutSupported) {
                        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
                            ?: Intent(context, Class.forName("$PACKAGE_NAME.MainActivity")).apply {
                                action = Intent.ACTION_MAIN
                                addCategory(Intent.CATEGORY_LAUNCHER)
                            }

                        val pinShortcutInfo = ShortcutInfo.Builder(context, "airbeats_icon_${icon.id}")
                            .setIcon(android.graphics.drawable.Icon.createWithBitmap(bitmap))
                            .setShortLabel(context.getString(com.darkxvenom.airbeats.R.string.app_name))
                            .setLongLabel("${context.getString(com.darkxvenom.airbeats.R.string.app_name)} - ${icon.title}")
                            .setIntent(launchIntent)
                            .build()

                        shortcutManager.requestPinShortcut(pinShortcutInfo, null)
                        return@withContext true
                    }
                }
            }
            return@withContext true
        } catch (e: Exception) {
            Timber.e(e, "Failed to apply community icon: ${icon.id}")
            return@withContext false
        }
    }

    /**
     * Generates a GitHub Issue template URL for users to submit custom SVG icons.
     */
    fun buildCommunitySubmissionUrl(iconTitle: String, authorName: String, svgUrl: String): String {
        val titleEncoded = java.net.URLEncoder.encode("[Icon Submission] $iconTitle", "UTF-8")
        val bodyContent = """
            ### Custom Icon Submission for AirBeats
            - **Icon Name:** $iconTitle
            - **Designer / Author:** $authorName
            - **Direct SVG Link:** $svgUrl
            
            *Submitted from AirBeats App Icon Customizer*
        """.trimIndent()
        val bodyEncoded = java.net.URLEncoder.encode(bodyContent, "UTF-8")
        return "https://github.com/d0x-dev/AirBeats/issues/new?title=$titleEncoded&body=$bodyEncoded"
    }

    /**
     * Fetches community SVG icons hosted in the GitHub repository catalog.
     * File: assets/icons/community_icons.json
     */
    private fun parseCommunityJson(jsonStr: String, destination: MutableList<AppIcon>) {
        try {
            val jsonArray = JSONArray(jsonStr)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val id = obj.optString("id", "community_$i")
                val title = obj.optString("title", obj.optString("name", "Community Icon"))
                val author = obj.optString("author", "Community Designer")
                val subtitle = obj.optString("subtitle", "Designed by $author")
                var svgUrl = obj.optString("svgUrl", "")
                if (svgUrl.startsWith("http://")) {
                    svgUrl = "https://" + svgUrl.substring(7)
                }
                if (svgUrl.isNotBlank()) {
                    destination.add(
                        AppIcon(
                            id = id,
                            title = title,
                            subtitle = subtitle,
                            author = author,
                            aliasName = DEFAULT_ICON.aliasName,
                            bgColors = listOf(Color(0xFF1E1E24), Color(0xFF282830)),
                            fgTint = null,
                            isCommunity = true,
                            svgUrl = svgUrl
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Error parsing community icons JSON")
        }
    }

    /**
     * Fetches community SVG icons hosted in the GitHub repository catalog and bundled assets.
     */
    suspend fun fetchCommunityIcons(context: Context? = null): List<AppIcon> = withContext(Dispatchers.IO) {
        val list = mutableListOf<AppIcon>()

        // 1. Load locally bundled assets/icons/community_icons.json first for instant display
        if (context != null) {
            try {
                val assetJson = context.assets.open("icons/community_icons.json").bufferedReader().use { it.readText() }
                parseCommunityJson(assetJson, list)
            } catch (e: Exception) {
                Timber.d("No bundled community icons: ${e.message}")
            }
        }

        // 2. Fetch latest remote icons from GitHub
        try {
            val client = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .build()
            val cacheBusterUrl = "$GITHUB_COMMUNITY_ICONS_URL?_t=${System.currentTimeMillis()}"
            val request = Request.Builder()
                .url(cacheBusterUrl)
                .header("Cache-Control", "no-cache, no-store, must-revalidate")
                .header("Pragma", "no-cache")
                .build()
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string()
                if (!body.isNullOrBlank()) {
                    val remoteList = mutableListOf<AppIcon>()
                    parseCommunityJson(body, remoteList)
                    for (remoteIcon in remoteList) {
                        val existingIndex = list.indexOfFirst { it.id == remoteIcon.id }
                        if (existingIndex >= 0) {
                            list[existingIndex] = remoteIcon
                        } else {
                            list.add(remoteIcon)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Timber.d("No remote community icons loaded: ${e.message}")
        }

        list
    }
}
