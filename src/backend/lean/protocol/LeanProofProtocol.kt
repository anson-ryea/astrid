package backend.lean.protocol

import dataset.TaskMaterialReader
import domain.BenchmarkTask
import domain.ProtocolId
import domain.SubmissionKind
import prompt.PromptRenderer
import protocol.TaskProtocol
import protocol.enrichedEvidenceRole
import protocol.paperEvidenceRole
import protocol.requireTaskShape

/** Prepares public Lean proof tasks and extracts their proof bodies. */
class LeanProofProtocol(
    private val promptRenderer: PromptRenderer,
    private val taskInstructions: PromptRenderer,
    private val responseInstructions: PromptRenderer,
    private val submissionExtractor: LeanSubmissionExtractor = LeanSubmissionExtractor(),
) : TaskProtocol {
    override val id = ProtocolId("lean_proof")

    override fun renderPrompt(task: BenchmarkTask, submissionInstructions: String?): String {
        requireTaskShape(task, id, SubmissionKind("lean_proof_body"))
        val reader = TaskMaterialReader(task)
        return promptRenderer.render(
            leanPromptValues(
                task = task,
                reader = reader,
                marker = MARKER,
                allowedEvidenceRoles = setOf(paperEvidenceRole, enrichedEvidenceRole),
                evidenceRequired = false,
            ) + mapOf(
                "TASK_INSTRUCTIONS" to taskInstructions.render(emptyMap()),
                "SUBMISSION_INSTRUCTIONS" to (submissionInstructions ?: responseInstructions.render(emptyMap())),
            ),
        )
    }

    override fun extractSubmission(response: String): String {
        return submissionExtractor.extractLeanProof(response)
    }

    companion object {
        internal const val MARKER = "__BENCHMARK_PROOF__"
    }
}
