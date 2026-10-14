package domain

/** Research evidence, never used as model feedback. Private source text is not stored here. */
data class EvaluationEvidence(
    val contractSha256: String,
    val fileDigests: Map<String, String>,
    val elaboration: String,
    val candidateAxioms: Map<String, Set<String>> = emptyMap(),
    val obligations: List<CheckEvidence> = emptyList(),
    val compatibility: List<CheckEvidence> = emptyList(),
)

data class CheckEvidence(val name: String, val certificate: String, val refutation: String = "not_configured")

/** Only publicDiagnostic may enter a repair prompt; a null value makes the verdict terminal. */
data class EvaluationReport(
    val result: EvaluationResult,
    val evidence: EvaluationEvidence? = null,
    val publicDiagnostic: String? = (result as? EvaluationResult.Rejected)?.reason,
)
