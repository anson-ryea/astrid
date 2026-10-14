package backend.lean.evaluation

import dataset.HashCalculator
import domain.*
import domain.ArtefactPath
import domain.ArtefactRole
import domain.BenchmarkTask
import domain.ConditionId
import domain.ContextId
import domain.EvaluationKind
import domain.EvaluationSpecification
import domain.PaperId
import domain.ProtocolId
import domain.SourceResultId
import domain.SubmissionKind
import domain.SubmissionSpecification
import domain.TaskArtefact
import domain.TaskId
import domain.TaskManifest
import domain.TaskTypeId
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlin.time.Duration.Companion.seconds

internal fun withLeanProofTask(block: (BenchmarkTask) -> Unit) {
    val root = Files.createTempDirectory("lean-proof-test-")
    try {
        val directory = root.resolve("task_1.1_theorem_11")
        Files.createDirectory(directory)
        Files.writeString(directory.resolve("manifest.json"), "{}")

        val context = Files.writeString(
            directory.resolve("Context.lean"),
            "namespace Benchmark\n",
        )
        val target = Files.writeString(
            directory.resolve("Target.lean"),
            """
            theorem target : True :=
            __BENCHMARK_PROOF__

            #print axioms Benchmark.target
            end Benchmark
            """.trimIndent(),
        )

        val hashes = HashCalculator()
        val manifest = TaskManifest(
            schemaVersion = TaskManifest.SCHEMA_VERSION,
            taskId = TaskId("task_1.1_theorem_11"),
            paperId = PaperId("paper_1"),
            sourceResultId = SourceResultId("theorem_11"),
            taskType = TaskTypeId(2),
            protocol = ProtocolId("lean_proof"),
            status = TaskStatus.CANDIDATE_PENDING_INDEPENDENT_REVIEW,
            condition = ConditionId("paper_evidence"),
            contextId = ContextId("theorem_11"),
            artefacts = listOf(
                TaskArtefact(
                    ArtefactRole("lean_context"),
                    ArtefactPath(Path.of("Context.lean")),
                    hashes.sha256(context),
                ),
                TaskArtefact(
                    ArtefactRole("lean_target"),
                    ArtefactPath(Path.of("Target.lean")),
                    hashes.sha256(target),
                ),
            ),
            submission = SubmissionSpecification(
                kind = SubmissionKind("lean_proof_body"),
                allowLocalHelpers = true,
                allowTopLevelDeclarations = false,
            ),
            evaluation = EvaluationSpecification(
                kind = EvaluationKind("lean_kernel"),
                timeout = 180.seconds,
                allowedAxioms = setOf("propext"),
                forbiddenMechanisms = setOf("sorry"),
                forbiddenIdentifiers = emptySet(),
            ),
        )

        block(BenchmarkTask(manifest, directory))
    } finally {
        Files.walk(root).use { paths ->
            paths.sorted(Comparator.reverseOrder())
                .forEach(Files::deleteIfExists)
        }
    }
}
