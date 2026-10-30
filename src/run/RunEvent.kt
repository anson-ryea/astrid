package run

import domain.EvaluationResult
import domain.TaskId

/** Execution updates, independent of terminal rendering and persisted agent trajectories. */
sealed interface RunEvent {
    data class SampleStarted(
        val taskId: TaskId,
        val sampleIndex: Int,
    ) : RunEvent

    data class PhaseChanged(
        val taskId: TaskId,
        val sampleIndex: Int,
        val phase: SamplePhase,
    ) : RunEvent

    /** Emitted only after the sample record has been durably saved. */
    data class SampleFinished(
        val taskId: TaskId,
        val sampleIndex: Int,
        val result: EvaluationResult,
    ) : RunEvent
}
