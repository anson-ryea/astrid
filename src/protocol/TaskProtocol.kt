package protocol

import domain.BenchmarkTask
import domain.ProtocolId

/** Defines the model input and expected response for one task protocol. */
interface TaskProtocol {
    val id: ProtocolId

    /** Renders the public task material as a model prompt. */
    fun renderPrompt(task: BenchmarkTask, submissionInstructions: String? = null): String

    /** Extracts the proposed submission from a model response. */
    fun extractSubmission(response: String): String
}
