package backend.lean.protocol

import domain.ProtocolId
import domain.SubmissionKind
import prompt.PromptRenderer

/** Requests a Lean term for a paper definition. */
class LeanDefinitionProtocol(
    promptRenderer: PromptRenderer,
    taskInstructions: PromptRenderer,
    submissionExtractor: LeanSubmissionExtractor = LeanSubmissionExtractor(),
) : LeanFormalisationProtocol(
    id = ProtocolId("lean_definition"),
    submissionKind = SubmissionKind("lean_term"),
    marker = "__BENCHMARK_TERM__",
    promptRenderer = promptRenderer,
    taskInstructions = taskInstructions,
    submissionExtractor = submissionExtractor,
)
