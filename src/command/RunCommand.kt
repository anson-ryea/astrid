package command

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.convert
import com.github.ajalt.clikt.parameters.arguments.defaultLazy
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.path
import com.github.ajalt.clikt.parameters.types.restrictTo
import domain.ConditionId
import domain.PaperId
import domain.TaskId
import domain.TaskTypeId
import run.JsonBenchmarkRunConfigReader
import run.LangfuseConfig
import run.TaskSelection
import java.nio.file.Path
import java.time.Clock
import java.time.format.DateTimeFormatter
import java.util.*

/** Runs reviewed tasks using a registered backend and configured OpenRouter model. */
class RunCommand(
    projectRoot: Path = Path.of("").toAbsolutePath().normalize(),
    apiKeyProvider: (() -> String?)? = null,
    private val configReader: JsonBenchmarkRunConfigReader = JsonBenchmarkRunConfigReader(),
    private val clock: Clock = Clock.systemDefaultZone(),
    langfuseConfigProvider: (() -> LangfuseConfig?)? = null,
) : BenchmarkCommand("run", projectRoot, apiKeyProvider, langfuseConfigProvider) {
    override fun help(context: Context): String =
        "Run frozen tasks from a dataset checkout with a V1 configuration and optional run name or output path."

    private val configPath by argument("config path", help = "V1 benchmark run configuration")
        .path(mustExist = true, canBeDir = false)
    override val datasetPath by argument("dataset path", help = "Dataset checkout directory")
        .path(mustExist = true, canBeFile = false)
    private val runIdOrPath by argument(
        "run_id/output path",
        help = "Run name or output path; defaults to <dataset-folder>_<timestamp>"
    ).convert { validateRunNameOrPath(it) }
        .defaultLazy(defaultForHelp = "<dataset-folder>_<timestamp>") { defaultRunId() }

    private val taskIds by option(
        "--task-id",
        help = "Select a frozen task; repeat to select several"
    ).convert { TaskId(it) }.multiple()
    private val paperIds by option(
        "--paper-id",
        help = "Select a paper; repeat to select several"
    ).convert { PaperId(it) }.multiple()
    private val taskTypes by option("--task-type", help = "Select a task type; repeat to select several").int()
        .restrictTo(min = 1).multiple()
    private val conditions by option(
        "--condition",
        help = "Select an experimental condition; repeat to select several"
    ).convert { ConditionId(it) }.multiple()

    override suspend fun run() {
        val selection = TaskSelection(
            taskIds.toSet(), paperIds.toSet(), taskTypes.map(::TaskTypeId).toSet(), conditions.toSet(),
        )
        runBenchmark(configReader.read(configPath), configPath, outputDirectory(runIdOrPath), selection)
    }

    private fun defaultRunId(): String {
        val datasetName = datasetPath.toAbsolutePath().normalize().fileName?.toString().orEmpty()
            .lowercase(Locale.ROOT).replace(Regex("[^a-z0-9_-]+"), "_").trim('_', '-').ifEmpty { "dataset" }
        return "${datasetName}_${runTimestamp.withZone(clock.zone).format(clock.instant())}"
    }

    private companion object {
        val runTimestamp: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuuMMdd_HHmmss_SSS", Locale.ROOT)
    }
}
