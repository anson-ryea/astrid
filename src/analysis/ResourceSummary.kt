package analysis

import kotlinx.serialization.json.*
import run.SampleResources

/** Full totals require coverage for every saved sample; known subtotals retain partial observations. */
class ResourceSummary private constructor(val values: JsonObject) {
    companion object {
        val columns: List<String> get() = from(emptyList()).values.keys.toList()

        fun from(samples: List<SavedSample>): ResourceSummary {
            val measured = samples.mapNotNull { it.resources }
            val complete = measured.size == samples.size && samples.isNotEmpty()
            fun sum(values: List<Long>) = values.fold(0L, Math::addExact)
            fun whole(get: (SampleResources) -> Long?): Long? {
                val values = measured.mapNotNull(get)
                return if (complete && values.size == measured.size) sum(values) else null
            }
            return ResourceSummary(buildJsonObject {
                put("samples_with_resources", JsonPrimitive(measured.size))
                put("samples_with_complete_input_usage", JsonPrimitive(measured.count { it.inputTokens != null }))
                put("samples_with_complete_output_usage", JsonPrimitive(measured.count { it.outputTokens != null }))
                put("samples_with_complete_total_usage", JsonPrimitive(measured.count { it.totalTokens != null }))
                put("elapsed_ms", JsonPrimitive(whole { it.elapsedMs }))
                put("model_ms", JsonPrimitive(whole { it.modelMs }))
                put("tool_ms", JsonPrimitive(whole { it.toolMs }))
                put("evaluation_ms", JsonPrimitive(whole { it.evaluationMs }))
                put("model_requests", JsonPrimitive(whole { it.modelRequests.toLong() }))
                put("model_responses", JsonPrimitive(whole { it.modelResponses.toLong() }))
                put("tool_calls", JsonPrimitive(whole { it.toolCalls.toLong() }))
                put("workspace_tool_calls", JsonPrimitive(whole { it.workspaceToolCalls.toLong() }))
                put("input_tokens", JsonPrimitive(whole { it.inputTokens }))
                put("output_tokens", JsonPrimitive(whole { it.outputTokens }))
                put("total_tokens", JsonPrimitive(whole { it.totalTokens }))
                put("known_input_tokens", JsonPrimitive(sum(measured.map { it.knownInputTokens })))
                put("known_output_tokens", JsonPrimitive(sum(measured.map { it.knownOutputTokens })))
                put("known_total_tokens", JsonPrimitive(sum(measured.map { it.knownTotalTokens })))
                put("actual_cost_usd", JsonNull)
                val estimates = measured.mapNotNull { it.estimatedCostUsd }
                val estimate = if (complete && estimates.size == measured.size) estimates.sum().takeIf(Double::isFinite) else null
                put("estimated_cost_usd", JsonPrimitive(estimate))
                put("cost_policy", JsonPrimitive("Billed cost unavailable from client; estimates use configured token prices without cache discounts."))
            })
        }
    }
}
