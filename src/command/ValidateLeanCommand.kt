package command

import backend.lean.LeanInputFiles
import backend.lean.validation.LeanPackageValidator
import backend.lean.validation.LeanValidationProfileReader
import backend.lean.workspace.LeanTaskWorkspaceFactory
import backend.lean.evaluation.LeanSemanticSourceBuilder
import com.github.ajalt.clikt.command.SuspendingCliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.path
import dataset.FileTaskRepository
import dataset.JsonTaskManifestReader
import domain.EvaluationResult
import domain.PaperId
import java.nio.file.Path
import kotlin.io.path.Path

/** Offline curation for any dataset using Astrid's Lean package schemas. */
class ValidateLeanCommand(projectRoot: Path = Path("")) : SuspendingCliktCommand("validate-lean") {
    private val projectRoot = LeanInputFiles.root(projectRoot)
    private val datasetRoot by option("--dataset-root", help = "V1 task dataset")
        .path(mustExist = true, canBeFile = false).default(this.projectRoot.resolve("dataset"))
    private val environmentRoot by option("--environment-root", help = "Pinned Lake project and dependency cache")
        .path(mustExist = true, canBeFile = false).default(this.projectRoot)
    private val validationRoot by option("--validation-root", help = "Private V1 package validation profiles")
        .path(mustExist = true, canBeFile = false).default(this.projectRoot.resolve("private/validation"))
    private val referenceRoot by option("--reference-root", help = "Root for hashed private reference files")
        .path(mustExist = true, canBeFile = false).default(this.projectRoot.resolve("private"))
    private val evaluationRoot by option("--evaluation-root", help = "Private semantic contracts")
        .path(canBeFile = false).default(this.projectRoot.resolve("private/evaluation"))
    private val paperId by option("--paper-id", help = "Validate one paper; otherwise discover every task")
    private val updateHashes by option("--update-hashes", help = "Refresh public artefact and closure hashes after intentional edits").flag()

    override fun help(context: Context) = "Validate Lean packages, isolated answer boundaries and private references without model calls."

    override suspend fun run() {
        val selectedPaper = paperId?.let(::PaperId)
        var tasks = FileTaskRepository(datasetRoot, JsonTaskManifestReader()).findAll()
            .filter { selectedPaper == null || it.manifest.paperId == selectedPaper }
        require(tasks.isNotEmpty()) { "dataset contains no selected tasks" }
        if (updateHashes) tasks = LeanPackageValidator.updatePublicHashes(tasks, datasetRoot)
        val factory = LeanTaskWorkspaceFactory(environmentRoot, datasetRoot, promptRoot = projectRoot.resolve("prompts"),
            evaluationRoot = evaluationRoot, semanticSources = LeanSemanticSourceBuilder(
                harnessPath = projectRoot.resolve("src/backend/lean/evaluation/SemanticHarness.lean")))
        val results = LeanPackageValidator(factory, LeanValidationProfileReader(validationRoot, referenceRoot)).validateAll(tasks) {
            val result = it.report.result
            echo(when (result) {
                EvaluationResult.Accepted -> "PASS ${it.taskId}: hashes, closure, isolated boundary and reference evaluation"
                is EvaluationResult.Rejected -> "FAIL ${it.taskId}: reference rejected: ${result.reason}"
                is EvaluationResult.NeedsReview -> "FAIL ${it.taskId}: reference needs review: ${result.reason}"
                is EvaluationResult.InfrastructureFailure -> "FAIL ${it.taskId}: ${result.reason}"
            })
        }
        val passed = results.count { it.report.result == EvaluationResult.Accepted }
        echo("Validated $passed/${results.size} Lean packages; task review status unchanged")
        if (passed != results.size) throw ProgramResult(1)
    }
}
