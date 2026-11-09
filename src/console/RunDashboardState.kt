package console

import domain.EvaluationResult
import domain.TaskId
import run.RunEvent
import run.RunPhase
import run.SampleOutcome
import run.SamplePhase

/** A snapshot for presentation; only the event consumer creates subsequent snapshots. */
data class RunDashboardState(
    val activeSamples: Map<SampleKey, SamplePhase> = emptyMap(),
    val acceptedSamples: Int = 0,
    val rejectedSamples: Int = 0,
    val needsReviewSamples: Int = 0,
    val infrastructureFailureSamples: Int = 0,
    val budgetExhaustedSamples: Int = 0,
    val providerFailureSamples: Int = 0,
    val executionFailureSamples: Int = 0,
    val latestEvent: RunEvent? = null,
    val completedSamplesByTask: Map<TaskId, Int> = emptyMap(),
    val recentDiagnostics: List<RunEvent.SampleFinished> = emptyList(),
    val runPhase: RunPhase = RunPhase.Preparing,
) {
    val completedSamples: Int
        get() = acceptedSamples + rejectedSamples + needsReviewSamples + infrastructureFailureSamples +
                budgetExhaustedSamples + providerFailureSamples + executionFailureSamples

    internal fun reduce(event: RunEvent): RunDashboardState = when (event) {
        is RunEvent.RunPhaseChanged -> copy(runPhase = event.phase, latestEvent = event)
        is RunEvent.SampleStarted -> copy(
            activeSamples = activeSamples + (SampleKey(event.taskId, event.sampleIndex) to SamplePhase.Preparing),
            latestEvent = event,
        )

        is RunEvent.PhaseChanged -> copy(
            activeSamples = activeSamples + (SampleKey(event.taskId, event.sampleIndex) to event.phase),
            latestEvent = event,
        )

        is RunEvent.SampleFinished -> {
            val finished = copy(
                activeSamples = activeSamples - SampleKey(event.taskId, event.sampleIndex),
                latestEvent = event,
                completedSamplesByTask = completedSamplesByTask +
                        (event.taskId to (completedSamplesByTask.getOrDefault(event.taskId, 0) + 1)),
                recentDiagnostics = if (event.outcome == SampleOutcome.Evaluated(EvaluationResult.Accepted)) recentDiagnostics
                else (recentDiagnostics + event).takeLast(20),
            )
            when (val outcome = event.outcome) {
                is SampleOutcome.BudgetExhausted -> finished.copy(budgetExhaustedSamples = budgetExhaustedSamples + 1)
                is SampleOutcome.ProviderFailure -> finished.copy(providerFailureSamples = providerFailureSamples + 1)
                is SampleOutcome.ExecutionFailure -> finished.copy(executionFailureSamples = executionFailureSamples + 1)
                is SampleOutcome.RepairLimitReached -> finished.copy(rejectedSamples = rejectedSamples + 1)
                is SampleOutcome.Evaluated -> when (outcome.result) {
                    EvaluationResult.Accepted -> finished.copy(acceptedSamples = acceptedSamples + 1)
                    is EvaluationResult.Rejected -> finished.copy(rejectedSamples = rejectedSamples + 1)
                    is EvaluationResult.NeedsReview -> finished.copy(needsReviewSamples = needsReviewSamples + 1)
                    is EvaluationResult.InfrastructureFailure -> finished.copy(
                        infrastructureFailureSamples = infrastructureFailureSamples + 1,
                    )
                }
            }
        }
    }
}
