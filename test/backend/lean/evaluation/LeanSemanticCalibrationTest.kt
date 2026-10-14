package backend.lean.evaluation

import domain.EvaluationResult
import org.junit.jupiter.api.Tag
import support.SimpleTestDataset
import kotlin.test.*

/** Kernel calibration for synthetic fixtures, independent of benchmark paper contents. */
@Tag("lean-integration")
class LeanSemanticCalibrationTest {
    private val contracts = SimpleTestDataset.contracts()

    private fun calibrate(result: String, alternative: (String) -> String, incorrect: List<String>) {
        val task = SimpleTestDataset.task(result)
        val contract = contracts.read(task)
        SimpleTestDataset.workspaceFactory().prepare(task).create().use { session ->
            val evaluator = LeanSemanticEvaluator(LeanLakeProcess(session.workspace.root, inheritLeanPath = false), contracts)
            val reference = evaluator.evaluateDetailed(task, contract.referenceSubmission)
            assertEquals(EvaluationResult.Accepted, reference.result)
            assertTrue(reference.evidence!!.candidateAxioms.keys.containsAll(contract.outputs.map { it.name }))
            assertTrue(reference.evidence.obligations.all { it.certificate == "proved" })
            assertEquals(EvaluationResult.Accepted, evaluator.evaluate(task, alternative(contract.referenceSubmission)))
            incorrect.forEach { submission ->
                val report = evaluator.evaluateDetailed(task, submission)
                assertIs<EvaluationResult.Rejected>(report.result)
                assertEquals("proved", report.evidence!!.obligations.single().refutation)
                assertNull(report.publicDiagnostic)
            }
        }
    }

    @Test fun definitionAcceptsEquivalentArithmeticAndRefutesAnIncorrectTerm() {
        calibrate("definition", { "1 + n" }, listOf("n"))
    }

    @Test fun statementUsesTheFixedInterfaceAndRefutesAnArbitraryTrueProposition() {
        calibrate("statement", { "value + 1 ≤ limit" }, listOf("True"))
    }

    @Test fun declarationChecksPremisesAndRejectsAnAdditionalRule() {
        val reference = contracts.read(SimpleTestDataset.task("declaration")).referenceSubmission
        calibrate("declaration", { it.replace("positive :", "assumption :") }, listOf(
            reference.replace(" (positive : 0 < n)", ""),
            "$reference  | extra : Target n\n",
        ))
    }
}
