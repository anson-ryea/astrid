package run

import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import domain.ConditionId
import domain.PaperId
import domain.TaskId
import domain.TaskTypeId
import kotlinx.serialization.json.*
import run.serialization.RunRecordJson
import run.serialization.SampleOutcomeJson
import serialization.intValue
import serialization.nonBlankString
import serialization.stringValue
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE

/** Reopens a run by its saved configuration and committed sample records. */
internal class RunResume private constructor(
    val directory: Path,
    private val saved: JsonObject,
    private val allowConfigOverride: Boolean,
) {
    private val model by lazy {
        Json.decodeFromJsonElement<LLModel>(saved.getValue("model")).also {
            require(it.provider == LLMProvider.OpenRouter) { "saved run does not use OpenRouter" }
        }
    }

    val taskIds = saved.getValue("task_ids").jsonArray.map { TaskId(requireNotNull(it.stringValue())) }.toSet()
    val selection = saved.getValue("selection").jsonObject.let { value ->
        fun <T> strings(key: String, convert: (String) -> T): Set<T> =
            value.getValue(key).jsonArray.map { convert(requireNotNull(it.stringValue())) }.toSet()
        TaskSelection(
            taskIds = strings("task_ids", ::TaskId),
            paperIds = strings("paper_ids", ::PaperId),
            taskTypes = value.getValue("task_types").jsonArray.map { TaskTypeId(requireNotNull(it.intValue())) }
                .toSet(),
            conditions = strings("conditions", ::ConditionId),
        )
    }

    fun config(reader: JsonBenchmarkRunConfigReader): BenchmarkRunConfig {
        val settings = RunRecordJson.configuration(saved).toMutableMap()
        settings["schema_version"] = saved.getValue("schema_version")
        settings["model"] = buildJsonObject {
            put("id", model.id)
            put("context_length", JsonPrimitive(model.contextLength))
            put("max_output_tokens", JsonPrimitive(model.maxOutputTokens))
            put("supports_tools", model.supports(LLMCapability.Tools))
        }
        val generation = settings.getValue("generation").jsonObject
        settings["generation"] = JsonObject(generation - "seed_policy")
        return reader.decode(JsonObject(settings))
    }

    fun specification(config: BenchmarkRunConfig): BenchmarkRunSpecification {
        val requested = config.toSpecification()
        return requested.copy(model = if (allowConfigOverride) requested.model else model, selection = selection)
    }

    data class Restored(
        val samples: List<RunEvent.SampleFinished>,
        val previousConfigurations: List<JsonObject>,
    )

    fun restore(
        expected: JsonObject,
        taskIds: List<TaskId>,
        samplesPerTask: Int,
    ): Restored {
        check(saved == readMetadata(directory)) { "run metadata changed while preparing to resume: $directory" }
        val identityFields = listOf("schema_version", "task_ids", "task_metadata", "selection")
        check(identityFields.all { saved[it] == expected[it] }) {
            "cannot resume: saved task selection or metadata has changed"
        }
        val previous = RunRecordJson.configuration(saved)
        val next = RunRecordJson.configuration(expected)
        val changed = (previous.keys + next.keys).filter { previous[it] != next[it] }
        check(changed.isEmpty() || allowConfigOverride) {
            "cannot resume: run settings have changed (${changed.joinToString()})"
        }
        val history = saved["previous_configurations"]?.jsonArray.orEmpty().map { it.jsonObject }

        val selected = taskIds.associateBy(TaskId::value)
        val restored = mutableListOf<RunEvent.SampleFinished>()
        val interrupted = mutableListOf<Path>()
        for (taskDirectory in directories(directory.resolve("tasks"))) {
            val taskId = checkNotNull(selected[taskDirectory.fileName.toString()]) {
                "run contains an unselected task directory: $taskDirectory"
            }
            for (sampleDirectory in directories(taskDirectory.resolve("samples"))) {
                val name = sampleDirectory.fileName.toString()
                val index = name.toIntOrNull()
                check(index != null && index > 0 && name == index.toString().padStart(3, '0')) {
                    "invalid sample directory: $sampleDirectory"
                }
                val recordPath = sampleDirectory.resolve("record.json")
                if (Files.notExists(recordPath, NOFOLLOW_LINKS)) {
                    interrupted.add(sampleDirectory)
                    continue
                }
                val record = readJson(recordPath)
                check(index <= samplesPerTask) {
                    "cannot resume with $samplesPerTask samples per task: $taskId already has saved sample $index"
                }
                val version = record["schema_version"].intValue()
                check(
                    version != null && version in 5..6 && record.nonBlankString("task_id") == taskId.value &&
                            record["sample_index"].intValue() == index
                ) {
                    "incompatible saved sample: $recordPath"
                }
                val trace = record.nonBlankString("trajectory_file")
                check(
                    trace == "trajectory.jsonl" && Files.isRegularFile(
                        sampleDirectory.resolve(trace),
                        NOFOLLOW_LINKS
                    )
                ) {
                    "missing or unsafe trajectory for $recordPath"
                }
                val configurationIndex = record["configuration_index"]?.let {
                    requireNotNull(it.intValue()) { "invalid configuration index in $recordPath" }
                } ?: 0
                check(configurationIndex in 0..history.size) { "unknown configuration index in $recordPath" }
                val outcome = SampleOutcomeJson.decode(record.getValue("outcome").jsonObject)
                restored += RunEvent.SampleFinished(taskId, index, outcome)
            }
        }

        // An interruption can leave files from a save that never published record.json.
        if (interrupted.isNotEmpty()) {
            val archive = Files.createTempDirectory(directory, ".interrupted-")
            for (sample in interrupted) {
                val taskId = sample.parent.parent.fileName
                Files.move(sample, archive.resolve("$taskId-${sample.fileName}"), ATOMIC_MOVE)
            }
        }
        return Restored(restored, if (changed.isEmpty()) history else history + previous)
    }

    private fun directories(path: Path): List<Path> {
        if (Files.notExists(path, NOFOLLOW_LINKS)) return emptyList()
        check(Files.isDirectory(path, NOFOLLOW_LINKS)) { "expected a directory without symlinks: $path" }
        return Files.list(path).use { entries ->
            entries.sorted().toList().onEach {
                check(!Files.isSymbolicLink(it)) { "unexpected symlink in run directory: $it" }
            }.filter { Files.isDirectory(it, NOFOLLOW_LINKS) }
        }
    }

    companion object {
        fun open(directory: Path, allowConfigOverride: Boolean = false): RunResume {
            check(Files.isDirectory(directory, NOFOLLOW_LINKS)) { "run directory does not exist: $directory" }
            val saved = readMetadata(directory)
            require(saved["schema_version"].intValue() == 1) { "unsupported run metadata schema: $directory" }
            val status = saved.nonBlankString("status")
            check(status in setOf("failed", "running")) { "cannot resume a run with status '$status': $directory" }
            return RunResume(directory, saved, allowConfigOverride)
        }

        fun readMetadata(directory: Path): JsonObject = readJson(directory.resolve("run.json"))

        private fun readJson(path: Path): JsonObject {
            check(Files.isRegularFile(path, NOFOLLOW_LINKS)) { "missing or unsafe run file: $path" }
            return Json.parseToJsonElement(Files.readString(path)).jsonObject
        }
    }
}
