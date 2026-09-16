package com.darkxvenom.airbeats.models

import androidx.annotation.Keep
import com.google.firebase.database.IgnoreExtraProperties

@Keep
@IgnoreExtraProperties
data class DeveloperNewsItem(
    val id: String = "",
    val title: String = "",
    val message: String = "",
    val imageUrl: String? = null,
    val actionUrl: String? = null,
    val actionText: String? = null,
    val tag: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val showPopup: Boolean = false,
    val priority: Int = 0,
    val active: Boolean = true
)
