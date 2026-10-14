package backend.lean.evaluation

import kotlin.test.Test
import kotlin.test.assertEquals

class LeanAxiomPolicyTest {
    private val policy = LeanAxiomPolicy()

    @Test
    fun acceptsNoAxioms() {
        assertEquals(
            LeanAxiomCheck.Allowed(emptySet()),
            policy.check(
                "Benchmark.target does not depend on any axioms",
                emptySet(),
            ),
        )
    }

    @Test
    fun acceptsAllowedAxioms() {
        assertEquals(
            LeanAxiomCheck.Allowed(setOf("propext")),
            policy.check(
                "Benchmark.target depends on axioms: [propext]",
                setOf("propext"),
            ),
        )
    }

    @Test
    fun rejectsUnexpectedAxioms() {
        assertEquals(
            LeanAxiomCheck.Forbidden(
                axioms = setOf("Classical.choice"),
                unexpected = setOf("Classical.choice"),
            ),
            policy.check(
                "Benchmark.target depends on axioms: [Classical.choice]",
                setOf("propext"),
            ),
        )
    }

    @Test
    fun rejectsReportedPlaceholder() {
        assertEquals(
            LeanAxiomCheck.Placeholder,
            policy.check("declaration uses 'sorry'", emptySet()),
        )
    }

    @Test
    fun reportsMissingAxiomOutput() {
        assertEquals(
            LeanAxiomCheck.Unreadable,
            policy.check("Lean completed without an axiom report", emptySet()),
        )
    }

    @Test
    fun usesTheLastAxiomReport() {
        assertEquals(
            LeanAxiomCheck.Forbidden(
                axioms = setOf("Classical.choice"),
                unexpected = setOf("Classical.choice"),
            ),
            policy.check(
                "axioms: []\nBenchmark.target depends on axioms: [Classical.choice]",
                emptySet(),
            ),
        )
    }
}
