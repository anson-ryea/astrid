package run

import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.ResponseMetaInfo
import domain.EvaluationResult
import domain.TaskStatus
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.io.TempDir

class TaskRunEngineTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun semanticRejectionIsTerminalAndPrivateDiagnosticsNeverBecomeRepairPrompts() = runBlocking {
        val task = createRunTask(temporaryDirectory)
        val executor = ScriptedPromptExecutor(listOf("candidate", "must not be called"))
        val evidence = domain.EvaluationEvidence("a".repeat(64), mapOf("Specification.lean" to "b".repeat(64)), "passed",
            obligations = listOf(domain.CheckEvidence("AstridSpec.agreement", "unresolved", "proved")))
        val engine = createRunEngine(temporaryDirectory, executor,
            detailedAssessment = { _, _ -> domain.EvaluationReport(
                EvaluationResult.Rejected("private semantic disagreement"), evidence, publicDiagnostic = null) })
        val record = engine.run(task, 1)
        assertEquals(1, executor.prompts.size)
        assertIs<EvaluationResult.Rejected>(record.finalResult)
        assertEquals(evidence, record.turns.single().evaluationEvidence)
        assertTrue(executor.prompts.none { it.messages.toString().contains("AstridSpec") || it.messages.toString().contains("private semantic") })
    }

    @Test
    fun unlimitedRepairsAndIterationsContinueUntilTheSubmissionIsAccepted() = runBlocking {
        val task = createRunTask(temporaryDirectory)
        val executor = ScriptedPromptExecutor(listOf("first", "second", "third", "accepted"))
        val specification = runTestSpecification(maxRepairTurns = null).copy(maxAgentIterations = null)
        val engine = createRunEngine(temporaryDirectory, executor, specification,
            assessSubmission = { _, submission ->
                if (submission == "accepted") EvaluationResult.Accepted else EvaluationResult.Rejected("retry")
            })
        val record = engine.run(task, 1)
        assertEquals(EvaluationResult.Accepted, record.finalResult)
        assertEquals(listOf(0, 1, 2, 3), record.turns.map { it.repairTurn })
        assertEquals(4, executor.prompts.size)
    }

    @Test
    fun acceptsTheFirstSubmission() {
        runBlocking {
            val task = createRunTask(temporaryDirectory)
            val executor = ScriptedPromptExecutor(listOf("proof"))
            val specification = runTestSpecification().copy(
                model = runTestSpecification().model.copy(maxOutputTokens = 256),
                maxAgentIterations = 1,
            )
            val engine = createRunEngine(
                temporaryDirectory,
                executor,
                specification = specification,
            )

            val record = engine.run(task, sampleIndex = 1)

            assertEquals(EvaluationResult.Accepted, record.finalResult)
            assertEquals(task.manifest.taskId, record.taskId)
            assertEquals("System instructions.", record.systemPrompt)
            assertEquals("Task: ${task.manifest.taskId.value}", record.taskPrompt)
            assertEquals(listOf(0), record.turns.map { it.repairTurn })
            assertEquals("proof", record.turns.single().submission)
            assertEquals(1, executor.prompts.size)
            assertEquals(
                listOf("LLMCallStartingEvent", "LLMCallCompletedEvent"),
                record.trajectory.filter { it.kind.startsWith("LLMCall") }.map { it.kind },
            )
            val recordedPrompt = record.trajectory.first { it.kind == "LLMCallStartingEvent" }.details["prompt"]!!.jsonObject
            assertTrue(recordedPrompt["messages"]!!.jsonArray.toString().contains(record.taskPrompt))
            val request = record.trajectory.first { it.kind == "LLMCallStartingEvent" }
            assertEquals(request.timestampEpochMillis, request.details["timestamp"]!!.jsonPrimitive.content.toLong())
            assertTrue(request.details["eventId"]!!.jsonPrimitive.content.isNotBlank())
            assertTrue(request.details["runId"]!!.jsonPrimitive.content.isNotBlank())
            assertTrue(record.trajectory.any { it.kind == "GraphStrategyStartingEvent" })
            assertTrue(record.trajectory.any { it.kind == "NodeExecutionCompletedEvent" })
            assertEquals("AgentStartingEvent", record.trajectory.first().kind)
            assertEquals("AgentClosingEvent", record.trajectory.last().kind)
            assertEquals(256, executor.prompts.single().params.maxTokens)
            assertEquals(
                record.taskPrompt,
                executor.prompts.single().messages
                    .filterIsInstance<Message.User>()
                    .last()
                    .textContent(),
            )
        }
    }

    @Test
    fun recordsTruncatedReasoningOnlyReplyWithoutEvaluatingOrRepairing() = runBlocking {
        val task = createRunTask(temporaryDirectory)
        val executor = ScriptedPromptExecutor(listOf("ignored")) {
            Message.Assistant(
                parts = listOf(MessagePart.Reasoning("reasoning-only reply")),
                metaInfo = ResponseMetaInfo.Empty.copy(outputTokensCount = 256),
                finishReason = "length",
            )
        }
        val engine = createRunEngine(
            temporaryDirectory,
            executor,
            specification = runTestSpecification(maxRepairTurns = 2),
            assessSubmission = { _, _ -> error("A truncated reply must not be evaluated") },
        )

        val record = engine.run(task, sampleIndex = 1)

        val failure = assertIs<EvaluationResult.InfrastructureFailure>(record.finalResult)
        assertContains(failure.reason, "output-token limit")
        assertEquals(1, executor.prompts.size)
        assertEquals(1, record.turns.size)
        assertEquals(null, record.turns.single().submission)
        assertEquals("", record.turns.single().response)
        val response = record.trajectory.single { it.kind == "LLMCallCompletedEvent" }
            .details["response"]!!.jsonObject
        assertEquals("length", response["finishReason"]!!.jsonPrimitive.content)
        assertEquals(
            256,
            response["metaInfo"]!!.jsonObject["outputTokensCount"]!!.jsonPrimitive.content.toInt(),
        )
        assertTrue(response["parts"]!!.jsonArray.toString().contains("reasoning-only reply"))
    }

    @Test
    fun repairsARejectedSubmissionWithinTheSameSample() {
        runBlocking {
            val task = createRunTask(temporaryDirectory)
            val executor = ScriptedPromptExecutor(listOf("bad", "good"))
            val engine = createRunEngine(
                temporaryDirectory,
                executor,
                assessSubmission = { _, submission ->
                    if (submission == "bad") {
                        EvaluationResult.Rejected("Lean rejected the proof")
                    } else {
                        EvaluationResult.Accepted
                    }
                },
            )

            val record = engine.run(task, sampleIndex = 1)

            assertEquals(EvaluationResult.Accepted, record.finalResult)
            assertEquals(listOf(0, 1), record.turns.map { it.repairTurn })
            assertEquals(listOf("bad", "good"), record.turns.map { it.submission })
            assertEquals("Repair: Lean rejected the proof", record.turns[1].userMessage)
            assertEquals(2, executor.prompts.size)
            assertEquals(
                listOf(
                    "LLMCallStartingEvent", "LLMCallCompletedEvent",
                    "LLMCallStartingEvent", "LLMCallCompletedEvent",
                ),
                record.trajectory.filter { it.kind.startsWith("LLMCall") }.map { it.kind },
            )
        }
    }

    @Test
    fun stopsAfterTheConfiguredRepairLimit() {
        runBlocking {
            val task = createRunTask(temporaryDirectory)
            val executor = ScriptedPromptExecutor(listOf("bad-1", "bad-2"))
            val engine = createRunEngine(
                temporaryDirectory,
                executor,
                specification = runTestSpecification(maxRepairTurns = 1),
                assessSubmission = { _, _ -> EvaluationResult.Rejected("still invalid") },
            )

            val record = engine.run(task, sampleIndex = 1)

            assertIs<EvaluationResult.Rejected>(record.finalResult)
            assertEquals(listOf(0, 1), record.turns.map { it.repairTurn })
            assertEquals(2, executor.prompts.size)
        }
    }

    @Test
    fun doesNotRepairAResultThatNeedsReview() {
        runBlocking {
            val task = createRunTask(temporaryDirectory)
            val executor = ScriptedPromptExecutor(listOf("proposal"))
            val engine = createRunEngine(
                temporaryDirectory,
                executor,
                assessSubmission = { _, _ -> EvaluationResult.NeedsReview("human check") },
            )

            val record = engine.run(task, sampleIndex = 1)

            assertIs<EvaluationResult.NeedsReview>(record.finalResult)
            assertEquals(1, record.turns.size)
            assertEquals(1, executor.prompts.size)
        }
    }

    @Test
    fun recordsExecutorFailureAsInfrastructureFailure() {
        runBlocking {
            val task = createRunTask(temporaryDirectory)
            val executor = ScriptedPromptExecutor(emptyList())
            val engine = createRunEngine(temporaryDirectory, executor)

            val record = engine.run(task, sampleIndex = 1)

            val failure = assertIs<EvaluationResult.InfrastructureFailure>(record.finalResult)
            assertEquals(
                "agent execution failed: IllegalStateException: no scripted model reply remains",
                failure.reason,
            )
            assertTrue(record.turns.isEmpty())
            assertEquals(1, executor.prompts.size)
            assertEquals(
                listOf("LLMCallStartingEvent", "LLMCallFailedEvent"),
                record.trajectory.filter { it.kind.startsWith("LLMCall") }.map { it.kind },
            )
            assertContains(
                record.trajectory.single { it.kind == "AgentExecutionFailedEvent" }
                    .details["error"]!!.jsonObject["message"]!!.jsonPrimitive.content,
                "no scripted model reply remains",
            )
        }
    }

    @Test
    fun rejectsUnfrozenTaskBeforeCallingModel() {
        runBlocking {
            val task = createRunTask(
                temporaryDirectory,
                status = TaskStatus.CANDIDATE_PENDING_INDEPENDENT_REVIEW,
            )
            val executor = ScriptedPromptExecutor(listOf("unused"))
            val engine = createRunEngine(temporaryDirectory, executor)

            assertFailsWith<IllegalArgumentException> {
                engine.run(task, sampleIndex = 1)
            }
            assertTrue(executor.prompts.isEmpty())
        }
    }
}
