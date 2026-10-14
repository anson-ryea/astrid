package backend.lean.evaluation

import domain.BenchmarkTask
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.JsonPrimitive

/** Runner-owned code is kept outside the candidate's namespace and editable slot. */
class LeanSemanticSourceBuilder(
    harness: String? = null,
    private val harnessPath: Path = Path.of("src/backend/lean/evaluation/SemanticHarness.lean"),
) {
    private val harness by lazy { harness ?: Files.readString(harnessPath) }
    fun candidate(task: BenchmarkTask, submission: String, contract: LeanSemanticContract): String {
        val context = read(task, "lean_context")
        val template = read(task, "lean_target")
        val marker = marker(task.manifest.protocol.value)
        require(template.split(marker).size == 2) { "target must contain exactly one $marker" }
        require(!submission.contains(Regex("__BENCHMARK_[A-Z_]+__"))) { "replace the submission marker" }
        // Import all prerequisites before defining the trusted parser/auditor.
        val block = task.manifest.protocol.value == "lean_declaration"
        val guard = "run_cmd AstridHarness.validate ${quote(submission)} $block ${task.manifest.submission.allowLocalHelpers} " +
            array(contract.outputs.map { it.name }) + " " + array(task.manifest.evaluation.forbiddenIdentifiers.toList())
        val interfaces = contract.outputs.joinToString("\n") { "#check (${it.name} : ${it.type})" }
        return "import Lean\n$context\n${harness.removePrefix("import Lean\n")}\n$guard\n" +
            template.replace(marker, submission) + "\n$interfaces\nrun_cmd AstridHarness.auditCandidate ${array(contract.outputs.map { it.name })}\n"
    }

    /** Must succeed in a separate process before any submitted code is elaborated. */
    fun validation(task: BenchmarkTask, submission: String, contract: LeanSemanticContract): String =
        "import Lean\n${read(task, "lean_context")}\n${harness.removePrefix("import Lean\n")}\n" +
            "run_cmd AstridHarness.validate ${quote(submission)} ${task.manifest.protocol.value == "lean_declaration"} " +
            "${task.manifest.submission.allowLocalHelpers} ${array(contract.outputs.map { it.name })} " +
            "${array(task.manifest.evaluation.forbiddenIdentifiers.toList())}\n"

    fun specification(candidate: String, contract: LeanSemanticContract): String =
        candidate + "\n" + contract.reference + "\n" + contract.specification + "\n" +
            contract.outputs.joinToString("\n") { "#check (${it.name.replaceFirst("Benchmark.", "AstridReference.")} : ${it.type})" } + "\n" +
            (contract.obligations + contract.compatibility).joinToString("\n") { "#check (${it.name} : Prop)" }

    fun proofValidation(task: BenchmarkTask, contract: LeanSemanticContract): String =
        "import Lean\n${read(task, "lean_context")}\n${harness.removePrefix("import Lean\n")}\n" +
            (contract.obligations + contract.compatibility).flatMap { listOfNotNull(it.proof, it.refutation) }
                .joinToString("\n") {
                    require(Regex("^by(?:\\s|$)").containsMatchIn(it.trimStart())) { "certificate scripts must be by proof bodies" }
                    "run_cmd AstridHarness.validateProof ${quote(it)}"
                }

    fun certificate(base: String, check: SemanticObligation, refutation: Boolean): String {
        val body = requireNotNull(if (refutation) check.refutation else check.proof)
        require(Regex("^by(?:\\s|$)").containsMatchIn(body.trimStart())) { "certificate scripts must be by proof bodies" }
        val name = "AstridCertificates.checked"
        val goal = if (refutation) "¬ ${check.name}" else check.name
        return "$base\ntheorem $name : $goal :=\n$body\nrun_cmd AstridHarness.auditCertificate `$name\n"
    }

    private fun read(task: BenchmarkTask, role: String) = Files.readString(task.pathFor(task.manifest.artefacts.single { it.role.value == role }))
    private fun quote(value: String) = JsonPrimitive(value).toString()
    private fun array(values: List<String>) = values.joinToString(", ", "#[", "]", transform = ::quote)

    companion object {
        fun marker(protocol: String): String = when (protocol) {
            "lean_proof" -> "__BENCHMARK_PROOF__"
            "lean_definition" -> "__BENCHMARK_TERM__"
            "lean_declaration" -> "__BENCHMARK_DECLARATION__"
            "lean_statement" -> "__BENCHMARK_STATEMENT__"
            else -> error("unsupported Lean protocol: $protocol")
        }
    }
}
