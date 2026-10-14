package backend.lean.evaluation

sealed interface LeanAxiomCheck {
    data class Allowed(val axioms: Set<String>) : LeanAxiomCheck

    data class Forbidden(
        val axioms: Set<String>,
        val unexpected: Set<String>,
    ) : LeanAxiomCheck

    data object Placeholder : LeanAxiomCheck

    /** The expected axiom report was absent or could not be parsed. */
    data object Unreadable: LeanAxiomCheck
}
