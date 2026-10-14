package dataset

import domain.ArtefactRole
import domain.TaskManifest
import support.SimpleTestDataset
import java.nio.file.Files
import kotlin.io.path.deleteIfExists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

class JsonTaskManifestReaderTest {
    private val reader = JsonTaskManifestReader()

    @Test
    fun readsCurrentV1Manifest() {
        val manifest = reader.read(
            manifestPath("task_1.4_proof"),
        )

        assertEquals(TaskManifest.SCHEMA_VERSION, manifest.schemaVersion)
        assertEquals("task_1.4_proof", manifest.taskId.value)
        assertEquals("paper_1", manifest.paperId.value)
        assertEquals("proof", manifest.sourceResultId.value)
        assertEquals(2, manifest.taskType.value)
        assertEquals("lean_proof", manifest.protocol.value)
        assertEquals("paper_evidence", manifest.condition.value)
        assertEquals("proof", manifest.contextId.value)
        assertEquals(
            setOf(
                ArtefactRole("lean_context"),
                ArtefactRole("lean_target"),
                ArtefactRole("paper_evidence"),
            ),
            manifest.artefacts.map { artefact -> artefact.role }.toSet(),
        )
        assertEquals("lean_proof_body", manifest.submission.kind.value)
        assertTrue(manifest.submission.allowLocalHelpers)
        assertEquals(3.minutes, manifest.evaluation.timeout)
        assertEquals(
            setOf("propext", "Classical.choice", "Quot.sound"),
            manifest.evaluation.allowedAxioms,
        )
    }

    @Test
    fun readsTaskTypesWithoutAClosedLegacyMapping() {
        val manifest = reader.read(
            manifestPath("task_1.5_proof_completion"),
        )

        assertEquals(5, manifest.taskType.value)
        assertEquals("lean_proof", manifest.protocol.value)
        assertEquals(
            ArtefactRole("enriched_evidence"),
            manifest.artefacts.single { artefact ->
                artefact.path.toString() == "evidence.md"
            }.role,
        )
    }

    @Test
    fun readsEverySimpleDatasetManifest() {
        val tasksDirectory = SimpleTestDataset.datasetRoot.resolve("paper_1/tasks")
        val manifestPaths = Files.list(tasksDirectory).use { paths ->
            paths.filter(Files::isDirectory)
                .map { directory -> directory.resolve("manifest.json") }
                .toList()
        }
        assertTrue(manifestPaths.isNotEmpty())
        manifestPaths.forEach(reader::read)
    }

    @Test
    fun rejectsTheRemovedManifestShape() {
        withTemporaryManifest(
            """
            {
              "schema_version": 1,
              "hashes": {}
            }
            """.trimIndent(),
        ) { manifestPath ->
            val failure = assertFailsWith<ManifestReadException> {
                reader.read(manifestPath)
            }

            assertEquals(
                "manifest contains unsupported fields: hashes",
                failure.reason,
            )
        }
    }

    @Test
    fun rejectsUnsupportedSchemaVersion() {
        withTemporaryManifest("""{"schema_version": 2}""") { manifestPath ->
            val failure = assertFailsWith<ManifestReadException> {
                reader.read(manifestPath)
            }

            assertEquals("unsupported schema version: 2", failure.reason)
        }
    }

    @Test
    fun rejectsLegacyNumericTimeoutField() {
        val current = currentManifestContents()
        val legacy = current.replace(
            "\"timeout\": \"PT3M\"",
            "\"timeout_seconds\": 180",
        )
        assertTrue(legacy != current)

        withTemporaryManifest(legacy) { manifestPath ->
            val failure = assertFailsWith<ManifestReadException> {
                reader.read(manifestPath)
            }
            assertEquals(
                "evaluation contains unsupported fields: timeout_seconds",
                failure.reason,
            )
        }
    }

    @Test
    fun rejectsInvalidTimeouts() {
        val current = currentManifestContents()
        for (invalid in listOf("3m", "PT0S", "-PT1S")) {
            val changed = current.replace(
                "\"timeout\": \"PT3M\"",
                "\"timeout\": \"$invalid\"",
            )
            assertTrue(changed != current)

            withTemporaryManifest(changed) { manifestPath ->
                val failure = assertFailsWith<ManifestReadException> {
                    reader.read(manifestPath)
                }
                assertTrue(failure.reason.contains("timeout"))
            }
        }
    }

    @Test
    fun rejectsNumericTimeout() {
        val current = currentManifestContents()
        val changed = current.replace("\"timeout\": \"PT3M\"", "\"timeout\": 180")
        assertTrue(changed != current)

        withTemporaryManifest(changed) { manifestPath ->
            val failure = assertFailsWith<ManifestReadException> {
                reader.read(manifestPath)
            }
            assertEquals("required field 'timeout' must be a string", failure.reason)
        }
    }

    private fun manifestPath(taskId: String) = SimpleTestDataset.datasetRoot.resolve("paper_1/tasks/$taskId/manifest.json")

    private fun currentManifestContents(): String = manifestPath("task_1.4_proof").readText()

    private fun withTemporaryManifest(
        contents: String,
        block: (java.nio.file.Path) -> Unit,
    ) {
        val manifestPath = Files.createTempFile(
            "task-manifest-",
            ".json",
        )
        try {
            manifestPath.writeText(contents)
            block(manifestPath)
        } finally {
            manifestPath.deleteIfExists()
        }
    }
}
