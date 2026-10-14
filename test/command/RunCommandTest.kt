package command

import ai.koog.prompt.llm.LLMCapability
import com.github.ajalt.clikt.command.parse
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import run.JsonBenchmarkRunConfigReader

class RunCommandTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun rejectsAnUnimplementedBackendBeforeReadingApiKey() = runBlocking {
        Files.createDirectory(temporaryDirectory.resolve("dataset"))
        val command = RunCommand(projectRoot = temporaryDirectory,
            apiKeyProvider = { error("API key must not be read") })
        val failure = assertFailsWith<IllegalArgumentException> {
            command.parse(listOf(writeConfig(validConfig).toString(), "test-run", "--backend", "rocq"))
        }
        assertContains(failure.message.orEmpty(), "unsupported formalisation backend: rocq")
        assertFalse(Files.exists(temporaryDirectory.resolve("runs")))
    }

    @Test
    fun acceptsAnEditorSchemaReferenceWithoutChangingTheConfiguration() {
        val reader = JsonBenchmarkRunConfigReader()
        val original = reader.read(writeConfig(validConfig))
        val annotated = validConfig.replace("\"schema_version\": 1",
            "\"\$schema\": \"../schemas/run-config-v1.schema.json\", \"schema_version\": 1")
        assertEquals(original, reader.read(writeConfig(annotated)))
        for (invalid in listOf("null", "7", "\"\"", "\"  \"")) {
            assertFailsWith<IllegalArgumentException> {
                reader.read(writeConfig(validConfig.replace("\"schema_version\": 1",
                    "\"\$schema\": $invalid, \"schema_version\": 1")))
            }
        }
    }

    @Test
    fun readsExplicitModelAndRunLimits() {
        val config = JsonBenchmarkRunConfigReader().read(writeConfig(validConfig))
        val specification = config.toSpecification()

        assertEquals("test/model", specification.model.id)
        assertEquals(32_000, specification.model.contextLength)
        assertEquals(4_000, specification.model.maxOutputTokens)
        assertTrue(specification.model.supports(LLMCapability.Completion))
        assertEquals(false, specification.model.supports(LLMCapability.Tools))
        assertEquals(2, specification.samplesPerTask)
        assertEquals(4, specification.maxConcurrentTasks)
        assertEquals(1, specification.repairPolicy.maxRepairTurns)
        assertEquals(20, specification.maxAgentIterations)
        assertEquals(0, specification.maxToolCallsPerAttempt)
        assertEquals(0, specification.maxWorkspaceToolCallsPerAttempt)
        assertFalse(specification.workspaceToolsEnabled)
    }

    @Test
    fun readsAndValidatesTaskConcurrency() {
        val reader = JsonBenchmarkRunConfigReader()
        val source = validConfig.replace("\"samples_per_task\": 2,",
            "\"samples_per_task\": 2, \"max_concurrent_tasks\": 3,")
        assertEquals(3, reader.read(writeConfig(source)).toSpecification().maxConcurrentTasks)
        assertEquals(1, reader.read(writeConfig(source.replace("\"max_concurrent_tasks\": 3",
            "\"max_concurrent_tasks\": 1"))).toSpecification().maxConcurrentTasks)
        for (invalid in listOf("null", "0", "-1", "1.5", "\"2\"", "2147483648")) {
            assertFailsWith<IllegalArgumentException>("max_concurrent_tasks=$invalid") {
                reader.read(writeConfig(source.replace("\"max_concurrent_tasks\": 3", "\"max_concurrent_tasks\": $invalid")))
            }
        }
    }

    @Test
    fun includesCompletionWhenToolsAreEnabled() {
        val config = JsonBenchmarkRunConfigReader().read(
            writeConfig(validConfig.replace("\"supports_tools\": false", "\"supports_tools\": true")),
        )
        val model = config.toSpecification().model

        assertTrue(model.supports(LLMCapability.Completion))
        assertTrue(model.supports(LLMCapability.Tools))
    }

    @Test
    fun readsAndValidatesWorkspaceToolBudget() {
        val config = validConfig.replace("\"supports_tools\": false", "\"supports_tools\": true")
            .replace("\"max_agent_iterations\": 20", "\"max_agent_iterations\": 80, \"max_workspace_tool_calls_per_attempt\": 12")
        val specification = JsonBenchmarkRunConfigReader().read(writeConfig(config)).toSpecification()
        assertEquals(12, specification.maxWorkspaceToolCallsPerAttempt)
        assertEquals(0, specification.maxToolCallsPerAttempt)
        assertTrue(specification.workspaceToolsEnabled)
        assertFailsWith<IllegalArgumentException> {
            JsonBenchmarkRunConfigReader().read(writeConfig(config.replace("\"max_workspace_tool_calls_per_attempt\": 12", "\"max_workspace_tool_calls_per_attempt\": -1")))
        }
        assertFailsWith<IllegalArgumentException> {
            JsonBenchmarkRunConfigReader().read(writeConfig(config.replace("\"supports_tools\": true", "\"supports_tools\": false")))
        }
        assertFailsWith<IllegalArgumentException> {
            JsonBenchmarkRunConfigReader().read(writeConfig(config.replace("\"max_workspace_tool_calls_per_attempt\": 12", "\"max_workspace_tool_calls_per_attempt\": 1.5")))
        }
    }

    @Test
    fun nullLimitsAreUnlimitedAndEnableWorkspaceToolsIndependently() {
        val source = validConfig.replace("\"supports_tools\": false", "\"supports_tools\": true")
            .replace("\"context_length\": 32000", "\"context_length\": null")
            .replace("\"max_output_tokens\": 4000", "\"max_output_tokens\": null")
            .replace("\"max_repair_turns\": 1", "\"max_repair_turns\": null")
            .replace("\"max_agent_iterations\": 20", "\"max_agent_iterations\": null, \"max_tool_calls_per_attempt\": 0, \"max_workspace_tool_calls_per_attempt\": null")
        val config = JsonBenchmarkRunConfigReader().read(writeConfig(source))
        val specification = config.toSpecification()
        assertNull(specification.model.contextLength)
        assertNull(specification.model.maxOutputTokens)
        assertNull(specification.repairPolicy.maxRepairTurns)
        assertNull(specification.maxAgentIterations)
        assertNull(specification.maxWorkspaceToolCallsPerAttempt)
        assertEquals(0, specification.maxToolCallsPerAttempt)
        assertTrue(specification.workspaceToolsEnabled)

        val otherOnly = JsonBenchmarkRunConfigReader().read(writeConfig(source
            .replace("\"max_tool_calls_per_attempt\": 0", "\"max_tool_calls_per_attempt\": null")
            .replace("\"max_workspace_tool_calls_per_attempt\": null", "\"max_workspace_tool_calls_per_attempt\": 0"))).toSpecification()
        assertNull(otherOnly.maxToolCallsPerAttempt)
        assertFalse(otherOnly.workspaceToolsEnabled)
    }

    @Test
    fun rejectsInvalidNullableLimitsAndUnlimitedToolsWithoutToolSupport() {
        val source = validConfig.replace("\"max_agent_iterations\": 20",
            "\"max_agent_iterations\": 20, \"max_tool_calls_per_attempt\": 0, \"max_workspace_tool_calls_per_attempt\": 0")
        for (field in listOf("max_tool_calls_per_attempt", "max_workspace_tool_calls_per_attempt")) {
            for (invalid in listOf("null", "-1", "1.5", "\"null\"", "2147483648")) {
                assertFailsWith<IllegalArgumentException>("$field=$invalid") {
                    JsonBenchmarkRunConfigReader().read(writeConfig(source.replace("\"$field\": 0", "\"$field\": $invalid")))
                }
            }
        }
        for (field in listOf("schema_version", "samples_per_task")) {
            val old = if (field == "schema_version") 1 else 2
            assertFailsWith<IllegalArgumentException> {
                JsonBenchmarkRunConfigReader().read(writeConfig(source.replace("\"$field\": $old", "\"$field\": null")))
            }
        }
    }

    @Test
    fun rejectsUnknownConfigurationFields() {
        val path = writeConfig(
            validConfig.replace(
                "\"samples_per_task\": 2,",
                "\"samples_per_task\": 2, \"unknown\": true,",
            ),
        )

        val failure = assertFailsWith<IllegalArgumentException> {
            JsonBenchmarkRunConfigReader().read(path)
        }
        assertContains(failure.message.orEmpty(), "unknown fields")
    }

    @Test
    fun rejectsUnsupportedVersionAndInvalidLimits() {
        val reader = JsonBenchmarkRunConfigReader()
        val oldVersion = writeConfig(validConfig.replace("\"schema_version\": 1", "\"schema_version\": 2"))
        assertFailsWith<IllegalArgumentException> { reader.read(oldVersion) }

        val invalidLimit = writeConfig(validConfig.replace("\"max_repair_turns\": 1", "\"max_repair_turns\": -1"))
        assertFailsWith<IllegalArgumentException> { reader.read(invalidLimit) }
    }

    @Test
    fun rejectsNoFrozenTasksBeforeReadingApiKey() {
        runBlocking {
            Files.createDirectory(temporaryDirectory.resolve("dataset"))
            val configPath = writeConfig(validConfig)
            val command = RunCommand(
                projectRoot = temporaryDirectory,
                apiKeyProvider = { error("API key must not be read") },
            )

            val failure = assertFailsWith<IllegalStateException> {
                command.parse(listOf(configPath.toString(), "test-run"))
            }

            assertContains(failure.message.orEmpty(), "no frozen tasks are available for backend lean")
            assertFalse(Files.exists(temporaryDirectory.resolve("runs")))
        }
    }

    @Test
    fun honoursAnExplicitDatasetRootBeforeReadingApiKey() = runBlocking {
        val dataset = Files.createDirectory(temporaryDirectory.resolve("custom-dataset"))
        val configPath = writeConfig(validConfig)
        val command = RunCommand(projectRoot = temporaryDirectory,
            apiKeyProvider = { error("API key must not be read") })
        val failure = assertFailsWith<IllegalStateException> {
            command.parse(listOf(configPath.toString(), "test-run", "--dataset-root", dataset.toString()))
        }
        assertContains(failure.message.orEmpty(), "no frozen tasks are available for backend lean")
        assertFalse(Files.exists(temporaryDirectory.resolve("runs")))
    }

    @Test
    fun rejectsUnsafeRunIdentifierBeforeReadingConfiguration() {
        runBlocking {
            val configPath = writeConfig("not JSON")
            val command = RunCommand(
                projectRoot = temporaryDirectory,
                apiKeyProvider = { error("API key must not be read") },
            )

            val failure = assertFails {
                command.parse(listOf(configPath.toString(), "../other"))
            }
            assertContains(failure.message.orEmpty(), "run ID must use lower-case letters, digits, underscores or hyphens")
            assertFalse(Files.exists(temporaryDirectory.resolve("runs")))
        }
    }

    private fun writeConfig(contents: String): Path {
        val path = temporaryDirectory.resolve("run-config.json")
        Files.writeString(path, contents)
        return path
    }

    private val validConfig = """
        {
          "schema_version": 1,
          "model": {
            "id": "test/model",
            "context_length": 32000,
            "max_output_tokens": 4000,
            "supports_tools": false
          },
          "samples_per_task": 2,
          "max_repair_turns": 1,
          "max_agent_iterations": 20
        }
    """.trimIndent()
}
