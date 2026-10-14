package backend.lean.evaluation

import support.SimpleTestDataset
import domain.*
import kotlin.test.*

class LeanSemanticEvaluatorTest {
    private val task = SimpleTestDataset.task("definition")
    private val reader = SimpleTestDataset.contracts()
    private val contract = reader.read(task)
    private fun success(source: String): LeanProcessResult = if ("theorem AstridCertificates.checked : ¬" in source)
        LeanProcessResult.Completed(1, "reference has no semantic refutation") else LeanProcessResult.Completed(0, when {
        "theorem AstridCertificates.checked" in source -> "ASTRID_CERTIFICATE|AstridCertificates.checked|propext"
        "run_cmd AstridHarness.auditCandidate" in source -> "ASTRID_AUDIT|Benchmark.target|\nASTRID_AUDIT|Benchmark.helper|\nASTRID_AUDIT_END"
        else -> ""
    })
    private fun assessed(process: (String) -> LeanProcessResult): EvaluationReport {
        var calibrated = false
        val evaluator = LeanSemanticEvaluator({ source, _ -> if (calibrated) process(source) else success(source) }, reader)
        evaluator.validateTask(task)
        calibrated = true
        return evaluator.evaluateDetailed(task, "candidate")
    }

    @Test fun certificatesOwnFixedStatementTypes() {
        val sources = LeanSemanticSourceBuilder()
        val source = sources.certificate(sources.specification(sources.candidate(task, contract.referenceSubmission, contract), contract), contract.obligations.single(), false)
        assertContains(source, "theorem AstridCertificates.checked : AstridSpec.agreement :=")
        assertFalse("private/evaluation" in source)
        assertContains(sources.certificate("", SemanticObligation("AstridSpec.agreement", "by trivial", "by trivial"), true), ": ¬ AstridSpec.agreement :=")
    }

    @Test fun collectsAllCandidateAuditsIncludingUnusedHelpers() {
        val report = assessed { source ->
            if ("run_cmd AstridHarness.auditCandidate" in source) LeanProcessResult.Completed(0,
                "ASTRID_AUDIT|Benchmark.target|\nASTRID_AUDIT|Benchmark.helper|sorryAx\nASTRID_AUDIT|Benchmark.target|\nASTRID_AUDIT_END") else success(source)
        }
        assertIs<EvaluationResult.Rejected>(report.result)
        assertEquals(setOf("sorryAx"), report.evidence!!.candidateAxioms["Benchmark.helper"])
        assertNotNull(report.publicDiagnostic)
    }

    @Test fun successfulCompilationWithoutAuditIsInfrastructureFailure() {
        assertIs<EvaluationResult.InfrastructureFailure>(assessed { LeanProcessResult.Completed(0, "") }.result)
    }

    @Test fun earlySubmissionFailuresRetainDigestsAndExplicitUnrunChecks() {
        val evaluator = LeanSemanticEvaluator({ _, _ -> error("no compilation for an unassessed submission") }, reader)
        val report = evaluator.reportUnassessed(task, EvaluationResult.Rejected("public slot error"))
        assertEquals("public slot error", report.publicDiagnostic)
        assertEquals(contract.digest, report.evidence!!.contractSha256)
        assertEquals(contract.fileDigests, report.evidence.fileDigests)
        assertEquals("policy_rejected", report.evidence.elaboration)
        assertEquals("not_run", report.evidence.obligations.single().certificate)
        assertEquals("not_run", report.evidence.obligations.single().refutation)
    }

    @Test fun unresolvedAndTimedOutCertificatesNeedReviewAndCannotTriggerRepair() {
        for (failure in listOf(LeanProcessResult.Completed(1, "PRIVATE SOURCE SECRET"), LeanProcessResult.TimedOut)) {
            val report = assessed { source -> if ("theorem AstridCertificates.checked" in source) failure else success(source) }
            assertIs<EvaluationResult.NeedsReview>(report.result)
            assertNull(report.publicDiagnostic)
            assertFalse(report.toString().contains("PRIVATE SOURCE SECRET"))
        }
    }

    @Test fun candidateAndParserTimeoutsAreInfrastructureFailures() {
        assertIs<EvaluationResult.InfrastructureFailure>(assessed { LeanProcessResult.TimedOut }.result)
        assertIs<EvaluationResult.InfrastructureFailure>(assessed { source ->
            if ("run_cmd AstridHarness.auditCandidate" in source) LeanProcessResult.TimedOut else success(source)
        }.result)
    }

    @Test fun forbiddenCertificateAxiomsAreHarnessFailures() {
        assertIs<EvaluationResult.InfrastructureFailure>(assessed { source ->
            if ("theorem AstridCertificates.checked" in source) LeanProcessResult.Completed(0, "ASTRID_CERTIFICATE|AstridCertificates.checked|sorryAx") else success(source)
        }.result)
    }

    @Test fun candidatePolicyAndElaborationFailuresRemainPublic() {
        for (stage in listOf("run_cmd AstridHarness.validate", "run_cmd AstridHarness.auditCandidate")) {
            val report = assessed { source -> if (stage in source) LeanProcessResult.Completed(1, "public syntax error") else success(source) }
            assertIs<EvaluationResult.Rejected>(report.result)
            assertContains(report.publicDiagnostic!!, "public syntax error")
        }
    }

    @Test fun hashChangesMissingContractsAndUnsupportedProtocolsAreConfigurationFailures() {
        val noContract = BenchmarkTask(task.manifest.copy(evaluation = task.manifest.evaluation.copy(contract = null)), task.packageDirectory)
        assertFails { LeanSemanticEvaluator({ _, _ -> error("must not execute") }, reader).validateTask(noContract) }
        val locator = task.manifest.evaluation.contract!!
        val wrongHash = BenchmarkTask(task.manifest.copy(evaluation = task.manifest.evaluation.copy(contract = locator.copy(sha256 = Sha256Digest("0".repeat(64))))), task.packageDirectory)
        assertFails { reader.read(wrongHash) }
        val wrongProtocol = BenchmarkTask(task.manifest.copy(protocol = ProtocolId("lean_proof")), task.packageDirectory)
        assertFails { reader.read(wrongProtocol) }
    }

    @Test fun contradictoryCertificatesAndRefutationsAreHarnessFailures() {
        val task = SimpleTestDataset.task("definition")
        val evaluator = LeanSemanticEvaluator({ source, _ ->
            if ("theorem AstridCertificates.checked" in source) LeanProcessResult.Completed(0, "ASTRID_CERTIFICATE|AstridCertificates.checked|propext")
            else success(source)
        }, SimpleTestDataset.contracts())
        assertFailsWith<IllegalArgumentException> { evaluator.validateTask(task) }
    }

    @Test fun compatibilityCannotOverrideMandatoryResultsAndContradictionsFailTheHarness() {
        val task = SimpleTestDataset.task("statement")
        var mode = "calibration"
        val evaluator = LeanSemanticEvaluator({ source, _ ->
            val refutation = "theorem AstridCertificates.checked : ¬ AstridSpec.agreement" in source
            val compatibility = "theorem AstridCertificates.checked : AstridSpec.compatibility" in source
            val agreement = "theorem AstridCertificates.checked : AstridSpec.agreement" in source
            if ((refutation && mode !in setOf("refuted", "contradiction")) ||
                (compatibility && mode == "compatibility_unresolved") ||
                (agreement && mode in setOf("unresolved", "refuted"))) LeanProcessResult.Completed(1, "PRIVATE DIAGNOSTIC")
            else if (refutation) LeanProcessResult.Completed(0, "ASTRID_CERTIFICATE|AstridCertificates.checked|propext")
            else success(source)
        }, SimpleTestDataset.contracts())
        evaluator.validateTask(task)
        for ((setting, expected) in listOf(
            "compatibility_unresolved" to EvaluationResult.Accepted::class,
            "unresolved" to EvaluationResult.NeedsReview::class,
            "refuted" to EvaluationResult.Rejected::class,
            "contradiction" to EvaluationResult.InfrastructureFailure::class,
        )) {
            mode = setting
            val report = evaluator.evaluateDetailed(task, "candidate")
            assertEquals(expected, report.result::class)
            assertNull(report.publicDiagnostic)
            assertFalse(report.toString().contains("PRIVATE DIAGNOSTIC"))
        }
    }
}
