package backend.lean.workspace

import backend.lean.evaluation.LeanLakeProcess
import backend.lean.evaluation.LeanProcess
import backend.lean.evaluation.LeanProofEvaluator
import backend.lean.evaluation.LeanSemanticEvaluator
import backend.lean.evaluation.LeanSemanticContractReader
import backend.lean.evaluation.LeanSemanticSourceBuilder
import dataset.HashCalculator
import dataset.TaskPackageVerifier
import domain.BenchmarkTask
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import prompt.PromptRenderer
import workspace.TaskWorkspace
import workspace.TaskWorkspaceFactory
import workspace.TaskWorkspacePlan
import workspace.WorkspaceFile
import workspace.WorkspaceSpecification

/** Stages only the public task materials and its reviewed, hashed import closure. */
class LeanTaskWorkspaceFactory(
    environmentRoot: Path,
    datasetRoot: Path = environmentRoot.resolve("dataset"),
    private val buildDependencies: (Path, List<String>) -> Unit = ::buildLeanDependencies,
    private val leanProcessFactory: (Path) -> LeanProcess = { LeanLakeProcess(it, inheritLeanPath = false) },
    private val promptRoot: Path = Path.of("prompts"),
    private val evaluationRoot: Path = environmentRoot.resolve("private/evaluation"),
    private val semanticSources: LeanSemanticSourceBuilder = LeanSemanticSourceBuilder(),
) : TaskWorkspaceFactory {
    private val environmentRoot = environmentRoot.toAbsolutePath().normalize()
    private val datasetRoot = datasetRoot.toAbsolutePath().normalize()

    /** Validate and snapshot inputs before any provider calls. No golden files are read. */
    override fun prepare(task: BenchmarkTask): Plan {
        require(task.manifest.protocol.value in setOf("lean_proof", "lean_definition", "lean_declaration", "lean_statement")) { "unsupported Lean workspace protocol" }
        TaskPackageVerifier().verify(task)
        val paper = task.manifest.paperId.value
        val contextId = task.manifest.contextId.value
        val contextPath = datasetRoot.resolve("$paper/contexts/$contextId.json")
        val metadata = readRegular(contextPath, datasetRoot)
        val document = Json.parseToJsonElement(metadata) as? JsonObject
            ?: error("context metadata must be an object")
        require((document["schema_version"] as? JsonPrimitive)?.intOrNull == 1) { "unsupported context schema version" }
        require(document.string("context_id") == contextId && document.string("paper_id") == paper) {
            "context metadata identity does not match task"
        }
        val imports = document.strings("imports")
        require(imports.isNotEmpty() && imports.distinct().size == imports.size) { "context imports must be non-empty and unique" }
        val declaredFiles = document.strings("files")
        val closure = document["closure"] as? JsonObject ?: error("missing context closure")
        require(closure.string("algorithm") == "sha256_length_prefixed_paths_and_contents_v1") {
            "unsupported context closure hash algorithm"
        }

        val sources = linkedMapOf<String, String>()
        val visiting = mutableSetOf<String>()
        fun visit(module: String) {
            require(module.matches(Regex("dataset\\.${Regex.escape(paper)}\\.formalisation\\.[A-Za-z0-9_.]+"))) {
                "context import must be a public formalisation module: $module"
            }
            val relative = module.replace('.', '/') + ".lean"
            if (relative in sources) return
            require(visiting.add(module)) { "cyclic context imports: $module" }
            val source = readRegular(datasetRoot.resolve(relative.removePrefix("dataset/")), datasetRoot)
            sourceImports(source).filter { it.startsWith("dataset.") }.forEach(::visit)
            visiting.remove(module)
            sources[relative] = source
        }
        imports.forEach(::visit)
        require(sources.keys.toList() == declaredFiles) { "context file list does not match its import closure" }
        val closureHash = HashCalculator().sha256Closure(sources).value
        require(closureHash == closure.string("sha256")) { "context closure hash mismatch: $contextId" }

        val packagePath = "tasks/${task.manifest.taskId.value}"
        val artefacts = task.manifest.artefacts.associate { artefact ->
            require(artefact.role.value in publicRoles) { "workspace cannot expose artefact role ${artefact.role}" }
            "$packagePath/${artefact.path.value}" to readRegular(task.pathFor(artefact), task.packageDirectory)
        }
        fun required(role: String): String {
            val artefact = task.manifest.artefacts.single { it.role.value == role }
            return artefacts.getValue("$packagePath/${artefact.path.value}")
        }
        val context = required("lean_context")
        require(sourceImports(context) == imports) { "task Context.lean imports do not match context metadata" }
        val target = required("lean_target")
        // The task protocol and evaluator retain the original marker/statement contract.
        val template = context + "\n" + target
        val marker = LeanSemanticSourceBuilder.marker(task.manifest.protocol.value)
        require(template.indexOf(marker) >= 0 && template.indexOf(marker) == template.lastIndexOf(marker)) {
            "target template must contain exactly one submission marker"
        }
        val inputs = linkedMapOf<String, String>()
        for (name in listOf("lean-toolchain", "lakefile.toml", "lake-manifest.json")) {
            inputs[name] = readRegular(environmentRoot.resolve(name), environmentRoot)
        }
        // The staged tree contains only the selected closure. Its library glob must
        // discover new paper identifiers without adding them to the host Lake file.
        inputs["lakefile.toml"] = inputs.getValue("lakefile.toml").replace(
            Regex("(?s)globs\\s*=\\s*\\[.*?]"), "globs = [\"dataset.+\"]")
        // No host dataset .olean files are reused: these modules are built from this closure.
        require(Files.isDirectory(environmentRoot.resolve(".lake/packages"))) { "Lake package cache is missing; run lake build first" }
        inputs.putAll(sources)
        inputs["contexts/$contextId.json"] = metadata
        inputs["$packagePath/manifest.json"] = readRegular(task.packageDirectory.resolve("manifest.json"), task.packageDirectory)
        inputs.putAll(artefacts)
        val instructions = PromptRenderer(promptRoot.resolve("common/v1/workspace.md")).render(emptyMap()) +
            "\n\n" + if (task.manifest.protocol.value == "lean_proof") {
                PromptRenderer(promptRoot.resolve("lean/v1/proof-workspace.md")).render(mapOf("PROOF_MARKER" to marker))
            } else {
                "Edit only the $marker slot in /workspace/Solution.lean. Submit the requested Lean " +
                    "term or declarations, preserving the fixed context and target. Your final response is not the submission. " +
                    "Scratch files are not imported. Private semantic checks provide no repair feedback."
            }
        return Plan(task, imports, contextId, closureHash, template,
            inputs.map { (path, content) -> TaskWorkspace.snapshotFile(path, content) }, instructions)
    }

    private fun create(plan: Plan): LeanProofWorkspace {
        val root = Files.createTempDirectory("astrid-task-").toAbsolutePath().normalize()
        try {
            val workspace = TaskWorkspace(root, plan.inputs, plan.contextId, plan.closureSha256,
                WorkspaceSpecification(mapOf("Solution.lean" to plan.template), Regex("scratch/[A-Za-z0-9_-]+\\.lean")))
            Files.createDirectories(root.resolve(".lake"))
            // Runner-only shared external packages. File tools cannot see or traverse this link.
            Files.createSymbolicLink(root.resolve(".lake/packages"), environmentRoot.resolve(".lake/packages").toRealPath())
            buildDependencies(root, plan.imports)
            return LeanProofWorkspace(workspace,
                BenchmarkTask(plan.task.manifest, root.resolve("tasks/${plan.task.manifest.taskId.value}")),
                plan.template, if (plan.task.manifest.protocol.value == "lean_proof") LeanProofEvaluator(leanProcessFactory(root))
                else LeanSemanticEvaluator(leanProcessFactory(root), LeanSemanticContractReader(evaluationRoot), semanticSources),
                plan.submissionInstructions, LeanSemanticSourceBuilder.marker(plan.task.manifest.protocol.value))
        } catch (failure: Throwable) {
            try {
                Files.walk(root).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
            } catch (cleanup: Exception) { failure.addSuppressed(cleanup) }
            throw failure
        }
    }

    inner class Plan internal constructor(
        internal val task: BenchmarkTask,
        internal val imports: List<String>,
        internal val contextId: String,
        internal val closureSha256: String,
        internal val template: String,
        internal val inputs: List<WorkspaceFile>,
        internal val submissionInstructions: String,
    ) : TaskWorkspacePlan {
        override fun create(): LeanProofWorkspace = this@LeanTaskWorkspaceFactory.create(this)
    }

    private fun readRegular(path: Path, boundary: Path): String {
        val normal = path.toAbsolutePath().normalize()
        require(normal.startsWith(boundary)) { "input escapes source directory" }
        require(Files.isRegularFile(normal, NOFOLLOW_LINKS) && normal.toRealPath() == normal &&
            normal.toRealPath().startsWith(boundary.toRealPath())) { "input must be a regular file without symbolic links: $path" }
        return Files.readString(normal)
    }

    private fun JsonObject.string(name: String): String {
        val value = this[name] as? JsonPrimitive
        require(value != null && value.isString) { "context field $name must be a string" }
        return value.content
    }

    private fun JsonObject.strings(name: String): List<String> {
        val values = this[name] as? JsonArray ?: error("context field $name must be an array")
        return values.map {
            val value = it as? JsonPrimitive
            require(value != null && value.isString) { "context field $name must contain strings" }
            value.content
        }
    }

    companion object {
        private val publicRoles = setOf("lean_context", "lean_target", "paper_evidence", "enriched_evidence")

        internal fun sourceImports(source: String): List<String> = Regex("(?m)^import\\s+([^\\r\\n]+)")
            .findAll(source).flatMap { it.groupValues[1].substringBefore("--").trim().split(Regex("\\s+")).asSequence() }
            .filter { it.isNotEmpty() }.toList()

        private fun buildLeanDependencies(root: Path, imports: List<String>) {
            val output = root.resolve(".lake/workspace-build.log")
            val builder = ProcessBuilder(listOf("lake", "build") + imports)
                .directory(root.toFile()).redirectErrorStream(true).redirectOutput(output.toFile())
            builder.environment().remove("LEAN_PATH")
            builder.environment().remove("LEAN_SRC_PATH")
            val process = builder.start()
            try {
                check(process.waitFor(5.minutes.toJavaDuration())) { "workspace prerequisite build timed out" }
                check(process.exitValue() == 0) { "workspace prerequisite build failed:\n${Files.readString(output)}" }
            } finally {
                if (process.isAlive) {
                    process.destroyForcibly()
                    process.waitFor(5.seconds.toJavaDuration())
                }
            }
        }
    }
}
