package run

import domain.EvaluationResult
import domain.TaskId
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import workspace.WorkspaceFile

/** Saves one run and its samples in a new directory. */
class FileRunStore(runDirectory: Path) : RunStore {
    private val runDirectory = runDirectory.toAbsolutePath().normalize()
    private val json = Json { prettyPrint = true }

    private var activeSpecification: BenchmarkRunSpecification? = null
    private var selectedTaskIds: List<TaskId> = emptyList()
    private val savedSamples = mutableSetOf<Pair<TaskId, Int>>()
    private var finished = false

    override suspend fun begin(
        specification: BenchmarkRunSpecification,
        taskIds: List<TaskId>,
    ) {
        check(activeSpecification == null) { "run has already begun" }
        require(taskIds.isNotEmpty()) { "run must select at least one task" }
        require(taskIds.distinct().size == taskIds.size) {
            "selected task IDs must be unique"
        }
        val parent = requireNotNull(runDirectory.parent) {
            "run directory must have a parent"
        }
        check(Files.isDirectory(parent)) {
            "run directory parent does not exist: $parent"
        }

        // createDirectory fails if the run ID has already been used.
        withContext(Dispatchers.IO) {
            Files.createDirectory(runDirectory)
        }
        writeAtomically(
            target = runDirectory.resolve("run.json"),
            value = runJson(specification, taskIds, "running"),
            replace = false,
        )

        activeSpecification = specification
        selectedTaskIds = taskIds.toList()
    }

    override suspend fun save(record: TaskRunRecord) {
        val specification = checkNotNull(activeSpecification) {
            "run has not begun"
        }
        check(!finished) { "run has already finished" }
        check(record.taskId in selectedTaskIds) {
            "task was not selected: ${record.taskId}"
        }
        check(record.sampleIndex in 1..specification.samplesPerTask) {
            "sample index is outside the configured range"
        }

        val key = record.taskId to record.sampleIndex
        check(key !in savedSamples) {
            "sample has already been saved: $key"
        }

        val sampleDirectory = runDirectory
            .resolve("tasks")
            .resolve(record.taskId.value)
            .resolve("samples")
            .resolve(record.sampleIndex.toString().padStart(3, '0'))
        val outputDirectory = sampleDirectory.resolve("output")
        val outputFiles = record.workspace?.finalFiles.orEmpty().map { file ->
            val relativePath = Path.of(file.path)
            require(file.path.isNotBlank() && !relativePath.isAbsolute &&
                relativePath.none { it.toString() == "." || it.toString() == ".." }) {
                "output file must have a safe relative path: ${file.path}"
            }
            outputDirectory.resolve(relativePath) to file.content
        }
        require(outputFiles.map { it.first }.distinct().size == outputFiles.size) {
            "output file paths must be unique"
        }

        withContext(Dispatchers.IO) {
            Files.createDirectories(sampleDirectory)
            val recordFile = sampleDirectory.resolve("record.json")
            val trajectoryFile = sampleDirectory.resolve("trajectory.json")
            // Check all destinations before writing any artefacts.
            val destinations = listOf(recordFile, trajectoryFile) +
                if (record.workspace != null) listOf(outputDirectory) else emptyList()
            for (target in destinations) {
                check(Files.notExists(target)) { "run file already exists: $target" }
            }
            if (record.workspace != null) {
                Files.createDirectory(outputDirectory)
                for ((target, content) in outputFiles) {
                    Files.createDirectories(target.parent)
                    writeAtomically(target, content, replace = false)
                }
            }
            writeAtomically(
                target = trajectoryFile,
                value = trajectoryJson(record),
                replace = false,
            )
            // Publish the sample summary only after its outputs and trace exist.
            writeAtomically(
                target = recordFile,
                value = sampleJson(record),
                replace = false,
            )
        }
        savedSamples += key
    }

    override suspend fun complete() {
        val specification = checkNotNull(activeSpecification) {
            "run has not begun"
        }
        check(!finished) { "run has already finished" }

        val expected =
            selectedTaskIds.size.toLong() * specification.samplesPerTask
        check(savedSamples.size.toLong() == expected) {
            "run has ${savedSamples.size} of $expected samples"
        }

        writeAtomically(
            target = runDirectory.resolve("run.json"),
            value = runJson(specification, selectedTaskIds, "complete"),
            replace = true,
        )
        finished = true
    }

    override suspend fun fail(reason: String) {
        require(reason.isNotBlank()) { "failure reason must not be blank" }
        val specification = checkNotNull(activeSpecification) {
            "run has not begun"
        }
        check(!finished) { "run has already finished" }

        writeAtomically(
            target = runDirectory.resolve("run.json"),
            value = runJson(specification, selectedTaskIds, "failed", reason),
            replace = true,
        )
        finished = true
    }

    private fun runJson(
        specification: BenchmarkRunSpecification,
        taskIds: List<TaskId>,
        status: String,
        failureReason: String? = null,
    ): JsonObject = buildJsonObject {
        put("schema_version", JsonPrimitive(1))
        put("status", JsonPrimitive(status))
        failureReason?.let { put("failure_reason", JsonPrimitive(it)) }
        put("model", json.encodeToJsonElement(specification.model))
        put("samples_per_task", JsonPrimitive(specification.samplesPerTask))
        put(
            "max_repair_turns",
            JsonPrimitive(specification.repairPolicy.maxRepairTurns),
        )
        put(
            "max_tool_calls_per_attempt",
            JsonPrimitive(specification.maxToolCallsPerAttempt),
        )
        put(
            "max_workspace_tool_calls_per_attempt",
            JsonPrimitive(specification.maxWorkspaceToolCallsPerAttempt),
        )
        put(
            "max_agent_iterations",
            JsonPrimitive(specification.maxAgentIterations),
        )
        put(
            "max_concurrent_tasks",
            JsonPrimitive(specification.maxConcurrentTasks),
        )
        put(
            "task_ids",
            JsonArray(taskIds.map { JsonPrimitive(it.value) }),
        )
    }

    private fun sampleJson(record: TaskRunRecord): JsonObject =
        buildJsonObject {
            put("schema_version", JsonPrimitive(3))
            put("task_id", JsonPrimitive(record.taskId.value))
            put("sample_index", JsonPrimitive(record.sampleIndex))
            put("system_prompt", JsonPrimitive(record.systemPrompt))
            put("task_prompt", JsonPrimitive(record.taskPrompt))
            put("turns", JsonArray(record.turns.map(::turnJson)))
            put("final_result", resultJson(record.finalResult))
            put("trajectory_file", JsonPrimitive("trajectory.json"))
            record.workspace?.let { workspace ->
                put("workspace", buildJsonObject {
                    put("context_id", JsonPrimitive(workspace.contextId))
                    put("closure_sha256", JsonPrimitive(workspace.closureSha256))
                    put("output_directory", JsonPrimitive("output"))
                    put("inputs", JsonArray(workspace.inputs.map(::workspaceFileJson)))
                    put("final_files", JsonArray(workspace.finalFiles.map(::workspaceFileJson)))
                })
            }
        }

    private fun trajectoryJson(record: TaskRunRecord): JsonObject = buildJsonObject {
        put("schema_version", JsonPrimitive(1))
        put("task_id", JsonPrimitive(record.taskId.value))
        put("sample_index", JsonPrimitive(record.sampleIndex))
        put("events", JsonArray(record.trajectory.map(::trajectoryEventJson)))
    }

    private fun workspaceFileJson(file: WorkspaceFile): JsonObject = buildJsonObject {
        put("path", JsonPrimitive(file.path))
        put("sha256", JsonPrimitive(file.sha256))
        put("content", JsonPrimitive(file.content))
    }

    private fun trajectoryEventJson(event: AgentTrajectoryEvent): JsonObject =
        buildJsonObject {
            put("kind", JsonPrimitive(event.kind))
            put("timestamp_epoch_ms", JsonPrimitive(event.timestampEpochMillis))
            put("details", event.details)
        }

    private fun turnJson(turn: TaskRunRecord.Turn): JsonObject =
        buildJsonObject {
            put("repair_turn", JsonPrimitive(turn.repairTurn))
            put("user_message", JsonPrimitive(turn.userMessage))
            put("response", JsonPrimitive(turn.response))
            put(
                "submission",
                turn.submission?.let { JsonPrimitive(it) } ?: JsonNull,
            )
            put("result", resultJson(turn.result))
            turn.evaluationEvidence?.let { evidence ->
                put("evaluation_evidence", buildJsonObject {
                    put("contract_sha256", JsonPrimitive(evidence.contractSha256))
                    put("file_digests", buildJsonObject { evidence.fileDigests.forEach { (name, digest) -> put(name, JsonPrimitive(digest)) } })
                    put("elaboration", JsonPrimitive(evidence.elaboration))
                    put("candidate_axioms", buildJsonObject { evidence.candidateAxioms.forEach { (name, axioms) -> put(name, JsonArray(axioms.sorted().map(::JsonPrimitive))) } })
                    fun checks(values: List<domain.CheckEvidence>) = JsonArray(values.map { check -> buildJsonObject {
                        put("name", JsonPrimitive(check.name))
                        put("certificate", JsonPrimitive(check.certificate))
                        put("refutation", JsonPrimitive(check.refutation))
                    } })
                    put("obligations", checks(evidence.obligations))
                    put("compatibility", checks(evidence.compatibility))
                })
            }
            if (turn.workspaceFiles.isNotEmpty()) {
                put("workspace_files", JsonArray(turn.workspaceFiles.map(::workspaceFileJson)))
            }
        }

    private fun resultJson(result: EvaluationResult): JsonObject =
        buildJsonObject {
            when (result) {
                EvaluationResult.Accepted ->
                    put("kind", JsonPrimitive("accepted"))

                is EvaluationResult.Rejected -> {
                    put("kind", JsonPrimitive("rejected"))
                    put("reason", JsonPrimitive(result.reason))
                }

                is EvaluationResult.NeedsReview -> {
                    put("kind", JsonPrimitive("needs_review"))
                    put("reason", JsonPrimitive(result.reason))
                }

                is EvaluationResult.InfrastructureFailure -> {
                    put("kind", JsonPrimitive("infrastructure_failure"))
                    put("reason", JsonPrimitive(result.reason))
                }
            }
        }

    private fun writeAtomically(
        target: Path,
        value: JsonElement,
        replace: Boolean,
    ) = writeAtomically(
        target,
        json.encodeToString(JsonElement.serializer(), value) + "\n",
        replace,
    )

    private fun writeAtomically(
        target: Path,
        value: String,
        replace: Boolean,
    ) {
        if (!replace) {
            check(Files.notExists(target)) {
                "run file already exists: $target"
            }
        }

        val temporary = Files.createTempFile(
            target.parent,
            ".${target.fileName}.",
            ".tmp",
        )

        try {
            Files.writeString(temporary, value, StandardCharsets.UTF_8)

            if (replace) {
                Files.move(
                    temporary,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } else {
                Files.move(
                    temporary,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}
