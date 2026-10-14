package backend.lean.evaluation

import domain.BenchmarkTask
import domain.EvaluationReport
import domain.EvaluationResult
import evaluation.TaskEvaluator
import workspace.TaskWorkspaceFactory

/** Response-mode candidates use the same fresh public source closure as workspace-mode candidates. */
class IsolatedLeanSemanticEvaluator(
    private val preflight: LeanSemanticEvaluator,
    private val workspaces: TaskWorkspaceFactory,
) : TaskEvaluator {
    override val kind = preflight.kind
    override fun validateTask(task: BenchmarkTask) {
        preflight.validateMaterial(task)
        workspaces.prepare(task).create().use { it.validateEvaluation() }
    }
    override fun evaluate(task: BenchmarkTask, submission: String): EvaluationResult = evaluateDetailed(task, submission).result
    override fun reportUnassessed(task: BenchmarkTask, result: EvaluationResult) = preflight.reportUnassessed(task, result)
    override fun evaluateDetailed(task: BenchmarkTask, submission: String): EvaluationReport = try {
        validateTask(task)
        workspaces.prepare(task).create().use { session ->
            val template = session.workspace.read("Solution.lean")
            session.workspace.write("Solution.lean", template.replace(LeanSemanticSourceBuilder.marker(task.manifest.protocol.value), submission))
            session.assessDetailed().second
        }
    } catch (failure: Exception) {
        EvaluationReport(EvaluationResult.InfrastructureFailure("isolated semantic evaluation failed: ${failure.message}"), publicDiagnostic = null)
    }
}
