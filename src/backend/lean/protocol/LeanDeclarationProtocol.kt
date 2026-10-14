package backend.lean.protocol

import domain.ProtocolId
import domain.SubmissionKind
import prompt.PromptRenderer

/** Requests Lean declarations for a paper grammar or judgement. */
class LeanDeclarationProtocol(
    promptRenderer: PromptRenderer,
    taskInstructions: PromptRenderer,
    submissionExtractor: LeanSubmissionExtractor = LeanSubmissionExtractor(),
) : LeanFormalisationProtocol(
    id = ProtocolId("lean_declaration"),
    submissionKind = SubmissionKind("lean_declaration_block"),
    marker = "__BENCHMARK_DECLARATION__",
    promptRenderer = promptRenderer,
    taskInstructions = taskInstructions,
    submissionExtractor = submissionExtractor,
)
