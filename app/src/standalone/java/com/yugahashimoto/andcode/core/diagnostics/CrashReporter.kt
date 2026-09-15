package com.yugahashimoto.andcode.core.diagnostics

/**
 * No-op stand-in for the `github` flavor's Firebase Crashlytics reporter.
 *
 * The standalone build excludes Firebase entirely so it can install side-by-side
 * with the main AndCode app; it reports crashes nowhere.
 */
object CrashReporter {
    fun install() = Unit

    fun log(message: String) = Unit

    fun recordException(
        error: Throwable,
        message: String? = null,
        customKeys: Map<String, String> = emptyMap(),
    ) = Unit
}
