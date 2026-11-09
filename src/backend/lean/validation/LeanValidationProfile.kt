package backend.lean.validation

/** Private curation metadata; its filenames and declarations are data, not evaluator rules. */
data class LeanValidationProfile(
    val excludedDeclarations: List<String>,
    val referenceSubmission: String,
    val standaloneReference: String?,
    val sameArtefactsAs: List<LeanArtefactComparison>,
)
