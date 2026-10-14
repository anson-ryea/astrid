package backend.lean.evaluation

import domain.*
import support.SimpleTestDataset
import org.junit.jupiter.api.Tag
import kotlin.test.*

@Tag("lean-integration")
class LeanSemanticWorkspaceTest {
    @Test fun bothSubmissionModesAgreeAndBoundaryWhitespaceDoesNotExposePrivateFiles() {
        val tasks = SimpleTestDataset.tasks().filter { it.manifest.protocol.value in setOf("lean_definition", "lean_declaration") }
        val contracts = SimpleTestDataset.contracts()
        val factory = SimpleTestDataset.workspaceFactory()
        val response = IsolatedLeanSemanticEvaluator(LeanSemanticEvaluator(
            { _, _ -> error("preflight must not execute Lean in the host environment") }, contracts), factory)
        tasks.forEach { task ->
            val reference = contracts.read(task).referenceSubmission
            val responseReport = response.evaluateDetailed(task, reference)
            assertEquals(EvaluationResult.Accepted, responseReport.result)
            factory.prepare(task).create().use { session ->
                session.validateEvaluation()
                val workspace = session.workspace
                assertFalse(workspace.visibleFiles().any { it.contains("private") || it.contains("reference") || it.contains("Specification") })
                assertFalse(workspace.record().inputs.any { it.content.contains("AstridSpec.agreement") || it.content.contains("AstridReference.target") })
                val template = workspace.read("Solution.lean")
                val marker = LeanSemanticSourceBuilder.marker(task.manifest.protocol.value)
                workspace.write("Solution.lean", template.replace(marker, "  \n$reference \n  ").replace("\n", "\r\n"))
                val (submission, report) = session.assessDetailed()
                assertEquals(reference.trim(), submission)
                assertEquals(responseReport.result, report.result)
                assertEquals(responseReport.evidence!!.obligations, report.evidence!!.obligations)
                val wrong = if (task.manifest.protocol.value == "lean_definition") "n"
                    else reference.replace(" (positive : 0 < n)", "")
                workspace.write("Solution.lean", template.replace(marker, wrong))
                assertEquals(response.evaluate(task, wrong), session.assess().second)
                workspace.write("Solution.lean", template.replace(marker, reference).replace("namespace Benchmark", "namespace Other"))
                val rejected = session.assessDetailed().second
                assertIs<EvaluationResult.Rejected>(rejected.result)
                assertEquals(contracts.read(task).digest, rejected.evidence!!.contractSha256)
                assertTrue(rejected.evidence.obligations.all { it.certificate == "not_run" })
                assertFalse(workspace.record().finalFiles.any { it.content.contains("AstridSpec") || it.content.contains("AstridReference") })
            }
        }
    }
}
