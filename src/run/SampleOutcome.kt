package run

import domain.EvaluationResult

/** One sample outcome. A saved submission can be evaluated after an interruption. */
sealed interface SampleOutcome {
    data class Evaluated(val result: EvaluationResult) : SampleOutcome

    /** Includes a rejected zero-repair baseline. Private semantic rejections remain Evaluated. */
    data class RepairLimitReached(val result: EvaluationResult.Rejected) : SampleOutcome

    sealed interface Interruption : SampleOutcome {
        val reason: String
    }

    data class BudgetExhausted(val limit: SampleBudget, override val reason: String) : Interruption {
        init {
            require(reason.isNotBlank()) { "budget exhaustion reason must not be blank" }
        }
    }

    data class ProviderFailure(override val reason: String) : Interruption {
        init {
            require(reason.isNotBlank()) { "provider failure reason must not be blank" }
        }
    }

    data class ExecutionFailure(override val reason: String) : Interruption {
        init {
            require(reason.isNotBlank()) { "execution failure reason must not be blank" }
        }
    }
}
