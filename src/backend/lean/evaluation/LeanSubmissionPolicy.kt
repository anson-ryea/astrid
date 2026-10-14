package backend.lean.evaluation

import backend.lean.protocol.LeanProofProtocol
import domain.EvaluationResult
import domain.EvaluationSpecification
import domain.SubmissionKind
import domain.SubmissionSpecification

/** Checks the textual restrictions on Lean proof submissions. */
class LeanSubmissionPolicy {
    fun validateSpecification(
        submissionSpecification: SubmissionSpecification,
        evaluationSpecification: EvaluationSpecification,
    ): EvaluationResult.InfrastructureFailure? {
        if (submissionSpecification.kind != SubmissionKind("lean_proof_body")) {
            return EvaluationResult.InfrastructureFailure(
                "unsupported proof submission kind: ${submissionSpecification.kind}",
            )
        }
        if (!submissionSpecification.allowLocalHelpers) {
            return EvaluationResult.InfrastructureFailure(
                "proof-body restrictions on local helpers are not implemented",
            )
        }

        val unknownMechanisms =
            evaluationSpecification.forbiddenMechanisms - mechanismPatterns.keys
        if (unknownMechanisms.isNotEmpty()) {
            return EvaluationResult.InfrastructureFailure(
                "unsupported forbidden mechanisms: " +
                        unknownMechanisms.sorted().joinToString(),
            )
        }
        return null
    }

    fun checkProofBody(
        submission: String,
        submissionSpecification: SubmissionSpecification,
        evaluationSpecification: EvaluationSpecification,
    ): EvaluationResult? {
        validateSpecification(submissionSpecification, evaluationSpecification)?.let { return it }
        if (!proofStart.matchesAt(submission, 0)) {
            return EvaluationResult.Rejected(
                "submission must be one Lean `by` proof body",
            )
        }
        if (LeanProofProtocol.MARKER in submission) {
            return EvaluationResult.Rejected(
                "submission contains the proof marker",
            )
        }
        if (!submissionSpecification.allowTopLevelDeclarations &&
            topLevelDeclaration.containsMatchIn(submission)
        ) {
            return EvaluationResult.Rejected(
                "forbidden mechanism: top-level declaration",
            )
        }

        for (mechanism in evaluationSpecification.forbiddenMechanisms) {
            if (mechanismPatterns.getValue(mechanism).containsMatchIn(submission)) {
                return EvaluationResult.Rejected("forbidden mechanism: $mechanism")
            }
        }

        for (identifier in evaluationSpecification.forbiddenIdentifiers) {
            if (Regex(Regex.escape(identifier), RegexOption.IGNORE_CASE)
                    .containsMatchIn(submission)
            ) {
                return EvaluationResult.Rejected("forbidden identifier: $identifier")
            }
        }

        return null
    }

    private companion object {
        val proofStart = Regex("""\s*by\b""")

        val topLevelDeclaration = Regex(
            """\b(?:axiom|theorem|lemma|def|abbrev|instance)\b""",
            RegexOption.IGNORE_CASE,
        )

        val mechanismPatterns = mapOf(
            "sorry" to Regex("""\bsorry\b""", RegexOption.IGNORE_CASE),
            "admit" to Regex("""\badmit\b""", RegexOption.IGNORE_CASE),
            "import" to Regex("""\bimport\b""", RegexOption.IGNORE_CASE),
            "native_decide" to Regex("""\bnative_decide\b""", RegexOption.IGNORE_CASE),
            "bv_decide" to Regex("""\bbv_decide\b""", RegexOption.IGNORE_CASE),
            "metaprogramming_escape" to Regex(
                """\b(?:run_tac|elab|macro|unsafe)\b""",
                RegexOption.IGNORE_CASE,
            ),
        )
    }
}
