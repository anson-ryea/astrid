package run

import ai.koog.prompt.executor.clients.LLMClientException
import ai.koog.prompt.executor.clients.openrouter.OpenRouterClientSettings
import ai.koog.prompt.executor.clients.openrouter.OpenRouterLLMClient
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.ResponseMetaInfo
import com.sun.net.httpserver.HttpServer
import domain.EvaluationResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import run.serialization.SampleResourcesJson
import workspace.TaskWorkspaceFactory
import workspace.TaskWorkspacePlan
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds

class ExperimentalControlsTest {
    @TempDir
    lateinit var root: Path
    private fun meta(input: Int, output: Int) = ResponseMetaInfo.Empty.copy(
        inputTokensCount = input, outputTokensCount = output, totalTokensCount = input + output
    )

    @Test
    fun forwardsSamplingAndDerivedSeedThroughTheRealClientSerializer() = runBlocking {
        val body = AtomicReference<JsonObject>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            body.set(Json.parseToJsonElement(exchange.requestBody.bufferedReader().readText()).jsonObject)
            val response =
                """{"id":"local","created":1,"model":"test-model","choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"proof"}}],"usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15}}""".toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        server.start()
        val client = OpenRouterLLMClient(
            "unused",
            settings = OpenRouterClientSettings(baseUrl = "http://127.0.0.1:${server.address.port}")
        )
        val executor = MultiLLMPromptExecutor(client)
        try {
            val spec = runTestSpecification().let {
                it.copy(
                    model = it.model.copy(
                        maxOutputTokens = 25,
                        capabilities = listOf(ai.koog.prompt.llm.LLMCapability.Completion)
                    ),
                    generation = GenerationSettings(0.2, 0.8, 10, 42)
                )
            }
            val record = createRunEngine(root, executor, spec).run(createRunTask(root), 2)
            assertEquals(SampleOutcome.Evaluated(EvaluationResult.Accepted), record.outcome)
            val request = body.get()
            assertEquals("43", request.getValue("seed").jsonPrimitive.content)
            assertEquals("0.2", request.getValue("temperature").jsonPrimitive.content)
            assertEquals("0.8", request.getValue("top_p").jsonPrimitive.content)
            assertEquals("10", request.getValue("top_k").jsonPrimitive.content)
            assertEquals(
                "true",
                request.getValue("provider").jsonObject.getValue("require_parameters").jsonPrimitive.content
            )
            assertEquals(43, record.resources!!.effectiveSeed)
            assertEquals(15, record.resources.totalTokens)
        } finally {
            executor.close(); server.stop(0)
        }
    }

    @Test
    fun capsRequestsAcrossRepairsAndPersistsUsageAndLabelledCostEstimates() = runBlocking {
        val task = createRunTask(root)
        val executor = ScriptedPromptExecutor(listOf("bad", "good")) { reply ->
            Message.Assistant(reply, if (reply == "bad") meta(10, 4) else meta(0, 6))
        }
        val spec = runTestSpecification().let {
            it.copy(
                model = it.model.copy(maxOutputTokens = 100),
                generation = GenerationSettings(seed = Int.MAX_VALUE), limits = SampleLimits(maxTotalTokens = 20),
                pricing = TokenPricing(2.0, 4.0), selection = TaskSelection(taskIds = setOf(task.manifest.taskId))
            )
        }
        val record = createRunEngine(root, executor, spec, assessSubmission = { _, text ->
            if (text == "bad") EvaluationResult.Rejected("repair") else EvaluationResult.Accepted
        }).run(task, 2)
        assertEquals(SampleOutcome.Evaluated(EvaluationResult.Accepted), record.outcome)
        assertEquals(listOf(20, 6), executor.prompts.map { it.params.maxTokens })
        val resources = requireNotNull(record.resources)
        assertEquals(0, resources.effectiveSeed)
        assertEquals(2, resources.modelRequests)
        assertEquals(2, resources.modelResponses)
        assertEquals(10, resources.inputTokens)
        assertEquals(10, resources.outputTokens)
        assertEquals(20, resources.totalTokens)
        assertEquals(0.00006, resources.estimatedCostUsd!!, 1e-12)
        assertEquals(listOf(20, 6), resources.requestOutputTokenCaps)
        assertEquals(resources, SampleResourcesJson.decode(SampleResourcesJson.encode(resources)))
        assertEquals(JsonNull, SampleResourcesJson.encode(resources).getValue("actual_cost_usd"))
        val directory = root.resolve("saved")
        val store = FileRunStore(directory)
        store.begin(spec, listOf(task.manifest.taskId))
        store.save(record.copy(sampleIndex = 1)); store.complete()
        val saved =
            Json.parseToJsonElement(withContext(Dispatchers.IO) {
                Files.readString(directory.resolve("tasks/${task.manifest.taskId}/samples/001/record.json"))
            }).jsonObject
        assertEquals(SampleResourcesJson.encode(resources), saved.getValue("resources"))
        assertEquals("6", saved.getValue("schema_version").jsonPrimitive.content)
        val run = Json.parseToJsonElement(withContext(Dispatchers.IO) {
            Files.readString(directory.resolve("run.json"))
        }).jsonObject
        assertEquals(
            Int.MAX_VALUE.toString(),
            run.getValue("generation").jsonObject.getValue("seed").jsonPrimitive.content
        )
        assertEquals(
            "20",
            run.getValue("limits").jsonObject.getValue("max_total_tokens_per_sample").jsonPrimitive.content
        )
        assertEquals("2.0", run.getValue("pricing").jsonObject.getValue("input_usd_per_million").jsonPrimitive.content)
        assertEquals(
            task.manifest.taskId.value,
            run.getValue("selection").jsonObject.getValue("task_ids").jsonArray.single().jsonPrimitive.content
        )
    }

    @Test
    fun overshootStopsBeforeAssessmentAndRetainsTheRawResponseInTheTrajectory() = runBlocking {
        val executor = ScriptedPromptExecutor(listOf("proof", "unused")) { Message.Assistant(it, meta(30, 1)) }
        val record = createRunEngine(
            root, executor, runTestSpecification().copy(limits = SampleLimits(maxTotalTokens = 20)),
            assessSubmission = { _, _ -> error("over-budget output must not be assessed") }).run(createRunTask(root), 1)
        assertEquals(SampleBudget.TOTAL_TOKENS, assertIs<SampleOutcome.BudgetExhausted>(record.outcome).limit)
        assertEquals(31, record.resources!!.totalTokens)
        assertEquals(1, executor.prompts.size)
        assertTrue(record.trajectory.any {
            it.kind == "LLMCallCompletedEvent" && it.details.toString().contains("proof")
        })
    }

    @Test
    fun exhaustedBudgetPreventsAnotherRequestAndMissingUsageIsNeverAssumedFree() = runBlocking {
        val used = ScriptedPromptExecutor(listOf("bad", "unused")) { Message.Assistant(it, meta(4, 6)) }
        val spec = runTestSpecification().copy(limits = SampleLimits(maxTotalTokens = 10))
        val rejected =
            createRunEngine(root, used, spec, assessSubmission = { _, _ -> EvaluationResult.Rejected("repair") })
                .run(createRunTask(root), 1)
        assertEquals(SampleBudget.TOTAL_TOKENS, assertIs<SampleOutcome.BudgetExhausted>(rejected.outcome).limit)
        assertEquals(1, rejected.resources!!.modelRequests)
        assertEquals(1, used.prompts.size)
        assertIs<EvaluationResult.Rejected>(rejected.turns.single().result)
        val missing = ScriptedPromptExecutor(listOf("proof", "unused"))
        val record = createRunEngine(root, missing, spec).run(createRunTask(root), 1)
        assertContains(assertIs<SampleOutcome.ExecutionFailure>(record.outcome).reason, "omitted total usage")
        assertNull(record.resources!!.totalTokens)
        assertEquals(1, missing.prompts.size)
        val unrestricted = createRunEngine(root, ScriptedPromptExecutor(listOf("proof"))).run(createRunTask(root), 1)
        assertEquals(SampleOutcome.Evaluated(EvaluationResult.Accepted), unrestricted.outcome)
        assertNull(unrestricted.resources!!.inputTokens)
        assertNull(unrestricted.resources.estimatedCostUsd)
        assertEquals(
            unrestricted.resources,
            SampleResourcesJson.decode(SampleResourcesJson.encode(unrestricted.resources))
        )
    }

    @Test
    fun providerFailureKeepsKnownUsageButMarksTheSampleTotalsIncomplete() = runBlocking {
        val executor = ScriptedPromptExecutor(listOf("bad", "fail")) {
            if (it == "fail") throw LLMClientException("local", "unavailable")
            Message.Assistant(it, meta(10, 4))
        }
        val record = createRunEngine(root, executor, assessSubmission = { _, _ -> EvaluationResult.Rejected("repair") })
            .run(createRunTask(root), 1)
        assertIs<SampleOutcome.ProviderFailure>(record.outcome)
        val resources = record.resources!!
        assertEquals(2, resources.modelRequests)
        assertEquals(1, resources.modelResponses)
        assertNull(resources.totalTokens)
        assertEquals(14, resources.knownTotalTokens)
        assertEquals(resources, SampleResourcesJson.decode(SampleResourcesJson.encode(resources)))
    }

    @Test
    fun deadlineCancelsGenerationAndInterruptsBlockingAssessmentAndPreparation() = runBlocking {
        val task = createRunTask(root)
        val limits = SampleLimits(timeout = 500.milliseconds)
        val executor = ScriptedPromptExecutor(listOf("never")) { awaitCancellation() }
        val generation = createRunEngine(root, executor, runTestSpecification().copy(limits = limits)).run(task, 1)
        assertEquals(SampleBudget.SAMPLE_TIME, assertIs<SampleOutcome.BudgetExhausted>(generation.outcome).limit)
        assertEquals(1, generation.resources!!.modelRequests)
        assertEquals(0, generation.resources.modelResponses)
        assertNull(generation.resources.totalTokens)
        var interrupted = false
        val assessment = createRunEngine(
            root, ScriptedPromptExecutor(listOf("proof")), runTestSpecification().copy(limits = limits),
            assessSubmission = { _, _ ->
                try {
                    Thread.sleep(10_000); EvaluationResult.Accepted
                } finally {
                    interrupted = true
                }
            }).run(task, 1)
        assertEquals(SampleBudget.SAMPLE_TIME, assertIs<SampleOutcome.BudgetExhausted>(assessment.outcome).limit)
        assertTrue(interrupted)
        assertTrue(assessment.resources!!.evaluationMs > 0)
        val factory = TaskWorkspaceFactory { TaskWorkspacePlan { Thread.sleep(10_000); error("unreachable") } }
        val spec = runTestSpecification().let {
            it.copy(
                model = it.model.copy(
                    capabilities = listOf(
                        ai.koog.prompt.llm.LLMCapability.Completion,
                        ai.koog.prompt.llm.LLMCapability.Tools
                    )
                ), maxWorkspaceToolCallsPerAttempt = 1, limits = limits
            )
        }
        val preparation =
            createRunEngine(root, ScriptedPromptExecutor(listOf("unused")), spec, workspaceFactory = factory).run(
                task,
                1
            )
        assertEquals(SampleBudget.SAMPLE_TIME, assertIs<SampleOutcome.BudgetExhausted>(preparation.outcome).limit)
        assertEquals(0, preparation.resources!!.modelRequests)
    }
}
