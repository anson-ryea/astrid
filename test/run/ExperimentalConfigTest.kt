package run

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

class ExperimentalConfigTest {
    @TempDir lateinit var root: Path
    private val base = """{"schema_version":1,"model":{"id":"test","context_length":1000,"max_output_tokens":100,"supports_tools":false},"samples_per_task":1,"max_repair_turns":0,"max_agent_iterations":20}"""
    private fun read(extra: String): BenchmarkRunConfig {
        val path = root.resolve("config.json")
        Files.writeString(path, base.dropLast(1) + (if (extra.isBlank()) "" else ",$extra") + "}")
        return JsonBenchmarkRunConfigReader().read(path)
    }

    @Test
    fun readsOptionalControlsAndPreservesExistingDefaults() {
        assertEquals(GenerationSettings(), read("").generation)
        assertEquals(SampleLimits(), read("").limits)
        assertNull(read("").pricing)
        val config = read(""""generation":{"temperature":0.2,"top_p":0.9,"top_k":5,"seed":7},"limits":{"sample_timeout":"PT3S","max_total_tokens_per_sample":500},"pricing":{"input_usd_per_million":1.5,"output_usd_per_million":3}""")
        assertEquals(GenerationSettings(0.2, 0.9, 5, 7), config.toSpecification().generation)
        assertEquals(SampleLimits(3.seconds, 500), config.toSpecification().limits)
        assertEquals(TokenPricing(1.5, 3.0), config.pricing)
        assertEquals(read(""), read(""""generation":null,"limits":null,"pricing":null"""))
    }

    @Test
    fun rejectsInvalidOrUnknownExperimentalSettings(): Unit {
        for (extra in listOf(
            """"generation":{"temperature":2.1}""", """"generation":{"top_p":0}""",
            """"generation":{"top_k":0}""", """"generation":{"seed":-1}""",
            """"generation":{"seed":2147483648}""", """"generation":{"top_k":1.5}""",
            """"generation":{"temperature":"0.2"}""", """"generation":{"unknown":1}""",
            """"limits":{"sample_timeout":"PT0S"}""", """"limits":{"sample_timeout":"PT0.0001S"}""",
            """"limits":{"sample_timeout":2}""", """"limits":{"max_total_tokens_per_sample":0}""",
            """"limits":{"max_total_tokens_per_sample":"10"}""", """"limits":{"unknown":1}""",
            """"pricing":{"input_usd_per_million":-1,"output_usd_per_million":2}""",
            """"pricing":{"input_usd_per_million":1}""")) {
            assertFailsWith<IllegalArgumentException>(extra) { read(extra) }
        }
    }
}
