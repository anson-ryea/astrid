package run

import domain.BenchmarkTask
import domain.EvaluationKind
import domain.TaskStatus

class TaskSelector(
    private val evaluationKinds: Set<EvaluationKind>,
    private val selection: TaskSelection = TaskSelection(),
) {
    init {
        require(evaluationKinds.isNotEmpty()) {
            "at least one evaluation kind is required"
        }
    }

    fun select(tasks: Collection<BenchmarkTask>): List<BenchmarkTask> {
        val eligible =
            tasks.filter { it.manifest.status == TaskStatus.FROZEN && it.manifest.evaluation.kind in evaluationKinds }

        fun <T> requireKnown(requested: Set<T>, available: Set<T>, label: String) {
            require(requested.all { it in available }) { "no frozen supported tasks for $label: ${(requested - available).joinToString()}" }
        }
        requireKnown(selection.taskIds, eligible.map { it.manifest.taskId }.toSet(), "task IDs")
        requireKnown(selection.paperIds, eligible.map { it.manifest.paperId }.toSet(), "paper IDs")
        requireKnown(selection.taskTypes, eligible.map { it.manifest.taskType }.toSet(), "task types")
        requireKnown(selection.conditions, eligible.map { it.manifest.condition }.toSet(), "conditions")
        return eligible.filter(selection::matches).sortedBy { it.manifest.taskId.value }
    }
}
