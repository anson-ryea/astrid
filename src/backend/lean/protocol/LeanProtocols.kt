package backend.lean.protocol

import java.nio.file.Path
import prompt.PromptRenderer
import protocol.TaskProtocolRegistry

/** Registers Lean protocols with separately owned backend and dataset templates. */
object LeanProtocols {
    fun create(promptDirectory: Path, datasetPromptDirectory: Path): TaskProtocolRegistry {
        fun backend(name: String) = PromptRenderer(promptDirectory.resolve(name))
        fun dataset(name: String) = PromptRenderer(datasetPromptDirectory.resolve(name))
        return TaskProtocolRegistry(
            listOf(
                LeanProofProtocol(backend("lean-proof.md"), dataset("lean-proof.md"), backend("proof-response.md")),
                LeanDefinitionProtocol(backend("lean-definition.md"), dataset("lean-definition.md")),
                LeanDeclarationProtocol(backend("lean-declaration.md"), dataset("lean-declaration.md")),
                LeanStatementProtocol(backend("lean-statement.md"), dataset("lean-statement.md")),
            ),
        )
    }
}
