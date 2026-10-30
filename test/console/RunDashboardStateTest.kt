package console

import domain.EvaluationResult
import domain.TaskId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import run.RunEvent
import run.SamplePhase

class RunDashboardStateTest {
    private val taskId = TaskId("task_1.1_theorem_11")

    @Test
    fun tracksConcurrentSamplesIndependentlyWithoutChangingEarlierSnapshots() {
        val empty = RunDashboardState()
        val firstStarted = empty.reduce(RunEvent.SampleStarted(taskId, 1))
        val bothStarted = firstStarted.reduce(RunEvent.SampleStarted(taskId, 2))
        val changed = bothStarted.reduce(RunEvent.PhaseChanged(taskId, 1, SamplePhase.Evaluating))
        val finishedEvent = RunEvent.SampleFinished(taskId, 1, EvaluationResult.Accepted)
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
            state = state.reduce(RunEvent.SampleFinished(taskId, index + 1, outcome))
            assertEquals(index + 1, state.completedSamples)
            assertEquals(outcomes.size - index - 1, state.activeSamples.size)
        }

        assertEquals(1, state.acceptedSamples)
        assertEquals(1, state.rejectedSamples)
        assertEquals(1, state.needsReviewSamples)
        assertEquals(1, state.infrastructureFailureSamples)
    }
}
