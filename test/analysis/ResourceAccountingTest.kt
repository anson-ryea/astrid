package analysis

import ai.koog.prompt.message.ResponseMetaInfo
import domain.EvaluationResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import run.SampleOutcome
import run.SampleResourceRecorder
import run.SampleResources
import run.TokenPricing
import run.serialization.SampleResourcesJson
import java.nio.file.Path
import kotlin.test.*

class ResourceAccountingTest {
    @TempDir
    lateinit var root: Path
    private fun observed(): SampleResources {
        val resources = SampleResourceRecorder(TokenPricing(1.0, 2.0), 42)
        resources.request(100)
        resources.response(
            ResponseMetaInfo.Empty.copy(
                inputTokensCount = 10,
                outputTokensCount = 5,
                totalTokensCount = 15
            )
        )
        return resources.snapshot()
    }

    @Test
    fun aggregatesResourcesInEveryGroupAndAllExportFormats() = runBlocking {
        val resources = observed()
        val records = (1..2).map {
            sample(
                taskA,
                it,
                SampleOutcome.Evaluated(EvaluationResult.Accepted)
            ).copy(resources = resources)
        }
        val path = savedRun(root.resolve("full"), records, samplesPerTask = 2)
        val analysis = RunAnalyser().analyse(SavedRunReader().read(path))
        val totals = analysis.summaries.first().resources.values
        assertEquals("2", totals.getValue("model_requests").jsonPrimitive.content)
        assertEquals("20", totals.getValue("input_tokens").jsonPrimitive.content)
        assertEquals("10", totals.getValue("output_tokens").jsonPrimitive.content)
        assertEquals("30", totals.getValue("total_tokens").jsonPrimitive.content)
        assertEquals(0.00004, totals.getValue("estimated_cost_usd").jsonPrimitive.double, 1e-12)
        assertEquals(JsonNull, totals.getValue("actual_cost_usd"))
        assertTrue(analysis.summaries.all { it.resources.values == totals })
        assertContains(AnalysisRenderer.csv(listOf(analysis)), "resources_total_tokens")
        assertContains(AnalysisRenderer.text(listOf(analysis)), "total=30")
        val json = Json.parseToJsonElement(AnalysisRenderer.json(listOf(analysis))).jsonObject
        assertEquals(
            totals,
            json.getValue("runs").jsonArray.single().jsonObject.getValue("summaries").jsonArray.first().jsonObject.getValue(
                "resources"
            )
        )
    }

    @Test
    fun missingSamplesOrUsageYieldUnknownTotalsAndPreserveKnownSubtotals() = runBlocking {
        val known = observed()
        val failed = SampleResourceRecorder(TokenPricing(1.0, 2.0), null).also { it.request(100) }.snapshot()
        val records = listOf(
            sample(taskA, 1, SampleOutcome.Evaluated(EvaluationResult.Accepted)).copy(resources = known),
            sample(taskA, 2, SampleOutcome.ProviderFailure("failed")).copy(resources = failed)
        )
        val path = savedRun(root.resolve("partial-usage"), records, samplesPerTask = 2)
        val run = SavedRunReader().read(path)
        val totals = RunAnalyser().analyse(run).summaries.first().resources.values
        assertEquals(JsonNull, totals.getValue("total_tokens"))
        assertEquals("15", totals.getValue("known_total_tokens").jsonPrimitive.content)
        assertEquals(JsonNull, totals.getValue("estimated_cost_usd"))
        assertEquals("1", totals.getValue("samples_with_complete_total_usage").jsonPrimitive.content)
        val legacy = run.copy(samples = listOf(run.samples.first(), run.samples.last().copy(resources = null)))
        val mixed = RunAnalyser().analyse(legacy).summaries.first().resources.values
        assertEquals(JsonNull, mixed.getValue("model_requests"))
        assertEquals("15", mixed.getValue("known_total_tokens").jsonPrimitive.content)
        assertEquals("1", mixed.getValue("samples_with_resources").jsonPrimitive.content)
    }

    @Test
    fun rejectsInconsistentResourceCoverage() {
        val resources = SampleResourcesJson.encode(observed())
        val invalid = JsonObject(resources + ("responses_with_total_usage" to JsonPrimitive(0)))
        assertFailsWith<IllegalArgumentException> { SampleResourcesJson.decode(invalid) }
    }
}
