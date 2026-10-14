package run

import support.SimpleTestDataset
import ai.koog.prompt.llm.LLMCapability
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.reflect.ToolSet
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.ResponseMetaInfo
import backend.lean.evaluation.LeanProcess
import backend.lean.evaluation.LeanProcessResult
import backend.lean.evaluation.LeanProofEvaluator
import backend.lean.protocol.LeanProtocols
import backend.lean.workspace.LeanTaskWorkspaceFactory
import domain.EvaluationResult
import evaluation.TaskEvaluatorRegistry
import java.nio.file.Files
import java.nio.file.Path
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import prompt.PromptRenderer

class WorkspaceTaskRunEngineTest {
    private val projectRoot = Path.of("").toAbsolutePath().normalize()
    private val task = SimpleTestDataset.task("proof").let { task ->
        task.copy(manifest = task.manifest.copy(status = domain.TaskStatus.FROZEN))
    }
    private val roots = mutableListOf<Path>()

    private fun engine(executor: ScriptedPromptExecutor, budget: Int? = 12, iterations: Int? = 80,
        otherBudget: Int? = 0, otherTools: ToolRegistry = ToolRegistry.EMPTY,
        check: (String) -> LeanProcessResult = { LeanProcessResult.Completed(0, "'Benchmark.target' does not depend on any axioms") },
    ): TaskRunEngine {
        val prompts = projectRoot.resolve("prompts/common/v1")
        return TaskRunEngine(
            LeanProtocols.create(projectRoot.resolve("prompts/lean/v1"), SimpleTestDataset.promptRoot),
            TaskEvaluatorRegistry(listOf(LeanProofEvaluator({ _, _ -> error("host evaluator must not run") }))),
            executor,
            BenchmarkRunSpecification(LLModel(LLMProvider.OpenRouter, "test-model",
                capabilities = listOf(LLMCapability.Completion, LLMCapability.Tools)), 2, RepairPolicy(1), otherBudget, iterations,
                maxWorkspaceToolCallsPerAttempt = budget),
            PromptRenderer(prompts.resolve("agent-system.md")), PromptRenderer(prompts.resolve("repair.md")),
            toolRegistry = otherTools,
            workspaceFactory = LeanTaskWorkspaceFactory(projectRoot, SimpleTestDataset.datasetRoot, buildDependencies = { root, _ -> roots.add(root) },
                leanProcessFactory = { LeanProcess { source, _ -> check(source) } }),
        )
    }

    private fun edit(old: String, new: String): Message.Assistant = Message.Assistant(
        MessagePart.Tool.Call("edit-1", "edit_file", buildJsonObject {
            put("path", JsonPrimitive("/workspace/Solution.lean"))
            put("original", JsonPrimitive(old))
            put("replacement", JsonPrimitive(new))
        }), ResponseMetaInfo.Empty, finishReason = "tool_calls")

    @Test
    fun usesFileSubmissionsAndOneConversationForBoundedRepairs() = runBlocking {
        val checked = mutableListOf<String>()
        val executor = ScriptedPromptExecutor(listOf("initial edit", "finish", "repair edit", "finish")) { reply ->
            when (reply) {
                "initial edit" -> edit("__BENCHMARK_PROOF__", "by\n  trivial")
                "repair edit" -> edit("by\n  trivial", "by\n  simp")
                else -> Message.Assistant("Finished; this text is not a Lean proof.", ResponseMetaInfo.Empty)
            }
        }
        val record = engine(executor, budget = 1) { source ->
            checked += source
            if (source.contains("  trivial")) LeanProcessResult.Completed(1, "test compiler diagnostic")
            else LeanProcessResult.Completed(0, "'Benchmark.target' does not depend on any axioms")
        }.run(task, 1)
        assertEquals(EvaluationResult.Accepted, record.finalResult)
        assertEquals(listOf("by\n  trivial", "by\n  simp"), record.turns.map { it.submission })
        assertEquals(2, checked.size)
        assertContains(record.turns[1].userMessage, "test compiler diagnostic")
        assertContains(record.turns[0].workspaceFiles.single().content, "  trivial")
        assertContains(record.turns[1].workspaceFiles.single().content, "  simp")
        assertContains(requireNotNull(record.workspace).finalFiles.single().content, "  simp")
        assertEquals(1, roots.size)
        assertFalse(Files.exists(roots.single()))
        assertTrue(executor.prompts[2].messages.size > executor.prompts[0].messages.size)
        executor.offeredTools.forEach { tools ->
            assertEquals(setOf("__list_directory__", "__read_file__", "search_files", "__write_file__", "edit_file"), tools.map { it.name }.toSet())
        }
        val toolEvents = record.trajectory.filter { it.kind == "ToolCallCompletedEvent" }
        assertEquals(2, toolEvents.size)
        assertEquals("edit_file", toolEvents.first().details["toolName"]!!.jsonPrimitive.content)
        assertEquals("/workspace/Solution.lean", toolEvents.first().details["toolArgs"]!!.jsonObject["path"]!!.jsonPrimitive.content)
        assertContains(toolEvents.first().details["result"].toString(), "trivial")
    }

    @Test
    fun samplesStartWithFreshFilesAndFreshConversation() = runBlocking {
        val executor = ScriptedPromptExecutor(listOf("edit", "finish", "finish", "finish")) { reply ->
            if (reply == "edit") edit("__BENCHMARK_PROOF__", "by\n  trivial")
            else Message.Assistant("Finished", ResponseMetaInfo.Empty)
        }
        val engine = engine(executor)
        val first = engine.run(task, 1)
        val second = engine.run(task, 2)
        assertEquals(EvaluationResult.Accepted, first.finalResult)
        assertIs<EvaluationResult.Rejected>(second.finalResult)
        assertContains(requireNotNull(second.workspace).finalFiles.single().content, "__BENCHMARK_PROOF__")
        assertEquals(2, second.turns.size)
        assertEquals(executor.prompts[0].messages.map { it.role to it.parts },
            executor.prompts[2].messages.map { it.role to it.parts })
        assertEquals(2, roots.distinct().size)
        assertTrue(roots.none(Files::exists))
    }

    @Test
    fun deniedBuiltInOperationsReturnToolErrorsAndTheAgentCanContinue() = runBlocking {
        val executor = ScriptedPromptExecutor(listOf("host read", "hidden read", "readonly write", "edit", "finish")) { reply ->
            when (reply) {
                "host read", "hidden read" -> Message.Assistant(
                    MessagePart.Tool.Call(reply, "__read_file__", buildJsonObject {
                        put("path", JsonPrimitive(if (reply == "host read") "/etc/passwd" else "/workspace/.lake/packages/mathlib/Mathlib.lean"))
                    }), ResponseMetaInfo.Empty, finishReason = "tool_calls")
                "readonly write" -> Message.Assistant(
                    MessagePart.Tool.Call(reply, "__write_file__", buildJsonObject {
                        put("path", JsonPrimitive("/workspace/lean-toolchain"))
                        put("content", JsonPrimitive("bad"))
                    }), ResponseMetaInfo.Empty, finishReason = "tool_calls")
                "edit" -> edit("__BENCHMARK_PROOF__", "by\n  trivial")
                else -> Message.Assistant("Finished", ResponseMetaInfo.Empty)
            }
        }
        val record = engine(executor).run(task, 1)
        assertEquals(EvaluationResult.Accepted, record.finalResult)
        val results = executor.prompts.last().messages.flatMap { it.parts }.filterIsInstance<MessagePart.Tool.Result>()
        assertEquals(4, results.size)
        assertTrue(results.take(3).all { it.isError })
        assertFalse(results.last().isError)
        assertTrue(roots.none(Files::exists))
    }

    @Test
    fun stopsAtToolBudgetAndArchivesTheLastFilesOnFailure() = runBlocking {
        val executor = ScriptedPromptExecutor(listOf("edit", "second edit")) {
            edit(if (it == "edit") "__BENCHMARK_PROOF__" else "by\n  trivial", "by\n  trivial")
        }
        val record = engine(executor, budget = 1).run(task, 1)
        val failure = assertIs<EvaluationResult.InfrastructureFailure>(record.finalResult)
        assertEquals(
            "agent execution failed: IllegalStateException: workspace tool call limit exceeded",
            failure.reason,
        )
        assertTrue(record.turns.isEmpty())
        assertContains(requireNotNull(record.workspace).finalFiles.single().content, "  trivial")
        assertEquals(1, record.trajectory.count { it.kind == "ToolCallCompletedEvent" })
        assertTrue(record.trajectory.any { it.kind == "AgentExecutionFailedEvent" && it.details["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content?.contains("tool call limit") == true })
        assertTrue(roots.none(Files::exists))
    }

    private fun readCall(id: String) = MessagePart.Tool.Call(id, "__read_file__", buildJsonObject {
        put("path", JsonPrimitive("/workspace/Solution.lean"))
    })

    private fun lookupCall(id: String) = MessagePart.Tool.Call(id, "lookup", JsonObject(emptyMap()))

    class LookupTools : ToolSet {
        var calls = 0

        @Tool("lookup")
        @LLMDescription("Look up a test result.")
        fun lookup(): String {
            calls++
            return "result"
        }
    }

    @Test
    fun unlimitedWorkspaceCallsDoNotConsumeTheOtherToolBudget() = runBlocking {
        val executor = ScriptedPromptExecutor(listOf("read batch", "edit", "finish")) { reply ->
            when (reply) {
                "read batch" -> Message.Assistant(listOf(readCall("read-1"), readCall("read-2")),
                    ResponseMetaInfo.Empty, finishReason = "tool_calls")
                "edit" -> edit("__BENCHMARK_PROOF__", "by\n  trivial")
                else -> Message.Assistant("Finished", ResponseMetaInfo.Empty)
            }
        }
        val record = engine(executor, budget = null, iterations = null, otherBudget = 0).run(task, 1)
        assertEquals(EvaluationResult.Accepted, record.finalResult)
        assertEquals(3, record.trajectory.count { it.kind == "ToolCallCompletedEvent" })
        assertEquals(3, executor.prompts.size)
    }

    @Test
    fun mixedBatchesUseSeparateWorkspaceAndOtherToolBudgets() = runBlocking {
        val lookup = LookupTools()
        val otherTools = ToolRegistry { lookup.asTools().forEach { tool(it) } }
        val executor = ScriptedPromptExecutor(listOf("mixed batch", "finish")) { reply ->
            if (reply == "mixed batch") Message.Assistant(
                listOf(readCall("read-1"), lookupCall("lookup-1"), readCall("read-2")) +
                    edit("__BENCHMARK_PROOF__", "by\n  trivial").parts,
                ResponseMetaInfo.Empty, finishReason = "tool_calls")
            else Message.Assistant("Finished", ResponseMetaInfo.Empty)
        }
        val record = engine(executor, budget = 3, otherBudget = 1, otherTools = otherTools).run(task, 1)
        assertEquals(EvaluationResult.Accepted, record.finalResult)
        assertEquals(1, lookup.calls)
        assertEquals(4, record.trajectory.count { it.kind == "ToolCallCompletedEvent" })
        assertTrue(executor.offeredTools.all { it.size == 6 })
    }

    @Test
    fun exceedingTheOtherBudgetRejectsTheWholeMixedBatchBeforeExecution() = runBlocking {
        val lookup = LookupTools()
        val otherTools = ToolRegistry { lookup.asTools().forEach { tool(it) } }
        val executor = ScriptedPromptExecutor(listOf("first batch", "over budget")) { reply ->
            val calls = if (reply == "first batch") listOf(lookupCall("lookup-1")) +
                edit("__BENCHMARK_PROOF__", "by\n  trivial").parts
            else listOf(readCall("read-1"), lookupCall("lookup-2"))
            Message.Assistant(calls, ResponseMetaInfo.Empty, finishReason = "tool_calls")
        }
        val record = engine(executor, budget = null, otherBudget = 1, otherTools = otherTools).run(task, 1)
        assertContains(assertIs<EvaluationResult.InfrastructureFailure>(record.finalResult).reason,
            "tool call limit exceeded")
        assertEquals(1, lookup.calls)
        assertEquals(2, record.trajectory.count { it.kind == "ToolCallCompletedEvent" })
        assertContains(record.workspace!!.finalFiles.single().content, "by\n  trivial")
    }

    @Test
    fun unlimitedOtherCallsStillRespectTheWorkspaceBudget() = runBlocking {
        val lookup = LookupTools()
        val otherTools = ToolRegistry { lookup.asTools().forEach { tool(it) } }
        val executor = ScriptedPromptExecutor(listOf("first batch", "over budget")) { reply ->
            val calls = if (reply == "first batch") listOf(lookupCall("lookup-1"), lookupCall("lookup-2")) +
                edit("__BENCHMARK_PROOF__", "by\n  trivial").parts
            else listOf(lookupCall("lookup-3"), readCall("read-1"))
            Message.Assistant(calls, ResponseMetaInfo.Empty, finishReason = "tool_calls")
        }
        val record = engine(executor, budget = 1, otherBudget = null, otherTools = otherTools).run(task, 1)
        assertContains(assertIs<EvaluationResult.InfrastructureFailure>(record.finalResult).reason,
            "workspace tool call limit exceeded")
        assertEquals(2, lookup.calls)
        assertEquals(3, record.trajectory.count { it.kind == "ToolCallCompletedEvent" })
    }

    @Test
    fun truncatedReplyDoesNotRunToolsOrCompileAndStillCapturesFiles() = runBlocking {
        val executor = ScriptedPromptExecutor(listOf("truncated")) {
            edit("__BENCHMARK_PROOF__", "by\n  trivial").copy(finishReason = "length")
        }
        val record = engine(executor, check = { error("must not compile") }).run(task, 1)
        assertIs<EvaluationResult.InfrastructureFailure>(record.finalResult)
        assertEquals(1, record.turns.size)
        assertFalse(record.trajectory.any { it.kind == "ToolCallStartingEvent" })
        assertContains(requireNotNull(record.workspace).finalFiles.single().content, "__BENCHMARK_PROOF__")
        assertTrue(roots.none(Files::exists))
    }

    @Test
    fun cancellationRemovesTheWorkspaceAndPropagates() = runBlocking {
        val executor = ScriptedPromptExecutor(listOf("cancel")) { throw CancellationException("cancelled") }
        assertFailsWith<CancellationException> { engine(executor).run(task, 1) }
        assertEquals(1, roots.size)
        assertTrue(roots.none(Files::exists))
    }

    @Test
    fun enforcesTheTotalIterationBudgetAcrossToolsAndRepairs() = runBlocking {
        val executor = ScriptedPromptExecutor(listOf("edit", "finish", "must not request")) { reply ->
            if (reply == "edit") edit("__BENCHMARK_PROOF__", "by\n  trivial")
            else Message.Assistant("Finished", ResponseMetaInfo.Empty)
        }
        val record = engine(executor, iterations = 2,
            check = { LeanProcessResult.Completed(1, "rejected proof") }).run(task, 1)
        assertIs<EvaluationResult.InfrastructureFailure>(record.finalResult)
        assertEquals(2, executor.prompts.size)
        assertEquals(1, record.turns.size)
        assertIs<EvaluationResult.Rejected>(record.turns.single().result)
        assertTrue(record.trajectory.any { it.kind == "AgentExecutionFailedEvent" &&
            it.details["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content?.contains("iteration limit") == true })
        assertTrue(roots.none(Files::exists))
    }

    @Test
    fun savesTheRecordBeforeRemovingTheWorkspace() = runBlocking {
        val executor = ScriptedPromptExecutor(listOf("edit", "finish")) { reply ->
            if (reply == "edit") edit("__BENCHMARK_PROOF__", "by\n  trivial")
            else Message.Assistant("Finished", ResponseMetaInfo.Empty)
        }
        var saved: TaskRunRecord? = null
        engine(executor).run(task, 1) { record ->
            assertTrue(Files.exists(roots.single().resolve("Solution.lean")))
            assertContains(requireNotNull(record.workspace).finalFiles.single().content, "  trivial")
            saved = record
        }
        assertEquals(EvaluationResult.Accepted, requireNotNull(saved).finalResult)
        assertTrue(roots.none(Files::exists))
    }
}
