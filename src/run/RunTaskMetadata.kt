package run

import domain.TaskManifest

/** Analysis labels captured at run start, independent of later dataset edits. */
data class RunTaskMetadata(
    val paperId: String,
    val taskType: Int,
    val condition: String,
    val sourceResultId: String,
) {
    companion object {
        fun from(manifest: TaskManifest) = RunTaskMetadata(
            manifest.paperId.value, manifest.taskType.value, manifest.condition.value, manifest.sourceResultId.value,
        )
    }
}
