package com.darkxvenom.airbeats.utils

import com.google.firebase.crashlytics.FirebaseCrashlytics

fun reportException(throwable: Throwable) {
    if (throwable is java.util.concurrent.CancellationException ||
        throwable is kotlinx.coroutines.CancellationException) {
        return
    }
    throwable.printStackTrace()
    runCatching {
        FirebaseCrashlytics.getInstance().recordException(throwable)
    }
}
