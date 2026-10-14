package domain

/** The validated, versioned description of one benchmark task. */
data class TaskManifest(
    val schemaVersion: Int,
    val taskId: TaskId,
    val paperId: PaperId,
    val sourceResultId: SourceResultId,
    val taskType: TaskTypeId,
    val protocol: ProtocolId,
    val status: TaskStatus,
    val condition: ConditionId,
    val contextId: ContextId,
    val artefacts: List<TaskArtefact>,
    val submission: SubmissionSpecification,
    val evaluation: EvaluationSpecification,
) {
    init {
        require(schemaVersion == SCHEMA_VERSION) {
            "task manifest schema version must be $SCHEMA_VERSION: $schemaVersion"
        }
        require(artefacts.isNotEmpty()) {
            "task manifest must contain at least one artefact"
        }

        val duplicatePaths = artefacts
            .groupingBy(TaskArtefact::path)
            .eachCount()
            .filterValues { count -> count > 1 }
            .keys

        require(duplicatePaths.isEmpty()) {
            "task artefact paths must be unique: " +
                    duplicatePaths
                        .sortedBy(ArtefactPath::toString)
                        .joinToString()
        }
    }

    companion object {
        const val SCHEMA_VERSION = 1
    }
}
