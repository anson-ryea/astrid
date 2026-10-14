package model

import ai.koog.prompt.llm.LLModel
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Resolve advertised maxima instead of relying on a provider's smaller default. */
internal fun resolveOpenRouterModel(model: LLModel, catalogue: JsonObject): LLModel {
    val entries = catalogue["data"] as? JsonArray ?: error("OpenRouter model metadata is missing data")
    val entry = entries.filterIsInstance<JsonObject>().firstOrNull {
        (it["id"] as? JsonPrimitive)?.content == model.id
    } ?: error("OpenRouter model is unavailable: ${model.id}")

    fun JsonObject.positiveLong(name: String): Long? =
        (this[name] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull?.takeIf { it > 0 }

    val context = model.contextLength ?: checkNotNull(entry.positiveLong("context_length")) {
        "OpenRouter does not report context length for ${model.id}; configure context_length explicitly"
    }
    val output = model.maxOutputTokens ?: checkNotNull(
        (entry["top_provider"] as? JsonObject)?.positiveLong("max_completion_tokens"),
    ) {
        "OpenRouter does not report maximum output tokens for ${model.id}; configure max_output_tokens explicitly"
    }.coerceAtMost(context)
    require(output in 1..Int.MAX_VALUE.toLong()) { "maximum output tokens must fit Koog's request limit" }
    require(output <= context) { "maximum output tokens must not exceed context length" }
    return model.copy(contextLength = context, maxOutputTokens = output)
}
