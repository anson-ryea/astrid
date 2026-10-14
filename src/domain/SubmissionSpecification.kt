package domain

/** Describes the expected submission form and permitted declaration scope. */
data class SubmissionSpecification(
    val kind: SubmissionKind,
    val allowLocalHelpers: Boolean,
    val allowTopLevelDeclarations: Boolean,
)
