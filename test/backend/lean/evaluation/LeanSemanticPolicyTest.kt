package backend.lean.evaluation

import domain.*
import support.SimpleTestDataset
import org.junit.jupiter.api.Tag
import kotlin.test.*

/** Runs the native parser before elaboration, including boundary-escape attempts. */
@Tag("lean-integration")
class LeanSemanticPolicyTest {
    @Test fun parserRestrictsDeclarationsAndPrivateBodiesWithoutTextMatchingComments() {
        val original = SimpleTestDataset.task("declaration")
        val task = BenchmarkTask(original.manifest.copy(submission = original.manifest.submission.copy(allowLocalHelpers = true)), original.packageDirectory)
        val contract = SimpleTestDataset.contracts().read(task)
        val sources = LeanSemanticSourceBuilder()
        SimpleTestDataset.workspaceFactory().prepare(task).create().use { session ->
            val process = LeanLakeProcess(session.workspace.root, inheritLeanPath = false)
            val invalid = listOf("end Benchmark\naxiom malicious : False", "axiom helper : False", "unsafe def helper := 0",
                "@[simp] def helper := 0", "def _root_.helper := 0", "run_cmd pure ()", "def helper := by run_tac pure ()",
                "def helper := AstridSpec.agreement")
            for (submission in invalid) {
                val result = assertIs<LeanProcessResult.Completed>(process.run(sources.validation(task, submission, contract), task.manifest.evaluation.timeout))
                assertNotEquals(0, result.exitCode, submission)
            }
            val valid = contract.referenceSubmission + "\n-- sorry, axiom, unsafe and AstridSpec are harmless inside this comment\ndef helper : String := \"sorry axiom\"\n"
            val validResult = assertIs<LeanProcessResult.Completed>(process.run(sources.validation(task, valid, contract), task.manifest.evaluation.timeout))
            assertEquals(0, validResult.exitCode, validResult.output)
            val audited = LeanSemanticEvaluator(process, SimpleTestDataset.contracts())
                .evaluateDetailed(task, contract.referenceSubmission + "\nprivate theorem helper : True ∨ ¬ True := Classical.em True\n")
            assertIs<EvaluationResult.Rejected>(audited.result)
            assertTrue(audited.evidence!!.candidateAxioms.any { (name, axioms) -> name.contains("helper") && "Classical.choice" in axioms })
            val noHelpers = BenchmarkTask(task.manifest.copy(submission = task.manifest.submission.copy(allowLocalHelpers = false)), task.packageDirectory)
            assertNotEquals(0, assertIs<LeanProcessResult.Completed>(process.run(sources.validation(noHelpers, valid, contract), task.manifest.evaluation.timeout)).exitCode)
            val injected = contract.copy(obligations = listOf(SemanticObligation("AstridSpec.agreement", "by trivial\naxiom injected : False")))
            assertNotEquals(0, assertIs<LeanProcessResult.Completed>(process.run(sources.proofValidation(task, injected), task.manifest.evaluation.timeout)).exitCode)
            val forbiddenTerm = "sorryAx _ true"
            val definition = SimpleTestDataset.task("definition")
            val definitionContract = SimpleTestDataset.contracts().read(definition)
            // sorryAx is caught by the actual dependency audit if reached; direct sorry is forbidden syntactically.
            assertIs<EvaluationResult.Rejected>(LeanSemanticEvaluator(process, SimpleTestDataset.contracts()).evaluate(definition, forbiddenTerm))
            assertNotEquals(0, assertIs<LeanProcessResult.Completed>(process.run(sources.validation(definition, "by sorry", definitionContract), definition.manifest.evaluation.timeout)).exitCode)
        }
    }
}
