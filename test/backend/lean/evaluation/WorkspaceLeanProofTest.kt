package backend.lean.evaluation

import ai.koog.agents.ext.tool.file.EditFileTool
import domain.EvaluationResult
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Tag
import run.tools.WorkspaceTools
import support.SimpleTestDataset
import kotlin.test.*

/** Kernel checks using only the simple fixtures' staged public import closure. */
@Tag("lean-integration")
class WorkspaceLeanProofTest {
    @Test
    fun checksProofCodeAcrossLineAndNestedBlockComments() {
        val task = SimpleTestDataset.task("proof")
        SimpleTestDataset.workspaceFactory().prepare(task).create().use { session ->
            val workspace = session.workspace
            val template = workspace.read("Solution.lean")
            fun assess(body: String): EvaluationResult {
                workspace.write("Solution.lean", template.replace("__BENCHMARK_PROOF__", body))
                return session.assess().second
            }

            val proof = """
                /- A proof of the lemma. -/
                by
                  -- Lemma 25: no axiom, sorry or __BENCHMARK_PROOF__ required.
                  /- outer /- Simple.referenceProof -/ still a comment -/
                  let quoted := "-- this is a string"
                  rfl
            """.trimIndent()
            assertEquals(EvaluationResult.Accepted, assess(proof))
            val rejected = assertIs<EvaluationResult.Rejected>(assess(proof.replace("  rfl", "  sorry")))
            assertContains(rejected.reason, "forbidden mechanism: sorry")
            val forbidden =
                assertIs<EvaluationResult.Rejected>(assess(proof.replace("  rfl", "  exact Simple.referenceProof")))
            assertContains(forbidden.reason, "forbidden identifier")
        }
    }

    @Test
    fun referencesCompileInStagedWorkspacesWithoutExposingTheirAnswers() = runBlocking {
        val tasks = SimpleTestDataset.tasks().filter { it.manifest.protocol.value == "lean_proof" }
        val profiles = SimpleTestDataset.profiles()
        val factory = SimpleTestDataset.workspaceFactory()
        assertEquals(2, tasks.size)
        tasks.forEach { task ->
            factory.prepare(task).create().use { session ->
                val workspace = session.workspace
                assertFalse(
                    workspace.visibleFiles()
                        .any { it.contains("private") || it.contains("reference") || it.contains("Unrelated") })
                val edit = EditFileTool(WorkspaceTools(workspace).fileSystem)
                edit.execute(
                    EditFileTool.Args(
                        "/workspace/Solution.lean",
                        "__BENCHMARK_PROOF__",
                        "by exact Simple.referenceProof"
                    )
                )
                assertIs<EvaluationResult.Rejected>(session.assess().second, task.manifest.taskId.value)
                edit.execute(
                    EditFileTool.Args(
                        "/workspace/Solution.lean",
                        "by exact Simple.referenceProof",
                        profiles.read(task).referenceSubmission.trim()
                    )
                )
                assertEquals(EvaluationResult.Accepted, session.assess().second, task.manifest.taskId.value)
            }
        }
    }
}
