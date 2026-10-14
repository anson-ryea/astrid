package backend.lean.validation

import support.SimpleTestDataset
import backend.lean.evaluation.LeanProcess
import backend.lean.evaluation.LeanProcessResult
import backend.lean.workspace.LeanTaskWorkspaceFactory
import dataset.HashCalculator
import dataset.JsonTaskManifestReader
import domain.BenchmarkTask
import domain.EvaluationResult
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir

class LeanPackageValidatorTest {
    @TempDir
    lateinit var temporaryDirectory: Path
    private val project = Path.of("").toAbsolutePath().normalize()
    private val original = SimpleTestDataset.task("proof")
    private val reader = JsonTaskManifestReader()
    private val hash = HashCalculator()

    /** An additional paper in an external dataset, with no evaluator or Lake configuration edits. */
    private fun fixture(): BenchmarkTask {
        val root = temporaryDirectory.toRealPath()
        val packageRoot = root.resolve("dataset/paper_37/tasks/task_37.1_addition")
        Files.createDirectories(packageRoot)
        val source = "namespace Synthetic\ndef base : Nat := 1\nend Synthetic\n"
        val relative = "dataset/paper_37/formalisation/Prerequisite.lean"
        val module = "dataset.paper_37.formalisation.Prerequisite"
        val sourcePath = root.resolve(relative)
        Files.createDirectories(sourcePath.parent)
        Files.writeString(sourcePath, source)
        Files.writeString(packageRoot.resolve("Context.lean"), "import $module\n")
        Files.writeString(packageRoot.resolve("Target.lean"),
            "namespace Benchmark\ntheorem target : Synthetic.base + 1 = 2 :=\n__BENCHMARK_PROOF__\nend Benchmark\n#print axioms Benchmark.target\n")
        Files.writeString(packageRoot.resolve("evidence.md"), "Synthetic arithmetic evidence.\n")
        val old = Json.parseToJsonElement(Files.readString(original.packageDirectory.resolve("manifest.json"))).jsonObject
        val artefacts = old.getValue("artefacts").jsonArray.map {
            val artefact = it.jsonObject
            JsonObject(artefact + ("sha256" to JsonPrimitive(hash.sha256(packageRoot.resolve(artefact.getValue("path").jsonPrimitive.content)).value)))
        }
        val evaluation = old.getValue("evaluation").jsonObject
        val manifest = JsonObject(old + mapOf(
            "task_id" to JsonPrimitive("task_37.1_addition"), "paper_id" to JsonPrimitive("paper_37"),
            "source_result_id" to JsonPrimitive("addition"), "context_id" to JsonPrimitive("addition"),
            "status" to JsonPrimitive("candidate_pending_independent_review"),
            "artefacts" to JsonArray(artefacts),
            "evaluation" to JsonObject(evaluation + ("forbidden_identifiers" to JsonArray(emptyList()))),
        ))
        Files.writeString(packageRoot.resolve("manifest.json"), manifest.toString())
        val context = buildJsonObject {
            put("schema_version", 1); put("context_id", "addition"); put("paper_id", "paper_37")
            put("imports", JsonArray(listOf(JsonPrimitive(module))))
            put("files", JsonArray(listOf(JsonPrimitive(relative))))
            put("closure", buildJsonObject {
                put("algorithm", "sha256_length_prefixed_paths_and_contents_v1")
                put("sha256", hash.sha256Closure(linkedMapOf(relative to source)).value)
            })
        }
        Files.createDirectories(root.resolve("dataset/paper_37/contexts"))
        Files.writeString(root.resolve("dataset/paper_37/contexts/addition.json"), context.toString())
        Files.createDirectories(root.resolve("private/validation/paper_37"))
        Files.writeString(root.resolve("private/reference.proof"), "by decide\n")
        val profile = buildJsonObject {
            put("schema_version", 1); put("task_id", "task_37.1_addition")
            put("excluded_declarations", JsonArray(listOf(JsonPrimitive("Synthetic.answer"))))
            put("reference_submission", buildJsonObject {
                put("path", "reference.proof"); put("sha256", hash.sha256(root.resolve("private/reference.proof")).value)
            })
        }
        Files.writeString(root.resolve("private/validation/paper_37/task_37.1_addition.json"), profile.toString())
        return BenchmarkTask(reader.read(packageRoot.resolve("manifest.json")), packageRoot)
    }

    private fun validator(realLean: Boolean = false): LeanPackageValidator {
        val root = temporaryDirectory.toRealPath()
        val profiles = LeanValidationProfileReader(root.resolve("private/validation"), root.resolve("private"))
        if (realLean) return LeanPackageValidator(LeanTaskWorkspaceFactory(project, root.resolve("dataset"),
            promptRoot = project.resolve("prompts")), profiles)
        val process = LeanProcess { _, _ -> LeanProcessResult.Completed(0, "'Benchmark.target' does not depend on any axioms") }
        val factory = LeanTaskWorkspaceFactory(project, root.resolve("dataset"), promptRoot = project.resolve("prompts"),
            buildDependencies = { _, _ -> }, leanProcessFactory = { process })
        return LeanPackageValidator(factory, profiles, processFactory = { process })
    }

    @org.junit.jupiter.api.Tag("lean-integration")
    @Test
    fun newPaperValidatesWithRealLeanAndDetectsAnExposedAnswer() {
        val task = fixture()
        assertEquals(EvaluationResult.Accepted, validator(realLean = true).validateAll(listOf(task)).single().report.result)
        val source = temporaryDirectory.resolve("dataset/paper_37/formalisation/Prerequisite.lean")
        Files.writeString(source, Files.readString(source) + "def Synthetic.answer : Nat := 2\n")
        val refreshed = LeanPackageValidator.updatePublicHashes(listOf(task), temporaryDirectory.resolve("dataset"))
        val failed = validator(realLean = true).validateAll(refreshed).single().report.result
        assertIs<EvaluationResult.InfrastructureFailure>(failed)
        assertContains(failed.reason, "boundary check failed")
    }

    @Test fun reportsPolicyFailuresAndRejectsEmptyOrDuplicateSelections() {
        val task = fixture()
        val rejected = task.copy(manifest = task.manifest.copy(submission = task.manifest.submission.copy(allowLocalHelpers = false)))
        assertIs<EvaluationResult.InfrastructureFailure>(validator().validateAll(listOf(rejected)).single().report.result)
        assertEquals(EvaluationResult.Accepted, validator().validateAll(listOf(task)).single().report.result)
        assertFails { validator().validateAll(emptyList()) }
        assertFails { validator().validateAll(listOf(task, task)) }
    }

    @Test fun continuesAfterAPackageFailureAndReportsEverySelectedTask() {
        val tasks = SimpleTestDataset.tasks().filter { it.manifest.protocol.value == "lean_proof" }
        val process = LeanProcess { _, _ -> LeanProcessResult.Completed(0, "'Benchmark.target' does not depend on any axioms") }
        val factory = LeanTaskWorkspaceFactory(project, SimpleTestDataset.datasetRoot, buildDependencies = { _, _ -> }, leanProcessFactory = { process })
        val failingFactory = workspace.TaskWorkspaceFactory { task ->
            if (task.manifest.taskId == tasks.first().manifest.taskId) error("synthetic package failure")
            factory.prepare(task)
        }
        val profiles = SimpleTestDataset.profiles()
        val observed = mutableListOf<LeanPackageValidation>()
        val results = LeanPackageValidator(failingFactory, profiles, processFactory = { process }).validateAll(tasks, observed::add)
        assertEquals(results, observed)
        assertIs<EvaluationResult.InfrastructureFailure>(results.first().report.result)
        assertEquals(EvaluationResult.Accepted, results.last().report.result)
    }

    @Test fun detectsStalePublicHashesAndExplicitlyRefreshesOnlyPublicInputs() {
        val task = fixture()
        Files.writeString(task.packageDirectory.resolve("Target.lean"), Files.readString(task.packageDirectory.resolve("Target.lean")) + "\n")
        assertIs<EvaluationResult.InfrastructureFailure>(validator().validateAll(listOf(task)).single().report.result)
        val privateFile = temporaryDirectory.resolve("private/validation/paper_37/task_37.1_addition.json")
        val profile = Files.readString(privateFile)
        val refreshed = LeanPackageValidator.updatePublicHashes(listOf(task), temporaryDirectory.resolve("dataset"))
        assertEquals(EvaluationResult.Accepted, validator().validateAll(refreshed).single().report.result)
        assertEquals(profile, Files.readString(privateFile))
    }

    @Test fun checksSourceResultRegistrationWhenPaperMetadataIsPresent() {
        val task = fixture()
        val path = temporaryDirectory.resolve("dataset/paper_37/paper.json")
        Files.writeString(path, """{"schema_version":1,"paper_id":"paper_37","results":[{"id":"addition"}]}""")
        assertEquals(EvaluationResult.Accepted, validator().validateAll(listOf(task)).single().report.result)
        Files.writeString(path, """{"schema_version":1,"paper_id":"paper_37","results":[{"id":"different"}]}""")
        val failure = validator().validateAll(listOf(task)).single().report.result
        assertContains(assertIs<EvaluationResult.InfrastructureFailure>(failure).reason, "unregistered paper source result")
    }

    @Test fun refusesTypeThreeProofEvidenceAndChangedStandaloneReferences() {
        val task = fixture()
        val invalid = task.copy(manifest = task.manifest.copy(taskType = domain.TaskTypeId(3), condition = domain.ConditionId("paper_proof")))
        assertContains((validator().validateAll(listOf(invalid)).single().report.result as EvaluationResult.InfrastructureFailure).reason,
            "statement-only")
        val root = temporaryDirectory.toRealPath()
        Files.writeString(root.resolve("private/reference.lean"), "theorem wrong : True := by trivial\n")
        val path = root.resolve("private/validation/paper_37/task_37.1_addition.json")
        val profile = Json.parseToJsonElement(Files.readString(path)).jsonObject
        Files.writeString(path, JsonObject(profile + ("standalone_reference" to buildJsonObject {
            put("path", "reference.lean"); put("sha256", hash.sha256(root.resolve("private/reference.lean")).value)
        })).toString())
        assertContains((validator().validateAll(listOf(task)).single().report.result as EvaluationResult.InfrastructureFailure).reason,
            "standalone reference differs")
    }
}
