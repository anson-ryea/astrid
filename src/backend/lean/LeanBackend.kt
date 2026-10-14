package backend.lean

import backend.FormalisationBackend
import backend.lean.evaluation.LeanLakeProcess
import backend.lean.evaluation.LeanProofEvaluator
import backend.lean.evaluation.LeanSemanticEvaluator
import backend.lean.evaluation.LeanSemanticContractReader
import backend.lean.evaluation.LeanSemanticSourceBuilder
import backend.lean.evaluation.IsolatedLeanSemanticEvaluator
import backend.lean.protocol.LeanProtocols
import backend.lean.workspace.LeanTaskWorkspaceFactory
import evaluation.TaskEvaluatorRegistry
import java.nio.file.Path

/** Wires Lean services without imposing a dataset or model provider on the runner. */
class LeanBackend(environmentRoot: Path, datasetRoot: Path, promptRoot: Path,
    evaluationRoot: Path = environmentRoot.resolve("private/evaluation")) : FormalisationBackend {
    override val id = "lean"
    override val protocols = LeanProtocols.create(promptRoot.resolve("lean/v1"), datasetRoot.resolve("prompts/v1"))
    private val sources = LeanSemanticSourceBuilder(harnessPath = promptRoot.parent.resolve("src/backend/lean/evaluation/SemanticHarness.lean"))
    override val workspaceFactory = LeanTaskWorkspaceFactory(environmentRoot, datasetRoot, promptRoot = promptRoot,
        evaluationRoot = evaluationRoot, semanticSources = sources)
    private val semantic = LeanSemanticEvaluator(LeanLakeProcess(environmentRoot, inheritLeanPath = false),
        LeanSemanticContractReader(evaluationRoot), sources, verifyContext = { workspaceFactory.prepare(it) })
    override val evaluators = TaskEvaluatorRegistry(listOf(LeanProofEvaluator(LeanLakeProcess(environmentRoot)),
        IsolatedLeanSemanticEvaluator(semantic, workspaceFactory)))
}
