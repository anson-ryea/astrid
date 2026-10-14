package run

import domain.EvaluationResult
import domain.EvaluationEvidence
import domain.TaskId
import workspace.WorkspaceFile
import workspace.WorkspaceRecord

/** One independent sample, including its initial attempt and any repairs. */
data class TaskRunRecord(
    val taskId: TaskId,
    val sampleIndex: Int,
    val systemPrompt: String,
    val taskPrompt: String,
    val turns: List<Turn>,
    val finalResult: EvaluationResult,
    /** Captured in memory with the sample, persisted in its own trajectory.json. */
    val trajectory: List<AgentTrajectoryEvent> = emptyList(),
    val workspace: WorkspaceRecord? = null,
) {
    init {
        require(sampleIndex > 0) { "sample index must be positive" }
    }

    data class Turn(
        val repairTurn: Int,
        val userMessage: String,
        val response: String,
        val submission: String?,
        val result: EvaluationResult,
        val workspaceFiles: List<WorkspaceFile> = emptyList(),
        val evaluationEvidence: EvaluationEvidence? = null,
    )
}
