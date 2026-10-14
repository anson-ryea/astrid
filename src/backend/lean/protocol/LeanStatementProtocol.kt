package backend.lean.protocol

import domain.ProtocolId
import domain.SubmissionKind
import prompt.PromptRenderer

/** Requests a proposition within the fixed public parameter and assumption interface. */
class LeanStatementProtocol(
    promptRenderer: PromptRenderer,
    taskInstructions: PromptRenderer,
    submissionExtractor: LeanSubmissionExtractor = LeanSubmissionExtractor(),
) : LeanFormalisationProtocol(
    id = ProtocolId("lean_statement"),
    submissionKind = SubmissionKind("lean_statement"),
    marker = "__BENCHMARK_STATEMENT__",
    promptRenderer = promptRenderer,
    taskInstructions = taskInstructions,
    submissionExtractor = submissionExtractor,
)
