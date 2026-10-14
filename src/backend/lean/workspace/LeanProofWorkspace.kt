package backend.lean.workspace

import backend.lean.protocol.LeanProofProtocol
import domain.BenchmarkTask
import domain.EvaluationResult
import domain.EvaluationReport
import evaluation.TaskEvaluator
import workspace.TaskWorkspace
import workspace.TaskWorkspaceSession

/** Protects the fixed Lean template and assesses only its editable submission slot. */
class LeanProofWorkspace internal constructor(
    override val workspace: TaskWorkspace,
    private val task: BenchmarkTask,
    template: String,
    private val evaluator: TaskEvaluator,
    override val submissionInstructions: String,
    private val marker: String = LeanProofProtocol.MARKER,
) : TaskWorkspaceSession {
    private val prefix = template.substringBefore(marker).replace("\r\n", "\n").trimEnd()
    private val suffix = template.substringAfter(marker).replace("\r\n", "\n").trimStart()

    override fun validateEvaluation() = evaluator.validateTask(task)

    override fun assess(): Pair<String?, EvaluationResult> = assessDetailed().let { it.first to it.second.result }

    override fun assessDetailed(): Pair<String?, EvaluationReport> {
        workspace.changedInput()?.let { path ->
            return null to evaluator.reportUnassessed(task, EvaluationResult.InfrastructureFailure("workspace input changed: $path"))
        }
        val candidate = workspace.read("Solution.lean").replace("\r\n", "\n")
        if (!candidate.startsWith(prefix) || !candidate.endsWith(suffix) || candidate.length < prefix.length + suffix.length) {
            return null to evaluator.reportUnassessed(task, EvaluationResult.Rejected("Solution.lean changed the fixed context or target; restore the original text around the submission slot"))
        }
        val submission = candidate.substring(prefix.length, candidate.length - suffix.length).trim()
        if (marker in submission) {
            return submission to evaluator.reportUnassessed(task, EvaluationResult.Rejected("replace the submission marker in Solution.lean"))
        }
        return submission to evaluator.evaluateDetailed(task, submission)
    }
}
