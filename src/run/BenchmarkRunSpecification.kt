package run

import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLModel

/** Model and limits shared by every sample in one benchmark run. */
data class BenchmarkRunSpecification(
    val model: LLModel,
    val samplesPerTask: Int,
    val repairPolicy: RepairPolicy,
    val maxToolCallsPerAttempt: Int?,
    val maxAgentIterations: Int?,
    val maxWorkspaceToolCallsPerAttempt: Int? = 0,
    val maxConcurrentTasks: Int = 4,
    val generation: GenerationSettings = GenerationSettings(),
    val limits: SampleLimits = SampleLimits(),
    val pricing: TokenPricing? = null,
    val selection: TaskSelection = TaskSelection(),
) {
    val workspaceToolsEnabled: Boolean
        get() = maxWorkspaceToolCallsPerAttempt != 0

    init {
        require(model.id.isNotBlank()) { "model ID must not be blank" }
        requireSampleCounts(samplesPerTask, maxConcurrentTasks)
        requireToolCallLimits(maxToolCallsPerAttempt, maxWorkspaceToolCallsPerAttempt)
        requireAgentIterationLimit(maxAgentIterations)
        requireOutputTokenLimit(model.maxOutputTokens)
        require(
            (maxToolCallsPerAttempt == 0 && maxWorkspaceToolCallsPerAttempt == 0) ||
                    model.supports(LLMCapability.Tools)
        ) {
            "tool calls require a model configured with tool support"
        }
    }
}
