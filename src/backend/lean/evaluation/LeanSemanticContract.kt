package backend.lean.evaluation

import domain.ArtefactPath
import domain.BenchmarkTask
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.security.MessageDigest
import kotlinx.serialization.json.*

data class SemanticOutput(val name: String, val type: String)
data class SemanticObligation(val name: String, val proof: String, val refutation: String? = null)
data class LeanSemanticContract(
    val digest: String,
    val fileDigests: Map<String, String>,
    val outputs: List<SemanticOutput>,
    val referenceSubmission: String,
    val reference: String,
    val specification: String,
    val obligations: List<SemanticObligation>,
    val compatibility: List<SemanticObligation>,
)

/** Reads immutable, runner-only contracts. No filenames depend on a particular paper. */
class LeanSemanticContractReader(root: Path) {
    private val root = root.toAbsolutePath().normalize().let { if (Files.exists(it)) it.toRealPath() else it }

    fun read(task: BenchmarkTask): LeanSemanticContract {
        val locator = requireNotNull(task.manifest.evaluation.contract) { "lean_semantic requires evaluation.contract" }
        val path = root.resolve(locator.path.value)
        val source = readRegular(path, root)
        require(hash(source) == locator.sha256.value) { "evaluation contract hash mismatch" }
        val document = Json.parseToJsonElement(source) as? JsonObject ?: error("evaluation contract must be an object")
        document.only("schema_version", "task_id", "paper_id", "protocol", "outputs", "reference_submission", "reference", "specification", "obligations", "compatibility")
        val version = document["schema_version"] as? JsonPrimitive
        require(version != null && !version.isString && version.intOrNull == 1) { "unsupported semantic contract version" }
        require(document.string("task_id") == task.manifest.taskId.value &&
            document.string("paper_id") == task.manifest.paperId.value &&
            document.string("protocol") == task.manifest.protocol.value) { "evaluation contract identity mismatch" }
        val digests = linkedMapOf<String, String>()
        fun file(value: JsonElement): String {
            val descriptor = value.jsonObject
            descriptor.only("path", "sha256")
            val relative = ArtefactPath(Path.of(descriptor.string("path"))).value
            val file = path.parent.resolve(relative)
            val text = readRegular(file, path.parent)
            val digest = domain.Sha256Digest(descriptor.string("sha256"))
            require(hash(text) == digest.value) { "semantic evaluation file hash mismatch: $relative" }
            digests[relative.toString()] = digest.value
            return text
        }
        fun checks(key: String): List<SemanticObligation> = document[key]?.jsonArray.orEmpty().map {
            val check = it.jsonObject
            check.only("name", "proof", "refutation")
            val name = check.string("name")
            require(name.matches(Regex("AstridSpec\\.[a-z][a-z0-9_]*"))) { "invalid semantic obligation name" }
            SemanticObligation(name, file(check.getValue("proof")), check["refutation"]?.let(::file))
        }
        val outputs = document.getValue("outputs").jsonArray.map {
            val output = it.jsonObject
            output.only("name", "type")
            val name = output.string("name")
            require(name.matches(Regex("Benchmark\\.[A-Za-z][A-Za-z0-9_]*"))) { "output must be a declaration directly inside Benchmark" }
            SemanticOutput(name, output.string("type"))
        }
        require(outputs.isNotEmpty() && outputs.map { it.name }.distinct().size == outputs.size) { "required outputs must be non-empty and unique" }
        val referenceSubmission = file(document.getValue("reference_submission"))
        val reference = file(document.getValue("reference"))
        val specification = file(document.getValue("specification"))
        val obligations = checks("obligations")
        val compatibility = checks("compatibility")
        require(obligations.isNotEmpty()) { "semantic contracts require a mandatory obligation" }
        require((obligations + compatibility).map { it.name }.distinct().size == obligations.size + compatibility.size) { "duplicate obligation names" }
        return LeanSemanticContract(locator.sha256.value, digests, outputs, referenceSubmission, reference,
            specification, obligations, compatibility)
    }

    private fun readRegular(path: Path, boundary: Path): String {
        val normal = path.toAbsolutePath().normalize()
        require(normal.startsWith(boundary) && Files.isRegularFile(normal, NOFOLLOW_LINKS)) { "unsafe or missing evaluation file" }
        require(normal.toRealPath() == normal && normal.toRealPath().startsWith(boundary.toRealPath())) { "evaluation files must not use symbolic links" }
        return Files.readString(normal)
    }

    private fun hash(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun JsonObject.string(key: String): String {
        val value = this[key] as? JsonPrimitive
        require(value?.isString == true && value.content.isNotBlank()) { "$key must be a non-blank string" }
        return value.content
    }

    private fun JsonObject.only(vararg fields: String) {
        require((keys - fields.toSet()).isEmpty()) { "unsupported semantic contract fields" }
    }
}
