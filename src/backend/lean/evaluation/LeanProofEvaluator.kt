package backend.lean.evaluation

import dataset.TaskPackageVerifier
import domain.ArtefactRole
import domain.BenchmarkTask
import domain.EvaluationKind
import domain.EvaluationResult
import domain.ProtocolId
import evaluation.TaskEvaluator
import kotlin.io.path.readText

class LeanProofEvaluator(
    private val leanProcess: LeanProcess,
    private val packageVerifier: TaskPackageVerifier = TaskPackageVerifier(),
    private val submissionPolicy: LeanSubmissionPolicy = LeanSubmissionPolicy(),
    private val sourceBuilder: LeanProofSourceBuilder = LeanProofSourceBuilder(),
    private val axiomPolicy: LeanAxiomPolicy = LeanAxiomPolicy(),
): TaskEvaluator {
    override val kind: EvaluationKind
        get() = EvaluationKind("lean_kernel")

    override fun validateTask(task: BenchmarkTask) {
        super.validateTask(task)
        require(task.manifest.protocol == leanProofProtocol) { "task ${task.manifest.taskId} is not a Lean proof task" }
        submissionPolicy.validateSpecification(task.manifest.submission, task.manifest.evaluation)?.let {
            throw IllegalArgumentException(it.reason)
        }
    }

    override fun evaluate(
        task: BenchmarkTask,
        submission: String
    ): EvaluationResult {
        if (task.manifest.evaluation.kind != kind ||
            task.manifest.protocol != leanProofProtocol
        ) {
            return EvaluationResult.InfrastructureFailure(
                "task ${task.manifest.taskId} is not a Lean proof task",
            )
        }

        val material = try {
            packageVerifier.verify(task)
            readRequired(task, contextRole) to readRequired(task, targetRole)
        } catch (failure: Exception) {
            return EvaluationResult.InfrastructureFailure(
                "could not prepare task ${task.manifest.taskId}: ${failure.message}",
            )
        }

        submissionPolicy.checkProofBody(
            submission,
            task.manifest.submission,
            task.manifest.evaluation,
        )?.let { return it }

        val source = try {
            sourceBuilder.build(material.first, material.second, submission)
        } catch (failure: IllegalArgumentException) {
            return EvaluationResult.InfrastructureFailure(
                "invalid Lean target template: ${failure.message}",
            )
        }

        return when (
            val run = leanProcess.run(
                source,
                task.manifest.evaluation.timeout,
            )
        ) {
            is LeanProcessResult.Completed -> {
                if (run.exitCode != 0) {
                    EvaluationResult.Rejected(
                        "Lean rejected the submission:\n${run.output}",
                    )
                } else {
                    when (
                        val axioms = axiomPolicy.check(
                            run.output,
                            task.manifest.evaluation.allowedAxioms,
                        )
                    ) {
                        is LeanAxiomCheck.Allowed -> EvaluationResult.Accepted

                        is LeanAxiomCheck.Forbidden -> EvaluationResult.Rejected(
                            "target uses forbidden axioms: " +
                                    axioms.unexpected.sorted().joinToString(),
                        )

                        LeanAxiomCheck.Placeholder -> EvaluationResult.Rejected(
                            "Lean reported a placeholder or unresolved metavariable",
                        )

                        LeanAxiomCheck.Unreadable ->
                            EvaluationResult.InfrastructureFailure(
                                "could not read the target's axiom report",
                            )
                    }
                }
            }

            LeanProcessResult.TimedOut ->
                EvaluationResult.InfrastructureFailure("Lean timed out")

            is LeanProcessResult.InfrastructureFailure ->
                EvaluationResult.InfrastructureFailure(run.reason)
        }
    }

    private fun readRequired(task: BenchmarkTask, role: ArtefactRole): String {
        val matches = task.manifest.artefacts.filter { it.role == role }
        check(matches.size == 1) {
            "task ${task.manifest.taskId} must declare exactly one $role artefact"
        }
        return task.pathFor(matches.single()).readText()
    }

    private companion object {
        val leanProofProtocol = ProtocolId("lean_proof")
        val contextRole = ArtefactRole("lean_context")
        val targetRole = ArtefactRole("lean_target")
    }

}
