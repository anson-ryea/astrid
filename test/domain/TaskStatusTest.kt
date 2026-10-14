package domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TaskStatusTest {
    @Test
    fun parsesKnownManifestValues() {
        assertEquals(
            TaskStatus.CANDIDATE_PENDING_INDEPENDENT_REVIEW,
            TaskStatus.fromManifestValue(
                "candidate_pending_independent_review",
            ),
        )
        assertEquals(
            TaskStatus.FROZEN,
            TaskStatus.fromManifestValue("frozen"),
        )
    }

    @Test
    fun rejectsUnknownManifestValue() {
        assertFailsWith<IllegalArgumentException> {
            TaskStatus.fromManifestValue("draft")
        }
    }

    @Test
    fun rendersAsManifestValue() {
        assertEquals("frozen", TaskStatus.FROZEN.toString())
    }
}
