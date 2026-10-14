package model

import ai.koog.prompt.executor.clients.openrouter.OpenRouterLLMClient
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

class OpenRouterConnection(private val apiKey: String): AutoCloseable {
    init {
        require(apiKey.isNotBlank()) { "OpenRouter API key must not be blank" }
    }

    private val client = OpenRouterLLMClient(apiKey)

    val promptExecutor: PromptExecutor = MultiLLMPromptExecutor(client)

    /** Resolves null token limits from live metadata while preserving supplied capabilities. */
    suspend fun resolveModel(model: LLModel): LLModel {
        require(model.provider == LLMProvider.OpenRouter) { "model must use the OpenRouter provider" }
        require(model.id.isNotBlank()) { "model ID must not be blank" }
        // Koog 1.3.0 models() discards the API's limits for models outside its static catalogue.
        val source = withContext(Dispatchers.IO) {
            val request = HttpRequest.newBuilder(URI.create("https://openrouter.ai/api/v1/models"))
                .header("Authorization", "Bearer $apiKey")
                .timeout(Duration.ofSeconds(60))
                .GET().build()
            val response = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build()
                .send(request, HttpResponse.BodyHandlers.ofString())
            check(response.statusCode() in 200..299) {
                "OpenRouter model metadata request failed: HTTP ${response.statusCode()}"
            }
            response.body()
        }
        val catalogue = Json.parseToJsonElement(source) as? JsonObject
            ?: error("OpenRouter model metadata must be an object")
        return resolveOpenRouterModel(model, catalogue)
    }

    override fun close() {
        promptExecutor.close()
    }
}
