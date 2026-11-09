package run

import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** One elapsed-time allowance shared by preparation and sample execution. */
internal class SampleDeadline(private val timeout: Duration?, private val elapsedMs: () -> Long) {
    suspend fun <T : Any> runOrNull(block: suspend () -> T): T? {
        return when (val remaining = timeout?.inWholeMilliseconds?.minus(elapsedMs())?.coerceAtLeast(0)) {
            null -> block()
            0L -> null
            else -> withTimeoutOrNull(remaining.milliseconds) { block() }
        }
    }
}
