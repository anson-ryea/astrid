package workspace

import domain.EvaluationReport
import domain.EvaluationResult

/** A backend's submission contract bound to one sample workspace. */
interface TaskWorkspaceSession : AutoCloseable {
    val workspace: TaskWorkspace
    val submissionInstructions: String

    fun validateEvaluation() {}

    fun assess(): Pair<String?, EvaluationResult>

    fun assessDetailed(): Pair<String?, EvaluationReport> =
        assess().let { (submission, result) -> submission to EvaluationReport(result) }

    override fun close() = workspace.close()
}
