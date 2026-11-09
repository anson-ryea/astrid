package run.serialization

import domain.TaskId
import kotlinx.serialization.json.*
import run.AgentTrajectoryEvent
import run.BenchmarkRunSpecification
import run.RunTaskMetadata
import run.TaskRunRecord
import workspace.WorkspaceFile

/** Encoding only: directory ownership, atomic publication and lifecycle belong to FileRunStore. */
internal object RunRecordJson {
    private val json = Json { prettyPrint = true }

    fun run(
        specification: BenchmarkRunSpecification,
        taskIds: List<TaskId>,
        status: String,
        taskMetadata: Map<TaskId, RunTaskMetadata>,
        failureReason: String? = null,
    ): JsonObject = buildJsonObject {
        put("schema_version", JsonPrimitive(1))
        put("status", JsonPrimitive(status))
        failureReason?.let { put("failure_reason", JsonPrimitive(it)) }
        put("model", json.encodeToJsonElement(specification.model))
        put("generation", buildJsonObject {
            put("temperature", JsonPrimitive(specification.generation.temperature))
            put("top_p", JsonPrimitive(specification.generation.topP))
            put("top_k", JsonPrimitive(specification.generation.topK))
            put("seed", JsonPrimitive(specification.generation.seed))
            put("seed_policy", JsonPrimitive("base_plus_sample_index_minus_one_mod_2147483648"))
        })
        put("limits", buildJsonObject {
            put("sample_timeout", specification.limits.timeout?.let { JsonPrimitive(it.toIsoString()) } ?: JsonNull)
            put("max_total_tokens_per_sample", JsonPrimitive(specification.limits.maxTotalTokens))
        })
        put("pricing", specification.pricing?.let { pricing ->
            buildJsonObject {
                put("input_usd_per_million", JsonPrimitive(pricing.inputUsdPerMillion))
                put("output_usd_per_million", JsonPrimitive(pricing.outputUsdPerMillion))
            }
        } ?: JsonNull)
        put("selection", buildJsonObject {
            put("task_ids", JsonArray(specification.selection.taskIds.map { it.value }.sorted().map(::JsonPrimitive)))
            put("paper_ids", JsonArray(specification.selection.paperIds.map { it.value }.sorted().map(::JsonPrimitive)))
            put(
                "task_types",
                JsonArray(specification.selection.taskTypes.map { it.value }.sorted().map(::JsonPrimitive))
            )
            put(
                "conditions",
                JsonArray(specification.selection.conditions.map { it.value }.sorted().map(::JsonPrimitive))
            )
        })
        put("samples_per_task", JsonPrimitive(specification.samplesPerTask))
        put(
            "max_repair_turns",
            JsonPrimitive(specification.repairPolicy.maxRepairTurns),
        )
        put(
            "max_tool_calls_per_attempt",
            JsonPrimitive(specification.maxToolCallsPerAttempt),
        )
        put(
            "max_workspace_tool_calls_per_attempt",
            JsonPrimitive(specification.maxWorkspaceToolCallsPerAttempt),
        )
        put(
            "max_agent_iterations",
            JsonPrimitive(specification.maxAgentIterations),
        )
        put(
            "max_concurrent_tasks",
            JsonPrimitive(specification.maxConcurrentTasks),
        )
        put(
            "task_ids",
            JsonArray(taskIds.map { JsonPrimitive(it.value) }),
        )
        put("task_metadata", buildJsonObject {
            taskMetadata.toSortedMap(compareBy { it.value }).forEach { (taskId, metadata) ->
                put(taskId.value, buildJsonObject {
                    put("paper_id", JsonPrimitive(metadata.paperId))
                    put("task_type", JsonPrimitive(metadata.taskType))
                    put("condition", JsonPrimitive(metadata.condition))
                    put("source_result_id", JsonPrimitive(metadata.sourceResultId))
                })
            }
        })
    }

    fun sample(record: TaskRunRecord): JsonObject =
        buildJsonObject {
            put("schema_version", JsonPrimitive(6))
            put("task_id", JsonPrimitive(record.taskId.value))
            put("sample_index", JsonPrimitive(record.sampleIndex))
            put("system_prompt", JsonPrimitive(record.systemPrompt))
            put("task_prompt", JsonPrimitive(record.taskPrompt))
            put("turns", JsonArray(record.turns.map(::turnJson)))
            put("outcome", SampleOutcomeJson.encode(record.outcome))
            put("resources", record.resources?.let(SampleResourcesJson::encode) ?: JsonNull)
            put("trajectory_file", JsonPrimitive("trajectory.jsonl"))
            record.workspace?.let { workspace ->
                put("workspace", buildJsonObject {
                    put("context_id", JsonPrimitive(workspace.contextId))
                    put("closure_sha256", JsonPrimitive(workspace.closureSha256))
                    put("output_directory", JsonPrimitive("output"))
                    put("inputs", JsonArray(workspace.inputs.map(::workspaceFileJson)))
                    put("final_files", JsonArray(workspace.finalFiles.map(::workspaceFileJson)))
                })
            }
        }

    private fun workspaceFileJson(file: WorkspaceFile): JsonObject = buildJsonObject {
        put("path", JsonPrimitive(file.path))
        put("sha256", JsonPrimitive(file.sha256))
        put("content", JsonPrimitive(file.content))
    }

    fun trajectoryEvent(record: TaskRunRecord, event: AgentTrajectoryEvent): JsonObject =
        buildJsonObject {
            put("schema_version", JsonPrimitive(1))
            put("task_id", JsonPrimitive(record.taskId.value))
            put("sample_index", JsonPrimitive(record.sampleIndex))
            put("kind", JsonPrimitive(event.kind))
            put("timestamp_epoch_ms", JsonPrimitive(event.timestampEpochMillis))
            put("details", event.details)
        }

    private fun turnJson(turn: TaskRunRecord.Turn): JsonObject =
        buildJsonObject {
            put("repair_turn", JsonPrimitive(turn.repairTurn))
            put("user_message", JsonPrimitive(turn.userMessage))
            put("response", JsonPrimitive(turn.response))
            put(
                "submission",
                turn.submission?.let { JsonPrimitive(it) } ?: JsonNull,
            )
            put("result", EvaluationResultJson.encode(turn.result))
            turn.evaluationEvidence?.let { evidence ->
                put("evaluation_evidence", buildJsonObject {
                    put("contract_sha256", JsonPrimitive(evidence.contractSha256))
                    put(
                        "file_digests",
                        buildJsonObject {
                            evidence.fileDigests.forEach { (name, digest) ->
                                put(
                                    name,
                                    JsonPrimitive(digest)
                                )
                            }
                        })
                    put("elaboration", JsonPrimitive(evidence.elaboration))
                    put(
                        "candidate_axioms",
                        buildJsonObject {
                            evidence.candidateAxioms.forEach { (name, axioms) ->
                                put(
                                    name,
                                    JsonArray(axioms.sorted().map(::JsonPrimitive))
                                )
                            }
                        })
                    fun checks(values: List<domain.CheckEvidence>) = JsonArray(values.map { check ->
                        buildJsonObject {
                            put("name", JsonPrimitive(check.name))
                            put("certificate", JsonPrimitive(check.certificate))
                            put("refutation", JsonPrimitive(check.refutation))
                        }
                    })
                    put("obligations", checks(evidence.obligations))
                    put("compatibility", checks(evidence.compatibility))
                })
            }
            if (turn.workspaceFiles.isNotEmpty()) {
                put("workspace_files", JsonArray(turn.workspaceFiles.map(::workspaceFileJson)))
            }
        }

}
