package command

import backend.BackendRegistry
import backend.lean.LeanBackend
import com.github.ajalt.clikt.command.SuspendingCliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.terminal
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.check
import com.github.ajalt.clikt.parameters.options.default
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
import dataset.TaskPackageVerifier
import io.github.cdimascio.dotenv.dotenv
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.TimeSource
import kotlinx.coroutines.Dispatchers
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
) : SuspendingCliktCommand("run") {
    private val projectRoot = projectRoot.toAbsolutePath().normalize()

    override fun help(context: Context): String =
        "Run frozen tasks with a registered backend, V1 configuration and new run ID."

    private val configPath by argument(help = "V1 benchmark run configuration")
        .path(mustExist = true, canBeDir = false)
    private val runId by argument(help = "Unique lower-case run ID")
        .check("run ID must use lower-case letters, digits, underscores or hyphens", runIdPattern::matches)
    private val datasetRoot by option(
        "--dataset-root",
        help = "Dataset to run; defaults to this project's dataset directory",
    )
        .path(mustExist = true, canBeFile = false)
        .default(projectRoot.resolve("dataset"))
    private val backendId by option("--backend", help = "Formalisation backend")
        .default("lean")
    private val evaluationRoot by option("--evaluation-root", help = "Runner-only semantic contracts; never exposed to models")
        .path(canBeFile = false).default(projectRoot.resolve("private/evaluation"))
    private val environmentRoot by option(
        "--environment-root",
        help = "Pinned backend project and dependency cache; defaults to the Astrid project",
    ).path(mustExist = true, canBeFile = false)
    private val uiMode by option("--ui", help = "Dashboard mode: auto detects a terminal, live forces animation, plain prints milestones")
        .choice("auto" to RunConsoleMode.Auto, "live" to RunConsoleMode.Live, "plain" to RunConsoleMode.Plain)
        .default(RunConsoleMode.Auto)

    override suspend fun run() {
        val startedAt = TimeSource.Monotonic.markNow()
        val runConfig = configReader.read(configPath)
        val specification = runConfig.toSpecification()
        val repository = FileTaskRepository(
            datasetRoot,
            JsonTaskManifestReader(),
        )
        val backend = BackendRegistry(listOf(LeanBackend(
            (environmentRoot ?: projectRoot).toAbsolutePath().normalize(),
            datasetRoot.toAbsolutePath().normalize(),
            projectRoot.resolve("prompts"),
            evaluationRoot.toAbsolutePath().normalize(),
        ))).find(backendId)
        val selector = TaskSelector(backend.evaluationKinds)
        val selected = selector.select(repository.findAll())
        check(selected.isNotEmpty()) {
            "no frozen tasks are available for backend ${backend.id}"
        }

        val verifier = TaskPackageVerifier()
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
        val runsDirectory = projectRoot.resolve("runs")
        check(!Files.isSymbolicLink(runsDirectory)) {
            "runs directory must not be a symbolic link: $runsDirectory"
        }
        val runDirectory = runsDirectory.resolve(runId)
        check(Files.notExists(runDirectory)) {
            "run directory already exists: $runDirectory"
        }

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
                // Preflight can compile Lean or perform blocking filesystem/process I/O.
                withContext(Dispatchers.IO) {
                    systemPromptRenderer.render(emptyMap())
                    repairPromptRenderer.render(mapOf("DIAGNOSTIC" to "preflight"))
                    selected.forEach { task ->
                        verifier.verify(task)
                        backend.preflight(task)
                        workspaceFactory?.prepare(task)
                    }
                }
                val apiKey = apiKeyProvider()?.takeIf(String::isNotBlank)
                    ?: error("OPENROUTER_API_KEY is missing")
                onEvent(RunEvent.RunPhaseChanged(RunPhase.ResolvingModel))
                OpenRouterConnection(apiKey).use { connection ->
                    val resolvedSpecification = specification.copy(model = connection.resolveModel(specification.model))
                    withContext(Dispatchers.IO) { Files.createDirectories(runsDirectory) }
                    val engine = TaskRunEngine(
                        protocols = protocols,
                        evaluators = backend.evaluators,
                        promptExecutor = connection.promptExecutor,
                        specification = resolvedSpecification,
                        systemPromptRenderer = systemPromptRenderer,
                        repairPromptRenderer = repairPromptRenderer,
                        packageVerifier = verifier,
                        workspaceFactory = workspaceFactory,
                    )
                    onEvent(RunEvent.RunPhaseChanged(RunPhase.Running))
                    BenchmarkRunner(
                        repository = repository,
                        selector = selector,
                        engine = engine,
                        store = FileRunStore(runDirectory),
                        packageVerifier = verifier,
                        onEvent = onEvent,
                    ).run()
                }
            }
        } catch (stopped: RunQuitException) {
            // Persistence or terminal cleanup failures must still be reported.
            stopped.suppressed.firstOrNull()?.let { throw it }
            console.printMessage(if (Files.isDirectory(runDirectory))
                "Benchmark stopped. Partial results kept: $runDirectory"
            else "Benchmark stopped before the run folder was created.", green)
            return
        }

        console.printMessage("Benchmark run complete: $runDirectory", green)
    }

    private companion object {
        val runIdPattern = Regex("[a-z0-9][a-z0-9_-]*")
    }
}
