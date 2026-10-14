package run

import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.ResponseMetaInfo
import domain.EvaluationResult
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.io.TempDir
import workspace.TaskWorkspace
import workspace.TaskWorkspaceFactory
import workspace.TaskWorkspacePlan
import workspace.TaskWorkspaceSession
import workspace.WorkspaceSpecification

class BackendIndependentWorkspaceTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun runsAFileSubmissionUsingOnlySharedWorkspaceInterfaces() = runBlocking {
        val task = createRunTask(temporaryDirectory)
        val roots = mutableListOf<Path>()
        val factory = TaskWorkspaceFactory {
            TaskWorkspacePlan {
                val root = Files.createTempDirectory(temporaryDirectory, "sample-")
                roots.add(root)
                object : TaskWorkspaceSession {
                    override val workspace = TaskWorkspace(root, emptyList(), "context", "closure",
                        WorkspaceSpecification(mapOf("Answer.txt" to "initial"), Regex("scratch/.+\\.txt")))
                    override val submissionInstructions = "Write your answer to /workspace/Answer.txt."
                    override fun assess(): Pair<String?, EvaluationResult> {
                        val submission = workspace.read("Answer.txt")
                        return submission to if (submission == "file answer") EvaluationResult.Accepted
                        else EvaluationResult.Rejected("incorrect file answer")
                    }
                }
            }
        }
        val executor = ScriptedPromptExecutor(listOf("write", "finish")) { reply ->
            if (reply == "write") Message.Assistant(
                MessagePart.Tool.Call("write-1", "__write_file__", buildJsonObject {
                    put("path", JsonPrimitive("/workspace/Answer.txt"))
                    put("content", JsonPrimitive("file answer"))
                }), ResponseMetaInfo.Empty, finishReason = "tool_calls",
            ) else Message.Assistant("finished response", ResponseMetaInfo.Empty)
        }
        val specification = runTestSpecification(maxRepairTurns = 0).let {
            it.copy(model = it.model.copy(capabilities = listOf(LLMCapability.Completion, LLMCapability.Tools)),
                maxWorkspaceToolCallsPerAttempt = 1)
        }
        val engine = createRunEngine(temporaryDirectory, executor, specification,
            assessSubmission = { _, _ -> error("response evaluator must not run") },
            workspaceFactory = factory)
        val record = engine.run(task, 1) {
            assertEquals(true, Files.exists(roots.single().resolve("Answer.txt")))
        }
        assertEquals(EvaluationResult.Accepted, record.finalResult)
        assertEquals("file answer", record.turns.single().submission)
        assertEquals("finished response", record.turns.single().response)
        assertContains(record.taskPrompt, "Write your answer to /workspace/Answer.txt.")
        assertEquals("Answer.txt", record.workspace!!.finalFiles.single().path)
        assertFalse(Files.exists(roots.single()))
    }
}
