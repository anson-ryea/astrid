package domain

/** The outcome of evaluating a task submission. */
sealed interface EvaluationResult {
    data object Accepted: EvaluationResult

    data class Rejected(val reason: String): EvaluationResult {
        init {
            require(reason.isNotBlank()) {
                "rejection reason must not be blank"
            }
        }
    }

    /** Valid output whose semantic agreement has not been established automatically. */
    data class NeedsReview(val reason: String): EvaluationResult {
        init {
            require(reason.isNotBlank()) {
                "review reason must not be blank"
            }
        }
    }

    /** Evaluation could not complete reliably; this is **NOT** a rejected submission. */
    data class InfrastructureFailure(val reason: String) : EvaluationResult {
        init {
            require(reason.isNotBlank()) {
                "infrastructure failure reason must not be blank"
            }
        }
    }
}
