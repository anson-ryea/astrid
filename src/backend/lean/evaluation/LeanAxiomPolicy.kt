package backend.lean.evaluation

/** Checks the axioms reported for a compiled Lean target. */
class LeanAxiomPolicy {
    fun check(output: String, allowedAxioms: Set<String>): LeanAxiomCheck {
        if (reportedPlaceholder.containsMatchIn(output)) {
            return LeanAxiomCheck.Placeholder
        }

        // The target's #print axioms command follows the submitted proof.
        val report = axiomReports.findAll(output).lastOrNull()
            ?: return LeanAxiomCheck.Unreadable

        val axioms = report.groups[1]?.value
            ?.split(',')
            ?.map(String::trim)
            ?.filter(String::isNotEmpty)
            ?.toSet()
            ?: emptySet()

        val unexpected = axioms - allowedAxioms
        return if (unexpected.isEmpty()) {
            LeanAxiomCheck.Allowed(axioms)
        } else {
            LeanAxiomCheck.Forbidden(axioms, unexpected)
        }
    }

    private companion object {
        val reportedPlaceholder = Regex(
            """uses ['"]?sorry|sorryAx|declaration has metavariables""",
        )

        val axiomReports = Regex(
            """does not depend on any axioms|(?:depends on axioms|axioms):\s*\[([^]]*)]""",
            RegexOption.DOT_MATCHES_ALL,
        )
    }
}
