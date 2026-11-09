package analysis

import domain.EvaluationResult
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import run.SampleBudget
import run.SampleOutcome
import kotlin.test.*

class SavedRunReaderTest {
    @TempDir lateinit var root: Path
    private val accepted = SampleOutcome.Evaluated(EvaluationResult.Accepted)

    @Test
    fun readsAllHistoricalSchemasWithoutGuessingLegacyFailureReasons() = runBlocking {
        for (version in 1..5) {
            val path = savedRun(root.resolve("v$version"), listOf(sample(taskA, 1,
                SampleOutcome.Evaluated(EvaluationResult.InfrastructureFailure("tool call limit exceeded")))))
            editJson(samplePath(path)) { before -> buildJsonObject {
                before.forEach { (key, value) -> if (key != "outcome") put(key, value) }
                put("schema_version", JsonPrimitive(version))
                if (version == 5) put("outcome", before.getValue("outcome"))
                else {
                    put("final_result", before.getValue("outcome").jsonObject.getValue("result"))
                    if (version == 4) put("termination", buildJsonObject { put("kind", JsonPrimitive("evaluated")) })
                }
            } }
            val run = SavedRunReader().read(path)
            assertEquals(if (version <= 3) SampleCategory.LEGACY_INFRASTRUCTURE_FAILURE else SampleCategory.EVALUATOR_FAILURE,
                run.samples.single().category)
            assertEquals(version <= 3, run.warnings.any { it.contains("Legacy") })
            assertNull(run.samples.single().budget)
        }
    }

    @Test
    fun decodesVersionFourBudgetsAndExecutionFailuresExplicitly() = runBlocking {
        val kinds = listOf("output_token_limit", "agent_iteration_limit", "tool_call_limit", "workspace_tool_call_limit", "provider_failure", "execution_failure")
        kinds.forEachIndexed { index, kind ->
            val path = savedRun(root.resolve("old-$index"), listOf(sample(taskA, 1, SampleOutcome.ExecutionFailure("stopped"))))
            editJson(samplePath(path)) { before -> buildJsonObject {
                before.forEach { (key, value) -> if (key != "outcome") put(key, value) }
                put("schema_version", JsonPrimitive(4)); put("final_result", JsonNull)
                put("termination", buildJsonObject { put("kind", JsonPrimitive(kind)); put("reason", JsonPrimitive("stopped")) })
            } }
            val sample = SavedRunReader().read(path).samples.single()
            assertEquals(when (kind) {
                "provider_failure" -> SampleCategory.PROVIDER_FAILURE
                "execution_failure" -> SampleCategory.EXECUTION_FAILURE
                else -> SampleCategory.BUDGET_EXHAUSTED
            }, sample.category)
            if (index < 4) assertEquals(listOf(SampleBudget.OUTPUT_TOKENS, SampleBudget.AGENT_ITERATIONS, SampleBudget.TOOL_CALLS, SampleBudget.WORKSPACE_TOOL_CALLS)[index], sample.budget)
        }
    }

    @Test
    fun preservesSavedMetadataAndLabelsExternalEnrichmentAsCurrent() = runBlocking {
        val path = savedRun(root.resolve("metadata"), listOf(sample(taskA, 1, accepted)), labels = emptyMap())
        val missing = SavedRunReader().read(path)
        assertTrue(missing.warnings.any { it.contains("lack analysis metadata") })
        val enriched = SavedRunReader().read(path, metadata.mapKeys { it.key.value })
        assertEquals("current_dataset", enriched.taskMetadata.getValue(taskA).source)
        assertTrue(enriched.warnings.any { it.contains("current dataset") })
        val snap = savedRun(root.resolve("snapshot"), listOf(sample(taskA, 1, accepted)))
        val overridden = metadata.mapKeys { it.key.value }.mapValues { it.value.copy(condition = "changed") }
        assertEquals("paper_evidence", SavedRunReader().read(snap, overridden).taskMetadata.getValue(taskA).labels.condition)
        val persisted = Json.parseToJsonElement(Files.readString(snap.resolve("run.json"))).jsonObject
        assertEquals("paper_1", persisted.getValue("task_metadata").jsonObject.getValue(taskA).jsonObject.getValue("paper_id").jsonPrimitive.content)
    }

    @Test
    fun rejectsUnknownSchemasMalformedOutcomesAndIdentityMismatch() = runBlocking {
        val edits: List<(JsonObject) -> JsonObject> = listOf(
            { JsonObject(it + ("schema_version" to JsonPrimitive(7))) },
            { JsonObject(it + ("task_id" to JsonPrimitive(taskB))) },
            { JsonObject(it + ("sample_index" to JsonPrimitive(2))) },
            { JsonObject(it + ("outcome" to buildJsonObject { put("kind", JsonPrimitive("invented")) })) },
            { JsonObject(it + ("outcome" to buildJsonObject {
                put("kind", JsonPrimitive("budget_exhausted")); put("limit", JsonPrimitive("unknown")); put("reason", JsonPrimitive("limit"))
            })) },
            { JsonObject(it + ("outcome" to buildJsonObject {
                put("kind", JsonPrimitive("provider_failure")); put("reason", JsonPrimitive("provider"))
                put("result", buildJsonObject { put("kind", JsonPrimitive("accepted")) })
            })) },
            { JsonObject(it + ("turns" to JsonArray(it.getValue("turns").jsonArray.map { turn ->
                JsonObject(turn.jsonObject + ("repair_turn" to JsonPrimitive(1)))
            }))) },
            { JsonObject(it + ("outcome" to buildJsonObject {
                put("kind", JsonPrimitive("repair_limit")); put("result", buildJsonObject { put("kind", JsonPrimitive("accepted")) })
            })) },
        )
        edits.forEachIndexed { index, edit ->
            val path = savedRun(root.resolve("invalid-$index"), listOf(sample(taskA, 1, accepted)))
            editJson(samplePath(path), edit)
            assertFailsWith<IllegalArgumentException> { SavedRunReader().read(path) }
        }
    }

    @Test
    fun rejectsDuplicateRecordsAndMissingRecordsInCompletedRuns() = runBlocking {
        val path = savedRun(root.resolve("duplicate"), listOf(sample(taskA, 1, accepted)))
        val duplicate = Files.createDirectories(samplePath(path).parent.parent.resolve("1"))
        Files.copy(samplePath(path), duplicate.resolve("record.json"))
        assertFailsWith<IllegalArgumentException> { SavedRunReader().read(path) }
        val missing = savedRun(root.resolve("missing"), listOf(sample(taskA, 1, accepted)))
        Files.delete(samplePath(missing))
        assertFailsWith<IllegalArgumentException> { SavedRunReader().read(missing) }
        val partial = savedRun(root.resolve("partial"), emptyList(), complete = false)
        Files.createDirectories(samplePath(partial).parent)
        assertTrue(SavedRunReader().read(partial).samples.isEmpty())
    }
}
