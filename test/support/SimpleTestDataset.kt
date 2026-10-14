package support

import backend.lean.evaluation.LeanSemanticContractReader
import backend.lean.validation.LeanValidationProfileReader
import backend.lean.workspace.LeanTaskWorkspaceFactory
import dataset.FileTaskRepository
import dataset.JsonTaskManifestReader
import domain.BenchmarkTask
import java.nio.file.Path

/** Test data and evaluator-only answers, independent of the curated benchmark. */
internal object SimpleTestDataset {
    val projectRoot: Path = Path.of("").toAbsolutePath().normalize()
    val resourceRoot: Path = projectRoot.resolve("testResources")
    val datasetRoot: Path = resourceRoot.resolve("dataset/simple")
    val evaluationRoot: Path = resourceRoot.resolve("evaluation/simple")
    val privateRoot: Path = resourceRoot.resolve("private/simple")
    val promptRoot: Path = datasetRoot.resolve("prompts/v1")

    fun tasks(): List<BenchmarkTask> = FileTaskRepository(datasetRoot, JsonTaskManifestReader()).findAll()

    fun task(result: String): BenchmarkTask = tasks().single { it.manifest.sourceResultId.value == result }

    fun contracts() = LeanSemanticContractReader(evaluationRoot)

    fun profiles() = LeanValidationProfileReader(privateRoot.resolve("validation"), privateRoot)

    fun workspaceFactory() = LeanTaskWorkspaceFactory(projectRoot, datasetRoot, evaluationRoot = evaluationRoot)
}
