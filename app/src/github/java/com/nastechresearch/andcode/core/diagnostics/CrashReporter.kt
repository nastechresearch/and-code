package com.nastechresearch.andcode.core.diagnostics

import android.os.Build
import com.google.firebase.crashlytics.FirebaseCrashlytics

/**
 * Best-effort bridge for non-fatal diagnostics.
 *
 * Fatal crashes are collected by Crashlytics automatically. This wrapper keeps ordinary error
 * reporting from making an already failing app fail again, and centralizes the small amount of
 * sanitization needed before sending messages and keys off-device.
 */
object CrashReporter {
    @Volatile
    private var crashlytics: FirebaseCrashlytics? = null

    /** Initializes Crashlytics and records only non-sensitive app metadata. */
    fun install() {
        val client =
            runCatching { FirebaseCrashlytics.getInstance() }
                .getOrNull()
                ?: return
        crashlytics = client
        runCatching {
            client.setCrashlyticsCollectionEnabled(!com.nastechresearch.andcode.BuildConfig.DEBUG)
            client.setCustomKey("app_version", com.nastechresearch.andcode.BuildConfig.VERSION_NAME)
            client.setCustomKey(
                "build_type",
                if (com.nastechresearch.andcode.BuildConfig.DEBUG) "debug" else "release",
            )
            client.setCustomKey("os_version", Build.VERSION.RELEASE)
        }
    }

    fun log(message: String) {
        crashlytics?.let { client ->
            runCatching { client.log(CrashReportSanitizer.message(message)) }
        }
    }

    fun recordException(
        error: Throwable,
        message: String? = null,
        customKeys: Map<String, String> = emptyMap(),
    ) {
        val client = crashlytics ?: return
        runCatching {
            message?.let { client.log(CrashReportSanitizer.message(it)) }
            customKeys.forEach { (key, value) ->
                client.setCustomKey(
                    CrashReportSanitizer.key(key),
                    CrashReportSanitizer.value(value),
                )
            }
            client.recordException(error)
        }
    }
}
