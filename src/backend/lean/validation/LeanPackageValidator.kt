package backend.lean.validation

import backend.lean.LeanInputFiles
import backend.lean.evaluation.LeanLakeProcess
import backend.lean.evaluation.LeanProcess
import backend.lean.evaluation.LeanProcessResult
import backend.lean.evaluation.LeanSemanticSourceBuilder
import backend.lean.workspace.LeanContextReader
import dataset.HashCalculator
import dataset.JsonTaskManifestReader
import dataset.TaskPackageVerifier
import domain.BenchmarkTask
import domain.EvaluationReport
import domain.EvaluationResult
import domain.TaskId
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.*
import workspace.TaskWorkspaceFactory

data class LeanPackageValidation(val taskId: TaskId, val report: EvaluationReport)

/** Offline Lean curation using the same isolated evaluators as benchmark submissions. */
class LeanPackageValidator(
    private val workspaces: TaskWorkspaceFactory,
    private val profiles: LeanValidationProfileReader,
    private val processFactory: (Path) -> LeanProcess = { LeanLakeProcess(it, inheritLeanPath = false) },
) {
    fun validateAll(tasks: List<BenchmarkTask>, onResult: (LeanPackageValidation) -> Unit = {}): List<LeanPackageValidation> {
        require(tasks.isNotEmpty()) { "dataset contains no selected tasks" }
        require(tasks.map { it.manifest.taskId }.distinct().size == tasks.size) { "duplicate task identifiers" }
        val byId = tasks.associateBy { it.manifest.taskId }
        return tasks.map { task ->
            val report = try {
                TaskPackageVerifier().verify(task)
                verifySourceRegistration(task)
                require(task.manifest.protocol.value in protocols && task.manifest.evaluation.kind.value in kinds) {
                    "unsupported Lean protocol or evaluation kind"
                }
                require(task.manifest.taskType.value != 3 ||
                    (task.manifest.condition.value == "statement_only" && task.manifest.artefacts.none {
                        it.role.value in setOf("paper_evidence", "enriched_evidence")
                    })) { "Type 3 tasks must be statement-only without proof evidence" }
                val profile = profiles.read(task)
                for (comparison in profile.sameArtefactsAs) {
                    val other = byId[comparison.taskId] ?: error("comparison task is not selected: ${comparison.taskId}")
                    TaskPackageVerifier().verify(other)
                    comparison.roles.forEach { role ->
                        require(readRole(task, role.value) == readRole(other, role.value)) {
                            "${task.manifest.taskId} and ${other.manifest.taskId} differ in $role"
                        }
                    }
                }
                val plan = workspaces.prepare(task)
                plan.create().use { session ->
                    // Check the imported environment before inserting any private reference.
                    val boundary = processFactory(session.workspace.root).run(
                        boundarySource(readRole(task, "lean_context"), profile.excludedDeclarations),
                        task.manifest.evaluation.timeout,
                    )
                    require(boundary is LeanProcessResult.Completed && boundary.exitCode == 0) {
                        "isolated context boundary check failed"
                    }
                    session.validateEvaluation()
                    val template = session.workspace.read("Solution.lean")
                    val marker = LeanSemanticSourceBuilder.marker(task.manifest.protocol.value)
                    val instantiated = template.replace(marker, profile.referenceSubmission.trim())
                    profile.standaloneReference?.let {
                        require(it == instantiated) { "standalone reference differs from the instantiated target" }
                    }
                    session.workspace.write("Solution.lean", instantiated)
                    session.assessDetailed().second
                }
            } catch (failure: Exception) {
                // Profiles and calibration are private. Do not print their source diagnostics.
                EvaluationReport(EvaluationResult.InfrastructureFailure(failure.message ?: "Lean package validation failed"),
                    publicDiagnostic = null)
            }
            LeanPackageValidation(task.manifest.taskId, report).also(onResult)
        }
    }

    private fun readRole(task: BenchmarkTask, role: String): String {
        val artefact = task.manifest.artefacts.single { it.role.value == role }
        return LeanInputFiles.read(LeanInputFiles.root(task.packageDirectory), artefact.path.toString())
    }

    private fun verifySourceRegistration(task: BenchmarkTask) {
        val paperRoot = LeanInputFiles.root(task.packageDirectory.parent.parent)
        if (!Files.exists(paperRoot.resolve("paper.json"), java.nio.file.LinkOption.NOFOLLOW_LINKS)) return
        val document = Json.parseToJsonElement(LeanInputFiles.read(paperRoot, "paper.json")).jsonObject
        val version = document["schema_version"] as? JsonPrimitive
        require(version != null && !version.isString && version.intOrNull == 1 &&
            document["paper_id"] == JsonPrimitive(task.manifest.paperId.value)) { "paper registry identity or version mismatch" }
        val registered = document.getValue("results").jsonArray.map {
            val id = it.jsonObject["id"] as? JsonPrimitive
            require(id?.isString == true) { "registered source result must have a string identifier" }
            domain.SourceResultId(id.content)
        }
        require(registered.isNotEmpty() && registered.distinct().size == registered.size) { "paper results must be non-empty and unique" }
        require(task.manifest.sourceResultId in registered) { "unregistered paper source result: ${task.manifest.sourceResultId}" }
    }

    companion object {
        private val protocols = setOf("lean_proof", "lean_definition", "lean_declaration", "lean_statement")
        private val kinds = setOf("lean_kernel", "lean_semantic")

        internal fun boundarySource(context: String, names: List<String>): String =
            "import Lean\n$context\nrun_elab do\n  let env ← Lean.getEnv\n  pure ()\n" +
                names.joinToString("") { name ->
                    "  if env.contains `$name then\n    throwError \"excluded declaration exposed: $name\"\n"
                }

        /** Explicit authoring operation: refresh public hashes, then run normal validation. */
        fun updatePublicHashes(tasks: List<BenchmarkTask>, datasetRoot: Path): List<BenchmarkTask> {
            val contexts = LeanContextReader(datasetRoot)
            val reader = JsonTaskManifestReader()
            val json = Json { prettyPrint = true }
            return tasks.map { task ->
                val root = LeanInputFiles.root(task.packageDirectory)
                val path = LeanInputFiles.path(root, "manifest.json")
                val document = Json.parseToJsonElement(Files.readString(path)).jsonObject
                val artefacts = document.getValue("artefacts").jsonArray.map { value ->
                    val artefact = value.jsonObject
                    val file = LeanInputFiles.path(root, artefact.getValue("path").jsonPrimitive.content)
                    JsonObject(artefact + ("sha256" to JsonPrimitive(HashCalculator().sha256(file).value)))
                }
                // The context reader independently validates identities, paths and imports.
                contexts.read(task, updateHashes = true)
                Files.writeString(path, json.encodeToString(JsonObject.serializer(),
                    JsonObject(document + ("artefacts" to JsonArray(artefacts)))) + "\n")
                BenchmarkTask(reader.read(path), root)
            }
        }
    }
}
