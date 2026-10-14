package run

import domain.BenchmarkTask
import domain.EvaluationKind
import domain.TaskStatus

class TaskSelector(
    private val evaluationKinds: Set<EvaluationKind>,
) {
    init {
        require(evaluationKinds.isNotEmpty()) {
            "at least one evaluation kind is required"
        }
    }

    fun select(tasks: Collection<BenchmarkTask>): List<BenchmarkTask> =
        tasks
            .filter { task ->
                task.manifest.status == TaskStatus.FROZEN &&
                        task.manifest.evaluation.kind in evaluationKinds
            }
            .sortedBy { it.manifest.taskId.value }
}
