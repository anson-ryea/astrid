package dataset

import backend.lean.evaluation.LeanProofEvaluator
import backend.lean.protocol.LeanProtocols
import domain.EvaluationResult
import support.SimpleTestDataset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Package and prompt checks for the synthetic dataset, with no benchmark data or Lean subprocesses. */
class SimpleDatasetPackageTest {
    private val tasks = DatasetValidator(
        FileTaskRepository(SimpleTestDataset.datasetRoot, JsonTaskManifestReader()), TaskPackageVerifier(),
    ).validateAll()

    @Test fun allPackagesVerifyAndRenderPrompts() {
        assertEquals(5, tasks.size)
        val protocols = LeanProtocols.create(SimpleTestDataset.projectRoot.resolve("prompts/lean/v1"), SimpleTestDataset.promptRoot)
        tasks.forEach { protocols.findFor(it).renderPrompt(it) }
    }

    @Test fun bothProofConditionsRejectForbiddenMechanismsAndPrivateAnswersBeforeLeanRuns() {
        val evaluator = LeanProofEvaluator({ _, _ -> error("forbidden submissions must not launch Lean") })
        val proofs = tasks.filter { it.manifest.protocol.value == "lean_proof" }
        assertEquals(2, proofs.size)
        val bodies = listOf(
            "by sorry", "by admit", "by native_decide", "by bv_decide", "by\n  run_tac pure ()",
            "by exact Simple.referenceProof", "by\n  trivial\nimport private.reference",
            "by\n  trivial\naxiom forged : False",
        )
        proofs.forEach { task -> bodies.forEach { body ->
            assertIs<EvaluationResult.Rejected>(evaluator.evaluate(task, body), "${task.manifest.taskId}: $body")
        } }
    }
}
