package backend.lean.evaluation

import domain.EvaluationKind
import domain.EvaluationResult
import domain.EvaluationSpecification
import domain.SubmissionKind
import domain.SubmissionSpecification
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.minutes

class LeanSubmissionPolicyTest {
    private val policy = LeanSubmissionPolicy()

    private val submission = SubmissionSpecification(
        kind = SubmissionKind("lean_proof_body"),
        allowLocalHelpers = true,
        allowTopLevelDeclarations = false,
    )

    @Test
    fun allowsProofWithLocalHelper() {
        assertNull(
            policy.checkProofBody(
                "by\n  have h : True := by trivial\n  exact h",
                submission,
                evaluation(),
            ),
        )
    }

    @Test
    fun rejectsSorry() {
        val result = policy.checkProofBody(
            "by sorry",
            submission,
            evaluation(),
        )

        assertContains(assertIs<EvaluationResult.Rejected>(result).reason, "sorry")
    }

    @Test
    fun rejectsForbiddenIdentifier() {
        val result = policy.checkProofBody(
            "by\n  exact translation_preserves_typing",
            submission,
            evaluation(),
        )

        assertContains(
            assertIs<EvaluationResult.Rejected>(result).reason,
            "translation_preserves_typing",
        )
    }

    @Test
    fun distinguishesCaseSensitiveLeanNamesAndIdentifierBoundaries() {
        val specification = evaluation().copy(forbiddenIdentifiers = setOf(
            "Paper2.Contains.freshFunctionInversion", "freshFunctionInversion",
        ))
        assertNull(policy.checkProofBody("by\n  have h : FreshFunctionInversion := by assumption\n  exact h",
            submission, specification))
        assertNull(policy.checkProofBody("by exact freshFunctionInversionResult", submission, specification))
        for (name in listOf("freshFunctionInversion", "Paper2.Contains.freshFunctionInversion")) {
            assertIs<EvaluationResult.Rejected>(policy.checkProofBody("by exact $name", submission, specification))
        }
    }

    @Test
    fun rejectsProofMarker() {
        val result = policy.checkProofBody(
            "by\n  __BENCHMARK_PROOF__",
            submission,
            evaluation(),
        )

        assertContains(
            assertIs<EvaluationResult.Rejected>(result).reason,
            "proof marker",
        )
    }

    @Test
    fun reportsUnknownMechanismAsInfrastructureFailure() {
        val result = policy.checkProofBody(
            "by trivial",
            submission,
            evaluation(setOf("unknown_escape")),
        )

        assertIs<EvaluationResult.InfrastructureFailure>(result)
    }

    private fun evaluation(
        forbiddenMechanisms: Set<String> = setOf("sorry"),
    ) = EvaluationSpecification(
        kind = EvaluationKind("lean_kernel"),
        timeout = 3.minutes,
        allowedAxioms = setOf("propext"),
        forbiddenMechanisms = forbiddenMechanisms,
        forbiddenIdentifiers = setOf("translation_preserves_typing"),
    )
}
