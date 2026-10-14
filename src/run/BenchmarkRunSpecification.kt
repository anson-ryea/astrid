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
) {
    val workspaceToolsEnabled: Boolean
        get() = maxWorkspaceToolCallsPerAttempt != 0

    init {
        require(model.id.isNotBlank()) { "model ID must not be blank" }
        require(samplesPerTask > 0) { "samples per task must be positive" }
        require(maxToolCallsPerAttempt == null || maxToolCallsPerAttempt >= 0) {
            "maximum tool calls per attempt must not be negative"
        }
        require(maxWorkspaceToolCallsPerAttempt == null || maxWorkspaceToolCallsPerAttempt >= 0) {
            "maximum workspace tool calls per attempt must not be negative"
        }
        require(maxAgentIterations == null || maxAgentIterations > 0) {
            "maximum agent iterations must be positive"
        }
        require(model.maxOutputTokens == null ||
                model.maxOutputTokens in 1..Int.MAX_VALUE.toLong()) {
            "maximum output tokens must fit Koog's request limit"
        }
        require(
            (maxToolCallsPerAttempt == 0 && maxWorkspaceToolCallsPerAttempt == 0) ||
                    model.supports(LLMCapability.Tools)
        ) {
            "tool calls require a model configured with tool support"
        }
    }
}
