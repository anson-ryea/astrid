package backend.lean.evaluation

import ai.koog.agents.ext.tool.file.EditFileTool
import domain.EvaluationResult
import org.junit.jupiter.api.Tag
import support.SimpleTestDataset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking
import run.tools.WorkspaceTools

/** Kernel checks using only the simple fixtures' staged public import closure. */
@Tag("lean-integration")
class WorkspaceLeanProofTest {
    @Test
    fun referencesCompileInStagedWorkspacesWithoutExposingTheirAnswers() = runBlocking {
        val tasks = SimpleTestDataset.tasks().filter { it.manifest.protocol.value == "lean_proof" }
        val profiles = SimpleTestDataset.profiles()
        val factory = SimpleTestDataset.workspaceFactory()
        assertEquals(2, tasks.size)
        tasks.forEach { task ->
            factory.prepare(task).create().use { session ->
                val workspace = session.workspace
                assertFalse(workspace.visibleFiles().any { it.contains("private") || it.contains("reference") || it.contains("Unrelated") })
                val edit = EditFileTool(WorkspaceTools(workspace).fileSystem)
                edit.execute(EditFileTool.Args("/workspace/Solution.lean", "__BENCHMARK_PROOF__", "by exact Simple.referenceProof"))
                assertIs<EvaluationResult.Rejected>(session.assess().second, task.manifest.taskId.value)
                edit.execute(EditFileTool.Args("/workspace/Solution.lean", "by exact Simple.referenceProof", profiles.read(task).referenceSubmission.trim()))
                assertEquals(EvaluationResult.Accepted, session.assess().second, task.manifest.taskId.value)
            }
        }
    }
}
