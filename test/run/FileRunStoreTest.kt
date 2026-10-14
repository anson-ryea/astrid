package run

import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import domain.EvaluationResult
import domain.TaskId
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.io.TempDir
import workspace.TaskWorkspace
import workspace.WorkspaceRecord

class FileRunStoreTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    private val taskId = TaskId("task_1.1_theorem_11")

    @Test
    fun persistsSemanticEvidenceInVersionThreeWithoutPrivateSources() = runBlocking {
        val directory = temporaryDirectory.resolve("semantic")
        val store = FileRunStore(directory)
        store.begin(specification(), listOf(taskId))
        val base = record()
        val evidence = domain.EvaluationEvidence("a".repeat(64), mapOf("Specification.lean" to "b".repeat(64)), "passed",
            mapOf("Benchmark.target" to setOf("propext")),
            listOf(domain.CheckEvidence("AstridSpec.agreement", "unresolved", "proved")),
            listOf(domain.CheckEvidence("AstridSpec.compatibility", "proved")))
        store.save(base.copy(turns = listOf(base.turns.single().copy(evaluationEvidence = evidence))))
        store.complete()
        val sample = readJson(directory.resolve("tasks/${taskId.value}/samples/001/record.json"))
        assertEquals("3", sample.getValue("schema_version").jsonPrimitive.content)
        val saved = sample.getValue("turns").jsonArray.single().jsonObject.getValue("evaluation_evidence").jsonObject
        assertEquals("a".repeat(64), saved.getValue("contract_sha256").jsonPrimitive.content)
        assertEquals("proved", saved.getValue("obligations").jsonArray.single().jsonObject.getValue("refutation").jsonPrimitive.content)
        assertFalse("private_source" in saved)
    }

    @Test
    fun persistsSeparateUnlimitedLimitsAsJsonNull() = runBlocking {
        val runDirectory = temporaryDirectory.resolve("unlimited-run")
        val store = FileRunStore(runDirectory)
        val specification = specification().let {
            it.copy(model = it.model.copy(capabilities = listOf(ai.koog.prompt.llm.LLMCapability.Tools)),
                repairPolicy = RepairPolicy(null), maxAgentIterations = null,
                maxToolCallsPerAttempt = null, maxWorkspaceToolCallsPerAttempt = null)
        }
        store.begin(specification, listOf(taskId))
        for (field in listOf("max_repair_turns", "max_agent_iterations", "max_tool_calls_per_attempt",
            "max_workspace_tool_calls_per_attempt")) {
            assertEquals(kotlinx.serialization.json.JsonNull, readJson(runDirectory.resolve("run.json")).getValue(field))
        }
        store.save(record())
        store.complete()
        assertEquals(kotlinx.serialization.json.JsonNull,
            readJson(runDirectory.resolve("run.json")).getValue("max_workspace_tool_calls_per_attempt"))
    }

    @Test
    fun persistsWorkspaceInputsAndEverySubmissionSnapshot() = runBlocking {
        val runDirectory = temporaryDirectory.resolve("workspace-run")
        val store = FileRunStore(runDirectory)
        store.begin(specification(), listOf(taskId))
        val input = TaskWorkspace.snapshotFile("tasks/${taskId.value}/Target.lean", "theorem target : True := __BENCHMARK_PROOF__")
        val rejected = TaskWorkspace.snapshotFile("Solution.lean", "theorem target : True := by fail")
        val submitted = TaskWorkspace.snapshotFile("Solution.lean", "theorem target : True := by trivial")
        val scratch = TaskWorkspace.snapshotFile("scratch/notes.lean", "-- Unicode notes: ∀ x, x = x")
        val base = record()
        store.save(base.copy(
            workspace = WorkspaceRecord("theorem_11", "a".repeat(64), listOf(input), listOf(submitted, scratch)),
            turns = listOf(
                base.turns.single().copy(result = EvaluationResult.Rejected("invalid proof"), workspaceFiles = listOf(rejected)),
                base.turns.single().copy(repairTurn = 1, workspaceFiles = listOf(submitted)),
            ),
        ))
        store.complete()
        val sampleDirectory = runDirectory.resolve("tasks/${taskId.value}/samples/001")
        val sample = readJson(sampleDirectory.resolve("record.json"))
        val workspace = sample.getValue("workspace").jsonObject
        assertEquals("theorem_11", workspace.getValue("context_id").jsonPrimitive.content)
        val savedInput = workspace.getValue("inputs").jsonArray.single().jsonObject
        assertEquals(input.content, savedInput.getValue("content").jsonPrimitive.content)
        assertEquals(input.sha256, savedInput.getValue("sha256").jsonPrimitive.content)
        val savedTurns = sample.getValue("turns").jsonArray
        assertEquals(rejected.content, savedTurns.first().jsonObject.getValue("workspace_files")
            .jsonArray.single().jsonObject.getValue("content").jsonPrimitive.content)
        val savedSubmission = savedTurns.last().jsonObject
            .getValue("workspace_files").jsonArray.single().jsonObject
        assertEquals(submitted.content, savedSubmission.getValue("content").jsonPrimitive.content)
        assertEquals(submitted.sha256, savedSubmission.getValue("sha256").jsonPrimitive.content)
        assertEquals("Solution.lean", workspace.getValue("final_files").jsonArray.first().jsonObject.getValue("path").jsonPrimitive.content)
        val outputDirectory = sampleDirectory.resolve(workspace.getValue("output_directory").jsonPrimitive.content)
        for (file in listOf(submitted, scratch)) {
            val savedContent = Files.readString(outputDirectory.resolve(file.path))
            assertEquals(file.content, savedContent)
            assertEquals(file.sha256, TaskWorkspace.snapshotFile(file.path, savedContent).sha256)
        }
        assertFalse(Files.exists(outputDirectory.resolve(input.path)))
    }

    @Test
    fun savesPromptsTurnsAndCompletedRun() = runBlocking {
        val runDirectory = temporaryDirectory.resolve("run-001")
        val store = FileRunStore(runDirectory)
        store.begin(specification(), listOf(taskId))
        store.save(record())
        store.complete()

        val run = readJson(runDirectory.resolve("run.json"))
        assertEquals("complete", run["status"]?.jsonPrimitive?.content)
        assertEquals("4", run["max_concurrent_tasks"]?.jsonPrimitive?.content)
        assertEquals(taskId.value, run["task_ids"]?.jsonArray?.single()?.jsonPrimitive?.content)

        val sample = readJson(
            runDirectory.resolve("tasks/${taskId.value}/samples/001/record.json"),
        )
        assertEquals("system instructions", sample["system_prompt"]?.jsonPrimitive?.content)
        assertEquals("task instructions", sample["task_prompt"]?.jsonPrimitive?.content)
        assertEquals(1, sample["turns"]?.jsonArray?.size)
        assertEquals("3", sample["schema_version"]?.jsonPrimitive?.content)
        assertEquals("trajectory.json", sample["trajectory_file"]?.jsonPrimitive?.content)
        assertFalse("trajectory" in sample)
        assertFalse(Files.exists(runDirectory.resolve("tasks/${taskId.value}/samples/001/output")))
        val trajectory = readJson(runDirectory.resolve("tasks/${taskId.value}/samples/001/trajectory.json"))
        assertEquals("1", trajectory["schema_version"]?.jsonPrimitive?.content)
        assertEquals(taskId.value, trajectory["task_id"]?.jsonPrimitive?.content)
        assertEquals("1", trajectory["sample_index"]?.jsonPrimitive?.content)
        assertEquals(
            "LLMCallStartingEvent",
            trajectory["events"]?.jsonArray?.single()?.jsonObject
                ?.get("kind")?.jsonPrimitive?.content,
        )
        assertEquals(
            "test-run",
            trajectory["events"]?.jsonArray?.single()?.jsonObject
                ?.get("details")?.jsonObject?.get("runId")?.jsonPrimitive?.content,
        )
        assertEquals(
            "accepted",
            sample["final_result"]?.jsonObject?.get("kind")?.jsonPrimitive?.content,
        )
    }

    @Test
    fun doesNotCompleteWithMissingSamples() = runBlocking {
        val runDirectory = temporaryDirectory.resolve("run-002")
        val store = FileRunStore(runDirectory)
        store.begin(specification(samplesPerTask = 2), listOf(taskId))
        store.save(record())

        assertFailsWith<IllegalStateException> { store.complete() }
        assertEquals(
            "running",
            readJson(runDirectory.resolve("run.json"))["status"]?.jsonPrimitive?.content,
        )
    }

    @Test
    fun marksIncompleteRunFailedAndKeepsSavedSamples(): Unit = runBlocking {
        val runDirectory = temporaryDirectory.resolve("failed-run")
        val store = FileRunStore(runDirectory)
        store.begin(specification(samplesPerTask = 2), listOf(taskId))
        store.save(record())
        store.fail("model response was truncated")

        val run = readJson(runDirectory.resolve("run.json"))
        assertEquals("failed", run["status"]?.jsonPrimitive?.content)
        assertEquals(
            "model response was truncated",
            run["failure_reason"]?.jsonPrimitive?.content,
        )
        val sample = readJson(
            runDirectory.resolve("tasks/${taskId.value}/samples/001/record.json"),
        )
        assertEquals("accepted", sample["final_result"]?.jsonObject
            ?.get("kind")?.jsonPrimitive?.content)

        assertFailsWith<IllegalStateException> { store.complete() }
        assertFailsWith<IllegalStateException> {
            store.save(record().copy(sampleIndex = 2))
        }
    }

    @Test
    fun rejectsDuplicateSamples() {
        runBlocking {
            val store = FileRunStore(temporaryDirectory.resolve("run-003"))
            store.begin(specification(), listOf(taskId))
            store.save(record())

            assertFailsWith<IllegalStateException> { store.save(record()) }
        }
    }

    @Test
    fun savesToolEnabledRunWithTrajectory() {
        runBlocking {
            val store = FileRunStore(temporaryDirectory.resolve("run-004"))
            val toolsModel = LLModel(
                provider = LLMProvider.OpenRouter,
                id = "test-model",
                capabilities = listOf(ai.koog.prompt.llm.LLMCapability.Tools),
            )
            val specification = specification(
                model = toolsModel,
                maxToolCallsPerAttempt = 1,
            )

            store.begin(specification, listOf(taskId))
            store.save(record().copy(trajectory = record().trajectory + AgentTrajectoryEvent(
                kind = "ToolCallCompletedEvent",
                timestampEpochMillis = 124,
                details = buildJsonObject {
                    put("toolName", JsonPrimitive("lookup"))
                    put("result", JsonPrimitive("ok"))
                },
            )))
            store.complete()

            val trajectory = readJson(
                temporaryDirectory.resolve("run-004/tasks/${taskId.value}/samples/001/trajectory.json"),
            )
            val events = trajectory.getValue("events").jsonArray
            assertEquals(listOf("LLMCallStartingEvent", "ToolCallCompletedEvent"),
                events.map { it.jsonObject.getValue("kind").jsonPrimitive.content })
            assertEquals(listOf("123", "124"),
                events.map { it.jsonObject.getValue("timestamp_epoch_ms").jsonPrimitive.content })
            assertEquals("ok", events.last().jsonObject.getValue("details").jsonObject.getValue("result").jsonPrimitive.content)
        }
    }

    @Test
    fun persistsAnEmptyTrajectoryForEveryIndependentSample() = runBlocking {
        val runDirectory = temporaryDirectory.resolve("empty-trajectories")
        val store = FileRunStore(runDirectory)
        store.begin(specification(samplesPerTask = 2), listOf(taskId))
        for (index in 1..2) store.save(record().copy(
            sampleIndex = index,
            trajectory = emptyList(),
            workspace = WorkspaceRecord("theorem_11", "a".repeat(64), emptyList(),
                listOf(TaskWorkspace.snapshotFile("Solution.lean", "-- sample $index"))),
        ))
        store.complete()
        for (index in 1..2) {
            val sampleDirectory = runDirectory.resolve("tasks/${taskId.value}/samples/${index.toString().padStart(3, '0')}")
            val sample = readJson(sampleDirectory.resolve("record.json"))
            val trajectory = readJson(sampleDirectory.resolve(sample.getValue("trajectory_file").jsonPrimitive.content))
            assertFalse("trajectory" in sample)
            assertEquals(index.toString(), trajectory.getValue("sample_index").jsonPrimitive.content)
            assertEquals(0, trajectory.getValue("events").jsonArray.size)
            assertEquals("-- sample $index", Files.readString(sampleDirectory.resolve("output/Solution.lean")))
        }
    }

    @Test
    fun persistsFailedSampleTrajectoryWithoutCompletedTurns() = runBlocking {
        val runDirectory = temporaryDirectory.resolve("failed-sample")
        val store = FileRunStore(runDirectory)
        val failure = EvaluationResult.InfrastructureFailure("tool call limit exceeded")
        val event = AgentTrajectoryEvent("AgentExecutionFailedEvent", 125,
            buildJsonObject { put("message", JsonPrimitive(failure.reason)) })
        val finalFile = TaskWorkspace.snapshotFile("Solution.lean", "theorem target : True := by fail")
        store.begin(specification(), listOf(taskId))
        store.save(record().copy(
            turns = emptyList(), finalResult = failure, trajectory = listOf(event),
            workspace = WorkspaceRecord("theorem_11", "a".repeat(64), emptyList(), listOf(finalFile)),
        ))
        store.fail(failure.reason)
        val sampleDirectory = runDirectory.resolve("tasks/${taskId.value}/samples/001")
        val sample = readJson(sampleDirectory.resolve("record.json"))
        assertEquals(0, sample.getValue("turns").jsonArray.size)
        assertEquals("infrastructure_failure", sample.getValue("final_result").jsonObject.getValue("kind").jsonPrimitive.content)
        val trajectory = readJson(sampleDirectory.resolve(sample.getValue("trajectory_file").jsonPrimitive.content))
        val savedEvent = trajectory.getValue("events").jsonArray.single().jsonObject
        assertEquals(event.kind, savedEvent.getValue("kind").jsonPrimitive.content)
        assertEquals(event.details, savedEvent.getValue("details"))
        assertEquals(finalFile.content, Files.readString(sampleDirectory.resolve("output/Solution.lean")))
    }

    @Test
    fun doesNotOverwriteEitherSampleFileOrCountAFailedSave(): Unit = runBlocking {
        for (existingFile in listOf("record.json", "trajectory.json")) {
            val runDirectory = temporaryDirectory.resolve("existing-$existingFile")
            val store = FileRunStore(runDirectory)
            store.begin(specification(), listOf(taskId))
            val sampleDirectory = runDirectory.resolve("tasks/${taskId.value}/samples/001")
            Files.createDirectories(sampleDirectory)
            Files.writeString(sampleDirectory.resolve(existingFile), "existing data")
            assertFailsWith<IllegalStateException> { store.save(record()) }
            assertEquals("existing data", Files.readString(sampleDirectory.resolve(existingFile)))
            val otherFile = if (existingFile == "record.json") "trajectory.json" else "record.json"
            assertFalse(Files.exists(sampleDirectory.resolve(otherFile)))
            assertFailsWith<IllegalStateException> { store.complete() }
        }
    }

    @Test
    fun doesNotOverwriteExistingOutputsOrPublishAFailedSave(): Unit = runBlocking {
        val runDirectory = temporaryDirectory.resolve("existing-output")
        val store = FileRunStore(runDirectory)
        store.begin(specification(), listOf(taskId))
        val sampleDirectory = runDirectory.resolve("tasks/${taskId.value}/samples/001")
        val outputDirectory = sampleDirectory.resolve("output")
        Files.createDirectories(outputDirectory)
        Files.writeString(outputDirectory.resolve("Solution.lean"), "existing solution")
        val finalFile = TaskWorkspace.snapshotFile("Solution.lean", "replacement")

        assertFailsWith<IllegalStateException> {
            store.save(record().copy(workspace = WorkspaceRecord(
                "theorem_11", "a".repeat(64), emptyList(), listOf(finalFile),
            )))
        }
        assertEquals("existing solution", Files.readString(outputDirectory.resolve("Solution.lean")))
        assertFalse(Files.exists(sampleDirectory.resolve("record.json")))
        assertFalse(Files.exists(sampleDirectory.resolve("trajectory.json")))
        assertFailsWith<IllegalStateException> { store.complete() }
    }

    @Test
    fun rejectsOutputPathsThatEscapeTheOutputDirectory(): Unit = runBlocking {
        val runDirectory = temporaryDirectory.resolve("unsafe-output")
        val store = FileRunStore(runDirectory)
        store.begin(specification(), listOf(taskId))
        for (path in listOf("", "../record.json", "nested/../../Solution.lean", "/Solution.lean")) {
            assertFailsWith<IllegalArgumentException> {
                store.save(record().copy(workspace = WorkspaceRecord(
                    "theorem_11", "a".repeat(64), emptyList(),
                    listOf(TaskWorkspace.snapshotFile(path, "invalid output")),
                )))
            }
            assertFalse(Files.exists(runDirectory.resolve("tasks")))
        }
        assertFailsWith<IllegalStateException> { store.complete() }
    }

    @Test
    fun doesNotOverwriteAnExistingRunDirectory() {
        runBlocking {
            val runDirectory = temporaryDirectory.resolve("existing-run")
            Files.createDirectory(runDirectory)
            Files.writeString(runDirectory.resolve("keep.txt"), "existing data")

            assertFailsWith<FileAlreadyExistsException> {
                FileRunStore(runDirectory).begin(specification(), listOf(taskId))
            }

            assertEquals("existing data", Files.readString(runDirectory.resolve("keep.txt")))
            assertEquals(false, Files.exists(runDirectory.resolve("run.json")))
        }
    }

    @Test
    fun rejectsUnselectedTaskAndOutOfRangeSample() {
        runBlocking {
            val runDirectory = temporaryDirectory.resolve("run-005")
            val store = FileRunStore(runDirectory)
            store.begin(specification(), listOf(taskId))

            assertFailsWith<IllegalStateException> {
                store.save(record().copy(taskId = TaskId("task_1.2_theorem_11")))
            }
            assertFailsWith<IllegalStateException> {
                store.save(record().copy(sampleIndex = 2))
            }

            assertEquals(false, Files.exists(runDirectory.resolve("tasks")))
        }
    }

    private fun specification(
        model: LLModel = LLModel(LLMProvider.OpenRouter, "test-model"),
        samplesPerTask: Int = 1,
        maxToolCallsPerAttempt: Int = 0,
    ) = BenchmarkRunSpecification(
        model = model,
        samplesPerTask = samplesPerTask,
        repairPolicy = RepairPolicy(1),
        maxToolCallsPerAttempt = maxToolCallsPerAttempt,
        maxAgentIterations = 4,
    )

    private fun record() = TaskRunRecord(
        taskId = taskId,
        sampleIndex = 1,
        systemPrompt = "system instructions",
        taskPrompt = "task instructions",
        turns = listOf(
            TaskRunRecord.Turn(
                repairTurn = 0,
                userMessage = "task instructions",
                response = "by trivial",
                submission = "by trivial",
                result = EvaluationResult.Accepted,
            ),
        ),
        finalResult = EvaluationResult.Accepted,
        trajectory = listOf(
            AgentTrajectoryEvent(
                kind = "LLMCallStartingEvent",
                timestampEpochMillis = 123,
                details = buildJsonObject { put("runId", JsonPrimitive("test-run")) },
            ),
        ),
    )

    private fun readJson(path: Path) =
        Json.parseToJsonElement(Files.readString(path)).jsonObject
}
