package workspace

import domain.BenchmarkTask
import domain.EvaluationResult
import domain.EvaluationReport

/** Validates and snapshots backend inputs before any model calls. */
fun interface TaskWorkspaceFactory {
    fun prepare(task: BenchmarkTask): TaskWorkspacePlan
}

/** Creates a fresh sample environment from previously validated inputs. */
fun interface TaskWorkspacePlan {
    fun create(): TaskWorkspaceSession
}

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
