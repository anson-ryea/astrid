package command

import ai.koog.prompt.llm.LLMCapability
import com.github.ajalt.clikt.command.parse
import com.github.ajalt.clikt.core.terminal
import com.github.ajalt.mordant.rendering.AnsiLevel
import com.github.ajalt.mordant.terminal.Terminal
import com.github.ajalt.mordant.terminal.TerminalRecorder
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
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
import support.SimpleTestDataset

class RunCommandTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun rejectsAnUnimplementedBackendBeforeReadingApiKey() = runBlocking {
        Files.createDirectory(temporaryDirectory.resolve("dataset"))
        val command = RunCommand(projectRoot = temporaryDirectory,
            apiKeyProvider = { error("API key must not be read") })
        val failure = assertFailsWith<IllegalArgumentException> {
            command.parse(listOf(writeConfig(validConfig).toString(), temporaryDirectory.toString(), "test-run", "--backend", "rocq"))
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
                command.parse(listOf(configPath.toString(), temporaryDirectory.toString(), "test-run"))
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
            command.parse(listOf(configPath.toString(), temporaryDirectory.toString(), "test-run", "--dataset-root", dataset.toString()))
        }
        assertContains(failure.message.orEmpty(), "no frozen tasks are available for backend lean")
        assertFalse(Files.exists(temporaryDirectory.resolve("runs")))
    }

    @Test
    fun defaultsToTheCheckoutDatasetAndGeneratesAnIdFromItsFolderName() = runBlocking {
        val checkout = Files.createDirectory(temporaryDirectory.resolve("Example Corpus.v1"))
        writeFrozenProofManifest(checkout)
        val expectedId = "example_corpus_v1_20261104_120506_123"
        for (root in listOf<String?>(null, "experiment-runs")) {
            val recorder = TerminalRecorder(ansiLevel = AnsiLevel.NONE, width = 512)
            val command = RunCommand(temporaryDirectory,
                apiKeyProvider = { error("API key must not be read") }, clock = fixedClock)
            command.configureContext { terminal = Terminal(terminalInterface = recorder) }
            val arguments = listOf(writeConfig(validConfig).toString(), checkout.toString(), "--ui=live") +
                (root?.let { listOf("--output-root", it) } ?: emptyList())

            assertFailsWith<java.nio.file.NoSuchFileException> { command.parse(arguments) }

            assertContains(recorder.output(), expectedId)
            assertContains(recorder.output(), checkout.resolve("dataset").toString())
            assertContains(recorder.output(), temporaryDirectory.resolve("${root ?: "runs"}/$expectedId").toString())
            assertFalse(Files.exists(temporaryDirectory.resolve(root ?: "runs")))
        }
    }

    @Test
    fun defaultsTheLeanEnvironmentToTheCheckoutAndHonoursAnOverride() = runBlocking {
        copyDirectory(SimpleTestDataset.projectRoot.resolve("prompts"), temporaryDirectory.resolve("prompts"))
        val checkout = copyCheckout(temporaryDirectory.resolve("synthetic"), "proof")
        val external = Files.createDirectory(temporaryDirectory.resolve("external-environment"))
        Files.writeString(external.resolve("lean-toolchain"), "test-toolchain\n")
        val config = validConfig.replace("\"supports_tools\": false", "\"supports_tools\": true")
            .replace("\"max_agent_iterations\": 20", "\"max_agent_iterations\": 20, \"max_workspace_tool_calls_per_attempt\": 1")

        for (environmentOverride in listOf<Path?>(null, external)) {
            val command = RunCommand(temporaryDirectory, apiKeyProvider = { error("API key must not be read") })
            val arguments = listOf(writeConfig(config).toString(), checkout.toString(), "test-run", "--ui=plain", "--validate-before-run") +
                (environmentOverride?.let { listOf("--environment-root", it.toString()) } ?: emptyList())

            val failure = assertFailsWith<IllegalArgumentException> { command.parse(arguments) }

            // Fail on a missing configuration file before compiling Lean or making model calls.
            val missing = if (environmentOverride == null) checkout.resolve("lean-toolchain") else external.resolve("lakefile.toml")
            assertContains(failure.message.orEmpty(), missing.parent.toRealPath().resolve(missing.fileName).toString())
            assertFalse(Files.exists(temporaryDirectory.resolve("runs")))
        }
    }

    @Test
    fun defaultsPrivateContractsToTheCheckoutAndHonoursAnOverride() = runBlocking {
        copyDirectory(SimpleTestDataset.projectRoot.resolve("prompts"), temporaryDirectory.resolve("prompts"))
        for (useOverride in listOf(false, true)) {
            val checkout = copyCheckout(temporaryDirectory.resolve("corpus-$useOverride"), "definition")
            SimpleTestDataset.mockLeanEnvironment(checkout)
            val evaluation = if (useOverride) temporaryDirectory.resolve("external-contracts")
                else checkout.resolve("private/evaluation")
            val contract = evaluation.resolve(SimpleTestDataset.task("definition").manifest.evaluation.contract!!.path.value)
            Files.createDirectories(contract.parent)
            Files.writeString(contract, "{}")
            val command = RunCommand(temporaryDirectory, apiKeyProvider = { error("API key must not be read") })
            val arguments = listOf(writeConfig(validConfig).toString(), checkout.toString(), "test-run", "--ui=plain", "--validate-before-run") +
                if (useOverride) listOf("--evaluation-root", evaluation.toString()) else emptyList()

            val failure = assertFailsWith<IllegalArgumentException> { command.parse(arguments) }

            // Finding the deliberately corrupted contract proves which root was selected.
            // Its hash fails before any native Lean process can start.
            assertContains(failure.message.orEmpty(), "evaluation contract hash mismatch")
            assertFalse(Files.exists(temporaryDirectory.resolve("runs")))
        }
    }

    @Test
    fun skipsDatasetValidationUnlessRequested() = runBlocking {
        copyDirectory(SimpleTestDataset.projectRoot.resolve("prompts"), temporaryDirectory.resolve("prompts"))
        for (result in listOf("proof", "definition")) {
            val checkout = copyCheckout(temporaryDirectory.resolve("corpus-$result"), result)
            val source = SimpleTestDataset.task(result)
            val evidence = checkout.resolve("dataset/paper_1/tasks/${source.manifest.taskId}/evidence.md")
            Files.writeString(evidence, "changed after hashing")
            for (validate in listOf(false, true)) {
                var apiKeyRead = false
                val command = RunCommand(temporaryDirectory, apiKeyProvider = {
                    apiKeyRead = true
                    error("reached model setup")
                })
                val arguments = listOf(writeConfig(validConfig).toString(), checkout.toString(), "test-run", "--ui=plain") +
                    if (validate) listOf("--validate-before-run") else emptyList()

                val failure = assertFailsWith<IllegalStateException> { command.parse(arguments) }

                assertEquals(!validate, apiKeyRead)
                assertContains(failure.message.orEmpty(), if (validate) "task artefact hash mismatch" else "reached model setup")
                assertFalse(Files.exists(temporaryDirectory.resolve("runs")))
            }
        }
    }

    @Test
    fun rejectsInvalidPlainRunIdentifierBeforeReadingConfiguration() {
        runBlocking {
            val configPath = writeConfig("not JSON")
            val command = RunCommand(
                projectRoot = temporaryDirectory,
                apiKeyProvider = { error("API key must not be read") },
            )

            val failure = assertFails {
                command.parse(listOf(configPath.toString(), temporaryDirectory.toString(), "Invalid Run ID"))
            }
            assertContains(failure.message.orEmpty(), "run ID must use lower-case letters, digits, underscores or hyphens")
            assertFalse(Files.exists(temporaryDirectory.resolve("runs")))
        }
    }

    @Test
    fun activatesTheDashboardBeforePreflightAndCleansUpOnPreflightFailure() = runBlocking {
        writeFrozenProofManifest()
        val recorder = TerminalRecorder(ansiLevel = AnsiLevel.NONE, width = 512)
        val command = RunCommand(temporaryDirectory, apiKeyProvider = { error("API key must not be read") })
        command.configureContext { terminal = Terminal(terminalInterface = recorder) }

        assertFailsWith<java.nio.file.NoSuchFileException> {
            // Missing prompt files fail preflight, before any credentials or model requests.
            command.parse(listOf(writeConfig(validConfig).toString(), temporaryDirectory.toString(), "test-run", "--ui=live"))
        }
        assertContains(recorder.output(), "PREPARING")
        assertContains(recorder.output(), "FAILED")
        assertContains(recorder.output(), temporaryDirectory.resolve("runs/test-run").toString())
        assertTrue(recorder.output().endsWith("\u001b[?25h"))
        assertFalse(Files.exists(temporaryDirectory.resolve("runs")))
    }

    @Test
    fun storesNamedRunsUnderAnAbsoluteOrRelativeOutputRoot() = runBlocking {
        writeFrozenProofManifest()
        for (root in listOf(temporaryDirectory.resolve("absolute-root").toString(), "relative-root")) {
            val expected = temporaryDirectory.resolve(root).resolve("test-run").normalize()
            val recorder = TerminalRecorder(ansiLevel = AnsiLevel.NONE, width = 512)
            val command = RunCommand(temporaryDirectory, apiKeyProvider = { error("API key must not be read") })
            command.configureContext { terminal = Terminal(terminalInterface = recorder) }

            assertFailsWith<java.nio.file.NoSuchFileException> {
                command.parse(listOf(writeConfig(validConfig).toString(), temporaryDirectory.toString(), "test-run",
                    "--output-root", root, "--ui=live"))
            }

            assertContains(recorder.output(), expected.toString())
            assertFalse(Files.exists(expected.parent), "preflight failures must not create output parents")
            assertFalse(Files.exists(temporaryDirectory.resolve("runs")))
        }
    }

    @Test
    fun runPathsBypassTheOutputRootAndRelativePathsUseTheWorkingDirectory() = runBlocking {
        val working = Files.createDirectory(temporaryDirectory.resolve("working"))
        writeFrozenProofManifest(working)
        val ignoredRoot = Files.createDirectory(working.resolve("ignored-root"))
        val sentinel = ignoredRoot.resolve("keep.txt")
        Files.writeString(sentinel, "other runs")
        for (output in listOf(temporaryDirectory.resolve("absolute/output").toString(),
            "relative/Run A.v1", "./direct-run", "../sibling-run")) {
            val expected = working.resolve(output).normalize()
            val recorder = TerminalRecorder(ansiLevel = AnsiLevel.NONE, width = 512)
            val command = RunCommand(working, apiKeyProvider = { error("API key must not be read") })
            command.configureContext { terminal = Terminal(terminalInterface = recorder) }

            assertFailsWith<java.nio.file.NoSuchFileException> {
                command.parse(listOf(writeConfig(validConfig).toString(), working.toString(), output,
                    "--output-root", ignoredRoot.toString(), "--ui=live"))
            }

            assertContains(recorder.output(), expected.toString())
            assertFalse(ignoredRoot.resolve(expected.fileName).toString() in recorder.output())
            assertFalse(Files.exists(expected), "preflight failures must not create run directories")
            assertEquals("other runs", Files.readString(sentinel))
            assertFalse(Files.exists(working.resolve("runs")))
        }
    }

    @Test
    fun refusesExistingNamedAndPathOutputsWithoutChangingTheirContents() = runBlocking {
        writeFrozenProofManifest()
        val output = Files.createDirectories(temporaryDirectory.resolve("existing-output/test-run"))
        val saved = output.resolve("keep.txt")
        Files.writeString(saved, "previous results")
        for (destination in listOf(listOf(output.toString()),
            listOf("test-run", "--output-root", output.parent.toString()))) {
            val command = RunCommand(temporaryDirectory, apiKeyProvider = { error("API key must not be read") })

            val failure = assertFailsWith<IllegalStateException> {
                command.parse(listOf(writeConfig(validConfig).toString(), temporaryDirectory.toString()) + destination)
            }

            assertContains(failure.message.orEmpty(), "run directory already exists: $output")
            assertEquals("previous results", Files.readString(saved))
            assertFalse(Files.exists(temporaryDirectory.resolve("runs")))
        }
    }

    @Test
    fun rejectsSymbolicLinkOutputParentsBeforePreflight() = runBlocking {
        writeFrozenProofManifest()
        val realParent = Files.createDirectory(temporaryDirectory.resolve("real-output"))
        val linkedParent = temporaryDirectory.resolve("linked-output")
        Files.createSymbolicLink(linkedParent, realParent)
        for (destination in listOf(listOf(linkedParent.resolve("run").toString()),
            listOf("run", "--output-root", linkedParent.toString()))) {
            val command = RunCommand(temporaryDirectory, apiKeyProvider = { error("API key must not be read") })

            val failure = assertFailsWith<IllegalStateException> {
                command.parse(listOf(writeConfig(validConfig).toString(), temporaryDirectory.toString()) + destination)
            }

            assertContains(failure.message.orEmpty(), "run directory parent must not be a symbolic link")
            assertFalse(Files.exists(realParent.resolve("run")))
        }
    }

    @Test
    fun validatesUiModeBeforeExecution() = runBlocking {
        val command = RunCommand(temporaryDirectory, apiKeyProvider = { error("API key must not be read") })
        val failure = assertFails {
            command.parse(listOf(writeConfig("not JSON").toString(), temporaryDirectory.toString(), "test-run", "--ui=invalid"))
        }
        assertContains(failure.message.orEmpty(), "invalid")
        assertContains(failure.message.orEmpty(), "live")
    }

    private fun writeFrozenProofManifest(checkout: Path = temporaryDirectory) {
        val source = SimpleTestDataset.task("proof")
        val taskDirectory = checkout.resolve("dataset/paper_1/tasks/${source.manifest.taskId}")
        Files.createDirectories(taskDirectory)
        Files.writeString(taskDirectory.resolve("manifest.json"),
            Files.readString(source.packageDirectory.resolve("manifest.json"))
                .replace("candidate_pending_independent_review", "frozen"))
    }

    private fun copyCheckout(directory: Path, result: String): Path {
        copyDirectory(SimpleTestDataset.datasetRoot, directory.resolve("dataset"))
        val task = SimpleTestDataset.task(result)
        val manifest = directory.resolve("dataset/paper_1/tasks/${task.manifest.taskId}/manifest.json")
        Files.writeString(manifest, Files.readString(manifest).replace("candidate_pending_independent_review", "frozen"))
        return directory.toRealPath()
    }

    private fun copyDirectory(source: Path, destination: Path) {
        Files.walk(source).use { paths ->
            paths.forEach { path ->
                val target = destination.resolve(source.relativize(path))
                if (Files.isDirectory(path)) Files.createDirectories(target) else Files.copy(path, target)
            }
        }
    }

    private fun writeConfig(contents: String): Path {
        val path = temporaryDirectory.resolve("run-config.json")
        Files.writeString(path, contents)
        return path
    }

    private val fixedClock = Clock.fixed(Instant.parse("2026-11-04T04:05:06.123Z"), ZoneId.of("Asia/Hong_Kong"))

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
