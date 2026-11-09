package domain

/** Only publicDiagnostic may enter a repair prompt; a null value makes the verdict terminal. */
data class EvaluationReport(
    val result: EvaluationResult,
    val evidence: EvaluationEvidence? = null,
    val publicDiagnostic: String? = (result as? EvaluationResult.Rejected)?.reason,
)
