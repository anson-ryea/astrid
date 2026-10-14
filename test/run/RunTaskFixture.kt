package run

import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import dataset.HashCalculator
import domain.ArtefactPath
import domain.ArtefactRole
import domain.BenchmarkTask
import domain.ConditionId
import domain.ContextId
import domain.EvaluationKind
import domain.EvaluationResult
import domain.EvaluationSpecification
import domain.PaperId
import domain.ProtocolId
import domain.SourceResultId
import domain.SubmissionKind
import domain.SubmissionSpecification
import domain.TaskArtefact
import domain.TaskId
import domain.TaskManifest
import domain.TaskStatus
import domain.TaskTypeId
import evaluation.TaskEvaluator
import evaluation.TaskEvaluatorRegistry
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds
import prompt.PromptRenderer
import protocol.TaskProtocol
import protocol.TaskProtocolRegistry
import workspace.TaskWorkspaceFactory

/** Creates a verified synthetic package for run tests. */
internal fun createRunTask(
    root: Path,
    id: String = "task_1.1_theorem_11",
    status: TaskStatus = TaskStatus.FROZEN,
): BenchmarkTask {
    val taskId = TaskId(id)
    val packageDirectory = root.resolve(id).toAbsolutePath().normalize()
    Files.createDirectories(packageDirectory)

    // The runner uses the manifest object; the verifier checks that the file exists.
    Files.writeString(packageDirectory.resolve("manifest.json"), "{}")
    val artefactPath = packageDirectory.resolve("Context.txt")
    Files.writeString(artefactPath, "-- synthetic context\n")

    val manifest = TaskManifest(
        schemaVersion = TaskManifest.SCHEMA_VERSION,
        taskId = taskId,
        paperId = PaperId("paper_1"),
        sourceResultId = SourceResultId("theorem_11"),
        taskType = TaskTypeId(2),
        protocol = ProtocolId("test_protocol"),
        status = status,
        condition = ConditionId("statement_only"),
        contextId = ContextId("theorem_11"),
        artefacts = listOf(
            TaskArtefact(
                role = ArtefactRole("context"),
                path = ArtefactPath(Path.of("Context.txt")),
                sha256Digest = HashCalculator().sha256(artefactPath),
            ),
        ),
        submission = SubmissionSpecification(
            kind = SubmissionKind("test_submission"),
            allowLocalHelpers = false,
            allowTopLevelDeclarations = false,
        ),
        evaluation = EvaluationSpecification(
            kind = EvaluationKind("test_evaluation"),
            timeout = 10.seconds,
            allowedAxioms = emptySet(),
            forbiddenMechanisms = emptySet(),
            forbiddenIdentifiers = emptySet(),
        ),
    )

    return BenchmarkTask(manifest, packageDirectory)
}

internal fun runTestSpecification(
    samplesPerTask: Int = 1,
    maxRepairTurns: Int? = 1,
    maxConcurrentTasks: Int = 4,
): BenchmarkRunSpecification = BenchmarkRunSpecification(
    model = LLModel(LLMProvider.OpenRouter, "test-model"),
    samplesPerTask = samplesPerTask,
    repairPolicy = RepairPolicy(maxRepairTurns),
    maxToolCallsPerAttempt = 0,
    maxAgentIterations = 20,
    maxConcurrentTasks = maxConcurrentTasks,
)

internal fun createRunEngine(
    root: Path,
    executor: PromptExecutor,
    specification: BenchmarkRunSpecification = runTestSpecification(),
    assessSubmission: (BenchmarkTask, String) -> EvaluationResult = { _, _ ->
        EvaluationResult.Accepted
    },
    workspaceFactory: TaskWorkspaceFactory? = null,
    detailedAssessment: ((BenchmarkTask, String) -> domain.EvaluationReport)? = null,
): TaskRunEngine {
    val promptDirectory = root.resolve("prompts")
    Files.createDirectories(promptDirectory)
    Files.writeString(promptDirectory.resolve("system.md"), "System instructions.")
    Files.writeString(promptDirectory.resolve("repair.md"), "Repair: {{DIAGNOSTIC}}")

    val protocol = object : TaskProtocol {
        override val id = ProtocolId("test_protocol")

        override fun renderPrompt(task: BenchmarkTask, submissionInstructions: String?): String =
            "Task: ${task.manifest.taskId.value}" + submissionInstructions?.let { "\n\n$it" }.orEmpty()

        override fun extractSubmission(response: String): String {
            require(response.isNotBlank()) { "empty submission" }
            return response
        }
    }
    val evaluator = object : TaskEvaluator {
        override val kind = EvaluationKind("test_evaluation")

        override fun evaluate(
            task: BenchmarkTask,
            submission: String,
        ): EvaluationResult = assessSubmission(task, submission)

        override fun evaluateDetailed(task: BenchmarkTask, submission: String): domain.EvaluationReport =
            detailedAssessment?.invoke(task, submission) ?: super.evaluateDetailed(task, submission)
    }

    return TaskRunEngine(
        protocols = TaskProtocolRegistry(listOf(protocol)),
        evaluators = TaskEvaluatorRegistry(listOf(evaluator)),
        promptExecutor = executor,
        specification = specification,
        systemPromptRenderer = PromptRenderer(promptDirectory.resolve("system.md")),
        repairPromptRenderer = PromptRenderer(promptDirectory.resolve("repair.md")),
        workspaceFactory = workspaceFactory,
    )
}
