package run

import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.ResponseMetaInfo
import com.sun.net.httpserver.HttpServer
import domain.EvaluationResult
import java.net.InetSocketAddress
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.io.TempDir

class LangfuseTracingTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun exportsConcurrentSamplesAndFlushesOnCloseWhileKeepingLocalTrajectories() = runBlocking {
        withReceiver { receiver ->
            val task = createRunTask(temporaryDirectory)
            val tracing = receiver.tracing()
            val records = coroutineScope {
                (1..2).map { index ->
                    async {
                        createRunEngine(temporaryDirectory.resolve("sample-$index"),
                            ScriptedPromptExecutor(listOf("bad", "good")), langfuse = tracing,
                            assessSubmission = { _, submission ->
                                if (submission == "bad") EvaluationResult.Rejected("repair diagnostic")
                                else EvaluationResult.Accepted
                            }).run(task, index)
                    }
                }.awaitAll()
            }
            records.forEach {
                assertEquals(EvaluationResult.Accepted, it.finalResult)
                assertEquals(2, it.turns.size)
                assertEquals("AgentClosingEvent", it.trajectory.last().kind)
            }
            val spans = receiver.spans()
            assertTrue(spans.isNotEmpty(), "Agent close must flush spans before returning")
            val attributes = spans.map(::attributes)
            assertEquals(setOf("1", "2"), attributes.mapNotNull { it["langfuse.trace.metadata.sample_index"] }.toSet())
            attributes.forEach {
                assertEquals("test-run", it["langfuse.session.id"])
                assertEquals("plum", it["langfuse.trace.metadata.benchmark"])
                assertEquals(task.manifest.taskId.value, it["langfuse.trace.metadata.task_id"])
            }
            assertEquals(2, spans.map { it.getValue("traceId").jsonPrimitive.content }.toSet().size)
            val payloads = receiver.payloads.joinToString()
            assertContains(payloads, "System instructions.")
            assertContains(payloads, "Repair: repair diagnostic")
            assertContains(payloads, "gen_ai.completion.0.content")
            assertFalse(payloads.contains("sk-test"))
            assertTrue(receiver.authorisations.all { it == "Basic " +
                Base64.getEncoder().encodeToString("pk-test:sk-test".toByteArray()) })
            assertTrue(receiver.ingestionVersions.all { it == "4" })
        }
    }

    @Test
    fun canMaskPromptAndResponseContent() = runBlocking {
        withReceiver { receiver ->
            val task = createRunTask(temporaryDirectory)
            val record = createRunEngine(temporaryDirectory, ScriptedPromptExecutor(listOf("sensitive-response")),
                langfuse = receiver.tracing(captureContent = false)).run(task, 1)
            assertEquals(EvaluationResult.Accepted, record.finalResult)
            val payloads = receiver.payloads.joinToString()
            assertTrue(receiver.spans().isNotEmpty())
            assertFalse(payloads.contains("System instructions."))
            assertFalse(payloads.contains("sensitive-response"))
            assertContains(record.trajectory.toString(), "sensitive-response")
        }
    }

    @Test
    fun exportFailureDoesNotChangeTheEvaluationOrLoseTheLocalTrace() = runBlocking {
        withReceiver(status = 503) { receiver ->
            val task = createRunTask(temporaryDirectory)
            val engine = createRunEngine(temporaryDirectory, ScriptedPromptExecutor(listOf("one", "two")),
                langfuse = receiver.tracing())
            for (index in 1..2) {
                val record = engine.run(task, index)
                assertEquals(EvaluationResult.Accepted, record.finalResult)
                assertEquals("AgentClosingEvent", record.trajectory.last().kind)
            }
            assertTrue(receiver.payloads.isNotEmpty())
        }
    }

    @Test
    fun recordsToolCallsAndResults() = runBlocking {
        withReceiver { receiver ->
            val task = createRunTask(temporaryDirectory)
            val tools = TestTools()
            val registry = ToolRegistry { tools.asTools().forEach { tool(it) } }
            val executor = ScriptedPromptExecutor(listOf("call", "finished")) {
                if (it == "call") Message.Assistant(
                    listOf(MessagePart.Tool.Call("lookup-1", "lookup", JsonObject(emptyMap()))),
                    ResponseMetaInfo.Empty, finishReason = "tool_calls")
                else Message.Assistant(it, ResponseMetaInfo.Empty)
            }
            val specification = runTestSpecification().let {
                it.copy(model = it.model.copy(capabilities = listOf(LLMCapability.Completion, LLMCapability.Tools)),
                    maxToolCallsPerAttempt = 1)
            }
            val record = createRunEngine(temporaryDirectory, executor, specification,
                langfuse = receiver.tracing(), toolRegistry = registry).run(task, 1)
            assertEquals(EvaluationResult.Accepted, record.finalResult)
            assertTrue(record.trajectory.any { it.kind == "ToolCallCompletedEvent" })
            val payloads = receiver.payloads.joinToString()
            assertContains(payloads, "gen_ai.tool.name")
            assertContains(payloads, "lookup")
            assertContains(payloads, "synthetic-tool-result")
        }
    }

    @Test
    fun flushesAgentFailuresAsErrorSpans() = runBlocking {
        withReceiver { receiver ->
            val task = createRunTask(temporaryDirectory)
            val executor = ScriptedPromptExecutor(listOf("unused")) { error("synthetic-model-failure") }
            val record = createRunEngine(temporaryDirectory, executor,
                langfuse = receiver.tracing()).run(task, 1)
            assertIs<EvaluationResult.InfrastructureFailure>(record.finalResult)
            assertTrue(record.trajectory.any { it.kind == "AgentExecutionFailedEvent" })
            assertTrue(receiver.spans().any { span ->
                span["status"]?.jsonObject?.get("code")?.jsonPrimitive?.content in listOf("2", "STATUS_CODE_ERROR")
            })
            assertContains(receiver.payloads.joinToString(), "synthetic-model-failure")
        }
    }

    class TestTools : ToolSet {
        @Tool("lookup")
        @LLMDescription("Return a synthetic result.")
        fun lookup(): String = "synthetic-tool-result"
    }

    private suspend fun withReceiver(status: Int = 200, block: suspend (Receiver) -> Unit) {
        val receiver = Receiver(status)
        try { block(receiver) } finally { receiver.server.stop(0) }
    }

    private class Receiver(status: Int) {
        val payloads = CopyOnWriteArrayList<String>()
        val authorisations = CopyOnWriteArrayList<String>()
        val ingestionVersions = CopyOnWriteArrayList<String>()
        val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/api/public/otel/v1/traces") { exchange ->
                payloads += exchange.requestBody.bufferedReader().use { it.readText() }
                authorisations += exchange.requestHeaders.getFirst("Authorization")
                ingestionVersions += exchange.requestHeaders.getFirst("x-langfuse-ingestion-version")
                val response = "{}".toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(status, response.size.toLong())
                exchange.responseBody.use { it.write(response) }
            }
            start()
        }

        fun tracing(captureContent: Boolean = true): LangfuseTracing {
            val config = LangfuseConfig.fromEnvironment(mapOf(
                "LANGFUSE_HOST" to "http://127.0.0.1:${server.address.port}",
                "LANGFUSE_PUBLIC_KEY" to "pk-test", "LANGFUSE_SECRET_KEY" to "sk-test",
                "LANGFUSE_CAPTURE_CONTENT" to captureContent.toString(),
            )::get)!!
            return LangfuseTracing(config, "test-run", "plum", "lean")
        }

        fun spans(): List<JsonObject> = payloads.flatMap { payload ->
            Json.parseToJsonElement(payload).jsonObject.getValue("resourceSpans").jsonArray.flatMap { resource ->
                resource.jsonObject.getValue("scopeSpans").jsonArray.flatMap { scope ->
                    scope.jsonObject.getValue("spans").jsonArray.map { it.jsonObject }
                }
            }
        }
    }

    private fun attributes(span: JsonObject): Map<String, String> =
        span.getValue("attributes").jsonArray.associate {
            val attribute = it.jsonObject
            attribute.getValue("key").jsonPrimitive.content to
                attribute.getValue("value").jsonObject.values.single().toString().trim('"')
        }
}
