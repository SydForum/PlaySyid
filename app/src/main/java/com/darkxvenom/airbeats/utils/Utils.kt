package com.darkxvenom.airbeats.utils

import com.google.firebase.crashlytics.FirebaseCrashlytics

fun reportException(throwable: Throwable) {
    throwable.printStackTrace()
    runCatching {
        FirebaseCrashlytics.getInstance().recordException(throwable)
    }
}
