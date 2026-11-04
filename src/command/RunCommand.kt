package command

import backend.BackendRegistry
import backend.BackendDatasetValidator
import backend.lean.LeanBackend
import com.github.ajalt.clikt.command.SuspendingCliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.terminal
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.convert
import com.github.ajalt.clikt.parameters.arguments.defaultLazy
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.defaultLazy
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.path
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.mordant.rendering.TextColors.green
import console.RunConsole
import console.RunConsoleMode
import console.RunQuitException
import console.RunDashboardContext
import console.RunTaskProblem
import dataset.FileTaskRepository
import dataset.JsonTaskManifestReader
import io.github.cdimascio.dotenv.dotenv
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.time.Clock
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.time.TimeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import model.OpenRouterConnection
import prompt.PromptRenderer
import run.BenchmarkRunner
import run.FileRunStore
import run.JsonBenchmarkRunConfigReader
import run.TaskRunEngine
import run.TaskSelector
import run.RunEvent
import run.RunPhase

/** Runs reviewed tasks using a registered backend and configured OpenRouter model. */
class RunCommand(
    projectRoot: Path = Path.of("").toAbsolutePath().normalize(),
    private val apiKeyProvider: () -> String? = {
        System.getenv("OPENROUTER_API_KEY")
            ?: dotenv {
                directory = projectRoot.toString()
                ignoreIfMissing = true
            }["OPENROUTER_API_KEY"]
    },
    private val configReader: JsonBenchmarkRunConfigReader = JsonBenchmarkRunConfigReader(),
    private val clock: Clock = Clock.systemDefaultZone(),
) : SuspendingCliktCommand("run") {
    private val projectRoot = projectRoot.toAbsolutePath().normalize()

    override fun help(context: Context): String =
        "Run frozen tasks from a dataset checkout with a V1 configuration and optional run name or output path."

    private val configPath by argument("config path", help = "V1 benchmark run configuration")
        .path(mustExist = true, canBeDir = false)
    private val datasetPath by argument("dataset path", help = "Dataset checkout directory containing dataset/ and its backend environment")
        .path(mustExist = true, canBeFile = false)
    private val runIdOrPath by argument("run_id/output path", help = "Run name or output path; defaults to <dataset-folder>_<timestamp>")
        .convert { value ->
            require(isRunPath(value) || runIdPattern.matches(value)) {
                "run ID must use lower-case letters, digits, underscores or hyphens, or be a path"
            }
            value
        }
        .defaultLazy(defaultForHelp = "<dataset-folder>_<timestamp>") { defaultRunId() }
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
    private val evaluationRoot by option("--evaluation-root",
        help = "Runner-only semantic contracts; defaults to <dataset-path>/private/evaluation")
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
    private val uiMode by option("--ui", help = "Dashboard mode: auto detects a terminal, live forces animation, plain prints milestones")
        .choice("auto" to RunConsoleMode.Auto, "live" to RunConsoleMode.Live, "plain" to RunConsoleMode.Plain)
        .default(RunConsoleMode.Auto)

    override suspend fun run() {
        val startedAt = TimeSource.Monotonic.markNow()
        val datasetRoot = this.datasetRoot.toAbsolutePath().normalize()
        val environmentRoot = this.environmentRoot.toAbsolutePath().normalize()
        val evaluationRoot = this.evaluationRoot.toAbsolutePath().normalize()
        val runConfig = configReader.read(configPath)
        val specification = runConfig.toSpecification()
        val repository = FileTaskRepository(
            datasetRoot,
            JsonTaskManifestReader(),
        )
        val backend = BackendRegistry(listOf(LeanBackend(
            environmentRoot,
            datasetRoot,
            projectRoot.resolve("prompts"),
            evaluationRoot,
        ))).find(backendId)
        val selector = TaskSelector(backend.evaluationKinds)
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
        val runIsPath = isRunPath(runIdOrPath)
        val runDirectory = resolveRunDirectory(projectRoot, outputRoot, runIdOrPath)
        val runId = if (runIsPath) runDirectory.fileName?.toString() ?: runIdOrPath else runIdOrPath
        val outputParent = requireNotNull(runDirectory.parent) { "run directory must have a parent" }
        check(!Files.isSymbolicLink(outputParent)) {
            "run directory parent must not be a symbolic link: $outputParent"
        }
        check(Files.notExists(runDirectory, NOFOLLOW_LINKS)) {
            "run directory already exists: $runDirectory"
        }

        val store = FileRunStore(runDirectory)
        val console = RunConsole(terminal, RunDashboardContext(
            runId = runId,
            modelId = specification.model.id,
            datasetRoot = datasetRoot,
            taskSubmissions = selected.associate { it.manifest.taskId to it.manifest.submission.kind },
            samplesPerTask = specification.samplesPerTask,
            configPath = configPath,
            outputPath = runDirectory,
            runConfig = runConfig,
            backendId = backendId,
            taskProblems = selected.associate { it.manifest.taskId to RunTaskProblem(
                it.manifest.paperId.value, it.manifest.sourceResultId.value,
            ) },
        ), startedAt, uiMode)
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
                console.printMessage(if (discarded) "Benchmark stopped. Run output discarded: $runDirectory"
                    else "Benchmark stopped before the run folder was created.", green)
                return
            }
            // Persistence or terminal cleanup failures must still be reported.
            stopped.suppressed.firstOrNull()?.let { throw it }
            console.printMessage(if (Files.isDirectory(runDirectory))
                "Benchmark stopped. Partial results kept: $runDirectory"
            else "Benchmark stopped before the run folder was created.", green)
            return
        }

        console.printMessage("Benchmark run complete: $runDirectory", green)
    }

    private fun defaultRunId(): String {
        val datasetName = datasetPath.toAbsolutePath().normalize().fileName?.toString().orEmpty()
            .lowercase(Locale.ROOT).replace(Regex("[^a-z0-9_-]+"), "_").trim('_', '-').ifEmpty { "dataset" }
        return "${datasetName}_${runTimestamp.withZone(clock.zone).format(clock.instant())}"
    }

    private companion object {
        val runIdPattern = Regex("[a-z0-9][a-z0-9_-]*")
        val runTimestamp: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuuMMdd_HHmmss_SSS", Locale.ROOT)
    }
}
