package backend

import domain.BenchmarkTask
import domain.EvaluationKind
import evaluation.TaskEvaluatorRegistry
import protocol.TaskProtocolRegistry
import workspace.TaskWorkspaceFactory

/** Proof assistant services selected by the command and consumed through shared interfaces. */
interface FormalisationBackend {
    val id: String
    val protocols: TaskProtocolRegistry
    val evaluators: TaskEvaluatorRegistry
    val workspaceFactory: TaskWorkspaceFactory

    val evaluationKinds: Set<EvaluationKind>
        get() = evaluators.kinds

    fun preflight(task: BenchmarkTask) {
        protocols.findFor(task).renderPrompt(task)
        evaluators.findFor(task).validateTask(task)
    }
}
