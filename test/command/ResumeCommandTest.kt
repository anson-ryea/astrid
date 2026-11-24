package command

import com.github.ajalt.clikt.command.parse
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import run.FileRunStore
import run.TaskSelection
import run.runTestSpecification
import support.SimpleTestDataset
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ResumeCommandTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun usesSavedTaskIdsAndSettingsBeforeAccessingProvider() = runBlocking {
        val directory = temporaryDirectory.resolve("run")
        val fixture = SimpleTestDataset.task("proof")
        val task = fixture.manifest.taskId
        val manifest = temporaryDirectory.resolve("dataset/paper_1/tasks/$task/manifest.json")
        Files.createDirectories(manifest.parent)
        Files.writeString(
            manifest, Files.readString(fixture.packageDirectory.resolve("manifest.json"))
                .replace("candidate_pending_independent_review", "frozen")
        )
        val specification = runTestSpecification().copy(selection = TaskSelection(taskIds = setOf(task)))
        FileRunStore(directory).use { store ->
            store.begin(specification, listOf(task))
            store.fail("interrupted")
        }
        val metadata = Files.readString(directory.resolve("run.json"))
        val arguments = listOf(directory.toString(), temporaryDirectory.toString(), "--ui=plain")
        fun command() = ResumeCommand(SimpleTestDataset.projectRoot, apiKeyProvider = { error("provider reached") })
        val missing = assertFailsWith<IllegalStateException> {
            command().parse(listOf(temporaryDirectory.resolve("missing").toString()) + arguments.drop(1))
        }
        assertContains(missing.message.orEmpty(), "run directory does not exist")
        val preflight = assertFailsWith<IllegalStateException> { command().parse(arguments) }
        assertEquals("provider reached", preflight.message)
        assertEquals(metadata, Files.readString(directory.resolve("run.json")))
    }
}
