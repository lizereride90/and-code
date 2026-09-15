/**
 * No-op stand-in for the `github` flavor's Firebase Analytics reporter.
 *
 * The standalone build excludes Firebase entirely so it can install side-by-side
 * with the main AndCode app; it collects/reports nothing.
 */
object AnalyticsReporter {
    fun install(
        context: Context,
        enabled: Boolean = false,
    ) = Unit

    fun setEnabled(enabled: Boolean) = Unit

    fun recordRuntimeSessionCompleted() = Unit

    fun recordRuntimeSessionError() = Unit

    fun recordRuntimeSessionStalled(reason: StallReason) = Unit
}
