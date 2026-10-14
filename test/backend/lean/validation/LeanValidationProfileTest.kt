package backend.lean.validation

import support.SimpleTestDataset
import dataset.HashCalculator
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir

class LeanValidationProfileTest {
    @TempDir lateinit var temporaryDirectory: Path
    private val task = SimpleTestDataset.task("proof")

    private fun setup(): Pair<LeanValidationProfileReader, JsonObject> {
        val root = temporaryDirectory.toRealPath()
        Files.createDirectories(root.resolve("profiles/paper_1"))
        Files.writeString(root.resolve("reference.proof"), "by trivial\n")
        val document = buildJsonObject {
            put("schema_version", 1)
            put("task_id", task.manifest.taskId.value)
            put("excluded_declarations", JsonArray(listOf(JsonPrimitive("Synthetic.answer"))))
            put("reference_submission", buildJsonObject {
                put("path", "reference.proof")
                put("sha256", HashCalculator().sha256(root.resolve("reference.proof")).value)
            })
        }
        write(document)
        return LeanValidationProfileReader(root.resolve("profiles"), root) to document
    }

    private fun write(document: JsonObject) = Files.writeString(temporaryDirectory.resolve(
        "profiles/paper_1/${task.manifest.taskId.value}.json"), document.toString())

    @Test fun rejectsWrongIdentitiesTypesUnknownFieldsAndMissingFields() {
        val (reader, original) = setup()
        val invalid = listOf(
            JsonObject(original + ("schema_version" to JsonPrimitive("1"))),
            JsonObject(original + ("task_id" to JsonPrimitive("task_9.1_wrong"))),
            JsonObject(original + ("unexpected" to JsonPrimitive(true))),
            JsonObject(original - "excluded_declarations"),
            JsonObject(original - "reference_submission"),
            JsonObject(original + ("excluded_declarations" to JsonArray(listOf(JsonPrimitive("A.b"), JsonPrimitive("A.b"))))),
            JsonObject(original + ("excluded_declarations" to JsonArray(listOf(JsonPrimitive("A.b; import Lean"))))),
            JsonObject(original + ("same_artefacts_as" to JsonPrimitive(true))),
        )
        invalid.forEach { value -> write(value); assertFails { reader.read(task) } }
    }

    @Test fun rejectsChangedReferencesEscapesAndSymlinkedAncestors() {
        val (reader, document) = setup()
        assertEquals("by trivial\n", reader.read(task).referenceSubmission)
        Files.writeString(temporaryDirectory.resolve("reference.proof"), "by sorry\n")
        assertContains(assertFails { reader.read(task) }.message.orEmpty(), "hash mismatch")
        val descriptor = document.getValue("reference_submission").jsonObject
        for (path in listOf("../reference.proof", "/reference.proof", "a/../reference.proof")) {
            write(JsonObject(document + ("reference_submission" to JsonObject(descriptor + ("path" to JsonPrimitive(path))))))
            assertFails { reader.read(task) }
        }
        write(document)
        val profiles = temporaryDirectory.resolve("profiles")
        Files.move(profiles.resolve("paper_1"), profiles.resolve("moved"))
        Files.createSymbolicLink(profiles.resolve("paper_1"), profiles.resolve("moved"))
        assertFails { reader.read(task) }
    }

    @Test fun simpleProfilesRetainAnswerExclusionsAndArtefactComparisons() {
        val reader = SimpleTestDataset.profiles()
        val profile = reader.read(task)
        assertTrue("Simple.referenceProof" in profile.excludedDeclarations)
        assertTrue("Simple.Unrelated.secret" in profile.excludedDeclarations)
        val tasks = SimpleTestDataset.tasks()
        tasks.forEach {
            val reference = reader.read(it)
            if (it.manifest.protocol.value == "lean_proof") {
                assertNull(backend.lean.evaluation.LeanSubmissionPolicy().checkProofBody(reference.referenceSubmission.trim(),
                    it.manifest.submission, it.manifest.evaluation), it.manifest.taskId.value)
            }
        }
        val proof = SimpleTestDataset.task("proof")
        assertEquals("task_1.5_proof_completion", reader.read(proof).sameArtefactsAs.single().taskId.value)
    }
}
