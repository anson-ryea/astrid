package run

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull

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
            ) + document.keys.intersect(setOf("\$schema", "max_tool_calls_per_attempt", "max_workspace_tool_calls_per_attempt")),
        )
        require(document.requiredInt("schema_version") == 1) {
            "unsupported run config schema version"
        }
        if ("\$schema" in document) {
            require(document.requiredString("\$schema").isNotBlank()) { "field '\$schema' must not be blank" }
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
        )
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

    private fun JsonObject.requiredString(name: String): String {
        val value = this[name] as? JsonPrimitive
        require(value != null && value.isString) {
            "field '$name' must be a string"
        }
        return value.content
    }

    private fun JsonObject.requiredInt(name: String): Int {
        val value = this[name] as? JsonPrimitive
        require(value != null && !value.isString && value.intOrNull != null) {
            "field '$name' must be an integer"
        }
        return value.int
    }

    private fun JsonObject.requiredNullableInt(name: String): Int? =
        if (this[name] == JsonNull) null else requiredInt(name)

    private fun JsonObject.requiredNullableLong(name: String): Long? {
        if (this[name] == JsonNull) return null
        val value = this[name] as? JsonPrimitive
        require(value != null && !value.isString && value.longOrNull != null) {
            "field '$name' must be an integer"
        }
        return value.long
    }

    private fun JsonObject.requiredBoolean(name: String): Boolean {
        val value = this[name] as? JsonPrimitive
        require(value != null && !value.isString && value.booleanOrNull != null) {
            "field '$name' must be a boolean"
        }
        return value.boolean
    }
}
