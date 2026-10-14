package backend.lean.evaluation

import support.SimpleTestDataset
import dataset.*
import domain.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir

class LeanSemanticContractTest {
    @TempDir lateinit var directory: Path
    private val task = SimpleTestDataset.task("definition")
    private fun fixture(transform: (MutableMap<String, JsonElement>) -> Unit = {}): BenchmarkTask {
        val original = SimpleTestDataset.evaluationRoot.resolve(task.manifest.evaluation.contract!!.path.value).parent
        Files.list(original).use { it.forEach { source -> Files.copy(source, directory.resolve(source.fileName), java.nio.file.StandardCopyOption.REPLACE_EXISTING) } }
        val contract = directory.resolve("contract.json")
        val document = Json.parseToJsonElement(Files.readString(contract)).jsonObject.toMutableMap()
        transform(document)
        Files.writeString(contract, JsonObject(document).toString())
        return BenchmarkTask(task.manifest.copy(evaluation = task.manifest.evaluation.copy(
            contract = EvaluationContract(ArtefactPath(Path.of("contract.json")), HashCalculator().sha256(contract)))), task.packageDirectory)
    }
    @Test fun readsAllHashedFilesAndRequiredInterfaces() {
        val contract = LeanSemanticContractReader(directory).read(fixture())
        assertEquals(5, contract.fileDigests.size)
        assertEquals(listOf("Benchmark.target"), contract.outputs.map { it.name })
        assertTrue(contract.outputs.single().type.startsWith("∀"))
    }
    @Test fun invalidIdentitiesShapesAndPathsFailClosed() {
        val mutations: List<(MutableMap<String, JsonElement>) -> Unit> = listOf(
            { it["task_id"] = JsonPrimitive("other") },
            { it["schema_version"] = JsonPrimitive("1") },
            { it["extra"] = JsonPrimitive(true) },
            { it["obligations"] = JsonArray(emptyList()) },
            { it["outputs"] = JsonArray(listOf(buildJsonObject { put("name", "Benchmark.target") })) },
            { it["reference"] = buildJsonObject { put("path", "../outside.lean"); put("sha256", "a".repeat(64)) } },
        )
        mutations.forEach { mutation -> assertFails { LeanSemanticContractReader(directory).read(fixture(mutation)) } }
    }
    @Test fun changedFilesAndSymbolicLinksAreRejected() {
        val task = fixture()
        Files.writeString(directory.resolve("Specification.lean"), "changed after hashing")
        assertFails { LeanSemanticContractReader(directory).read(task) }
        val restored = fixture()
        Files.delete(directory.resolve("Specification.lean"))
        Files.createSymbolicLink(directory.resolve("Specification.lean"), SimpleTestDataset.evaluationRoot.resolve("paper_1/task_1.1_definition/Specification.lean"))
        assertFails { LeanSemanticContractReader(directory).read(restored) }
    }
}
