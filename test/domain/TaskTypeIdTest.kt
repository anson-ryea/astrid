package domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TaskTypeIdTest {
    @Test
    fun acceptsPositiveIdentifier() {
        assertEquals(2, TaskTypeId(2).value)
    }

    @Test
    fun rejectsNonPositiveIdentifiers() {
        for (value in listOf(0, -1)) {
            assertFailsWith<IllegalArgumentException> {
                TaskTypeId(value)
            }
        }
    }

    @Test
    fun rendersAsItsNumericValue() {
        assertEquals("5", TaskTypeId(5).toString())
    }
}
