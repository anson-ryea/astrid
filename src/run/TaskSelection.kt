package run

import domain.*

/** OR within each dimension, AND between dimensions; frozen status is always required. */
data class TaskSelection(
    val taskIds: Set<TaskId> = emptySet(),
    val paperIds: Set<PaperId> = emptySet(),
    val taskTypes: Set<TaskTypeId> = emptySet(),
    val conditions: Set<ConditionId> = emptySet(),
) {
    fun matches(task: BenchmarkTask) = task.manifest.let {
        (taskIds.isEmpty() || it.taskId in taskIds) && (paperIds.isEmpty() || it.paperId in paperIds) &&
                (taskTypes.isEmpty() || it.taskType in taskTypes) && (conditions.isEmpty() || it.condition in conditions)
    }
}
