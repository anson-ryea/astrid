package command

import com.github.ajalt.clikt.command.parse
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.core.terminal
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.mordant.rendering.AnsiLevel
import com.github.ajalt.mordant.terminal.Terminal
import com.github.ajalt.mordant.terminal.TerminalRecorder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertFails
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import support.SimpleTestDataset
import dataset.HashCalculator
import dataset.JsonTaskManifestReader

class ValidateCommandTest {
    @TempDir lateinit var temporaryDirectory: Path

    @Test
    fun validatesCandidatePackagesWithoutModelConfigurationOrRunOutput() = runBlocking {
        val checkout = copyProofCheckout()
        SimpleTestDataset.mockLeanEnvironment(checkout)
        val recorder = TerminalRecorder(ansiLevel = AnsiLevel.NONE, width = 160)
        val command = ValidateCommand(SimpleTestDataset.projectRoot)
        command.configureContext { terminal = Terminal(terminalInterface = recorder) }
        val entry = EntryCommand().subcommands(command)

        entry.parse(listOf("validate", checkout.toString()))

        assertContains(recorder.output(), "Validated ${SimpleTestDataset.task("proof").manifest.taskId}")
        assertContains(recorder.output(), "Validated 1 task(s) for backend lean")
        assertFalse(Files.exists(checkout.resolve("runs")))
        assertFalse(Files.exists(temporaryDirectory.resolve("runs")))
        assertEquals(SimpleTestDataset.task("proof").manifest.status,
            dataset.FileTaskRepository(checkout.resolve("dataset"), JsonTaskManifestReader())
                .findAll().single().manifest.status)
    }

    @Test
    fun rejectsChangedPackages() = runBlocking {
        val checkout = copyProofCheckout()
        val taskId = SimpleTestDataset.task("proof").manifest.taskId
        Files.writeString(checkout.resolve("dataset/paper_1/tasks/$taskId/evidence.md"), "changed after hashing")

        val failure = assertFailsWith<IllegalStateException> {
            ValidateCommand(SimpleTestDataset.projectRoot).parse(listOf(checkout.toString()))
        }

        assertContains(failure.message.orEmpty(), "task artefact hash mismatch")
        assertFalse(Files.exists(checkout.resolve("runs")))
    }

    @Test
    fun honoursDatasetAndEnvironmentOverrides() = runBlocking {
        val checkout = copyProofCheckout()
        val otherCheckout = Files.createDirectory(temporaryDirectory.resolve("other"))
        val environment = SimpleTestDataset.mockLeanEnvironment(temporaryDirectory.resolve("environment"))
        val recorder = TerminalRecorder(ansiLevel = AnsiLevel.NONE)
        val command = ValidateCommand(SimpleTestDataset.projectRoot)
        command.configureContext { terminal = Terminal(terminalInterface = recorder) }

        command.parse(listOf(otherCheckout.toString(), "--dataset-root", checkout.resolve("dataset").toString(),
            "--environment-root", environment.toString(), "--evaluation-root", temporaryDirectory.resolve("contracts").toString()))

        assertContains(recorder.output(), "Validated 1 task(s) for backend lean")
        assertFalse(Files.exists(otherCheckout.resolve("runs")))
    }

    @Test
    fun rejectsEmptyDatasetsAndUnsupportedBackends() = runBlocking {
        Files.createDirectory(temporaryDirectory.resolve("dataset"))
        val empty = assertFailsWith<IllegalStateException> {
            ValidateCommand().parse(listOf(temporaryDirectory.toString()))
        }
        assertContains(empty.message.orEmpty(), "dataset contains no tasks supported by backend lean")
        val unsupported = assertFailsWith<IllegalArgumentException> {
            ValidateCommand().parse(listOf(temporaryDirectory.toString(), "--backend", "rocq"))
        }
        assertContains(unsupported.message.orEmpty(), "unsupported formalisation backend: rocq")
    }

    @Test
    fun honoursPaperSelectionAndRejectsInvalidPaperIdentifiers() = runBlocking {
        val dataset = Files.createDirectory(temporaryDirectory.resolve("external"))
        val empty = assertFails {
            ValidateCommand(temporaryDirectory).parse(listOf("--dataset-root", dataset.toString(),
                "--paper-id", "paper_37", "--check-references"))
        }
        assertContains(empty.message.orEmpty(), "no selected tasks")
        val invalid = assertFails {
            ValidateCommand(temporaryDirectory).parse(listOf("--dataset-root", dataset.toString(), "--paper-id", "../private"))
        }
        assertContains(invalid.message.orEmpty(), "paper identifier")
        assertFalse(Files.exists(temporaryDirectory.resolve("runs")))
    }

    @Test
    fun checksPrivateReferencesAndRefreshesPublicHashesOnlyWhenRequested() = runBlocking {
        val checkout = copyProofCheckout()
        val source = SimpleTestDataset.task("proof")
        val manifest = checkout.resolve("dataset/paper_1/tasks/${source.manifest.taskId}/manifest.json")
        val evidence = manifest.parent.resolve("evidence.md")
        val hash = HashCalculator()
        for (useOverrides in listOf(false, true)) {
            val privateRoot = if (useOverrides) temporaryDirectory.resolve("external-private") else checkout.resolve("private")
            val profiles = Files.createDirectories(privateRoot.resolve("validation/paper_1"))
            Files.writeString(privateRoot.resolve("reference.proof"), "by trivial\n")
            val profile = """{
                "schema_version": 1,
                "task_id": "${source.manifest.taskId}",
                "excluded_declarations": [],
                "reference_submission": {"path": "reference.proof", "sha256": "${"0".repeat(64)}"}
            }""".trimIndent()
            val profilePath = profiles.resolve("${source.manifest.taskId}.json")
            Files.writeString(profilePath, profile)
            if (useOverrides) Files.writeString(evidence, "changed after hashing")
            val recorder = TerminalRecorder(ansiLevel = AnsiLevel.NONE, width = 512)
            val command = ValidateCommand(SimpleTestDataset.projectRoot)
            command.configureContext { terminal = Terminal(terminalInterface = recorder) }
            val options = if (useOverrides) listOf("--update-hashes", "--validation-root", profiles.parent.toString(),
                "--reference-root", privateRoot.toString()) else listOf("--check-references")

            assertFailsWith<ProgramResult> {
                command.parse(listOf(checkout.toString(), "--paper-id", "paper_1") + options)
            }

            // A private reference hash error proves the curation path was reached before any Lean process.
            assertContains(recorder.output(), "validation reference hash mismatch")
            assertContains(recorder.output(), "Validated 0/1 Lean packages; task review status unchanged")
            assertEquals(profile, Files.readString(profilePath))
            assertEquals(hash.sha256(evidence), JsonTaskManifestReader().read(manifest).artefacts
                .single { it.path.value == Path.of("evidence.md") }.sha256Digest)
            assertFalse(Files.exists(checkout.resolve("runs")))
        }
    }

    private fun copyProofCheckout(): Path {
        val checkout = Files.createDirectory(temporaryDirectory.resolve("checkout")).toRealPath()
        val source = SimpleTestDataset.task("proof")
        val dataset = checkout.resolve("dataset")
        copyDirectory(source.packageDirectory, dataset.resolve("paper_1/tasks/${source.manifest.taskId}"))
        for (name in listOf("contexts", "formalisation")) {
            copyDirectory(SimpleTestDataset.datasetRoot.resolve("paper_1/$name"), dataset.resolve("paper_1/$name"))
        }
        copyDirectory(SimpleTestDataset.datasetRoot.resolve("prompts"), dataset.resolve("prompts"))
        return checkout
    }

    private fun copyDirectory(source: Path, destination: Path) {
        Files.walk(source).use { paths ->
            paths.forEach { path ->
                val target = destination.resolve(source.relativize(path))
                if (Files.isDirectory(path)) Files.createDirectories(target) else Files.copy(path, target)
            }
        }
    }
}
