package backend.lean.validation

import backend.lean.LeanInputFiles
import dataset.HashCalculator
import domain.ArtefactRole
import domain.BenchmarkTask
import domain.Sha256Digest
import domain.TaskId
import java.nio.file.Path
import kotlinx.serialization.json.*

data class LeanArtefactComparison(val taskId: TaskId, val roles: Set<ArtefactRole>)

/** Private curation metadata; its filenames and declarations are data, not evaluator rules. */
data class LeanValidationProfile(
    val excludedDeclarations: List<String>,
    val referenceSubmission: String,
    val standaloneReference: String?,
    val sameArtefactsAs: List<LeanArtefactComparison>,
)

/** Strict reader for lean-validation-profile-v1.schema.json. References stay runner-only. */
class LeanValidationProfileReader(validationRoot: Path, referenceRoot: Path) {
    private val validationRoot = LeanInputFiles.root(validationRoot)
    private val referenceRoot = LeanInputFiles.root(referenceRoot)

    fun read(task: BenchmarkTask): LeanValidationProfile {
        val relative = "${task.manifest.paperId.value}/${task.manifest.taskId.value}.json"
        val document = Json.parseToJsonElement(LeanInputFiles.read(validationRoot, relative)) as? JsonObject
            ?: error("validation profile must be an object")
        document.only("schema_version", "task_id", "excluded_declarations", "reference_submission", "standalone_reference", "same_artefacts_as")
        val version = document["schema_version"] as? JsonPrimitive
        require(version != null && !version.isString && version.intOrNull == 1) { "unsupported validation profile version" }
        require(document.string("task_id") == task.manifest.taskId.value) { "validation profile task identity mismatch" }
        val excluded = document.strings("excluded_declarations")
        require(excluded.distinct().size == excluded.size && excluded.all { it.matches(declarationName) }) {
            "excluded declarations must be unique qualified Lean names"
        }
        fun file(element: JsonElement): String {
            val descriptor = element as? JsonObject ?: error("reference descriptor must be an object")
            descriptor.only("path", "sha256")
            val path = LeanInputFiles.path(referenceRoot, descriptor.string("path"))
            val expected = Sha256Digest(descriptor.string("sha256"))
            require(HashCalculator().sha256(path) == expected) { "validation reference hash mismatch" }
            return java.nio.file.Files.readString(path)
        }
        val comparisons = document["same_artefacts_as"]?.let { element ->
            (element as? JsonArray ?: error("same_artefacts_as must be an array")).map {
                val comparison = it as? JsonObject ?: error("artefact comparison must be an object")
                comparison.only("task_id", "roles")
                val id = TaskId(comparison.string("task_id"))
                require(id != task.manifest.taskId) { "artefact comparison cannot reference itself" }
                val roles = comparison.strings("roles").map(::ArtefactRole)
                require(roles.isNotEmpty() && roles.distinct().size == roles.size) { "comparison roles must be non-empty and unique" }
                LeanArtefactComparison(id, roles.toSet())
            }
        }.orEmpty()
        require(comparisons.map { it.taskId }.distinct().size == comparisons.size) { "duplicate artefact comparison task" }
        return LeanValidationProfile(excluded, file(document.getValue("reference_submission")),
            document["standalone_reference"]?.let(::file), comparisons)
    }

    private fun JsonObject.only(vararg keys: String) {
        require((this.keys - keys.toSet()).isEmpty()) { "unsupported validation profile fields" }
    }

    private fun JsonObject.string(key: String): String {
        val primitive = this[key] as? JsonPrimitive
        require(primitive?.isString == true && primitive.content.isNotBlank()) { "$key must be a non-blank string" }
        return primitive.content
    }

    private fun JsonObject.strings(key: String): List<String> =
        (this[key] as? JsonArray ?: error("$key must be an array")).map {
            val primitive = it as? JsonPrimitive
            require(primitive?.isString == true && primitive.content.isNotBlank()) { "$key must contain non-blank strings" }
            primitive.content
        }

    companion object {
        private val declarationName = Regex("[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)+")
    }
}
