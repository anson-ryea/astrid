package domain

/** The review and publication state recorded in a task manifest. */
enum class TaskStatus(
    val manifestValue: String,
) {
    CANDIDATE_PENDING_INDEPENDENT_REVIEW(
        "candidate_pending_independent_review",
    ),
    FROZEN("frozen");

    override fun toString(): String = manifestValue

    companion object {
        /** Parses the exact status spelling used by task manifests. */
        fun fromManifestValue(value: String): TaskStatus {
            return entries.singleOrNull { status ->
                status.manifestValue == value
            } ?: throw IllegalArgumentException(
                "unknown task status: $value",
            )
        }
    }
}
