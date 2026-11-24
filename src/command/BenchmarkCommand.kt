package command

import backend.BackendDatasetValidator
import backend.BackendRegistry
import backend.lean.LeanBackend
import com.github.ajalt.clikt.command.SuspendingCliktCommand
import com.github.ajalt.clikt.core.terminal
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.defaultLazy
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.path
import com.github.ajalt.mordant.rendering.TextColors.green
import console.*
import dataset.FileTaskRepository
import dataset.JsonTaskManifestReader
import io.github.cdimascio.dotenv.dotenv
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import model.OpenRouterConnection
import prompt.PromptRenderer
import run.*
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import kotlin.time.TimeSource

/** Common dataset options and execution for new and resumed benchmark runs. */
abstract class BenchmarkCommand(
    name: String,
    projectRoot: Path,
    apiKeyProvider: (() -> String?)?,
    langfuseConfigProvider: (() -> LangfuseConfig?)?,
) : SuspendingCliktCommand(name) {
    private val projectRoot = projectRoot.toAbsolutePath().normalize()
    private val apiKeyProvider = apiKeyProvider ?: {
        System.getenv("OPENROUTER_API_KEY") ?: localEnvironment()["OPENROUTER_API_KEY"]
    }
    private val langfuseConfigProvider = langfuseConfigProvider ?: {
        val local = localEnvironment()
        LangfuseConfig.fromEnvironment { System.getenv(it) ?: local[it] }
    }

    protected abstract val datasetPath: Path

    private val backendId by option("--backend", help = "Formalisation backend")
        .default("lean")
    private val validateBeforeRun by option(
        "--validate-before-run",
        help = "Validate selected dataset packages and backend readiness before model calls",
    ).flag()
    private val datasetRoot by option(
        "--dataset-root",
        help = "Task directory; defaults to <dataset-path>/dataset",
    )
        .path(mustExist = true, canBeFile = false)
        .defaultLazy(defaultForHelp = "<dataset-path>/dataset") { datasetPath.resolve("dataset") }
    private val evaluationRoot by option(
        "--evaluation-root",
        help = "Runner-only semantic contracts; defaults to <dataset-path>/private/evaluation"
    )
        .path(canBeFile = false)
        .defaultLazy(defaultForHelp = "<dataset-path>/private/evaluation") { datasetPath.resolve("private/evaluation") }
    private val environmentRoot by option(
        "--environment-root",
        help = "Pinned backend project and dependency cache; defaults to <dataset-path>",
    ).path(mustExist = true, canBeFile = false)
        .defaultLazy(defaultForHelp = "<dataset-path>") { datasetPath }
    private val outputRoot by option(
        "--output-root",
        help = "Directory for named runs; defaults to runs/. Ignored when RUN_ID is a path",
    ).path(canBeFile = false).default(Path.of("runs"))
    private val uiMode by option(
        "--ui",
        help = "Dashboard mode: auto detects a terminal, live forces animation, plain prints milestones"
    )
        .choice("auto" to RunConsoleMode.Auto, "live" to RunConsoleMode.Live, "plain" to RunConsoleMode.Plain)
        .default(RunConsoleMode.Auto)

    private fun localEnvironment() = dotenv {
        directory = projectRoot.toString()
        ignoreIfMissing = true
    }

    internal fun outputDirectory(nameOrPath: String): Path {
        val directory = resolveRunDirectory(projectRoot, outputRoot, nameOrPath)
        val parent = requireNotNull(directory.parent) { "run directory must have a parent" }
        check(!Files.isSymbolicLink(parent)) { "run directory parent must not be a symbolic link: $parent" }
        return directory
    }

    internal suspend fun runBenchmark(
        runConfig: BenchmarkRunConfig,
        configPath: Path?,
        runDirectory: Path,
        selection: TaskSelection = TaskSelection(),
        resume: RunResume? = null,
    ) {
        val startedAt = TimeSource.Monotonic.markNow()
        val datasetRoot = this.datasetRoot.toAbsolutePath().normalize()
        val environmentRoot = this.environmentRoot.toAbsolutePath().normalize()
        val evaluationRoot = this.evaluationRoot.toAbsolutePath().normalize()
        val specification = resume?.specification(runConfig) ?: runConfig.toSpecification().copy(selection = selection)
        val repository = FileTaskRepository(
            datasetRoot,
            JsonTaskManifestReader(),
        )
        val backend = BackendRegistry(
            listOf(
                LeanBackend(
                    environmentRoot,
                    datasetRoot,
                    projectRoot.resolve("prompts"),
                    evaluationRoot,
                )
            )
        ).find(backendId)
        val selector =
            TaskSelector(backend.evaluationKinds, resume?.let { TaskSelection(taskIds = it.taskIds) } ?: selection)
        val selected = selector.select(repository.findAll())
        check(selected.isNotEmpty()) {
            "no frozen tasks are available for backend ${backend.id}"
        }

        val workspaceFactory = if (specification.workspaceToolsEnabled) {
            backend.workspaceFactory
        } else null
        val promptDirectory = projectRoot.resolve("prompts/common/v1")
        val protocols = backend.protocols
        val systemPromptRenderer = PromptRenderer(
            promptDirectory.resolve("agent-system.md"),
        )
        val repairPromptRenderer = PromptRenderer(
            promptDirectory.resolve("repair.md"),
        )
        val runId = runDirectory.fileName?.toString() ?: runDirectory.toString()
        val outputParent = requireNotNull(runDirectory.parent)
        if (resume == null) {
            check(Files.notExists(runDirectory, NOFOLLOW_LINKS)) { "run directory already exists: $runDirectory" }
        }
        val store = if (resume == null) FileRunStore(runDirectory) else FileRunStore(resume)
        val console = RunConsole(
            terminal, RunDashboardContext(
                runId = runId,
                modelId = specification.model.id,
                datasetRoot = datasetRoot,
                taskSubmissions = selected.associate { it.manifest.taskId to it.manifest.submission.kind },
                samplesPerTask = specification.samplesPerTask,
                configPath = configPath,
                outputPath = runDirectory,
                runConfig = runConfig,
                backendId = backendId,
                allowDiscard = resume == null,
                taskProblems = selected.associate {
                    it.manifest.taskId to RunTaskProblem(
                        it.manifest.paperId.value, it.manifest.sourceResultId.value,
                    )
                },
            ), startedAt, uiMode
        )
        try {
            console.run { onEvent ->
                onEvent(RunEvent.RunPhaseChanged(RunPhase.Preparing))
                // Optional validation can compile Lean or perform blocking filesystem/process I/O.
                withContext(Dispatchers.IO) {
                    systemPromptRenderer.render(emptyMap())
                    repairPromptRenderer.render(mapOf("DIAGNOSTIC" to "preflight"))
                    if (validateBeforeRun) {
                        BackendDatasetValidator(backend, workspaceFactory).validateAll(selected)
                    }
                }
                val apiKey = apiKeyProvider()?.takeIf(String::isNotBlank)
                    ?: error("OPENROUTER_API_KEY is missing")
                val langfuse = langfuseConfigProvider()?.let {
                    LangfuseTracing(it, runId, datasetPath.toAbsolutePath().normalize().fileName.toString(), backend.id)
                }
                onEvent(RunEvent.RunPhaseChanged(RunPhase.ResolvingModel))
                OpenRouterConnection(apiKey).use { connection ->
                    val resolvedSpecification = specification.copy(model = connection.resolveModel(specification.model))
                    withContext(Dispatchers.IO) { Files.createDirectories(outputParent) }
                    val engine = TaskRunEngine(
                        protocols = protocols,
                        evaluators = backend.evaluators,
                        promptExecutor = connection.promptExecutor,
                        specification = resolvedSpecification,
                        systemPromptRenderer = systemPromptRenderer,
                        repairPromptRenderer = repairPromptRenderer,
                        workspaceFactory = workspaceFactory,
                        langfuse = langfuse,
                    )
                    onEvent(RunEvent.RunPhaseChanged(RunPhase.Running))
                    BenchmarkRunner(
                        repository = repository,
                        selector = selector,
                        engine = engine,
                        store = store,
                        onEvent = onEvent,
                    ).run()
                }
            }
        } catch (stopped: RunQuitException) {
            if (stopped.discardResults) {
                // RunConsole has joined execution and restored the terminal before this point.
                val discarded = withContext(NonCancellable) { store.discard() }
                stopped.suppressed.firstOrNull()?.let { throw it }
                console.printMessage(
                    if (discarded) "Benchmark stopped. Run output discarded: $runDirectory"
                    else "Benchmark stopped before the run folder was created.", green
                )
                return
            }
            // Persistence or terminal cleanup failures must still be reported.
            stopped.suppressed.firstOrNull()?.let { throw it }
            console.printMessage(
                if (Files.isDirectory(runDirectory))
                    "Benchmark stopped. Partial results kept: $runDirectory"
                else "Benchmark stopped before the run folder was created.", green
            )
            return
        } finally {
            store.close()
        }

        console.printMessage("Benchmark run complete: $runDirectory", green)
    }

}
