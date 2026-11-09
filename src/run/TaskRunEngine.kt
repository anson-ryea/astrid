package run

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.dsl.builder.node
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.features.opentelemetry.feature.OpenTelemetry
import ai.koog.agents.features.tracing.feature.Tracing
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.LLMClientException
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.message.MessagePart
import ai.koog.utils.io.use
import domain.BenchmarkTask
import domain.EvaluationReport
import domain.EvaluationResult
import domain.TaskStatus
import evaluation.TaskEvaluator
import evaluation.TaskEvaluatorRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import prompt.PromptRenderer
import protocol.TaskProtocol
import protocol.TaskProtocolRegistry
import run.tools.WorkspaceTools
import workspace.TaskWorkspaceFactory
import workspace.TaskWorkspaceSession
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.TimeSource

/** Runs one independent task sample with separately configurable tool and repair limits. */
class TaskRunEngine(
    private val protocols: TaskProtocolRegistry,
    private val evaluators: TaskEvaluatorRegistry,
    private val promptExecutor: PromptExecutor,
    val specification: BenchmarkRunSpecification,
    private val systemPromptRenderer: PromptRenderer,
    private val repairPromptRenderer: PromptRenderer,
    private val toolRegistry: ToolRegistry = ToolRegistry.EMPTY,
    private val workspaceFactory: TaskWorkspaceFactory? = null,
    private val langfuse: LangfuseTracing? = null,
) {
    init {
        require(workspaceFactory == null || specification.workspaceToolsEnabled) {
            "workspace runs require a positive or unlimited workspace tool budget"
        }
    }

    /**
     * Reports sample phases through [onEvent]; lifecycle events belong to [BenchmarkRunner].
     * Event delivery suspends execution and delivery failures propagate without becoming sample outcomes.
     */
    suspend fun run(
        task: BenchmarkTask,
        sampleIndex: Int,
        onEvent: suspend (RunEvent) -> Unit = {},
        saveRecord: suspend (TaskRunRecord) -> Unit = {},
    ): TaskRunRecord {
        require(sampleIndex > 0) { "sample index must be positive" }
        require(task.manifest.status == TaskStatus.FROZEN) {
            "task ${task.manifest.taskId} is not frozen"
        }

        val resources =
            SampleResourceRecorder(specification.pricing, specification.generation.effectiveSeed(sampleIndex))
        val deadline = SampleDeadline(specification.limits.timeout) { resources.elapsedMs }
        onEvent(RunEvent.PhaseChanged(task.manifest.taskId, sampleIndex, SamplePhase.Preparing))
        var session: TaskWorkspaceSession? = null
        var primaryFailure: Throwable? = null
        try {
            suspend fun prepare(): Boolean {
                runInterruptible(Dispatchers.IO) { session = workspaceFactory?.prepare(task)?.create() }
                return true
            }

            val prepared = deadline.runOrNull(::prepare) == true
            val record = if (prepared) runPrepared(task, sampleIndex, session, onEvent, resources, deadline)
            else TaskRunRecord(
                task.manifest.taskId, sampleIndex, "", "", emptyList(),
                SampleOutcome.BudgetExhausted(SampleBudget.SAMPLE_TIME, "sample deadline exhausted during preparation"),
                workspace = session?.workspace?.record(), resources = resources.snapshot()
            )
            // Save before removing the workspace; persistence and cleanup are outside the experimental deadline.
            saveRecord(record)
            return record
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            try {
                session?.close()
            } catch (cleanup: Throwable) {
                if (primaryFailure == null) throw cleanup else primaryFailure.addSuppressed(cleanup)
            }
        }
    }

    private suspend fun runPrepared(
        task: BenchmarkTask,
        sampleIndex: Int,
        session: TaskWorkspaceSession?,
        onEvent: suspend (RunEvent) -> Unit,
        resources: SampleResourceRecorder,
        deadline: SampleDeadline,
    ): TaskRunRecord {
        // Keep the original failure even if Koog wraps an exception thrown inside its strategy.
        var eventDeliveryFailure: Exception? = null
        suspend fun phaseChanged(phase: SamplePhase) {
            try {
                onEvent(RunEvent.PhaseChanged(task.manifest.taskId, sampleIndex, phase))
            } catch (failure: Exception) {
                eventDeliveryFailure = failure
                throw failure
            }
        }

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
        var outcome: SampleOutcome? = null

        // Capture explicit limits before Koog can wrap their exceptions.
        fun exhaust(limit: SampleBudget, reason: String): Nothing {
            outcome = SampleOutcome.BudgetExhausted(limit, reason)
            throw IllegalStateException(reason)
        }

        fun enforceBudget(available: Boolean, limit: SampleBudget, reason: String) {
            if (!available) exhaust(limit, reason)
        }

        val measuredExecutor = MeasuredPromptExecutor(promptExecutor, specification, sampleIndex, resources, ::exhaust)

        fun checkResponseBudget() {
            specification.limits.maxTotalTokens?.let { limit ->
                resources.requireTotalUsage()
                enforceBudget(
                    resources.total <= limit, SampleBudget.TOTAL_TOKENS,
                    "provider-reported usage exceeded the sample total-token budget"
                )
            }
        }

        // Koog 1.3.0 tracing is a graph feature. The sample loop runs in one node.
        val strategy = strategy<String, String>("benchmarkSample") {
            val runSample by node<String, String> { initialPrompt ->
                var message = initialPrompt
                var usedAgentIterations = 0L

                // Bound model requests inside the node independently of graph traversal.
                fun consumeIteration() {
                    specification.maxAgentIterations?.let { limit ->
                        enforceBudget(
                            usedAgentIterations < limit, SampleBudget.AGENT_ITERATIONS,
                            "agent iteration limit exceeded"
                        )
                        usedAgentIterations++
                    }
                }

                var repairTurn = 0
                while (true) {
                    consumeIteration()
                    phaseChanged(SamplePhase.Generating)
                    var reply = llm.writeSession {
                        appendPrompt { user(message) }
                        requestLLM()
                    }
                    checkResponseBudget()
                    var toolCalls =
                        reply.parts.filterIsInstance<MessagePart.Tool.Call>()
                    var usedToolCalls = 0L
                    var usedWorkspaceToolCalls = 0L

                    while (toolCalls.isNotEmpty() && reply.finishReason != "length") {
                        val workspaceCalls = toolCalls.count { it.tool in workspaceToolNames }
                        val otherCalls = toolCalls.size - workspaceCalls
                        // Check both budgets before executing any part of the batch.
                        specification.maxWorkspaceToolCallsPerAttempt?.let { limit ->
                            enforceBudget(
                                workspaceCalls <= limit - usedWorkspaceToolCalls,
                                SampleBudget.WORKSPACE_TOOL_CALLS, "workspace tool call limit exceeded"
                            )
                        }
                        specification.maxToolCallsPerAttempt?.let { limit ->
                            enforceBudget(
                                otherCalls <= limit - usedToolCalls,
                                SampleBudget.TOOL_CALLS, "tool call limit exceeded"
                            )
                        }

                        if (specification.maxWorkspaceToolCallsPerAttempt != null) usedWorkspaceToolCalls += workspaceCalls
                        if (specification.maxToolCallsPerAttempt != null) usedToolCalls += otherCalls
                        val results = toolCalls.map { call ->
                            resources.toolCalls++
                            if (call.tool in workspaceToolNames) resources.workspaceToolCalls++
                            val started = TimeSource.Monotonic.markNow()
                            try {
                                environment.executeTool(call)
                            } finally {
                                resources.toolMs += started.elapsedNow().inWholeMilliseconds
                            }
                        }
                        consumeIteration()
                        reply = llm.writeSession {
                            appendPrompt {
                                user { results.forEach { toolResult(it.toMessagePart()) } }
                            }
                            requestLLM()
                        }
                        checkResponseBudget()
                        toolCalls =
                            reply.parts.filterIsInstance<MessagePart.Tool.Call>()
                    }

                    val response = reply.parts
                        .filterIsInstance<MessagePart.Text>()
                        .joinToString("\n") { it.text }

                    val (submission, report) =
                        if (reply.finishReason == "length") {
                            outcome = SampleOutcome.BudgetExhausted(
                                SampleBudget.OUTPUT_TOKENS,
                                "model response was truncated at the output-token limit"
                            )
                            null to null
                        } else {
                            phaseChanged(SamplePhase.Evaluating)
                            val started = TimeSource.Monotonic.markNow()
                            try {
                                runInterruptible(Dispatchers.IO) {
                                    session?.assessDetailed() ?: assess(task, protocol, evaluator, response)
                                }
                            } finally {
                                resources.evaluationMs += started.elapsedNow().inWholeMilliseconds
                            }
                        }
                    val result = report?.result

                    turns += TaskRunRecord.Turn(
                        repairTurn = repairTurn,
                        userMessage = message,
                        response = response,
                        submission = submission,
                        result = result,
                        workspaceFiles = workspace?.snapshot() ?: emptyList(),
                        evaluationEvidence = report?.evidence,
                    )

                    if (report == null) break
                    outcome = SampleOutcome.Evaluated(report.result)
                    if (report.publicDiagnostic == null) break
                    if (!specification.repairPolicy.shouldRepair(repairTurn, report.result)) {
                        if (result is EvaluationResult.Rejected) outcome = SampleOutcome.RepairLimitReached(result)
                        break
                    }

                    phaseChanged(SamplePhase.Repairing)
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

        val finalOutcome = try {
            val config = AIAgentConfig(
                prompt = prompt(
                    "benchmark-${task.manifest.taskId.value}-$sampleIndex",
                    params = specification.generation.params(sampleIndex, specification.model.maxOutputTokens?.toInt()),
                ) {
                    system(systemText)
                },
                model = specification.model,
                // The graph visits start, sample and finish once; the sample enforces the run budget.
                maxAgentIterations = 3,
            )

            suspend fun execute(): SampleOutcome {
                AIAgent(
                    promptExecutor = measuredExecutor,
                    agentConfig = config,
                    strategy = strategy,
                    toolRegistry = activeToolRegistry,
                ) {
                    install(Tracing) { addMessageProcessor(trajectory) }
                    langfuse?.let { tracing ->
                        install(OpenTelemetry) { tracing.configure(this, task, sampleIndex, specification.model.id) }
                    }
                }.use { agent -> agent.run(taskPrompt) }

                return checkNotNull(outcome) { "agent completed without a sample outcome" }
            }

            val completed = deadline.runOrNull(::execute)
            completed ?: SampleOutcome.BudgetExhausted(SampleBudget.SAMPLE_TIME, "sample deadline exhausted")
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            eventDeliveryFailure?.let { throw it }
            val budgetOutcome = outcome as? SampleOutcome.BudgetExhausted
            budgetOutcome ?: run {
                val reason = buildString {
                    append("agent execution failed: ${failure.javaClass.simpleName}")
                    failure.message?.takeIf { it.isNotBlank() }?.let { append(": $it") }
                }
                if (generateSequence<Throwable>(failure) { it.cause }.any { it is LLMClientException })
                    SampleOutcome.ProviderFailure(reason)
                else SampleOutcome.ExecutionFailure(reason)
            }
        }

        return TaskRunRecord(
            taskId = task.manifest.taskId,
            sampleIndex = sampleIndex,
            systemPrompt = systemText,
            taskPrompt = taskPrompt,
            turns = turns.toList(),
            outcome = finalOutcome,
            trajectory = trajectory.events,
            workspace = workspace?.record(),
            resources = resources.snapshot(),
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
            return null to evaluator.reportUnassessed(
                task, EvaluationResult.Rejected(
                    "invalid response: ${failure.message}",
                )
            )
        }

        return submission to evaluator.evaluateDetailed(task, submission)
    }
}
