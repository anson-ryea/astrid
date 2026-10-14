package run

import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel

/** Version-one settings; null limits impose no local cap. */
data class BenchmarkRunConfig(
    val modelId: String,
    val contextLength: Long?,
    val maxOutputTokens: Long?,
    val supportsTools: Boolean,
    val samplesPerTask: Int,
    val maxRepairTurns: Int?,
    val maxAgentIterations: Int?,
    val maxToolCallsPerAttempt: Int? = 0,
    val maxWorkspaceToolCallsPerAttempt: Int? = 0,
) {
    init {
        require(modelId.isNotBlank()) { "model ID must not be blank" }
        require(contextLength == null || contextLength > 0) { "context length must be positive" }
        require(maxOutputTokens == null || maxOutputTokens in 1..Int.MAX_VALUE.toLong()) {
            "maximum output tokens must fit Koog's request limit"
        }
        require(maxOutputTokens == null || contextLength == null || maxOutputTokens <= contextLength) {
            "maximum output tokens must not exceed context length"
        }
        require(samplesPerTask > 0) { "samples per task must be positive" }
        require(maxRepairTurns == null || maxRepairTurns >= 0) { "maximum repair turns must not be negative" }
        require(maxAgentIterations == null || maxAgentIterations > 0) { "maximum agent iterations must be positive" }
        require(maxToolCallsPerAttempt == null || maxToolCallsPerAttempt >= 0) {
            "maximum tool calls per attempt must not be negative"
        }
        require(maxWorkspaceToolCallsPerAttempt == null || maxWorkspaceToolCallsPerAttempt >= 0) {
            "maximum workspace tool calls per attempt must not be negative"
        }
        require((maxToolCallsPerAttempt == 0 && maxWorkspaceToolCallsPerAttempt == 0) || supportsTools) {
            "tool calls require model tool support"
        }
    }

    fun toSpecification(): BenchmarkRunSpecification = BenchmarkRunSpecification(
        model = LLModel(
            provider = LLMProvider.OpenRouter,
            id = modelId,
            capabilities = if (supportsTools) {
                listOf(LLMCapability.Completion, LLMCapability.Tools)
            } else {
                listOf(LLMCapability.Completion)
            },
            contextLength = contextLength,
            maxOutputTokens = maxOutputTokens,
        ),
        samplesPerTask = samplesPerTask,
        repairPolicy = RepairPolicy(maxRepairTurns),
        maxToolCallsPerAttempt = maxToolCallsPerAttempt,
        maxAgentIterations = maxAgentIterations,
        maxWorkspaceToolCallsPerAttempt = maxWorkspaceToolCallsPerAttempt,
    )
}
