package dataset

import domain.ArtefactRole
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import protocol.enrichedEvidenceRole
import protocol.paperEvidenceRole
import protocol.withProtocolTask

class TaskMaterialReaderTest {
    @Test
    fun readsRequiredDeclaredRole() {
        withProtocolTask(
            protocol = "lean_proof",
            submissionKind = "lean_proof_body",
            artefacts = mapOf("lean_context" to "context-marker\n"),
        ) { task ->
            assertEquals(
                "context-marker",
                TaskMaterialReader(task).readRequired(ArtefactRole("lean_context")),
            )
        }
    }

    @Test
    fun rejectsMissingRequiredRole() {
        withProtocolTask(
            protocol = "lean_proof",
            submissionKind = "lean_proof_body",
            artefacts = mapOf("lean_target" to "target-marker"),
        ) { task ->
            val failure = assertFailsWith<IllegalArgumentException> {
                TaskMaterialReader(task).readRequired(ArtefactRole("lean_context"))
            }
            assertContains(failure.message.orEmpty(), "exactly one lean_context artefact")
        }
    }

    @Test
    fun readsAtMostOneEvidenceRole() {
        withProtocolTask(
            protocol = "lean_proof",
            submissionKind = "lean_proof_body",
            artefacts = mapOf("enriched_evidence" to "evidence-marker"),
        ) { task ->
            assertEquals(
                enrichedEvidenceRole to "evidence-marker",
                TaskMaterialReader(task).readAtMostOneOf(
                    setOf(paperEvidenceRole, enrichedEvidenceRole),
                ),
            )
        }
    }

    @Test
    fun rejectsMultipleEvidenceRoles() {
        withProtocolTask(
            protocol = "lean_proof",
            submissionKind = "lean_proof_body",
            artefacts = mapOf(
                "paper_evidence" to "paper-marker",
                "enriched_evidence" to "enriched-marker",
            ),
        ) { task ->
            val failure = assertFailsWith<IllegalArgumentException> {
                TaskMaterialReader(task).readAtMostOneOf(
                    setOf(paperEvidenceRole, enrichedEvidenceRole),
                )
            }
            assertContains(failure.message.orEmpty(), "multiple artefacts")
        }
    }

    @Test
    fun returnsNullWhenOptionalRoleIsAbsent() {
        withProtocolTask(
            protocol = "lean_proof",
            submissionKind = "lean_proof_body",
            artefacts = mapOf("lean_target" to "target-marker"),
        ) { task ->
            assertNull(
                TaskMaterialReader(task).readAtMostOneOf(
                    setOf(paperEvidenceRole, enrichedEvidenceRole),
                ),
            )
        }
    }
}
