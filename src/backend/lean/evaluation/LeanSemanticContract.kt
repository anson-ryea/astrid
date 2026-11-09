package backend.lean.evaluation

data class LeanSemanticContract(
    val digest: String,
    val fileDigests: Map<String, String>,
    val outputs: List<SemanticOutput>,
    val referenceSubmission: String,
    val reference: String,
    val specification: String,
    val obligations: List<SemanticObligation>,
    val compatibility: List<SemanticObligation>,
)
