package backend.lean.evaluation

sealed interface LeanProcessResult {
    data class Completed(
        val exitCode: Int,
        val output: String,
    ): LeanProcessResult

    data object TimedOut: LeanProcessResult

    data class InfrastructureFailure(val reason: String) : LeanProcessResult {
        init {
            require(reason.isNotBlank()) {
                "infrastructure failure reason must not be blank"
            }
        }
    }
}
