package analysis

import domain.ConditionId
import domain.PaperId
import domain.SourceResultId
import domain.TaskId
import domain.TaskTypeId
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import kotlinx.serialization.json.*
import run.RunTaskMetadata
import run.SampleBudget

/** Reads summaries only: trajectories, outputs, prompts and private specifications are never interpreted. */
class SavedRunReader {
    fun read(directory: Path, datasetMetadata: Map<String, RunTaskMetadata> = emptyMap()): SavedRun {
        val root = directory.toAbsolutePath().normalize().toRealPath()
        val document = readObject(root.resolve("run.json"))
        require(document.int("schema_version") == 1) { "unsupported run metadata schema in $root" }
        val status = document.string("status")
        require(status in setOf("running", "complete", "failed")) { "unknown run status in $root: $status" }
        val samplesPerTask = document.int("samples_per_task").also { require(it > 0) { "samples_per_task must be positive" } }
        val taskIds = document.array("task_ids").map { value ->
            val id = value as? JsonPrimitive
            require(id != null && id.isString) { "task_ids must contain strings" }
            TaskId(id.content).value
        }
        require(taskIds.isNotEmpty() && taskIds.distinct().size == taskIds.size) { "task_ids must be non-empty and unique" }
        val modelId = document.obj("model").string("id")
        val metadata = document["task_metadata"]?.let { value ->
            require(value is JsonObject) { "task_metadata must be an object" }
            require(value.keys.all { it in taskIds }) { "metadata contains unselected task IDs" }
            value.mapValues { (_, item) ->
                require(item is JsonObject) { "task metadata must be an object" }
                AnalysisTaskMetadata(readMetadata(item), "saved_run")
            }
        }.orEmpty().toMutableMap()
        taskIds.forEach { id ->
            if (id !in metadata) datasetMetadata[id]?.let { metadata[id] = AnalysisTaskMetadata(it, "current_dataset") }
        }
        val samples = mutableListOf<SavedSample>()
        for (taskDirectory in directories(root.resolve("tasks"))) {
            val taskId = taskDirectory.fileName.toString()
            for (sampleDirectory in directories(taskDirectory.resolve("samples"))) {
                val recordPath = sampleDirectory.resolve("record.json")
                if (Files.notExists(recordPath, NOFOLLOW_LINKS)) continue // A sample may still be in progress.
                require(taskId in taskIds) { "saved record belongs to unselected task: $recordPath" }
                val sample = try {
                    readSample(readObject(recordPath))
                } catch (failure: IllegalArgumentException) {
                    throw IllegalArgumentException("invalid sample $recordPath: ${failure.message}", failure)
                }
                require(sample.taskId == taskId && sampleDirectory.fileName.toString().toIntOrNull() == sample.sampleIndex) {
                    "sample identity disagrees with its directory: $recordPath"
                }
                require(sample.sampleIndex in 1..samplesPerTask) { "sample index outside configured range: $recordPath" }
                samples += sample
            }
        }
        require(samples.map { it.taskId to it.sampleIndex }.distinct().size == samples.size) { "duplicate saved sample identities in $root" }
        val expected = taskIds.size.toLong() * samplesPerTask
        require(status != "complete" || samples.size.toLong() == expected) {
            "complete run is missing sample records: ${samples.size}/$expected in $root"
        }
        val warnings = buildList {
            if (status != "complete") add("Run is $status; saved-sample rates are provisional. Missing samples are reported separately.")
            if (samples.any { it.schemaVersion <= 3 }) add("Legacy sample schemas 1–3 cannot distinguish historical infrastructure, provider and budget failures; no diagnostic-text inference is applied.")
            if (metadata.size < taskIds.size) add("${taskIds.size - metadata.size} task(s) lack analysis metadata; their breakdown labels are unknown. Supply --dataset-root to enrich older runs.")
            if (metadata.values.any { it.source == "current_dataset" }) add("Some labels come from the current dataset checkout, not a historical snapshot; they do not establish experiment provenance.")
        }
        return SavedRun(root, status, modelId, samplesPerTask, taskIds.sorted(), metadata,
            samples.sortedWith(compareBy({ it.taskId }, { it.sampleIndex })),
            JsonObject(document.filterKeys { it !in setOf("task_ids", "task_metadata", "status", "schema_version", "failure_reason") }), warnings)
    }

    private fun readMetadata(value: JsonObject) = RunTaskMetadata(
        PaperId(value.string("paper_id")).value,
        TaskTypeId(value.int("task_type")).value,
        ConditionId(value.string("condition")).value,
        SourceResultId(value.string("source_result_id")).value,
    )

    private fun readSample(value: JsonObject): SavedSample {
        val version = value.int("schema_version")
        require(version in 1..5) { "unsupported sample schema version: $version" }
        val taskId = TaskId(value.string("task_id")).value
        val sampleIndex = value.int("sample_index")
        val turns = value.array("turns").mapIndexed { index, turn ->
            require(turn is JsonObject) { "turn must be an object" }
            require(turn.int("repair_turn") == index) { "repair turns must be contiguous, starting at zero" }
            val result = turn["result"]
            require(result != null) { "turn is missing result" }
            if (result == JsonNull) {
                require(version >= 4) { "legacy turn must have a verdict" }
                null
            } else verdict(result, legacy = version <= 3)
        }
        var budget: SampleBudget? = null
        val category = when (version) {
            5 -> {
                val outcome = value.obj("outcome")
                when (outcome.string("kind")) {
                    "evaluated", "repair_limit" -> {
                        outcome.fields("kind", "result")
                        val result = verdict(outcome["result"], legacy = false)
                        require(outcome.string("kind") != "repair_limit" || result == SampleCategory.REJECTED) { "repair_limit must contain a rejection" }
                        result
                    }
                    "budget_exhausted" -> {
                        outcome.fields("kind", "limit", "reason")
                        outcome.string("reason")
                        budget = SampleBudget.entries.singleOrNull { it.manifestValue == outcome.string("limit") }
                            ?: throw IllegalArgumentException("unknown sample budget")
                        SampleCategory.BUDGET_EXHAUSTED
                    }
                    "provider_failure", "execution_failure" -> {
                        outcome.fields("kind", "reason")
                        outcome.string("reason")
                        if (outcome.string("kind") == "provider_failure") SampleCategory.PROVIDER_FAILURE else SampleCategory.EXECUTION_FAILURE
                    }
                    else -> throw IllegalArgumentException("unknown sample outcome")
                }
            }
            4 -> {
                val termination = value.obj("termination")
                val result = value["final_result"]
                require(result != null) { "missing final_result" }
                when (val kind = termination.string("kind")) {
                    "evaluated", "repair_limit" -> verdict(result, legacy = false).also {
                        require(kind != "repair_limit" || it == SampleCategory.REJECTED) { "repair_limit must contain a rejection" }
                    }
                    else -> {
                        require(result == JsonNull) { "interrupted sample cannot contain a final verdict" }
                        termination.string("reason")
                        when (kind) {
                            "provider_failure" -> SampleCategory.PROVIDER_FAILURE
                            "execution_failure" -> SampleCategory.EXECUTION_FAILURE
                            else -> {
                                budget = when (kind) {
                                    "output_token_limit" -> SampleBudget.OUTPUT_TOKENS
                                    "agent_iteration_limit" -> SampleBudget.AGENT_ITERATIONS
                                    "tool_call_limit" -> SampleBudget.TOOL_CALLS
                                    "workspace_tool_call_limit" -> SampleBudget.WORKSPACE_TOOL_CALLS
                                    else -> throw IllegalArgumentException("unknown sample termination")
                                }
                                SampleCategory.BUDGET_EXHAUSTED
                            }
                        }
                    }
                }
            }
            else -> verdict(value["final_result"], legacy = true)
        }
        if (version >= 4 && category in setOf(SampleCategory.ACCEPTED, SampleCategory.REJECTED, SampleCategory.NEEDS_REVIEW, SampleCategory.EVALUATOR_FAILURE)) {
            require(turns.isNotEmpty() && turns.last() == category) { "final verdict disagrees with last assessed turn" }
        }
        require(turns.dropLast(1).none { it == null }) { "only the final turn may be unassessed" }
        return SavedSample(taskId, sampleIndex, version, category, budget,
            turns.firstOrNull() == SampleCategory.ACCEPTED,
            turns.firstOrNull()?.let { it !in setOf(SampleCategory.EVALUATOR_FAILURE, SampleCategory.LEGACY_INFRASTRUCTURE_FAILURE) } ?: false,
            (turns.size - 1).coerceAtLeast(0))
    }

    private fun verdict(value: JsonElement?, legacy: Boolean): SampleCategory {
        require(value is JsonObject) { "evaluation verdict must be an object" }
        val kind = value.string("kind")
        if (kind != "accepted") value.string("reason")
        return when (kind) {
            "accepted" -> SampleCategory.ACCEPTED
            "rejected" -> SampleCategory.REJECTED
            "needs_review" -> SampleCategory.NEEDS_REVIEW
            "infrastructure_failure" -> if (legacy) SampleCategory.LEGACY_INFRASTRUCTURE_FAILURE else SampleCategory.EVALUATOR_FAILURE
            else -> throw IllegalArgumentException("unknown evaluation verdict: $kind")
        }
    }

    private fun directories(path: Path): List<Path> {
        if (Files.notExists(path, NOFOLLOW_LINKS)) return emptyList()
        require(Files.isDirectory(path, NOFOLLOW_LINKS)) { "expected a directory without symlinks: $path" }
        return Files.list(path).use { entries -> entries.sorted().toList().onEach {
            require(!Files.isSymbolicLink(it)) { "saved run directories must not contain symlinks: $it" }
        }.filter { Files.isDirectory(it, NOFOLLOW_LINKS) } }
    }

    private fun readObject(path: Path): JsonObject {
        require(Files.isRegularFile(path, NOFOLLOW_LINKS)) { "missing or unsafe saved JSON file: $path" }
        return try {
            Json.parseToJsonElement(Files.readString(path)) as? JsonObject
                ?: throw IllegalArgumentException("JSON root must be an object")
        } catch (failure: IllegalArgumentException) {
            throw IllegalArgumentException("invalid saved JSON $path: ${failure.message}", failure)
        }
    }

    private fun JsonObject.string(name: String): String {
        val value = this[name] as? JsonPrimitive
        require(value != null && value.isString && value.content.isNotBlank()) { "'$name' must be a non-blank string" }
        return value.content
    }
    private fun JsonObject.int(name: String): Int {
        val value = this[name] as? JsonPrimitive
        require(value != null && !value.isString && value.intOrNull != null) { "'$name' must be an integer" }
        return value.int
    }
    private fun JsonObject.obj(name: String): JsonObject = this[name] as? JsonObject
        ?: throw IllegalArgumentException("'$name' must be an object")
    private fun JsonObject.array(name: String): JsonArray = this[name] as? JsonArray
        ?: throw IllegalArgumentException("'$name' must be an array")
    private fun JsonObject.fields(vararg names: String) {
        require(keys == names.toSet()) { "outcome fields disagree with its kind" }
    }
}
