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
