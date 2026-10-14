package evaluation

import domain.BenchmarkTask
import domain.EvaluationKind
import domain.EvaluationResult
import domain.EvaluationReport

/** Evaluates submissions for one kind of task evaluation. */
interface TaskEvaluator {
    val kind: EvaluationKind

    /** Validates the supported task and policy before a model is contacted. */
    fun validateTask(task: BenchmarkTask) {
        require(task.manifest.evaluation.kind == kind) { "task does not use evaluation kind $kind" }
    }

    /** Returns a verdict for an extracted submission. */
    fun evaluate(task: BenchmarkTask, submission: String): EvaluationResult

    fun evaluateDetailed(task: BenchmarkTask, submission: String): EvaluationReport =
        EvaluationReport(evaluate(task, submission))

    /** Record preparation evidence when extraction or a protected slot prevents assessment. */
    fun reportUnassessed(task: BenchmarkTask, result: EvaluationResult): EvaluationReport = EvaluationReport(result)
}
