package com.darkxvenom.airbeats.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A live, in-app, in-memory log of exactly what MediaScrobbleListenerService
 * is doing. Records track detection, threshold calculation, resets, and scrobble attempts.
 */
@Singleton
class ScrobbleDebugLog @Inject constructor() {
    private val _entries = MutableStateFlow<List<String>>(emptyList())
    val entries: StateFlow<List<String>> = _entries.asStateFlow()

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    fun log(message: String) {
        val stamped = "${timeFormat.format(Date())}  $message"
        _entries.update { (listOf(stamped) + it).take(100) }
    }

    fun clear() {
        _entries.update { emptyList() }
    }
}
