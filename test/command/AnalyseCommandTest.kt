package command

import analysis.sample
import analysis.samplePath
import analysis.savedRun
import analysis.taskA
import com.github.ajalt.clikt.command.parse
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.core.terminal
import com.github.ajalt.mordant.rendering.AnsiLevel
import com.github.ajalt.mordant.terminal.Terminal
import com.github.ajalt.mordant.terminal.TerminalRecorder
import domain.EvaluationResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import run.SampleOutcome
import support.SimpleTestDataset
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class AnalyseCommandTest {
    @TempDir
    lateinit var root: Path
    private val accepted = SampleOutcome.Evaluated(EvaluationResult.Accepted)

    private suspend fun invoke(vararg args: String): String {
        val recorder = TerminalRecorder(ansiLevel = AnsiLevel.NONE, width = 512)
        val command = AnalyseCommand()
        command.configureContext { terminal = Terminal(terminalInterface = recorder) }
        EntryCommand().subcommands(command).parse(listOf("analyse") + args)
        return recorder.output()
    }

    @Test
    fun commandReportsDenominatorsWithoutModelOrLeanAndLeavesRunsUnchanged() = runBlocking {
        val path = savedRun(root.resolve("run"), listOf(sample(taskA, 1, accepted)))
        val before = withContext(Dispatchers.IO) {
            Files.readString(path.resolve("run.json"))
        }
        val recordBefore = withContext(Dispatchers.IO) {
            Files.readString(samplePath(path))
        }
        val text = invoke(path.toString())
        assertContains(text, "First attempt: 1/1 accepted (100.00%)")
        assertContains(text, "pass@1: first=100.00%, final=100.00% (1/1 eligible tasks)")
        assertContains(text, "pass@5: first=n/a, final=n/a (0/1 eligible tasks)")
        assertContains(text, "paper paper_1")
        assertFalse(text.contains("private prompt"))
        assertEquals(before, withContext(Dispatchers.IO) {
            Files.readString(path.resolve("run.json"))
        })
        assertEquals(recordBefore, withContext(Dispatchers.IO) {
            Files.readString(samplePath(path))
        })
    }

    @Test
    fun jsonSupportsMultipleRunsAndCustomPassAtKWithoutPooling() = runBlocking {
        val a = savedRun(root.resolve("a"), listOf(sample(taskA, 1, accepted), sample(taskA, 2, accepted)), 2)
        val b = savedRun(root.resolve("b"), listOf(sample(taskA, 1, SampleOutcome.ProviderFailure("provider"))))
        val result = Json.parseToJsonElement(
            invoke(
                a.toString(),
                b.toString(),
                "--format",
                "json",
                "--k",
                "2",
                "--k",
                "2"
            )
        ).jsonObject
        val runs = result.getValue("runs").jsonArray
        assertEquals(2, runs.size)
        val first = runs[0].jsonObject.getValue("summaries").jsonArray.first().jsonObject
        val second = runs[1].jsonObject.getValue("summaries").jsonArray.first().jsonObject
        assertEquals("2", first.getValue("final_accepted").jsonPrimitive.content)
        assertEquals("0", second.getValue("final_accepted").jsonPrimitive.content)
        val pass = first.getValue("pass_at_k").jsonArray
        assertEquals(1, pass.size)
        assertEquals("2", pass.single().jsonObject.getValue("k").jsonPrimitive.content)
        assertEquals(JsonNull, second.getValue("pass_at_k").jsonArray.single().jsonObject.getValue("final"))
        assertContains(result.getValue("denominator_policy").jsonPrimitive.content, "All saved samples")
    }

    @Test
    fun exportsCsvWithQuotedFieldsAndExplicitPolicies() = runBlocking {
        val path = savedRun(root.resolve("run"), listOf(sample(taskA, 1, accepted)), model = "model,\"quoted\"")
        val destination = root.resolve("report.csv")
        assertTrue(invoke(path.toString(), "--format", "csv", "--output", destination.toString()).isBlank())
        val csv = withContext(Dispatchers.IO) {
            Files.readString(destination)
        }
        assertContains(csv, "\"model,\"\"quoted\"\"\"")
        assertContains(csv.lines().first(), "final_pass_at_1")
        assertContains(csv.lines().first(), "legacy_infrastructure_failure")
        assertContains(csv.lines().first(), "denominator_policy")
        assertEquals(6, csv.lineSequence().count { it.isNotBlank() }) // header + overall + 4 breakdown rows
    }

    @Test
    fun currentDatasetEnrichesLabelsOnlyWhenNoSnapshotExists() = runBlocking {
        val id = SimpleTestDataset.task("proof").manifest.taskId.value
        val path =
            savedRun(root.resolve("run"), listOf(sample(id, 1, accepted)), taskIds = listOf(id), labels = emptyMap())
        val text = invoke(path.toString(), "--dataset-root", SimpleTestDataset.datasetRoot.toString())
        assertContains(text, "task_type 2")
        assertContains(text, "current dataset checkout")
    }

    @Test
    fun refusesToOverwriteAnyOutputOrAnalyseTheSameRunTwice(): Unit = runBlocking {
        val path = savedRun(root.resolve("run"), listOf(sample(taskA, 1, accepted)))
        val source = path.resolve("run.json")
        val before = withContext(Dispatchers.IO) {
            Files.readString(source)
        }
        assertFailsWith<FileAlreadyExistsException> { invoke(path.toString(), "--output", source.toString()) }
        assertEquals(before, withContext(Dispatchers.IO) {
            Files.readString(source)
        })
        assertFailsWith<IllegalArgumentException> { invoke(path.toString(), path.resolve(".").toString()) }
        assertFails { invoke(path.toString(), "--k", "0") }
        assertFails { invoke(path.toString(), "--format", "yaml") }
    }
}
