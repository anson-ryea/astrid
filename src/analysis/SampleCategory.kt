package analysis

/** Legacy infrastructure outcomes deliberately retain their historical ambiguity. */
enum class SampleCategory(val value: String) {
    ACCEPTED("accepted"), REJECTED("rejected"), NEEDS_REVIEW("needs_review"),
    EVALUATOR_FAILURE("evaluator_failure"), BUDGET_EXHAUSTED("budget_exhausted"),
    PROVIDER_FAILURE("provider_failure"), EXECUTION_FAILURE("execution_failure"),
    LEGACY_INFRASTRUCTURE_FAILURE("legacy_infrastructure_failure"),
}
