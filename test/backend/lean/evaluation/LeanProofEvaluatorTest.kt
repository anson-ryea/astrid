package backend.lean.evaluation

import domain.EvaluationResult
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs

class LeanProofEvaluatorTest {
    @Test
    fun acceptsProofWithAllowedAxioms() = withLeanProofTask { task ->
        var source: String? = null
        val process = LeanProcess { candidate, _ ->
            source = candidate
            LeanProcessResult.Completed(
                0,
                "Benchmark.target depends on axioms: [propext]",
            )
        }

        val result = LeanProofEvaluator(process).evaluate(
            task,
            "by\n  trivial",
        )

        assertEquals(EvaluationResult.Accepted, result)
        assertContains(source.orEmpty(), "by\n  trivial")
    }

    @Test
    fun rejectsForbiddenSubmissionWithoutRunningLean() =
        withLeanProofTask { task ->
            val process = LeanProcess { _, _ ->
                error("Lean must not run for a forbidden submission")
            }

            val result = LeanProofEvaluator(process).evaluate(task, "by sorry")

            assertIs<EvaluationResult.Rejected>(result)
            assertContains(result.reason, "sorry")
        }

    @Test
    fun rejectsLeanCompilationFailure() = withLeanProofTask { task ->
        val process = LeanProcess { _, _ ->
            LeanProcessResult.Completed(1, "unknown identifier")
        }

        val result = LeanProofEvaluator(process).evaluate(task, "by\n  trivial")

        assertIs<EvaluationResult.Rejected>(result)
        assertContains(result.reason, "unknown identifier")
    }

    @Test
    fun treatsTimeoutAsInfrastructureFailure() = withLeanProofTask { task ->
        val process = LeanProcess { _, _ -> LeanProcessResult.TimedOut }

        val result = LeanProofEvaluator(process).evaluate(task, "by\n  trivial")

        assertIs<EvaluationResult.InfrastructureFailure>(result)
    }

    @Test
    fun rejectsUnexpectedAxiom() = withLeanProofTask { task ->
        val process = LeanProcess { _, _ ->
            LeanProcessResult.Completed(
                0,
                "Benchmark.target depends on axioms: [Classical.choice]",
            )
        }

        val result = LeanProofEvaluator(process).evaluate(task, "by\n  trivial")

        assertIs<EvaluationResult.Rejected>(result)
        assertContains(result.reason, "Classical.choice")
    }

    @Test
    fun treatsChangedTaskFileAsInfrastructureFailure() =
        withLeanProofTask { task ->
            Files.writeString(
                task.packageDirectory.resolve("Context.lean"),
                "changed",
            )
            val process = LeanProcess { _, _ ->
                error("Lean must not run for an invalid package")
            }

            val result = LeanProofEvaluator(process).evaluate(
                task,
                "by\n  trivial",
            )

            assertIs<EvaluationResult.InfrastructureFailure>(result)
        }
}
