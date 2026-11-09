package run

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import serialization.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration

/** Reads the strict V1 JSON format for a benchmark run. */
class JsonBenchmarkRunConfigReader {
    fun read(path: Path): BenchmarkRunConfig {
        val source = try {
            Files.readString(path)
        } catch (failure: Exception) {
            throw IllegalArgumentException("could not read run config: $path", failure)
        }

        val document = try {
            Json.parseToJsonElement(source) as? JsonObject
                ?: throw IllegalArgumentException("run config root must be an object")
        } catch (failure: IllegalArgumentException) {
            throw IllegalArgumentException("invalid run config $path: ${failure.message}", failure)
        }

        return try {
            decode(document)
        } catch (failure: IllegalArgumentException) {
            throw IllegalArgumentException("invalid run config $path: ${failure.message}", failure)
        }
    }

    private fun decode(document: JsonObject): BenchmarkRunConfig {
        document.requireFields(
            "run config",
            setOf(
                "schema_version",
                "model",
                "samples_per_task",
                "max_repair_turns",
                "max_agent_iterations",
            ) + document.keys.intersect(
                setOf(
                    $$"$schema",
                    "max_tool_calls_per_attempt",
                    "max_workspace_tool_calls_per_attempt",
                    "max_concurrent_tasks",
                    "generation",
                    "limits",
                    "pricing"
                )
            ),
        )
        require(document.requiredInt("schema_version") == 1) {
            "unsupported run config schema version"
        }
        if ($$"$schema" in document) {
            require(document.requiredString($$"$schema").isNotBlank()) { $$"field '$schema' must not be blank" }
        }

        val model = document["model"] as? JsonObject
            ?: throw IllegalArgumentException("field 'model' must be an object")
        model.requireFields(
            "model",
            setOf("id", "context_length", "max_output_tokens", "supports_tools"),
        )

        return BenchmarkRunConfig(
            modelId = model.requiredString("id"),
            contextLength = model.requiredNullableLong("context_length"),
            maxOutputTokens = model.requiredNullableLong("max_output_tokens"),
            supportsTools = model.requiredBoolean("supports_tools"),
            samplesPerTask = document.requiredInt("samples_per_task"),
            maxRepairTurns = document.requiredNullableInt("max_repair_turns"),
            maxAgentIterations = document.requiredNullableInt("max_agent_iterations"),
            maxToolCallsPerAttempt = if ("max_tool_calls_per_attempt" in document) {
                document.requiredNullableInt("max_tool_calls_per_attempt")
            } else 0,
            maxWorkspaceToolCallsPerAttempt = if ("max_workspace_tool_calls_per_attempt" in document) {
                document.requiredNullableInt("max_workspace_tool_calls_per_attempt")
            } else 0,
            generation = document.optionalObject("generation")?.let { value ->
                value.requireAllowed("generation", setOf("temperature", "top_p", "top_k", "seed"))
                GenerationSettings(
                    value.optionalDouble("temperature"), value.optionalDouble("top_p"),
                    value.optionalInt("top_k"), value.optionalInt("seed")
                )
            } ?: GenerationSettings(),
            limits = document.optionalObject("limits")?.let { value ->
                value.requireAllowed("limits", setOf("sample_timeout", "max_total_tokens_per_sample"))
                SampleLimits(
                    value["sample_timeout"]?.takeUnless { it == JsonNull }?.let {
                        Duration.parseIsoString(value.requiredString("sample_timeout"))
                    },
                    if ("max_total_tokens_per_sample" in value) value.requiredNullableLong("max_total_tokens_per_sample") else null
                )
            } ?: SampleLimits(),
            pricing = document.optionalObject("pricing")?.let { value ->
                value.requireFields("pricing", setOf("input_usd_per_million", "output_usd_per_million"))
                TokenPricing(
                    requireNotNull(value.optionalDouble("input_usd_per_million")),
                    requireNotNull(value.optionalDouble("output_usd_per_million"))
                )
            },
            maxConcurrentTasks = if ("max_concurrent_tasks" in document) {
                document.requiredInt("max_concurrent_tasks")
            } else 4,
        )
    }

    private fun JsonObject.optionalObject(name: String): JsonObject? {
        if (name !in this || this[name] == JsonNull) return null
        return this[name] as? JsonObject ?: throw IllegalArgumentException("'$name' must be an object or null")
    }

    private fun JsonObject.optionalInt(name: String): Int? =
        if (name !in this || this[name] == JsonNull) null else requiredInt(name)

    private fun JsonObject.optionalDouble(name: String): Double? {
        if (name !in this || this[name] == JsonNull) return null
        return this[name].doubleValue()?.takeIf { it.isFinite() }
            ?: throw IllegalArgumentException("'$name' must be a finite number")
    }

    private fun JsonObject.requireAllowed(label: String, allowed: Set<String>) {
        require((keys - allowed).isEmpty()) { "$label has unknown fields: ${(keys - allowed).sorted().joinToString()}" }
    }

    private fun JsonObject.requireFields(label: String, expected: Set<String>) {
        val missing = expected - keys
        val unexpected = keys - expected
        require(missing.isEmpty()) {
            "$label is missing fields: ${missing.sorted().joinToString()}"
        }
        require(unexpected.isEmpty()) {
            "$label has unknown fields: ${unexpected.sorted().joinToString()}"
        }
    }

    private fun JsonObject.requiredString(name: String): String = this[name].stringValue()
        ?: throw IllegalArgumentException("field '$name' must be a string")

    private fun JsonObject.requiredInt(name: String): Int = this[name].intValue()
        ?: throw IllegalArgumentException("field '$name' must be an integer")

    private fun JsonObject.requiredNullableInt(name: String): Int? =
        if (this[name] == JsonNull) null else requiredInt(name)

    private fun JsonObject.requiredNullableLong(name: String): Long? =
        if (this[name] == JsonNull) null else this[name].longValue()
            ?: throw IllegalArgumentException("field '$name' must be an integer")

    private fun JsonObject.requiredBoolean(name: String): Boolean = this[name].booleanValue()
        ?: throw IllegalArgumentException("field '$name' must be a boolean")
}
