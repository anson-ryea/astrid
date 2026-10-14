package domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TaskIdTest {
    @Test
    fun acceptsValidTaskIdentifiers() {
        val identifiers = listOf(
            "task_1.1_theorem_11",
            "task_2.3_corollary_6",
            "task_10.12_definition_4",
        )

        for (identifier in identifiers) {
            assertEquals(identifier, TaskId(identifier).value)
        }
    }

    @Test
    fun rejectsMalformedTaskIdentifiers() {
        val identifiers = listOf(
            "",
            "task_0.1_theorem_11",
            "task_1.0_theorem_11",
            "task_1_theorem_11",
            "task_1.1_Theorem_11",
            "task_1.1_theorem-11",
            "task_1.1_../hidden",
        )

        for (identifier in identifiers) {
            assertFailsWith<IllegalArgumentException> {
                TaskId(identifier)
            }
        }
    }

    @Test
    fun rendersAsItsStringValue() {
        val identifier = "task_1.1_theorem_11"

        assertEquals(identifier, TaskId(identifier).toString())
    }
}
