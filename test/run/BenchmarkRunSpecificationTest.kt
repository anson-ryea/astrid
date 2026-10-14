package run

import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BenchmarkRunSpecificationTest {
    private val model = LLModel(LLMProvider.OpenRouter, "test-model")

    @Test
    fun acceptsPositiveLimitsWithoutTools() {
        BenchmarkRunSpecification(
            model = model,
            samplesPerTask = 2,
            repairPolicy = RepairPolicy(1),
            maxToolCallsPerAttempt = 0,
            maxAgentIterations = 4,
        )
    }

    @Test
    fun rejectsInvalidLimits() {
        for (samples in listOf(0, -1)) {
            assertFailsWith<IllegalArgumentException> {
                specification(samplesPerTask = samples)
            }
        }
        assertFailsWith<IllegalArgumentException> {
            specification(maxToolCallsPerAttempt = -1)
        }
        assertFailsWith<IllegalArgumentException> {
            specification(maxAgentIterations = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            specification().copy(maxWorkspaceToolCallsPerAttempt = -1)
        }
        for (limit in listOf(0, -1)) {
            assertFailsWith<IllegalArgumentException> {
                specification().copy(maxConcurrentTasks = limit)
            }
        }
    }

    @Test
    fun rejectsToolCallsForModelWithoutToolCapability() {
        assertFailsWith<IllegalArgumentException> {
            specification(maxToolCallsPerAttempt = 1)
        }
        assertFailsWith<IllegalArgumentException> {
            specification().copy(maxWorkspaceToolCallsPerAttempt = null)
        }
        assertFailsWith<IllegalArgumentException> {
            specification().copy(maxToolCallsPerAttempt = null)
        }
    }

    @Test
    fun acceptsUnlimitedLimitsForAToolCapableModel() {
        val specification = specification().copy(
            model = model.copy(capabilities = listOf(ai.koog.prompt.llm.LLMCapability.Tools)),
            repairPolicy = RepairPolicy(null),
            maxToolCallsPerAttempt = null,
            maxWorkspaceToolCallsPerAttempt = null,
            maxAgentIterations = null,
        )
        assertTrue(specification.workspaceToolsEnabled)
    }

    private fun specification(
        samplesPerTask: Int = 1,
        maxToolCallsPerAttempt: Int = 0,
        maxAgentIterations: Int = 1,
    ) = BenchmarkRunSpecification(
        model = model,
        samplesPerTask = samplesPerTask,
        repairPolicy = RepairPolicy(0),
        maxToolCallsPerAttempt = maxToolCallsPerAttempt,
        maxAgentIterations = maxAgentIterations,
    )
}
