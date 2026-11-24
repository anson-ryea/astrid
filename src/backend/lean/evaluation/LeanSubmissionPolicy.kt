package backend.lean.evaluation

import backend.lean.protocol.LeanProofProtocol
import domain.EvaluationResult
import domain.EvaluationSpecification
import domain.SubmissionKind
import domain.SubmissionSpecification
import kotlinx.serialization.json.Json

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
        parseTokens: () -> LeanProcessResult,
    ): EvaluationResult? {
        validateSpecification(submissionSpecification, evaluationSpecification)?.let { return it }
        val rejection = checkTokens(submission, submissionSpecification, evaluationSpecification) ?: return null
        if (!mayContainComment(submission)) return rejection

        // Ask the pinned parser which whitespace belongs to comments. Never elaborate the submitted
        // term during this pass, and always compile its original source after checking the policy.
        return when (val parsed = parseTokens()) {
            LeanProcessResult.TimedOut -> EvaluationResult.InfrastructureFailure("Lean proof parsing timed out")
            is LeanProcessResult.InfrastructureFailure -> EvaluationResult.InfrastructureFailure(parsed.reason)
            is LeanProcessResult.Completed -> {
                if (parsed.exitCode != 0) return EvaluationResult.Rejected("Lean rejected the proof syntax:\n${parsed.output}")
                val report = parsed.output.lineSequence().singleOrNull { it.startsWith(TOKEN_PREFIX) }
                    ?: return EvaluationResult.InfrastructureFailure("missing Lean proof token report")
                val code = Json.decodeFromString<String>(report.removePrefix(TOKEN_PREFIX))
                checkTokens(code, submissionSpecification, evaluationSpecification)
            }
        }
    }

    private fun checkTokens(
        submission: String,
        submissionSpecification: SubmissionSpecification,
        evaluationSpecification: EvaluationSpecification,
    ): EvaluationResult? {
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
            // Lean names are case-sensitive; a permitted type or longer name
            // must not be mistaken for the forbidden theorem it resembles.
            if (Regex("""(?<![\p{L}\p{N}_'])${Regex.escape(identifier)}(?![\p{L}\p{N}_'])""")
                    .containsMatchIn(submission)
            ) {
                return EvaluationResult.Rejected("forbidden identifier: $identifier")
            }
        }

        return null
    }

    companion object {
        internal const val TOKEN_PREFIX = "ASTRID_PROOF_CODE "

        internal fun mayContainComment(source: String) = "--" in source || "/-" in source

        private val proofStart = Regex("""\s*by\b""")

        private val topLevelDeclaration = Regex(
            """\b(?:axiom|theorem|lemma|def|abbrev|instance)\b""",
            RegexOption.IGNORE_CASE,
        )

        private val mechanismPatterns = mapOf(
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
