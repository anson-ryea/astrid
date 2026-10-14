package backend.lean.evaluation

import dataset.TaskPackageVerifier
import domain.*
import evaluation.TaskEvaluator
import kotlin.time.Duration
import kotlin.time.TimeSource

/** Checks trusted, fixed semantic obligations; private compiler output is never model feedback. */
class LeanSemanticEvaluator(
    private val process: LeanProcess,
    private val contracts: LeanSemanticContractReader,
    private val sources: LeanSemanticSourceBuilder = LeanSemanticSourceBuilder(),
    private val verifyContext: (BenchmarkTask) -> Unit = {},
) : TaskEvaluator {
    override val kind = EvaluationKind("lean_semantic")
    private val calibrated = mutableSetOf<String>()

    override fun validateTask(task: BenchmarkTask) {
        val contract = validateMaterial(task)
        val key = contract.digest + task.manifest.toString()
        if (key in calibrated) return
        val scripts = process.run(sources.proofValidation(task, contract), task.manifest.evaluation.timeout)
        require(scripts is LeanProcessResult.Completed && scripts.exitCode == 0) { "invalid or unavailable private certificate scripts" }
        val report = assess(task, contract.referenceSubmission, contract, calibration = true)
        require(report.result == EvaluationResult.Accepted && report.evidence!!.compatibility.all { it.certificate == "proved" }) {
            "semantic reference calibration failed for ${task.manifest.taskId}: ${report.result}"
        }
        calibrated += key
    }

    /** Material validation is also used before creating an isolated preflight environment. */
    fun validateMaterial(task: BenchmarkTask): LeanSemanticContract {
        super.validateTask(task)
        require(task.manifest.taskType.value == 1) { "lean_semantic requires a Type 1 task" }
        val expected = when (task.manifest.protocol.value) {
            "lean_definition" -> "lean_term"
            "lean_declaration" -> "lean_declaration_block"
            "lean_statement" -> "lean_statement"
            else -> error("unsupported semantic protocol")
        }
        require(task.manifest.submission.kind.value == expected) { "semantic submission kind does not match protocol" }
        require(task.manifest.protocol.value != "lean_declaration" || task.manifest.submission.allowTopLevelDeclarations) { "declaration submissions must allow declarations" }
        require(task.manifest.evaluation.forbiddenMechanisms.all { it in mechanisms }) { "unsupported forbidden mechanism" }
        TaskPackageVerifier().verify(task)
        verifyContext(task)
        return contracts.read(task)
    }

    override fun evaluate(task: BenchmarkTask, submission: String): EvaluationResult = evaluateDetailed(task, submission).result

    override fun reportUnassessed(task: BenchmarkTask, result: EvaluationResult): EvaluationReport = try {
        val contract = contracts.read(task)
        EvaluationReport(result, evidence(contract, if (result is EvaluationResult.Rejected) "policy_rejected" else "not_run"))
    } catch (_: Exception) {
        EvaluationReport(EvaluationResult.InfrastructureFailure("semantic evidence is unavailable"), publicDiagnostic = null)
    }

    override fun evaluateDetailed(task: BenchmarkTask, submission: String): EvaluationReport = try {
        validateTask(task)
        assess(task, submission, contracts.read(task), calibration = false)
    } catch (failure: Exception) {
        EvaluationReport(EvaluationResult.InfrastructureFailure("could not prepare semantic evaluation: ${failure.message}"), publicDiagnostic = null)
    }

    private fun assess(task: BenchmarkTask, submission: String, contract: LeanSemanticContract, calibration: Boolean): EvaluationReport {
        val start = TimeSource.Monotonic.markNow()
        fun run(source: String): LeanProcessResult {
            val remaining = task.manifest.evaluation.timeout - start.elapsedNow()
            return if (remaining <= Duration.ZERO) LeanProcessResult.TimedOut else process.run(source, remaining)
        }
        var elaboration = "not_run"
        var axioms: Map<String, Set<String>> = emptyMap()
        val obligations = mutableListOf<CheckEvidence>()
        val compatibility = mutableListOf<CheckEvidence>()
        fun finish(result: EvaluationResult, public: Boolean = false): EvaluationReport = EvaluationReport(
            result, evidence(contract, elaboration, axioms, obligations, compatibility),
            if (public) (result as? EvaluationResult.Rejected)?.reason else null,
        )
        val candidate = try { sources.candidate(task, submission, contract) } catch (failure: IllegalArgumentException) {
            return finish(EvaluationResult.Rejected(failure.message ?: "invalid submission slot"), public = true)
        }
        when (val validation = run(sources.validation(task, submission, contract))) {
            is LeanProcessResult.Completed -> if (validation.exitCode != 0) {
                elaboration = "policy_rejected"
                return finish(EvaluationResult.Rejected("Lean submission policy rejected the candidate:\n${validation.output}"), public = true)
            }
            LeanProcessResult.TimedOut -> return finish(EvaluationResult.InfrastructureFailure("submission parsing timed out"))
            is LeanProcessResult.InfrastructureFailure -> return finish(EvaluationResult.InfrastructureFailure(validation.reason))
        }
        when (val compiled = run(candidate)) {
            is LeanProcessResult.Completed -> {
                if (compiled.exitCode != 0) {
                    elaboration = "rejected"
                    return finish(EvaluationResult.Rejected("Lean rejected the submission:\n${compiled.output}"), public = true)
                }
                elaboration = "passed"
                axioms = audit(compiled.output, "ASTRID_AUDIT")
                if (!compiled.output.contains("ASTRID_AUDIT_END") || !axioms.keys.containsAll(contract.outputs.map { it.name })) {
                    return finish(EvaluationResult.InfrastructureFailure("missing candidate environment audit"))
                }
                val forbidden = axioms.values.flatten().toSet() - task.manifest.evaluation.allowedAxioms
                if (forbidden.isNotEmpty()) return finish(EvaluationResult.Rejected("candidate uses forbidden axioms: ${forbidden.sorted().joinToString()}"), public = true)
            }
            LeanProcessResult.TimedOut -> return finish(EvaluationResult.InfrastructureFailure("candidate elaboration timed out"))
            is LeanProcessResult.InfrastructureFailure -> return finish(EvaluationResult.InfrastructureFailure(compiled.reason))
        }
        val base = sources.specification(candidate, contract)
        when (val spec = run(base)) {
            is LeanProcessResult.Completed -> if (spec.exitCode != 0) return finish(
                if (calibration) EvaluationResult.InfrastructureFailure("reference specification did not elaborate")
                else EvaluationResult.NeedsReview("candidate elaborates, but the private specification could not be instantiated"))
            LeanProcessResult.TimedOut -> return finish(EvaluationResult.NeedsReview("semantic checking budget exhausted"))
            is LeanProcessResult.InfrastructureFailure -> return finish(EvaluationResult.InfrastructureFailure(spec.reason))
        }
        fun prove(check: SemanticObligation, negate: Boolean): String = when (val proof = run(sources.certificate(base, check, negate))) {
            is LeanProcessResult.Completed -> if (proof.exitCode != 0) "unresolved" else {
                val report = audit(proof.output, "ASTRID_CERTIFICATE")
                when {
                    "AstridCertificates.checked" !in report -> "audit_missing"
                    report.values.flatten().any { it !in certificateAxioms } -> "forbidden_axioms"
                    else -> "proved"
                }
            }
            LeanProcessResult.TimedOut -> "timed_out"
            is LeanProcessResult.InfrastructureFailure -> "infrastructure_failure"
        }
        fun check(obligation: SemanticObligation): CheckEvidence {
            val proof = prove(obligation, false)
            // Also check an available refutation after a successful proof to detect inconsistent contracts.
            val refutation = if (obligation.refutation == null) "not_configured" else prove(obligation, true)
            return CheckEvidence(obligation.name, proof, refutation)
        }
        contract.obligations.forEach { obligations += check(it) }
        contract.compatibility.forEach { compatibility += check(it) }
        val all = obligations + compatibility
        if (all.any { it.certificate in failures || it.refutation in failures || (it.certificate == "proved" && it.refutation == "proved") }) {
            return finish(EvaluationResult.InfrastructureFailure("private certificate audit or execution failed"))
        }
        if (obligations.any { it.refutation == "proved" }) return finish(EvaluationResult.Rejected("private checks established semantic disagreement"))
        if (obligations.any { it.certificate != "proved" }) return finish(EvaluationResult.NeedsReview("semantic agreement was not established for every mandatory obligation"))
        return finish(EvaluationResult.Accepted)
    }

    private fun evidence(contract: LeanSemanticContract, elaboration: String,
        axioms: Map<String, Set<String>> = emptyMap(), obligations: List<CheckEvidence> = emptyList(),
        compatibility: List<CheckEvidence> = emptyList()): EvaluationEvidence {
        fun complete(configured: List<SemanticObligation>, assessed: List<CheckEvidence>) = configured.map { check ->
            assessed.find { it.name == check.name } ?: CheckEvidence(check.name, "not_run",
                if (check.refutation == null) "not_configured" else "not_run")
        }
        return EvaluationEvidence(contract.digest, contract.fileDigests, elaboration, axioms,
            complete(contract.obligations, obligations), complete(contract.compatibility, compatibility))
    }

    private fun audit(output: String, prefix: String): Map<String, Set<String>> {
        val reports = linkedMapOf<String, Set<String>>()
        Regex("${Regex.escape(prefix)}\\|([^|\\r\\n]+)\\|([^\\r\\n]*)").findAll(output).forEach {
            val name = it.groupValues[1].trim()
            reports[name] = reports[name].orEmpty() + it.groupValues[2].split(',').map(String::trim).filter(String::isNotEmpty)
        }
        return reports
    }

    companion object {
        private val certificateAxioms = setOf("propext", "Classical.choice", "Quot.sound")
        private val failures = setOf("audit_missing", "forbidden_axioms", "infrastructure_failure")
        private val mechanisms = setOf("sorry", "admit", "import", "native_decide", "bv_decide", "metaprogramming_escape")
    }
}
