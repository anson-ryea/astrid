package command

import analysis.AnalysisRenderer
import analysis.RunAnalyser
import analysis.SavedRunReader
import com.github.ajalt.clikt.command.SuspendingCliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.path
import com.github.ajalt.clikt.parameters.types.restrictTo
import dataset.FileTaskRepository
import dataset.JsonTaskManifestReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import run.RunTaskMetadata
import java.nio.file.Files
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE

class AnalyseCommand : SuspendingCliktCommand("analyse") {
    override fun help(context: Context) =
        "Analyse saved runs without model calls or Lean. Reports keep runs separate and include every saved sample in success-rate denominators."

    private val runPaths by argument("run paths", help = "One or more saved run directories")
        .path(mustExist = true, canBeFile = false).multiple(required = true)
    private val format by option("--format", help = "Report format").choice("text", "json", "csv").default("text")
    private val output by option(
        "--output",
        help = "Write to a new file instead of standard output; existing files are never overwritten"
    )
        .path(canBeDir = false)
    private val ks by option("--k", help = "Positive pass@k value; repeat for several values")
        .int().restrictTo(min = 1).multiple(default = listOf(1, 5, 10))
    private val datasetRoot by option(
        "--dataset-root",
        help = "Optional current task directory to supply labels missing from historical runs"
    )
        .path(mustExist = true, canBeFile = false)

    override suspend fun run() {
        val report = withContext(Dispatchers.IO) {
            val labels = datasetRoot?.let { root ->
                FileTaskRepository(root, JsonTaskManifestReader()).findAll()
                    .associate { it.manifest.taskId.value to RunTaskMetadata.from(it.manifest) }
            }.orEmpty()
            val reader = SavedRunReader()
            val analyser = RunAnalyser()
            val paths = runPaths.map { it.toRealPath() }
            require(paths.distinct().size == paths.size) { "run paths must be distinct" }
            val analyses = paths.map { analyser.analyse(reader.read(it, labels), ks) }
            when (format) {
                "json" -> AnalysisRenderer.json(analyses)
                "csv" -> AnalysisRenderer.csv(analyses)
                else -> AnalysisRenderer.text(analyses)
            }
        }
        val destination = output
        if (destination == null) echo(report, trailingNewline = false)
        else withContext(Dispatchers.IO) { Files.writeString(destination, report, CREATE_NEW, WRITE) }
    }
}
