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
    val maxConcurrentTasks: Int = 4,
    val generation: GenerationSettings = GenerationSettings(),
    val limits: SampleLimits = SampleLimits(),
    val pricing: TokenPricing? = null,
) {
    init {
        require(modelId.isNotBlank()) { "model ID must not be blank" }
        require(contextLength == null || contextLength > 0) { "context length must be positive" }
        requireOutputTokenLimit(maxOutputTokens)
        require(maxOutputTokens == null || contextLength == null || maxOutputTokens <= contextLength) {
            "maximum output tokens must not exceed context length"
        }
        requireSampleCounts(samplesPerTask, maxConcurrentTasks)
        require(maxRepairTurns == null || maxRepairTurns >= 0) { "maximum repair turns must not be negative" }
        requireAgentIterationLimit(maxAgentIterations)
        requireToolCallLimits(maxToolCallsPerAttempt, maxWorkspaceToolCallsPerAttempt)
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
        maxConcurrentTasks = maxConcurrentTasks,
        generation = generation, limits = limits, pricing = pricing,
    )
}
