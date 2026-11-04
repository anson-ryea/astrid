package command

import backend.BackendDatasetValidator
import backend.BackendRegistry
import backend.lean.LeanBackend
import backend.lean.validation.LeanPackageValidator
import backend.lean.validation.LeanValidationProfileReader
import com.github.ajalt.clikt.command.SuspendingCliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.default
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.defaultLazy
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.path
import com.github.ajalt.mordant.rendering.TextColors.*
import console.RunLogEntry
import console.renderMessage
import dataset.FileTaskRepository
import dataset.JsonTaskManifestReader
import domain.BenchmarkTask
import domain.EvaluationResult
import domain.PaperId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import workspace.TaskWorkspaceFactory
import java.nio.file.Path

/** Validates a dataset checkout independently of a benchmark run. */
class ValidateCommand(projectRoot: Path = Path.of("").toAbsolutePath().normalize()) :
    SuspendingCliktCommand("validate") {
    private val projectRoot = projectRoot.toAbsolutePath().normalize()

    override fun help(context: Context) =
        "Validate task packages, backend policies and workspaces without model calls. Includes tasks that are not frozen. Use --check-references for Lean curation and isolated private-reference checks."

    private val datasetPath by argument(help = "Dataset checkout directory containing dataset/ and its backend environment")
        .path(mustExist = true, canBeFile = false)
        .default(this.projectRoot)
    private val backendId by option("--backend", help = "Formalisation backend").default("lean")
    private val datasetRoot by option("--dataset-root", help = "Task directory; defaults to <dataset-path>/dataset")
        .path(mustExist = true, canBeFile = false)
        .defaultLazy(defaultForHelp = "<dataset-path>/dataset") { datasetPath.resolve("dataset") }
    private val environmentRoot by option(
        "--environment-root",
        help = "Pinned backend project and dependency cache; defaults to <dataset-path>"
    )
        .path(mustExist = true, canBeFile = false)
        .defaultLazy(defaultForHelp = "<dataset-path>") { datasetPath }
    private val evaluationRoot by option(
        "--evaluation-root",
        help = "Runner-only semantic contracts; defaults to <dataset-path>/private/evaluation"
    )
        .path(canBeFile = false)
        .defaultLazy(defaultForHelp = "<dataset-path>/private/evaluation") { datasetPath.resolve("private/evaluation") }
    private val checkReferences by option(
        "--check-references",
        help = "Also check Lean curation rules, isolated answer boundaries and private references"
    ).flag()
    private val validationRoot by option(
        "--validation-root",
        help = "Private Lean validation profiles; defaults to <dataset-path>/private/validation"
    )
        .path(mustExist = true, canBeFile = false)
        .defaultLazy(defaultForHelp = "<dataset-path>/private/validation") { datasetPath.resolve("private/validation") }
    private val referenceRoot by option(
        "--reference-root",
        help = "Root for hashed private references; defaults to <dataset-path>/private"
    )
        .path(mustExist = true, canBeFile = false)
        .defaultLazy(defaultForHelp = "<dataset-path>/private") { datasetPath.resolve("private") }
    private val paperId by option("--paper-id", help = "Validate one paper; otherwise discover every task")
    private val updateHashes by option(
        "--update-hashes",
        help = "Refresh public artefact and closure hashes after intentional edits, then check private references"
    ).flag()

    override suspend fun run() {
        val selectedPaper = paperId?.let(::PaperId)
        val datasetRoot = this.datasetRoot.toAbsolutePath().normalize()
        val backend = BackendRegistry(
            listOf(
                LeanBackend(
                    environmentRoot.toAbsolutePath().normalize(),
                    datasetRoot,
                    projectRoot.resolve("prompts"),
                    evaluationRoot.toAbsolutePath().normalize(),
                )
            )
        ).find(backendId)
        withContext(Dispatchers.IO) {
            var tasks = FileTaskRepository(datasetRoot, JsonTaskManifestReader()).findAll()
                .filter { selectedPaper == null || it.manifest.paperId == selectedPaper }
            if (checkReferences || updateHashes) {
                require(backend.id == "lean") { "private-reference validation is only supported by backend lean" }
                require(tasks.isNotEmpty()) { "dataset contains no selected tasks" }
                if (updateHashes) tasks = LeanPackageValidator.updatePublicHashes(tasks, datasetRoot)
                validateReferences(tasks, backend.workspaceFactory)
            } else {
                tasks = tasks.filter { it.manifest.evaluation.kind in backend.evaluationKinds }
                BackendDatasetValidator(backend).validateAll(tasks) { task ->
                    echo(RunLogEntry("Validated ${task.manifest.taskId}", green).renderMessage())
                }
                echo(RunLogEntry("Validated ${tasks.size} task(s) for backend ${backend.id}", green).renderMessage())
            }
        }
    }

    private fun validateReferences(tasks: List<BenchmarkTask>, workspaces: TaskWorkspaceFactory) {
        val results =
            LeanPackageValidator(workspaces, LeanValidationProfileReader(validationRoot, referenceRoot)).validateAll(
                tasks
            ) {
                val result = it.report.result
                val message = when (result) {
                    EvaluationResult.Accepted -> "PASS ${it.taskId}: hashes, closure, isolated boundary and reference evaluation"
                    is EvaluationResult.Rejected -> "FAIL ${it.taskId}: reference rejected: ${result.reason}"
                    is EvaluationResult.NeedsReview -> "FAIL ${it.taskId}: reference needs review: ${result.reason}"
                    is EvaluationResult.InfrastructureFailure -> "FAIL ${it.taskId}: ${result.reason}"
                }
                echo(
                    RunLogEntry(
                        message, when (result) {
                            EvaluationResult.Accepted -> green
                            is EvaluationResult.NeedsReview -> yellow
                            else -> red
                        }
                    ).renderMessage()
                )
            }
        val passed = results.count { it.report.result == EvaluationResult.Accepted }
        echo(
            RunLogEntry(
                "Validated $passed/${results.size} Lean packages; task review status unchanged",
                if (passed == results.size) green else red
            ).renderMessage()
        )
        if (passed != results.size) throw ProgramResult(1)
    }
}
