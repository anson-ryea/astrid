package domain

import kotlin.time.Duration

/** Constraints used when evaluating a task submission. */
data class EvaluationSpecification(
    val kind: EvaluationKind,
    val timeout: Duration,
    val allowedAxioms: Set<String>,
    val forbiddenMechanisms: Set<String>,
    val forbiddenIdentifiers: Set<String>,
    val contract: EvaluationContract? = null,
) {
    init {
        require(timeout.isFinite() && timeout > Duration.ZERO) {
            "evaluation timeout must be finite and positive: $timeout"
        }
        require(allowedAxioms.none(String::isBlank)) {
            "allowed axiom names must not be blank"
        }
        require(forbiddenMechanisms.all(mechanismPattern::matches)) {
            "forbidden mechanisms must use lower_snake_case"
        }
        require(forbiddenIdentifiers.none(String::isBlank)) {
            "forbidden identifiers must not be blank"
        }
    }

    companion object {
        private val mechanismPattern = Regex("[a-z][a-z0-9_]*")
    }
}
