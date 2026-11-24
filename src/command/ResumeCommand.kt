package command

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.convert
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.path
import run.JsonBenchmarkRunConfigReader
import run.LangfuseConfig
import run.RunResume
import java.nio.file.Path

/** Continues missing samples in an interrupted run. */
class ResumeCommand(
    projectRoot: Path = Path.of("").toAbsolutePath().normalize(),
    apiKeyProvider: (() -> String?)? = null,
    private val configReader: JsonBenchmarkRunConfigReader = JsonBenchmarkRunConfigReader(),
    langfuseConfigProvider: (() -> LangfuseConfig?)? = null,
) : BenchmarkCommand("resume", projectRoot, apiKeyProvider, langfuseConfigProvider) {
    override fun help(context: Context) =
        "Resume an interrupted run with its saved settings and task IDs."

    private val runIdOrPath by argument("run_id/output path", help = "Existing run name or output path")
        .convert { validateRunNameOrPath(it) }
    override val datasetPath by argument("dataset path", help = "Original dataset checkout directory")
        .path(mustExist = true, canBeFile = false)
    private val configPath by option("--config", help = "Use this configuration for remaining samples")
        .path(mustExist = true, canBeDir = false)

    override suspend fun run() {
        val directory = outputDirectory(runIdOrPath)
        val previous = RunResume.open(directory, allowConfigOverride = configPath != null)
        val config = configPath?.let(configReader::read) ?: previous.config(configReader)
        runBenchmark(config, configPath ?: directory.resolve("run.json"), directory, resume = previous)
    }
}
