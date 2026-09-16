package com.darkxvenom.airbeats.utils

import android.content.Context
import android.os.Build
import com.darkxvenom.airbeats.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import timber.log.Timber
import java.io.PrintWriter
import java.io.StringWriter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

object AirBeatsCrashReporter {

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    // 5-minute deduplication window for non-fatal exception reporting
    private const val DEDUP_WINDOW_MS = 5 * 60 * 1000L
    private val recentErrors = ConcurrentHashMap<String, Long>()

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .writeTimeout(3, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .build()
    }

    private fun isDuplicate(errorKey: String): Boolean {
        val now = System.currentTimeMillis()
        val lastReported = recentErrors[errorKey]
        if (lastReported != null && (now - lastReported) < DEDUP_WINDOW_MS) {
            return true
        }
        recentErrors[errorKey] = now
        if (recentErrors.size > 200) {
            recentErrors.entries.removeIf { (now - it.value) > DEDUP_WINDOW_MS }
        }
        return false
    }

    /**
     * Reports a non-fatal exception asynchronously with deduplication.
     */
    fun report(throwable: Throwable) {
        if (throwable is java.util.concurrent.CancellationException ||
            throwable is kotlinx.coroutines.CancellationException) {
            return
        }

        val errorKey = "${throwable.javaClass.simpleName}:${throwable.message}:${throwable.stackTrace.firstOrNull()?.toString()}"
        if (isDuplicate(errorKey)) {
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            sendPayload(
                errorName = throwable.javaClass.name,
                errorMessage = throwable.message ?: "Non-fatal exception occurred",
                stackTrace = getStackTraceString(throwable)
            )
        }
    }

    /**
     * Synchronously sends a fatal uncaught crash before the process terminates.
     */
    fun sendCrashSync(
        context: Context?,
        throwable: Throwable,
        stackTrace: String? = null
    ) {
        if (throwable is java.util.concurrent.CancellationException ||
            throwable is kotlinx.coroutines.CancellationException) {
            return
        }

        val trace = stackTrace ?: getStackTraceString(throwable)
        val firstLine = trace.lineSequence().firstOrNull()?.take(150) ?: throwable.javaClass.name
        val errorKey = "${throwable.javaClass.name}:${throwable.message}:$firstLine"
        if (isDuplicate(errorKey)) {
            Timber.d("AirBeatsCrashReporter: Duplicate fatal crash skipped")
            return
        }

        val targetUrl = getWebhookUrl()
        if (targetUrl.isBlank()) return

        val payload = buildJsonPayload(
            errorName = throwable.javaClass.name,
            errorMessage = throwable.message ?: "Fatal uncaught exception",
            stackTrace = trace
        )

        runCatching {
            val body = payload.toString().toRequestBody(jsonMediaType)
            val request = Request.Builder()
                .url(targetUrl)
                .post(body)
                .build()

            client.newCall(request).execute().use { response ->
                Timber.d("AirBeatsCrashReporter: Fatal crash sent with response code ${response.code}")
            }
        }.onFailure { e ->
            Timber.w(e, "AirBeatsCrashReporter: Failed to send fatal crash report to webhook")
        }
    }

    /**
     * Asynchronously sends crash or debug report.
     */
    fun sendCrashAsync(
        errorName: String,
        errorMessage: String,
        stackTrace: String
    ) {
        val firstLine = stackTrace.lineSequence().firstOrNull()?.take(150) ?: errorName
        val errorKey = "$errorName:$errorMessage:$firstLine"
        if (isDuplicate(errorKey)) {
            Timber.d("AirBeatsCrashReporter: Duplicate async crash skipped")
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            sendPayload(errorName, errorMessage, stackTrace)
        }
    }

    private fun sendPayload(
        errorName: String,
        errorMessage: String,
        stackTrace: String
    ) {
        val targetUrl = getWebhookUrl()
        if (targetUrl.isBlank()) return

        val payload = buildJsonPayload(errorName, errorMessage, stackTrace)
        runCatching {
            val body = payload.toString().toRequestBody(jsonMediaType)
            val request = Request.Builder()
                .url(targetUrl)
                .post(body)
                .build()

            client.newCall(request).execute().use { response ->
                Timber.d("AirBeatsCrashReporter: Report dispatched, code=${response.code}")
            }
        }.onFailure { e ->
            Timber.w(e, "AirBeatsCrashReporter: Failed to send report")
        }
    }

    private fun getWebhookUrl(): String {
        val configured = RemoteConfigManager.crashWebhookUrl.trim()
        if (configured.isNotBlank()) return configured

        val statsBase = RemoteConfigManager.statsBaseUrl.trim()
        if (statsBase.isNotBlank()) return "$statsBase/crash"

        return RemoteConfigManager.DEFAULT_CRASH_WEBHOOK_URL
    }

    private fun buildJsonPayload(
        errorName: String,
        errorMessage: String,
        stackTrace: String
    ): JSONObject {
        return JSONObject().apply {
            put("error", errorName)
            put("message", errorMessage)
            put("stack", stackTrace)
            put("version", BuildConfig.VERSION_NAME)
            put("versionCode", BuildConfig.VERSION_CODE.toString())
            put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            put("androidVersion", "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            put("timestamp", System.currentTimeMillis())
        }
    }

    private fun getStackTraceString(throwable: Throwable): String {
        val sw = StringWriter()
        val pw = PrintWriter(sw)
        throwable.printStackTrace(pw)
        pw.flush()
        return sw.toString()
    }
}
