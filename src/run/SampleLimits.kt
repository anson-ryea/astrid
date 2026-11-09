package run

import kotlin.time.Duration

data class SampleLimits(val timeout: Duration? = null, val maxTotalTokens: Long? = null) {
    init {
        require(timeout == null || timeout.isFinite() && timeout > Duration.ZERO && timeout.inWholeMilliseconds > 0) {
            "sample_timeout must be finite and at least one millisecond"
        }
        require(maxTotalTokens == null || maxTotalTokens > 0) { "max_total_tokens_per_sample must be positive" }
    }
}
