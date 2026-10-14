package model

import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

class OpenRouterModelMetadataTest {
    private val model = LLModel(LLMProvider.OpenRouter, "test/model",
        capabilities = listOf(LLMCapability.Completion, LLMCapability.Tools))
    private val catalogue = Json.parseToJsonElement("""
        {"data":[{"id":"test/model","context_length":32000,
          "top_provider":{"max_completion_tokens":8000}}]}
    """).jsonObject

    @Test
    fun nullTokenLimitsUseAdvertisedMaximaAndKeepConfiguredCapabilities() {
        val resolved = resolveOpenRouterModel(model, catalogue)
        assertEquals(32_000, resolved.contextLength)
        assertEquals(8_000, resolved.maxOutputTokens)
        assertEquals(model.capabilities, resolved.capabilities)
    }

    @Test
    fun explicitTokenLimitsArePreserved() {
        val configured = model.copy(contextLength = 16_000, maxOutputTokens = 4_000)
        assertEquals(configured, resolveOpenRouterModel(configured, catalogue))
        assertEquals(8_000, resolveOpenRouterModel(configured.copy(maxOutputTokens = null), catalogue).maxOutputTokens)
        assertEquals(32_000, resolveOpenRouterModel(configured.copy(contextLength = null), catalogue).contextLength)
    }

    @Test
    fun resolvedOutputFitsTheConfiguredContext() {
        val resolved = resolveOpenRouterModel(model.copy(contextLength = 4_000), catalogue)
        assertEquals(4_000, resolved.maxOutputTokens)
        assertFailsWith<IllegalArgumentException> {
            resolveOpenRouterModel(model.copy(contextLength = 1_000, maxOutputTokens = 2_000), catalogue)
        }
    }

    @Test
    fun missingAdvertisedLimitsRequireExplicitConfiguration() {
        val missing = Json.parseToJsonElement("""{"data":[{"id":"test/model"}]}""").jsonObject
        assertFailsWith<IllegalStateException> { resolveOpenRouterModel(model, missing) }
        assertFailsWith<IllegalStateException> { resolveOpenRouterModel(model.copy(contextLength = 32_000), missing) }
        val configured = model.copy(contextLength = 32_000, maxOutputTokens = 4_000)
        assertEquals(configured, resolveOpenRouterModel(configured, missing))
        assertFailsWith<IllegalStateException> {
            resolveOpenRouterModel(configured.copy(id = "unavailable/model"), catalogue)
        }
    }
}
