package run

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.dsl.builder.node
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.features.tracing.feature.Tracing
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.params.LLMParams
import ai.koog.utils.io.use
import dataset.TaskPackageVerifier
import domain.BenchmarkTask
import domain.EvaluationResult
import domain.EvaluationReport
import domain.TaskStatus
import evaluation.TaskEvaluator
import evaluation.TaskEvaluatorRegistry
import kotlin.coroutines.cancellation.CancellationException
import prompt.PromptRenderer
import protocol.TaskProtocol
import protocol.TaskProtocolRegistry
import run.tools.WorkspaceTools
import workspace.TaskWorkspaceFactory
import workspace.TaskWorkspaceSession

/** Runs one independent task sample with separately configurable tool and repair limits. */
class TaskRunEngine(
    private val protocols: TaskProtocolRegistry,
    private val evaluators: TaskEvaluatorRegistry,
    private val promptExecutor: PromptExecutor,
    val specification: BenchmarkRunSpecification,
    private val systemPromptRenderer: PromptRenderer,
    private val repairPromptRenderer: PromptRenderer,
    private val toolRegistry: ToolRegistry = ToolRegistry.EMPTY,
    private val packageVerifier: TaskPackageVerifier = TaskPackageVerifier(),
    private val workspaceFactory: TaskWorkspaceFactory? = null,
) {
    init {
        require(workspaceFactory == null || specification.workspaceToolsEnabled) {
            "workspace runs require a positive or unlimited workspace tool budget"
        }
    }

    suspend fun run(
        task: BenchmarkTask,
        sampleIndex: Int,
        saveRecord: suspend (TaskRunRecord) -> Unit = {},
    ): TaskRunRecord {
        require(sampleIndex > 0) { "sample index must be positive" }
        require(task.manifest.status == TaskStatus.FROZEN) {
            "task ${task.manifest.taskId} is not frozen"
        }

        // Preparation failures are run errors, not model samples.
        packageVerifier.verify(task)
        evaluators.findFor(task).validateTask(task)
        val session = workspaceFactory?.prepare(task)?.create()
        try { session?.validateEvaluation() } catch (failure: Throwable) {
            session?.close()
            throw failure
        }
        session.use { session ->
            val record = runPrepared(task, sampleIndex, session)
            // The orchestrator durably saves the record while the workspace still exists.
            saveRecord(record)
            return record
        }
    }

    private suspend fun runPrepared(task: BenchmarkTask, sampleIndex: Int, session: TaskWorkspaceSession?): TaskRunRecord {
        val workspace = session?.workspace
        val protocol = protocols.findFor(task)
        val evaluator = evaluators.findFor(task)
        val taskPrompt = protocol.renderPrompt(task, session?.submissionInstructions)
        val systemText = systemPromptRenderer.render(emptyMap())
        val workspaceRegistry = workspace?.let { WorkspaceTools(it).toolRegistry } ?: ToolRegistry.EMPTY
        val workspaceToolNames = workspaceRegistry.tools.map { it.name }.toSet()
        require(toolRegistry.tools.none { it.name in workspaceToolNames }) {
            "other tools must not duplicate workspace tool names"
        }
        val activeToolRegistry = workspaceRegistry + toolRegistry

        val turns = mutableListOf<TaskRunRecord.Turn>()
        val trajectory = KoogTrajectoryRecorder()

        // Koog 1.3.0 tracing is a graph feature. The sample loop runs in one node.
        val strategy = strategy<String, String>("benchmarkSample") {
            val runSample by node<String, String> { initialPrompt ->
                var message = initialPrompt
                var usedAgentIterations = 0L
                // Bound model requests inside the node independently of graph traversal.
                fun consumeIteration() {
                    specification.maxAgentIterations?.let { limit ->
                        check(usedAgentIterations < limit) { "agent iteration limit exceeded" }
                        usedAgentIterations++
                    }
                }

                var repairTurn = 0
                while (true) {
                    consumeIteration()
                    var reply = llm.writeSession {
                        appendPrompt { user(message) }
                        requestLLM()
                    }
                    var toolCalls =
                        reply.parts.filterIsInstance<MessagePart.Tool.Call>()
                    var usedToolCalls = 0L
                    var usedWorkspaceToolCalls = 0L

                    while (toolCalls.isNotEmpty() && reply.finishReason != "length") {
                        val workspaceCalls = toolCalls.count { it.tool in workspaceToolNames }
                        val otherCalls = toolCalls.size - workspaceCalls
                        // Check both budgets before executing any part of the batch.
                        specification.maxWorkspaceToolCallsPerAttempt?.let { limit ->
                            check(workspaceCalls <= limit - usedWorkspaceToolCalls) {
                                "workspace tool call limit exceeded"
                            }
                        }
                        specification.maxToolCallsPerAttempt?.let { limit ->
                            check(otherCalls <= limit - usedToolCalls) { "tool call limit exceeded" }
                        }

                        if (specification.maxWorkspaceToolCallsPerAttempt != null) usedWorkspaceToolCalls += workspaceCalls
                        if (specification.maxToolCallsPerAttempt != null) usedToolCalls += otherCalls
                        val results = toolCalls.map { environment.executeTool(it) }
                        consumeIteration()
                        reply = llm.writeSession {
                            appendPrompt {
                                user { results.forEach { toolResult(it.toMessagePart()) } }
                            }
                            requestLLM()
                        }
                        toolCalls =
                            reply.parts.filterIsInstance<MessagePart.Tool.Call>()
                    }

                    val response = reply.parts
                        .filterIsInstance<MessagePart.Text>()
                        .joinToString("\n") { it.text }

                    val (submission, report) =
                        if (reply.finishReason == "length") {
                            null to evaluator.reportUnassessed(task, EvaluationResult.InfrastructureFailure(
                                "model response was truncated at the output-token limit",
                            ))
                        } else {
                            session?.assessDetailed() ?: assess(task, protocol, evaluator, response)
                        }
                    val result = report.result

                    turns += TaskRunRecord.Turn(
                        repairTurn = repairTurn,
                        userMessage = message,
                        response = response,
                        submission = submission,
                        result = result,
                        workspaceFiles = workspace?.snapshot() ?: emptyList(),
                        evaluationEvidence = report.evidence,
                    )

                    if (report.publicDiagnostic == null || !specification.repairPolicy.shouldRepair(repairTurn, result)) break

                    message = repairPromptRenderer.render(
                        mapOf("DIAGNOSTIC" to report.publicDiagnostic),
                    )
                    repairTurn++
                }

                turns.last().response
            }
            edge(nodeStart forwardTo runSample)
            edge(runSample forwardTo nodeFinish)
        }

        val finalResult = try {
            val config = AIAgentConfig(
                prompt = prompt(
                    "benchmark-${task.manifest.taskId.value}-$sampleIndex",
                    params = LLMParams(
                        maxTokens = specification.model.maxOutputTokens?.toInt(),
                    ),
                ) {
                    system(systemText)
                },
                model = specification.model,
                // The graph visits start, sample and finish once; the sample enforces the run budget.
                maxAgentIterations = 3,
            )

            AIAgent(
                promptExecutor = promptExecutor,
                agentConfig = config,
                strategy = strategy,
                toolRegistry = activeToolRegistry,
            ) {
                install(Tracing) { addMessageProcessor(trajectory) }
            }.use { agent -> agent.run(taskPrompt) }

            turns.last().result
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            EvaluationResult.InfrastructureFailure(
                buildString {
                    append("agent execution failed: ${failure.javaClass.simpleName}")
                    failure.message?.takeIf { it.isNotBlank() }?.let { append(": $it") }
                },
            )
        }

        return TaskRunRecord(
            taskId = task.manifest.taskId,
            sampleIndex = sampleIndex,
            systemPrompt = systemText,
            taskPrompt = taskPrompt,
            turns = turns.toList(),
            finalResult = finalResult,
            trajectory = trajectory.events,
            workspace = workspace?.record(),
        )
    }

    private fun assess(
        task: BenchmarkTask,
        protocol: TaskProtocol,
        evaluator: TaskEvaluator,
        response: String,
    ): Pair<String?, EvaluationReport> {
        val submission = try {
            protocol.extractSubmission(response)
        } catch (failure: IllegalArgumentException) {
            return null to evaluator.reportUnassessed(task, EvaluationResult.Rejected(
                "invalid response: ${failure.message}",
            ))
        }

        return submission to evaluator.evaluateDetailed(task, submission)
    }
}
