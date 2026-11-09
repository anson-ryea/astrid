package run.serialization

import kotlinx.serialization.json.*
import run.SampleResources

/** The unchanged schema-6 resource wire format. */
internal object SampleResourcesJson {
    fun encode(resources: SampleResources): JsonObject = with(resources) {
        buildJsonObject {
            put("elapsed_ms", JsonPrimitive(elapsedMs))
            put("model_ms", JsonPrimitive(modelMs))
            put("tool_ms", JsonPrimitive(toolMs))
            put("evaluation_ms", JsonPrimitive(evaluationMs))
            put("model_requests", JsonPrimitive(modelRequests))
            put("model_responses", JsonPrimitive(modelResponses))
            put("tool_calls", JsonPrimitive(toolCalls))
            put("workspace_tool_calls", JsonPrimitive(workspaceToolCalls))
            put("input_tokens", JsonPrimitive(inputTokens))
            put(
                "output_tokens",
                JsonPrimitive(outputTokens)
            )
            put("total_tokens", JsonPrimitive(totalTokens))
            put("known_input_tokens", JsonPrimitive(knownInputTokens))
            put(
                "known_output_tokens",
                JsonPrimitive(knownOutputTokens)
            )
            put("known_total_tokens", JsonPrimitive(knownTotalTokens))
            put("responses_with_input_usage", JsonPrimitive(responsesWithInputUsage))
            put("responses_with_output_usage", JsonPrimitive(responsesWithOutputUsage))
            put("responses_with_total_usage", JsonPrimitive(responsesWithTotalUsage))
            put("effective_seed", JsonPrimitive(effectiveSeed))
            put("request_output_token_caps", JsonArray(requestOutputTokenCaps.map { JsonPrimitive(it) }))
            put("actual_cost_usd", JsonNull)
            put("actual_cost_source", JsonPrimitive("unavailable_from_client"))
            put("estimated_cost_usd", JsonPrimitive(estimatedCostUsd))
            put(
                "estimated_cost_source",
                JsonPrimitive(if (estimatedCostUsd == null) "unavailable" else "configured_token_prices")
            )
        }
    }

    fun decode(value: JsonObject): SampleResources {
        fun long(name: String): Long = (value[name] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
            ?.also { require(it >= 0) { "negative resource counter: $name" } }
            ?: throw IllegalArgumentException("invalid resource counter: $name")

        fun int(name: String) = long(name).also { require(it <= Int.MAX_VALUE) }.toInt()
        fun nullableLong(name: String): Long? = if (value[name] == JsonNull) null else long(name)
        val estimate = value["estimated_cost_usd"].let {
            if (it == JsonNull) null else (it as? JsonPrimitive)?.takeUnless { p -> p.isString }?.doubleOrNull
                ?.also { number -> require(number.isFinite() && number >= 0) }
                ?: throw IllegalArgumentException("invalid estimated cost")
        }
        val result = SampleResources(
            long("elapsed_ms"), long("model_ms"), long("tool_ms"), long("evaluation_ms"),
            int("model_requests"), int("model_responses"), int("tool_calls"), int("workspace_tool_calls"),
            nullableLong("input_tokens"), nullableLong("output_tokens"), nullableLong("total_tokens"),
            long("known_input_tokens"), long("known_output_tokens"), long("known_total_tokens"),
            int("responses_with_input_usage"), int("responses_with_output_usage"), int("responses_with_total_usage"),
            if (value["effective_seed"] == JsonNull) null else int("effective_seed"), estimate,
            (value["request_output_token_caps"] as? JsonArray
                ?: throw IllegalArgumentException("invalid request caps")).map {
                if (it == JsonNull) null else (it as? JsonPrimitive)?.takeUnless { p -> p.isString }?.intOrNull
                    ?.also { cap -> require(cap > 0) } ?: throw IllegalArgumentException("invalid request output cap")
            })
        require(result.modelResponses <= result.modelRequests && result.workspaceToolCalls <= result.toolCalls && result.requestOutputTokenCaps.size == result.modelRequests) { "inconsistent resource counters" }
        for ((coverage, total, known) in listOf(
            Triple(result.responsesWithInputUsage, result.inputTokens, result.knownInputTokens),
            Triple(result.responsesWithOutputUsage, result.outputTokens, result.knownOutputTokens),
            Triple(result.responsesWithTotalUsage, result.totalTokens, result.knownTotalTokens)
        )) {
            require(
                coverage <= result.modelResponses && (total != null) == (coverage == result.modelRequests) &&
                        (total == null || total == known)
            ) { "inconsistent resource usage coverage" }
        }
        return result
    }
}
