package com.darkxvenom.airbeats.utils

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import com.darkxvenom.airbeats.BuildConfig
import com.darkxvenom.airbeats.innertube.models.YTItem
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.google.firebase.remoteconfig.FirebaseRemoteConfigSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import timber.log.Timber

data class AppRemoteConfig(
    val playDomain: String,
    val statsBaseUrl: String,
    val statsApiKey: String,
    val googleApiKey: String,
    val listenTogetherUrl: String,
)

object RemoteConfigManager {
    private const val PREFS_NAME = "airbeats_remote_config_cache"
    private const val KEY_PLAY_DOMAIN = "play_domain"
    private const val KEY_STATS_BASE_URL = "stats_base_url"
    private const val KEY_STATS_API_KEY = "stats_api_key"
    private const val KEY_GOOGLE_API_KEY = "google_api_key"
    private const val KEY_LISTEN_TOGETHER_URL = "listen_together_url"
    private const val KEY_APP_CONFIG_JSON = "app_config"
    private const val KEY_LAST_SYNC = "last_sync_timestamp"

    val DEFAULT_PLAY_DOMAIN: String = "https://play.airbeats.org"
    val DEFAULT_STATS_BASE_URL: String = BuildConfig.STATS_BASE_URL.ifBlank { "https://db.drkvenom786.workers.dev" }
    val DEFAULT_STATS_API_KEY: String = BuildConfig.STATS_API_KEY
    val DEFAULT_GOOGLE_API_KEY: String = BuildConfig.GOOGLE_API_KEY
    val DEFAULT_LISTEN_TOGETHER_URL: String = "https://listentogether.airbeats.org"

    @Volatile
    private var activeConfig: AppRemoteConfig = AppRemoteConfig(
        playDomain = normalizeUrl(DEFAULT_PLAY_DOMAIN),
        statsBaseUrl = normalizeUrl(DEFAULT_STATS_BASE_URL),
        statsApiKey = DEFAULT_STATS_API_KEY,
        googleApiKey = DEFAULT_GOOGLE_API_KEY,
        listenTogetherUrl = normalizeUrl(DEFAULT_LISTEN_TOGETHER_URL),
    )

    private var sharedPreferences: SharedPreferences? = null

    val playDomain: String
        get() = activeConfig.playDomain

    val statsBaseUrl: String
        get() = activeConfig.statsBaseUrl

    val statsApiKey: String
        get() = activeConfig.statsApiKey

    val googleApiKey: String
        get() = activeConfig.googleApiKey

    val listenTogetherUrl: String
        get() = activeConfig.listenTogetherUrl

    /**
     * Initializes the RemoteConfigManager:
     * 1. Synchronously reads the local cache so valid configuration is immediately available on app startup.
     * 2. Sets the innertube shareDomainProvider delegate.
     * 3. Asynchronously fetches the latest values from Firebase Remote Config without blocking launch.
     */
    fun initialize(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        sharedPreferences = prefs

        // 1. Read cached values synchronously
        loadFromCache(prefs)

        // 2. Wire domain provider to innertube
        YTItem.shareDomainProvider = { playDomain }

        // 3. Asynchronously fetch from Firebase
        CoroutineScope(Dispatchers.IO).launch {
            fetchFromFirebase()
        }
    }

    private fun loadFromCache(prefs: SharedPreferences) {
        val cachedPlayDomain = prefs.getString(KEY_PLAY_DOMAIN, null)
        val cachedStatsBaseUrl = prefs.getString(KEY_STATS_BASE_URL, null)
        val cachedStatsApiKey = prefs.getString(KEY_STATS_API_KEY, null)
        val cachedGoogleApiKey = prefs.getString(KEY_GOOGLE_API_KEY, null)
        val cachedListenTogetherUrl = prefs.getString(KEY_LISTEN_TOGETHER_URL, null)

        activeConfig = AppRemoteConfig(
            playDomain = normalizeUrl(cachedPlayDomain ?: DEFAULT_PLAY_DOMAIN),
            statsBaseUrl = normalizeUrl(cachedStatsBaseUrl ?: DEFAULT_STATS_BASE_URL),
            statsApiKey = cachedStatsApiKey ?: DEFAULT_STATS_API_KEY,
            googleApiKey = cachedGoogleApiKey ?: DEFAULT_GOOGLE_API_KEY,
            listenTogetherUrl = normalizeUrl(cachedListenTogetherUrl ?: DEFAULT_LISTEN_TOGETHER_URL),
        )

        Timber.d("RemoteConfigManager: Loaded cached config -> playDomain=$playDomain, statsBaseUrl=$statsBaseUrl")
    }

    private suspend fun fetchFromFirebase() {
        try {
            val remoteConfig = FirebaseRemoteConfig.getInstance()
            val configSettings = FirebaseRemoteConfigSettings.Builder()
                .setMinimumFetchIntervalInSeconds(0) // Zero throttle so changes take effect immediately on next open
                .setFetchTimeoutInSeconds(10)
                .build()

            remoteConfig.setConfigSettingsAsync(configSettings)

            // Setup in-memory defaults
            val defaults = HashMap<String, Any>().apply {
                put(KEY_PLAY_DOMAIN, DEFAULT_PLAY_DOMAIN)
                put(KEY_STATS_BASE_URL, DEFAULT_STATS_BASE_URL)
                put(KEY_STATS_API_KEY, DEFAULT_STATS_API_KEY)
                put(KEY_GOOGLE_API_KEY, DEFAULT_GOOGLE_API_KEY)
                put(KEY_LISTEN_TOGETHER_URL, DEFAULT_LISTEN_TOGETHER_URL)
            }
            remoteConfig.setDefaultsAsync(defaults)

            remoteConfig.fetchAndActivate()
                .addOnSuccessListener {
                    parseAndUpdateConfig(remoteConfig)
                }
                .addOnFailureListener { e ->
                    Timber.w(e, "RemoteConfigManager: Failed to fetch remote config, using cached values.")
                }
        } catch (e: Exception) {
            Timber.w(e, "RemoteConfigManager: Exception during Firebase Remote Config initialization: ${e.message}")
        }

        // In addition, if a secure authenticated endpoint is configured, fetch from it with access key
        val secureUrl = BuildConfig.FIREBASE_CONFIG_URL.trim()
        val secureKey = BuildConfig.FIREBASE_CONFIG_KEY.trim()
        if (secureUrl.isNotBlank()) {
            fetchFromSecureEndpoint(secureUrl, secureKey)
        }
    }

    private suspend fun fetchFromSecureEndpoint(endpointUrl: String, authKey: String) {
        try {
            var requestUrl = endpointUrl
            if (authKey.isNotBlank()) {
                val separator = if (requestUrl.contains("?")) "&" else "?"
                if (!requestUrl.contains("auth=")) {
                    requestUrl = "$requestUrl${separator}auth=$authKey"
                }
            }

            val requestBuilder = okhttp3.Request.Builder().url(requestUrl)
            if (authKey.isNotBlank()) {
                requestBuilder.header("X-API-Key", authKey)
                requestBuilder.header("Authorization", "Bearer $authKey")
            }

            val httpClient = okhttp3.OkHttpClient.Builder()
                .callTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
                .build()

            httpClient.newCall(requestBuilder.build()).execute().use { response ->
                if (response.isSuccessful) {
                    val rawBody = response.body.string().trim()
                    if (rawBody.isBlank()) return@use
                    val resolvedJson = if (rawBody.startsWith("{")) {
                        val obj = JSONObject(rawBody)
                        if (obj.optBoolean("encrypted", false) && obj.has("data")) {
                            decryptAes(obj.getString("data"), authKey.ifBlank { "airbeats_secure_key" }) ?: rawBody
                        } else {
                            rawBody
                        }
                    } else {
                        // Might be raw encrypted base64 payload
                        decryptAes(rawBody, authKey.ifBlank { "airbeats_secure_key" }) ?: rawBody
                    }
                    parseAndApplyJson(resolvedJson)
                } else {
                    Timber.w("RemoteConfigManager: Secure URL returned code=${response.code} (Unauthorized/Protected)")
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "RemoteConfigManager: Failed to fetch from secure endpoint: ${e.message}")
        }
    }

    private fun decryptAes(cipherTextBase64: String, secretKey: String): String? {
        return try {
            val decoded = android.util.Base64.decode(cipherTextBase64, android.util.Base64.DEFAULT)
            if (decoded.size < 16) return null
            val iv = ByteArray(16)
            System.arraycopy(decoded, 0, iv, 0, 16)
            val cipherBytes = ByteArray(decoded.size - 16)
            System.arraycopy(decoded, 16, cipherBytes, 0, cipherBytes.size)

            val md = java.security.MessageDigest.getInstance("SHA-256")
            val keyBytes = md.digest(secretKey.toByteArray(Charsets.UTF_8))
            val keySpec = javax.crypto.spec.SecretKeySpec(keyBytes, "AES")
            val ivSpec = javax.crypto.spec.IvParameterSpec(iv)

            val cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(javax.crypto.Cipher.DECRYPT_MODE, keySpec, ivSpec)
            String(cipher.doFinal(cipherBytes), Charsets.UTF_8)
        } catch (e: Exception) {
            Timber.w(e, "RemoteConfigManager: Failed to decrypt payload")
            null
        }
    }

    private fun parseAndUpdateConfig(remoteConfig: FirebaseRemoteConfig) {
        val jsonConfigString = remoteConfig.getString(KEY_APP_CONFIG_JSON).trim()
        val individualPlayDomain = remoteConfig.getString(KEY_PLAY_DOMAIN).trim().ifBlank { null }
        val individualStatsBaseUrl = remoteConfig.getString(KEY_STATS_BASE_URL).trim().ifBlank { null }
        val individualStatsApiKey = remoteConfig.getString(KEY_STATS_API_KEY).trim().ifBlank { null }
        val individualGoogleApiKey = remoteConfig.getString(KEY_GOOGLE_API_KEY).trim().ifBlank { null }
        val individualListenTogetherUrl = remoteConfig.getString(KEY_LISTEN_TOGETHER_URL).trim().ifBlank { null }

        parseAndApplyJson(
            jsonString = jsonConfigString,
            overridePlayDomain = individualPlayDomain,
            overrideStatsBaseUrl = individualStatsBaseUrl,
            overrideStatsApiKey = individualStatsApiKey,
            overrideGoogleApiKey = individualGoogleApiKey,
            overrideListenTogetherUrl = individualListenTogetherUrl
        )
    }

    private fun parseAndApplyJson(
        jsonString: String?,
        overridePlayDomain: String? = null,
        overrideStatsBaseUrl: String? = null,
        overrideStatsApiKey: String? = null,
        overrideGoogleApiKey: String? = null,
        overrideListenTogetherUrl: String? = null,
    ) {
        var remotePlayDomain: String? = overridePlayDomain
        var remoteStatsBaseUrl: String? = overrideStatsBaseUrl
        var remoteStatsApiKey: String? = overrideStatsApiKey
        var remoteGoogleApiKey: String? = overrideGoogleApiKey
        var remoteListenTogetherUrl: String? = overrideListenTogetherUrl

        if (!jsonString.isNullOrBlank() && jsonString.startsWith("{")) {
            try {
                val json = JSONObject(jsonString)
                if (remotePlayDomain == null && json.has("play_domain")) remotePlayDomain = json.optString("play_domain")
                if (remoteStatsBaseUrl == null && json.has("stats_base_url")) remoteStatsBaseUrl = json.optString("stats_base_url")
                if (remoteStatsApiKey == null && json.has("stats_api_key")) remoteStatsApiKey = json.optString("stats_api_key")
                if (remoteGoogleApiKey == null && json.has("google_api_key")) remoteGoogleApiKey = json.optString("google_api_key")
                if (remoteListenTogetherUrl == null && json.has("listen_together_url")) remoteListenTogetherUrl = json.optString("listen_together_url")
            } catch (e: Exception) {
                Timber.w(e, "RemoteConfigManager: Failed to parse JSON configuration")
            }
        }

        val newPlayDomain = normalizeUrl(remotePlayDomain ?: activeConfig.playDomain)
        val newStatsBaseUrl = normalizeUrl(remoteStatsBaseUrl ?: activeConfig.statsBaseUrl)
        val newStatsApiKey = remoteStatsApiKey?.ifBlank { null } ?: activeConfig.statsApiKey
        val newGoogleApiKey = remoteGoogleApiKey?.ifBlank { null } ?: activeConfig.googleApiKey
        val newListenTogetherUrl = normalizeUrl(remoteListenTogetherUrl ?: activeConfig.listenTogetherUrl)

        val newConfig = AppRemoteConfig(
            playDomain = newPlayDomain,
            statsBaseUrl = newStatsBaseUrl,
            statsApiKey = newStatsApiKey,
            googleApiKey = newGoogleApiKey,
            listenTogetherUrl = newListenTogetherUrl,
        )

        // Compare against activeConfig
        if (newConfig != activeConfig) {
            Timber.i("RemoteConfigManager: Detected updated remote configuration! Saving to cache.")
            activeConfig = newConfig
            sharedPreferences?.edit()?.apply {
                putString(KEY_PLAY_DOMAIN, newConfig.playDomain)
                putString(KEY_STATS_BASE_URL, newConfig.statsBaseUrl)
                putString(KEY_STATS_API_KEY, newConfig.statsApiKey)
                putString(KEY_GOOGLE_API_KEY, newConfig.googleApiKey)
                putString(KEY_LISTEN_TOGETHER_URL, newConfig.listenTogetherUrl)
                putLong(KEY_LAST_SYNC, System.currentTimeMillis())
                apply()
            }
        } else {
            Timber.d("RemoteConfigManager: Remote config is identical to cached values. No update needed.")
        }
    }

    private fun normalizeUrl(url: String): String {
        return url.trim().trimEnd('/')
    }

    // Helper functions for share URLs
    fun getShareUrl(path: String, id: String): String = "$playDomain/$path?id=$id"
    fun getSongShareUrl(id: String): String = "$playDomain/song?id=$id"
    fun getPlaylistShareUrl(id: String): String = "$playDomain/playlist?id=$id"
    fun getAlbumShareUrl(id: String): String = "$playDomain/album?id=$id"
    fun getArtistShareUrl(id: String): String = "$playDomain/artist?id=$id"

    // Helper functions for deep link matching
    fun isMatchingPlayDomain(host: String?): Boolean {
        if (host == null) return false
        val currentHost = runCatching { Uri.parse(playDomain).host }.getOrNull()
        return host.equals(currentHost, ignoreCase = true) ||
                host.equals("play.airbeats.org", ignoreCase = true) ||
                host.equals("play.airbeats.app", ignoreCase = true) ||
                host.equals("airbeats.org", ignoreCase = true)
    }

    fun isMatchingListenTogetherDomain(host: String?): Boolean {
        if (host == null) return false
        val currentHost = runCatching { Uri.parse(listenTogetherUrl).host }.getOrNull()
        return host.equals(currentHost, ignoreCase = true) ||
                host.equals("listentogether.airbeats.org", ignoreCase = true) ||
                host.equals("listentogether.airbeats.app", ignoreCase = true)
    }
}
