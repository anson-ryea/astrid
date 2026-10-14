package protocol

import dataset.TaskMaterialReader
import domain.ArtefactRole
import domain.BenchmarkTask
import domain.ProtocolId
import domain.SubmissionKind
import prompt.PromptRenderer

/** Requests a prose proof from a paper statement and optional paper evidence. */
class InformalProofProtocol(
    private val promptRenderer: PromptRenderer,
) : TaskProtocol {
    override val id = ProtocolId("informal_proof")

    override fun renderPrompt(task: BenchmarkTask, submissionInstructions: String?): String {
        require(submissionInstructions == null) { "workspace submissions are not supported for $id" }
        requireTaskShape(task, id, SubmissionKind("informal_proof"))
        val reader = TaskMaterialReader(task)
        return promptRenderer.render(
            mapOf(
                "PAPER_STATEMENT" to reader.readRequired(
                    ArtefactRole("paper_statement"),
                ),
                "EVIDENCE_SECTION" to evidenceSection(
                    task = task,
                    reader = reader,
                    allowedRoles = setOf(paperEvidenceRole),
                    required = false,
                ),
            ),
        )
    }

    override fun extractSubmission(response: String): String {
        val proof = response.trim()
        require(proof.isNotEmpty()) { "response must contain an informal proof" }
        return proof
    }
}
