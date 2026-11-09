package analysis

import domain.EvaluationResult
import domain.TaskId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import run.*
import java.nio.file.Files
import java.nio.file.Path

internal const val taskA = "task_1.1_claim"
internal const val taskB = "task_1.2_claim"
internal const val taskC = "task_2.1_claim"
internal val metadata = mapOf(
    TaskId(taskA) to RunTaskMetadata("paper_1", 1, "paper_evidence", "claim"),
    TaskId(taskB) to RunTaskMetadata("paper_1", 2, "statement_only", "claim"),
    TaskId(taskC) to RunTaskMetadata("paper_2", 2, "statement_only", "claim"),
)

internal fun sample(taskId: String, index: Int, outcome: SampleOutcome, repaired: Boolean = false): TaskRunRecord {
    val result = when (outcome) {
        is SampleOutcome.Evaluated -> outcome.result
        is SampleOutcome.RepairLimitReached -> outcome.result
        else -> null
    }
    val turns = when {
        repaired -> listOf(
            TaskRunRecord.Turn(0, "task", "bad", "bad", EvaluationResult.Rejected("bad proof")),
            TaskRunRecord.Turn(1, "repair", "response", "submission", result),
        )

        result != null -> listOf(TaskRunRecord.Turn(0, "task", "response", "submission", result))
        outcome is SampleOutcome.BudgetExhausted && outcome.limit == SampleBudget.OUTPUT_TOKENS ->
            listOf(TaskRunRecord.Turn(0, "task", "partial", null, null))

        else -> emptyList()
    }
    return TaskRunRecord(TaskId(taskId), index, "private prompt never exported", "task prompt", turns, outcome)
}

internal suspend fun savedRun(
    path: Path,
    records: List<TaskRunRecord>,
    samplesPerTask: Int = 1,
    taskIds: List<String> = listOf(taskA),
    labels: Map<TaskId, RunTaskMetadata> = metadata.filterKeys { it.value in taskIds },
    complete: Boolean = true,
    model: String = "test-model",
): Path {
    withContext(Dispatchers.IO) {
        Files.createDirectories(path.parent)
    }
    val store = FileRunStore(path)
    val spec = runTestSpecification(samplesPerTask = samplesPerTask).let { it.copy(model = it.model.copy(id = model)) }
    store.begin(spec, taskIds.map(::TaskId), labels)
    records.forEach { store.save(it) }
    if (complete) store.complete() else store.fail("interrupted")
    return path
}

internal fun editJson(path: Path, transform: (JsonObject) -> JsonObject) {
    val before = Json.parseToJsonElement(Files.readString(path)).jsonObject
    Files.writeString(path, transform(before).toString())
}

internal fun samplePath(run: Path, task: String = taskA, index: Int = 1) =
    run.resolve("tasks/$task/samples/${index.toString().padStart(3, '0')}/record.json")
