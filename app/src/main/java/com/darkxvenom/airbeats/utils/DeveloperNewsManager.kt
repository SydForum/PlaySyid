package com.darkxvenom.airbeats.utils

import android.content.Context
import android.content.SharedPreferences
import com.darkxvenom.airbeats.models.DeveloperNewsItem
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import timber.log.Timber
import java.util.concurrent.TimeUnit

object DeveloperNewsManager {
    private const val PREFS_NAME = "airbeats_developer_news"
    private const val KEY_CACHED_NEWS = "cached_news_json"
    private const val KEY_DISMISSED_POPUPS = "dismissed_popup_ids"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var prefs: SharedPreferences? = null

    private val _newsList = MutableStateFlow<List<DeveloperNewsItem>>(emptyList())
    val newsList: StateFlow<List<DeveloperNewsItem>> = _newsList.asStateFlow()

    private val _activePopup = MutableStateFlow<DeveloperNewsItem?>(null)
    val activePopup: StateFlow<DeveloperNewsItem?> = _activePopup.asStateFlow()

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    private var databaseListenerAttached = false

    fun init(context: Context) {
        val appContext = context.applicationContext
        prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // Load cached announcements for instant offline display
        loadCachedNews()

        // Attach Realtime listener & trigger background fetch
        attachRealtimeDatabaseListener()
        refresh()
    }

    fun refresh() {
        scope.launch {
            _isSyncing.value = true
            try {
                fetchFromRestApi()
            } catch (e: Exception) {
                Timber.e(e, "DeveloperNewsManager: Failed to refresh news via REST")
            } finally {
                _isSyncing.value = false
            }
        }
    }

    private fun attachRealtimeDatabaseListener() {
        if (databaseListenerAttached) return
        try {
            val app = runCatching { FirebaseApp.getInstance() }.getOrNull() ?: return
            val database = FirebaseDatabase.getInstance(app)
            val newsRef = database.getReference("developer_news")

            newsRef.addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    scope.launch {
                        val items = mutableListOf<DeveloperNewsItem>()
                        for (child in snapshot.children) {
                            try {
                                val item = child.getValue(DeveloperNewsItem::class.java)
                                if (item != null) {
                                    val finalItem = if (item.id.isBlank()) item.copy(id = child.key.orEmpty()) else item
                                    if (finalItem.active) {
                                        items.add(finalItem)
                                    }
                                }
                            } catch (e: Exception) {
                                Timber.w(e, "DeveloperNewsManager: Error parsing child ")
                            }
                        }
                        updateNews(items)
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    Timber.w("DeveloperNewsManager: Realtime listener cancelled: ")
                    // On error (e.g. permission/network), fallback to authenticated REST query
                    refresh()
                }
            })
            databaseListenerAttached = true
            Timber.d("DeveloperNewsManager: Attached Firebase RTDB listener")
        } catch (e: Exception) {
            Timber.e(e, "DeveloperNewsManager: Failed to attach Realtime Database listener")
        }
    }

    private suspend fun fetchFromRestApi() = withContext(Dispatchers.IO) {
        val app = runCatching { FirebaseApp.getInstance() }.getOrNull() ?: return@withContext
        val options = app.options
        val baseUrl = (options.databaseUrl?.takeIf { it.isNotBlank() } ?: "https://-default-rtdb.firebaseio.com").trimEnd('/')

        var token: String? = null
        try {
            val auth = FirebaseAuth.getInstance()
            val user = auth.currentUser ?: auth.signInAnonymously().await().user
            token = user?.getIdToken(false)?.await()?.token
        } catch (e: Exception) {
            Timber.d("DeveloperNewsManager: Firebase anonymous auth skipped: ")
        }

        val requestUrl = if (!token.isNullOrBlank()) {
            "/developer_news.json?auth="
        } else {
            "/developer_news.json"
        }

        val request = Request.Builder()
            .url(requestUrl)
            .header("Cache-Control", "no-cache")
            .get()
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (response.isSuccessful) {
                val body = response.body.string()
                if (body.isNotBlank() && body != "null" && !body.contains("\"error\"")) {
                    parseNewsJson(body)
                }
            }
        }
    }

    private fun parseNewsJson(jsonString: String) {
        try {
            val root = JSONObject(jsonString)
            val items = mutableListOf<DeveloperNewsItem>()
            val keys = root.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val obj = root.optJSONObject(key) ?: continue
                val item = DeveloperNewsItem(
                    id = obj.optString("id", key),
                    title = obj.optString("title", ""),
                    message = obj.optString("message", ""),
                    imageUrl = obj.optString("imageUrl", "").takeIf { it.isNotBlank() },
                    actionUrl = obj.optString("actionUrl", "").takeIf { it.isNotBlank() },
                    actionText = obj.optString("actionText", "").takeIf { it.isNotBlank() },
                    tag = obj.optString("tag", "").takeIf { it.isNotBlank() },
                    timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
                    showPopup = obj.optBoolean("showPopup", false),
                    priority = obj.optInt("priority", 0),
                    active = obj.optBoolean("active", true)
                )
                if (item.active) {
                    items.add(item)
                }
            }
            updateNews(items)
            cacheNewsJson(jsonString)
        } catch (e: Exception) {
            Timber.e(e, "DeveloperNewsManager: Error parsing REST JSON")
        }
    }

    private fun updateNews(items: List<DeveloperNewsItem>) {
        val sorted = items.sortedWith(
            compareByDescending<DeveloperNewsItem> { it.priority }
                .thenByDescending { it.timestamp }
        )
        _newsList.value = sorted
        recalculateActivePopup(sorted)
    }

    private fun recalculateActivePopup(items: List<DeveloperNewsItem>) {
        val dismissed = getDismissedPopupIds()
        val candidate = items.firstOrNull { it.showPopup && it.id.isNotBlank() && !dismissed.contains(it.id) }
        _activePopup.value = candidate
    }

    fun dismissPopup(newsId: String) {
        if (newsId.isBlank()) return
        val dismissed = getDismissedPopupIds().toMutableSet()
        dismissed.add(newsId)
        prefs?.edit()?.putStringSet(KEY_DISMISSED_POPUPS, dismissed)?.apply()
        recalculateActivePopup(_newsList.value)
    }

    private fun getDismissedPopupIds(): Set<String> {
        return prefs?.getStringSet(KEY_DISMISSED_POPUPS, emptySet()) ?: emptySet()
    }

    private fun cacheNewsJson(jsonString: String) {
        prefs?.edit()?.putString(KEY_CACHED_NEWS, jsonString)?.apply()
    }

    private fun loadCachedNews() {
        val cached = prefs?.getString(KEY_CACHED_NEWS, null)
        if (!cached.isNullOrBlank()) {
            parseNewsJson(cached)
        }
    }
}
