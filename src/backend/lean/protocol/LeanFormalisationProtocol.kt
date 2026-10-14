package backend.lean.protocol

import dataset.TaskMaterialReader
import domain.BenchmarkTask
import domain.ProtocolId
import domain.SubmissionKind
import prompt.PromptRenderer
import protocol.TaskProtocol
import protocol.paperEvidenceRole
import protocol.requireTaskShape

/** Shared prompt handling for Lean Type 1 formalisations. */
abstract class LeanFormalisationProtocol(
    final override val id: ProtocolId,
    private val submissionKind: SubmissionKind,
    private val marker: String,
    private val promptRenderer: PromptRenderer,
    private val taskInstructions: PromptRenderer,
    private val submissionExtractor: LeanSubmissionExtractor,
) : TaskProtocol {
    final override fun renderPrompt(task: BenchmarkTask, submissionInstructions: String?): String {
        requireTaskShape(task, id, submissionKind)
        val reader = TaskMaterialReader(task)
        return promptRenderer.render(
            leanPromptValues(
                task = task,
                reader = reader,
                marker = marker,
                allowedEvidenceRoles = setOf(paperEvidenceRole),
                evidenceRequired = true,
            ) + mapOf("TASK_INSTRUCTIONS" to taskInstructions.render(emptyMap()),
                "SUBMISSION_INSTRUCTIONS" to (submissionInstructions ?: when (id.value) {
                    "lean_definition" -> "Return only the Lean term that replaces $marker, without a Markdown fence or explanation."
                    "lean_statement" -> "Return only the proposition expression that replaces $marker, without a proof, Markdown fence or explanation."
                    else -> "Return only the Lean declaration block that replaces $marker, without a Markdown fence or explanation."
                })),
        )
    }

    final override fun extractSubmission(response: String): String {
        return submissionExtractor.extractLeanCode(response)
    }
}
