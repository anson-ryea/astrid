package evaluation

import domain.BenchmarkTask
import domain.EvaluationKind

/** Selects an evaluator by the kind declared in a task manifest. */
class TaskEvaluatorRegistry(evaluators: Collection<TaskEvaluator>) {
    private val evaluatorsByKind: Map<EvaluationKind, TaskEvaluator>
    val kinds: Set<EvaluationKind>
        get() = evaluatorsByKind.keys

    init {
        val duplicateKinds = evaluators
            .groupingBy(TaskEvaluator::kind)
            .eachCount()
            .filterValues { count -> count > 1 }
            .keys

        require(duplicateKinds.isEmpty()) {
            "duplicate task evaluators: " +
                    duplicateKinds.sortedBy(EvaluationKind::value).joinToString()
        }

        evaluatorsByKind = evaluators.associateBy(TaskEvaluator::kind)
    }

    fun findFor(task: BenchmarkTask): TaskEvaluator {
        val kind = task.manifest.evaluation.kind
        return evaluatorsByKind[kind]
            ?: throw IllegalArgumentException("unsupported evaluation kind: $kind")
    }
}
