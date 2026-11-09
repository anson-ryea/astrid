package run

import ai.koog.prompt.executor.clients.openrouter.OpenRouterParams
import ai.koog.prompt.executor.clients.openrouter.models.ProviderPreferences
import kotlinx.serialization.json.JsonPrimitive
import kotlin.time.Duration

/** Null sampling values defer to the provider; configured parameters must be supported. */
data class GenerationSettings(
    val temperature: Double? = null,
    val topP: Double? = null,
    val topK: Int? = null,
    val seed: Int? = null,
) {
    init {
        require(temperature == null || temperature.isFinite() && temperature in 0.0..2.0) { "temperature must be in [0, 2]" }
        require(topP == null || topP.isFinite() && topP > 0 && topP <= 1) { "top_p must be in (0, 1]" }
        require(topK == null || topK > 0) { "top_k must be positive" }
        require(seed == null || seed >= 0) { "seed must not be negative" }
    }

    fun effectiveSeed(sampleIndex: Int): Int? {
        require(sampleIndex > 0)
        return seed?.let { ((it.toLong() + sampleIndex - 1) % (Int.MAX_VALUE.toLong() + 1)).toInt() }
    }

    fun params(sampleIndex: Int, maxTokens: Int?) = OpenRouterParams(
        temperature = temperature, topP = topP, topK = topK, maxTokens = maxTokens,
        additionalProperties = effectiveSeed(sampleIndex)?.let { mapOf("seed" to JsonPrimitive(it)) },
        provider = if (temperature != null || topP != null || topK != null || seed != null)
            ProviderPreferences(requireParameters = true) else null,
    )
}

data class SampleLimits(val timeout: Duration? = null, val maxTotalTokens: Long? = null) {
    init {
        require(timeout == null || timeout.isFinite() && timeout > Duration.ZERO && timeout.inWholeMilliseconds > 0) {
            "sample_timeout must be finite and at least one millisecond"
        }
        require(maxTotalTokens == null || maxTotalTokens > 0) { "max_total_tokens_per_sample must be positive" }
    }
}

/** User-supplied estimates; these are not provider invoices and do not model cache discounts. */
data class TokenPricing(val inputUsdPerMillion: Double, val outputUsdPerMillion: Double) {
    init {
        require(inputUsdPerMillion.isFinite() && inputUsdPerMillion >= 0 &&
            outputUsdPerMillion.isFinite() && outputUsdPerMillion >= 0) { "token prices must be finite and non-negative" }
    }
}
