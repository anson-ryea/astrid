package run

import domain.EvaluationResult

/** One complete sample outcome; interruptions cannot carry an evaluation verdict. */
sealed interface SampleOutcome {
    data class Evaluated(val result: EvaluationResult) : SampleOutcome

    /** Includes a rejected zero-repair baseline. Private semantic rejections remain Evaluated. */
    data class RepairLimitReached(val result: EvaluationResult.Rejected) : SampleOutcome

    data class BudgetExhausted(val limit: SampleBudget, val reason: String) : SampleOutcome {
        init { require(reason.isNotBlank()) { "budget exhaustion reason must not be blank" } }
    }

    data class ProviderFailure(val reason: String) : SampleOutcome {
        init { require(reason.isNotBlank()) { "provider failure reason must not be blank" } }
    }

    data class ExecutionFailure(val reason: String) : SampleOutcome {
        init { require(reason.isNotBlank()) { "execution failure reason must not be blank" } }
    }
}

/** Limits that can interrupt an attempt before assessment. Repair limits retain a rejection. */
enum class SampleBudget(val manifestValue: String) {
    OUTPUT_TOKENS("output_tokens"),
    TOTAL_TOKENS("total_tokens"),
    SAMPLE_TIME("sample_time"),
    AGENT_ITERATIONS("agent_iterations"),
    TOOL_CALLS("tool_calls"),
    WORKSPACE_TOOL_CALLS("workspace_tool_calls"),
}
