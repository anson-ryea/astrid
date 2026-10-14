package backend.lean.workspace

import backend.lean.LeanInputFiles
import dataset.HashCalculator
import domain.BenchmarkTask
import domain.Sha256Digest
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.*

internal data class LeanContext(
    val metadata: String,
    val imports: List<String>,
    val sources: Map<String, String>,
    val sha256: String,
)

/** Reads the V1 context schema and computes its exact public dependency closure. */
internal class LeanContextReader(datasetRoot: Path) {
    private val root = LeanInputFiles.root(datasetRoot)
    private val json = Json { prettyPrint = true }

    fun read(task: BenchmarkTask, updateHashes: Boolean = false): LeanContext {
        val paper = task.manifest.paperId.value
        val id = task.manifest.contextId.value
        val path = LeanInputFiles.path(root, "$paper/contexts/$id.json")
        var metadata = Files.readString(path)
        var document = Json.parseToJsonElement(metadata) as? JsonObject
            ?: error("context metadata must be an object")
        require(document.keys == setOf("schema_version", "context_id", "paper_id", "imports", "files", "closure")) {
            "context metadata has missing or unsupported fields"
        }
        val version = document["schema_version"] as? JsonPrimitive
        require(version != null && !version.isString && version.intOrNull == 1) { "unsupported context schema version" }
        require(document.string("context_id") == id && document.string("paper_id") == paper) {
            "context metadata identity does not match task"
        }
        val imports = document.strings("imports")
        require(imports.isNotEmpty() && imports.distinct().size == imports.size) { "context imports must be non-empty and unique" }
        val declaredFiles = document.strings("files")
        require(declaredFiles.isNotEmpty() && declaredFiles.distinct().size == declaredFiles.size) {
            "context files must be non-empty and unique"
        }
        require(declaredFiles.all {
            it.matches(Regex("dataset/${Regex.escape(paper)}/formalisation/(?:[A-Za-z0-9_]+/)*[A-Za-z0-9_]+\\.lean"))
        }) { "context files must name public formalisation sources for the task's paper" }
        val closure = document["closure"] as? JsonObject ?: error("missing context closure")
        require(closure.keys == setOf("algorithm", "sha256")) { "context closure has missing or unsupported fields" }
        require(closure.string("algorithm") == "sha256_length_prefixed_paths_and_contents_v1") {
            "unsupported context closure hash algorithm"
        }
        Sha256Digest(closure.string("sha256"))

        val contextArtefact = task.manifest.artefacts.single { it.role.value == "lean_context" }
        val contextSource = LeanInputFiles.read(LeanInputFiles.root(task.packageDirectory), contextArtefact.path.toString())
        require(sourceImports(contextSource) == imports) { "task Context.lean imports do not match context metadata" }
        val sources = linkedMapOf<String, String>()
        val visiting = mutableSetOf<String>()
        fun visit(module: String) {
            require(module.matches(Regex("dataset\\.${Regex.escape(paper)}\\.formalisation\\.[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)*"))) {
                "context import must be a public formalisation module: $module"
            }
            val relative = module.replace('.', '/') + ".lean"
            if (relative in sources) return
            require(visiting.add(module)) { "cyclic context imports: $module" }
            val source = LeanInputFiles.read(root, relative.removePrefix("dataset/"))
            sourceImports(source).filter { it.startsWith("dataset.") }.forEach(::visit)
            visiting.remove(module)
            sources[relative] = source
        }
        imports.forEach(::visit)
        val digest = HashCalculator().sha256Closure(sources).value
        if (updateHashes) {
            document = JsonObject(document + mapOf(
                "files" to JsonArray(sources.keys.map(::JsonPrimitive)),
                "closure" to JsonObject(closure + ("sha256" to JsonPrimitive(digest))),
            ))
            metadata = json.encodeToString(JsonObject.serializer(), document) + "\n"
            Files.writeString(path, metadata)
        } else {
            require(sources.keys.toList() == declaredFiles) { "context file list does not match its import closure" }
            require(digest == closure.string("sha256")) { "context closure hash mismatch: $id" }
        }
        return LeanContext(metadata, imports, sources, digest)
    }

    private fun JsonObject.string(name: String): String {
        val value = this[name] as? JsonPrimitive
        require(value?.isString == true && value.content.isNotBlank()) { "context field $name must be a non-blank string" }
        return value.content
    }

    private fun JsonObject.strings(name: String): List<String> =
        (this[name] as? JsonArray ?: error("context field $name must be an array")).map {
            val value = it as? JsonPrimitive
            require(value?.isString == true && value.content.isNotBlank()) { "context field $name must contain non-blank strings" }
            value.content
        }

    companion object {
        /** Remove nested comments before reading one or several modules per import line. */
        internal fun sourceImports(source: String): List<String> {
            val text = StringBuilder()
            var index = 0
            var depth = 0
            var lineComment = false
            var quoted = false
            while (index < source.length) {
                val char = source[index]
                val pair = source.substring(index, minOf(index + 2, source.length))
                when {
                    lineComment -> {
                        text.append(if (char == '\n') '\n' else ' ')
                        if (char == '\n') lineComment = false
                        index++
                    }
                    depth > 0 -> when (pair) {
                        "/-" -> { depth++; text.append("  "); index += 2 }
                        "-/" -> { depth--; text.append("  "); index += 2 }
                        else -> { text.append(if (char == '\n') '\n' else ' '); index++ }
                    }
                    quoted -> {
                        text.append(if (char == '\n') '\n' else ' ')
                        if (char == '\\' && index + 1 < source.length) { text.append(' '); index++ }
                        else if (char == '"') quoted = false
                        index++
                    }
                    pair == "--" -> { lineComment = true; text.append("  "); index += 2 }
                    pair == "/-" -> { depth = 1; text.append("  "); index += 2 }
                    char == '\'' && characterLiteral.matchAt(source, index) != null -> {
                        val literal = characterLiteral.matchAt(source, index)!!.value
                        text.append(" ".repeat(literal.length)); index += literal.length
                    }
                    char == '"' -> { quoted = true; text.append(' '); index++ }
                    else -> { text.append(char); index++ }
                }
            }
            require(depth == 0 && !quoted) { "unterminated Lean comment or string" }
            return Regex("(?m)^[\\t ]*(?:public[\\t ]+)?import[\\t ]+([^\\r\\n]+)")
                .findAll(text).flatMap { it.groupValues[1].trim().split(Regex("\\s+")).asSequence() }
                .filter(String::isNotEmpty).toList()
        }

        private val characterLiteral = Regex("""'(?:[^'\\\r\n]|\\[^\r\n]+?)'""")
    }
}
