package console

import domain.EvaluationResult
import domain.TaskId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import run.RunEvent
import run.SamplePhase
import run.SampleBudget
import run.SampleOutcome

class RunDashboardStateTest {
    private val taskId = TaskId("task_1.1_theorem_11")

    @Test
    fun tracksConcurrentSamplesIndependentlyWithoutChangingEarlierSnapshots() {
        val empty = RunDashboardState()
        val firstStarted = empty.reduce(RunEvent.SampleStarted(taskId, 1))
        val bothStarted = firstStarted.reduce(RunEvent.SampleStarted(taskId, 2))
        val changed = bothStarted.reduce(RunEvent.PhaseChanged(taskId, 1, SamplePhase.Evaluating))
        val finishedEvent = RunEvent.SampleFinished(taskId, 1, SampleOutcome.Evaluated(EvaluationResult.Accepted))
        val finished = changed.reduce(finishedEvent)

        assertEquals(emptyMap(), empty.activeSamples)
        assertNull(empty.latestEvent)
        assertEquals(mapOf(SampleKey(taskId, 1) to SamplePhase.Preparing), firstStarted.activeSamples)
        assertEquals(mapOf(
            SampleKey(taskId, 1) to SamplePhase.Preparing,
            SampleKey(taskId, 2) to SamplePhase.Preparing,
        ), bothStarted.activeSamples)
        assertEquals(SamplePhase.Evaluating, changed.activeSamples[SampleKey(taskId, 1)])
        assertEquals(mapOf(SampleKey(taskId, 2) to SamplePhase.Preparing), finished.activeSamples)
        assertEquals(1, finished.acceptedSamples)
        assertEquals(0, changed.completedSamples)
        assertEquals(finishedEvent, finished.latestEvent)
    }

    @Test
    fun countsEachOutcomeSeparatelyAndRemovesOnlyTheFinishedSample() {
        val outcomes = listOf(
            EvaluationResult.Accepted,
            EvaluationResult.Rejected("invalid submission"),
            EvaluationResult.NeedsReview("manual review"),
            EvaluationResult.InfrastructureFailure("provider unavailable"),
        )
        var state = RunDashboardState()
        outcomes.indices.forEach { state = state.reduce(RunEvent.SampleStarted(taskId, it + 1)) }
        outcomes.forEachIndexed { index, outcome ->
            state = state.reduce(RunEvent.SampleFinished(taskId, index + 1, SampleOutcome.Evaluated(outcome)))
            assertEquals(index + 1, state.completedSamples)
            assertEquals(outcomes.size - index - 1, state.activeSamples.size)
        }

        assertEquals(1, state.acceptedSamples)
        assertEquals(1, state.rejectedSamples)
        assertEquals(1, state.needsReviewSamples)
        assertEquals(1, state.infrastructureFailureSamples)
    }

    @Test
    fun countsBudgetAndExecutionTerminationsWithoutInventingEvaluationVerdicts() {
        val budgets = SampleBudget.entries.map { SampleOutcome.BudgetExhausted(it, "stopped") }
        var state = RunDashboardState()
        val outcomes = budgets + SampleOutcome.ProviderFailure("stopped") + SampleOutcome.ExecutionFailure("stopped")
        outcomes.forEachIndexed { index, outcome ->
            val event = RunEvent.SampleFinished(taskId, index + 1, outcome)
            state = state.reduce(event)
            assertEquals(index + 1, state.completedSamples)
            assertEquals(index + 1, state.completedSamplesByTask[taskId])
            val message = requireNotNull(event.logEntry()).message
            val label = when (outcome) {
                is SampleOutcome.ProviderFailure -> "provider error"
                is SampleOutcome.ExecutionFailure -> "execution error"
                is SampleOutcome.BudgetExhausted -> "budget exhausted (${outcome.limit.manifestValue})"
                else -> error("unexpected test outcome")
            }
            kotlin.test.assertContains(message, label)
        }
        assertEquals(budgets.size, state.budgetExhaustedSamples)
        assertEquals(1, state.providerFailureSamples)
        assertEquals(1, state.executionFailureSamples)
        assertEquals(0, state.infrastructureFailureSamples)
        assertEquals(0, state.rejectedSamples)
        assertEquals(outcomes.size, state.recentDiagnostics.size)

        state = state.reduce(RunEvent.SampleFinished(taskId, 7,
            SampleOutcome.RepairLimitReached(EvaluationResult.Rejected("bad proof"))))
        assertEquals(1, state.rejectedSamples)
        assertEquals(budgets.size, state.budgetExhaustedSamples)
    }

    @Test
    fun retainsOnlyRecentDiagnosticsAndCountsCompletedSamplesPerTask() {
        var state = RunDashboardState()
        for (sample in 1..25) {
            state = state.reduce(RunEvent.SampleFinished(taskId, sample, SampleOutcome.Evaluated(EvaluationResult.Rejected("retry"))))
        }
        val previous = state
        state = state.reduce(RunEvent.SampleFinished(taskId, 26, SampleOutcome.Evaluated(EvaluationResult.Accepted)))

        assertEquals(26, state.completedSamplesByTask[taskId])
        assertEquals(25, previous.completedSamplesByTask[taskId])
        assertEquals((6..25).toList(), state.recentDiagnostics.map { it.sampleIndex })
        assertEquals(previous.recentDiagnostics, state.recentDiagnostics)
    }
}
