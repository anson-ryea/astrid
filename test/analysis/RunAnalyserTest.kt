package analysis

import domain.EvaluationResult
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import run.SampleBudget
import run.SampleOutcome
import kotlin.test.*

class RunAnalyserTest {
    @TempDir lateinit var root: Path
    private val accepted = SampleOutcome.Evaluated(EvaluationResult.Accepted)

    @Test
    fun computesKnownPassAtKEstimatesWithoutFactorials() {
        assertEquals(5.0 / 6, RunAnalyser.passAtK(4, 2, 2), 1e-12)
        assertEquals(2.0 / 3, RunAnalyser.passAtK(3, 1, 2), 1e-12)
        assertEquals(0.0, RunAnalyser.passAtK(4, 0, 2))
        assertEquals(1.0, RunAnalyser.passAtK(4, 3, 2))
        assertEquals(0.5, RunAnalyser.passAtK(1000, 500, 1), 1e-12)
        assertTrue(RunAnalyser.passAtK(1000, 1, 999).isFinite())
        assertFailsWith<IllegalArgumentException> { RunAnalyser.passAtK(4, 1, 5) }
    }

    @Test
    fun separatesFirstAttemptsRepairsFailuresAndPaperWeighting() = runBlocking {
        val records = listOf(
            sample(taskA, 1, accepted), sample(taskA, 2, accepted), sample(taskA, 3, accepted, repaired = true),
            sample(taskA, 4, SampleOutcome.BudgetExhausted(SampleBudget.AGENT_ITERATIONS, "limit")),
            sample(taskB, 1, SampleOutcome.RepairLimitReached(EvaluationResult.Rejected("bad"))),
            sample(taskB, 2, SampleOutcome.Evaluated(EvaluationResult.NeedsReview("unresolved"))),
            sample(taskB, 3, SampleOutcome.ProviderFailure("provider")),
            sample(taskB, 4, SampleOutcome.Evaluated(EvaluationResult.InfrastructureFailure("Lean"))),
        ) + (1..4).map { sample(taskC, it, accepted) }
        val path = savedRun(root.resolve("run"), records, 4, listOf(taskA, taskB, taskC))
        val analysis = RunAnalyser().analyse(SavedRunReader().read(path), listOf(2, 1, 2, 5))
        val total = analysis.summaries.first()
        assertEquals(12, total.savedSamples)
        assertEquals(0, total.missingSamples)
        assertEquals(7, total.finalAccepted)
        assertEquals(6, total.firstAttemptAccepted)
        assertEquals(9, total.firstAttemptAssessed)
        assertEquals(1, total.repairedAccepted)
        assertEquals(1, total.recordedRepairTurns)
        assertEquals(7.0 / 12, total.finalRate!!, 1e-12)
        assertEquals(7.0 / 12, total.taskMacroFinalRate!!, 1e-12)
        assertEquals(0.6875, total.paperMacroFinalRate!!, 1e-12)
        assertEquals(1, total.counts.getValue(SampleCategory.NEEDS_REVIEW))
        assertEquals(1, total.counts.getValue(SampleCategory.EVALUATOR_FAILURE))
        assertEquals(1, total.budgets.getValue("agent_iterations"))
        assertEquals(listOf(1, 2, 5), total.passAtK.map { it.k })
        val pass2 = total.passAtK.single { it.k == 2 }
        assertEquals(3, pass2.eligibleTasks)
        assertEquals(2.0 / 3, pass2.final!!, 1e-12)
        assertEquals(11.0 / 18, pass2.firstAttempt!!, 1e-12)
        assertNull(total.passAtK.single { it.k == 5 }.final)
        assertEquals(0, total.passAtK.single { it.k == 5 }.eligibleTasks)
        assertEquals(setOf("overall", "paper", "task_type", "condition", "task"), analysis.summaries.map { it.dimension }.toSet())
        assertEquals(3, analysis.summaries.count { it.dimension == "task" })
        assertEquals(3, analysis.summaries.single { it.dimension == "paper" && it.label == "paper_1" }.finalAccepted)
        assertEquals(4, analysis.summaries.single { it.dimension == "task_type" && it.label == "1" }.savedSamples)
        assertFalse(AnalysisRenderer.json(listOf(analysis)).contains("private prompt"))
    }

    @Test
    fun incompleteAndEmptyRunsReportCoverageWithoutPretendingMissingSamplesFailed() = runBlocking {
        val path = savedRun(root.resolve("partial"), listOf(sample(taskA, 1, accepted),
            sample(taskA, 2, SampleOutcome.ExecutionFailure("failed"))), 3, listOf(taskA, taskB), complete = false)
        val total = RunAnalyser().analyse(SavedRunReader().read(path)).summaries.first()
        assertEquals(6, total.expectedSamples)
        assertEquals(4, total.missingSamples)
        assertEquals(0.5, total.finalRate)
        assertTrue(total.passAtK.all { it.eligibleTasks == 0 && it.final == null })
        val empty = savedRun(root.resolve("empty"), emptyList(), complete = false)
        val report = RunAnalyser().analyse(SavedRunReader().read(empty))
        assertNull(report.summaries.first().finalRate)
        assertContains(AnalysisRenderer.text(listOf(report)), "n/a")
        assertFalse(AnalysisRenderer.json(listOf(report)).contains("NaN"))
    }
}
