package dataset

import domain.ArtefactPath
import domain.ArtefactRole
import domain.ConditionId
import domain.ContextId
import domain.EvaluationKind
import domain.EvaluationContract
import domain.EvaluationSpecification
import domain.PaperId
import domain.ProtocolId
import domain.Sha256Digest
import domain.SourceResultId
import domain.SubmissionKind
import domain.SubmissionSpecification
import domain.TaskArtefact
import domain.TaskId
import domain.TaskManifest
import domain.TaskStatus
import domain.TaskTypeId
import java.nio.file.Path
import kotlin.io.path.Path as pathOf
import kotlin.io.path.readText
import kotlin.time.Duration
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

/** Strictly reads a task manifest using the V1 JSON schema. */
class JsonTaskManifestReader(
    private val json: Json = Json,
) : TaskManifestReader {
    override fun read(manifestPath: Path): TaskManifest {
        val source = try {
            manifestPath.readText()
        } catch (cause: Exception) {
            fail(
                manifestPath,
                "manifest file could not be read",
                cause,
            )
        }

        val document = try {
            json.parseToJsonElement(source) as? JsonObject
                ?: fail(
                    manifestPath,
                    "manifest root must be a JSON object",
                )
        } catch (failure: ManifestReadException) {
            throw failure
        } catch (cause: Exception) {
            fail(
                manifestPath,
                "manifest is not valid JSON",
                cause,
            )
        }

        val schemaVersion = document.requiredInt(
            manifestPath,
            "schema_version",
        )
        if (schemaVersion != TaskManifest.SCHEMA_VERSION) {
            fail(
                manifestPath,
                "unsupported schema version: $schemaVersion",
            )
        }

        return try {
            decodeV1(manifestPath, document)
        } catch (failure: ManifestReadException) {
            throw failure
        } catch (cause: IllegalArgumentException) {
            fail(
                manifestPath,
                cause.message ?: "manifest contains an invalid value",
                cause,
            )
        }
    }

    private fun decodeV1(
        manifestPath: Path,
        document: JsonObject,
    ): TaskManifest {
        document.requireOnlyFields(manifestPath, "manifest", rootFields)

        val artefacts = document.requiredArray(manifestPath, "artefacts")
            .mapIndexed { index, element ->
                val artefact = element as? JsonObject
                    ?: fail(
                        manifestPath,
                        "field 'artefacts[$index]' must be an object",
                    )
                artefact.requireOnlyFields(
                    manifestPath,
                    "artefacts[$index]",
                    artefactFields,
                )
                TaskArtefact(
                    role = ArtefactRole(
                        artefact.requiredString(manifestPath, "role"),
                    ),
                    path = ArtefactPath(
                        pathOf(artefact.requiredString(manifestPath, "path")),
                    ),
                    sha256Digest = Sha256Digest(
                        artefact.requiredString(manifestPath, "sha256"),
                    ),
                )
            }

        val submission = document.requiredObject(manifestPath, "submission")
        submission.requireOnlyFields(
            manifestPath,
            "submission",
            submissionFields,
        )

        val evaluation = document.requiredObject(manifestPath, "evaluation")
        evaluation.requireOnlyFields(
            manifestPath,
            "evaluation",
            evaluationFields,
        )

        val timeout = try {
            Duration.parseIsoString(
                evaluation.requiredString(manifestPath, "timeout"),
            )
        } catch (cause: IllegalArgumentException) {
            fail(manifestPath, "field 'timeout' must be an ISO-8601 duration", cause)
        }

        return TaskManifest(
            schemaVersion = TaskManifest.SCHEMA_VERSION,
            taskId = TaskId(document.requiredString(manifestPath, "task_id")),
            paperId = PaperId(document.requiredString(manifestPath, "paper_id")),
            sourceResultId = SourceResultId(
                document.requiredString(manifestPath, "source_result_id"),
            ),
            taskType = TaskTypeId(document.requiredInt(manifestPath, "task_type")),
            protocol = ProtocolId(document.requiredString(manifestPath, "protocol")),
            status = TaskStatus.fromManifestValue(
                document.requiredString(manifestPath, "status"),
            ),
            condition = ConditionId(document.requiredString(manifestPath, "condition")),
            contextId = ContextId(document.requiredString(manifestPath, "context_id")),
            artefacts = artefacts,
            submission = SubmissionSpecification(
                kind = SubmissionKind(submission.requiredString(manifestPath, "kind")),
                allowLocalHelpers = submission.requiredBoolean(
                    manifestPath,
                    "allow_local_helpers",
                ),
                allowTopLevelDeclarations = submission.requiredBoolean(
                    manifestPath,
                    "allow_top_level_declarations",
                ),
            ),
            evaluation = EvaluationSpecification(
                kind = EvaluationKind(evaluation.requiredString(manifestPath, "kind")),
                timeout = timeout,
                allowedAxioms = evaluation.requiredStringSet(
                    manifestPath,
                    "allowed_axioms",
                ),
                forbiddenMechanisms = evaluation.requiredStringSet(
                    manifestPath,
                    "forbidden_mechanisms",
                ),
                forbiddenIdentifiers = evaluation.requiredStringSet(
                    manifestPath,
                    "forbidden_identifiers",
                ),
                contract = evaluation["contract"]?.let {
                    val reference = it as? JsonObject ?: fail(manifestPath, "evaluation.contract must be an object")
                    reference.requireOnlyFields(manifestPath, "evaluation.contract", setOf("path", "sha256"))
                    EvaluationContract(
                        ArtefactPath(pathOf(reference.requiredString(manifestPath, "path"))),
                        Sha256Digest(reference.requiredString(manifestPath, "sha256")),
                    )
                },
            ),
        )
    }

    private fun JsonObject.requireOnlyFields(
        manifestPath: Path,
        location: String,
        allowed: Set<String>,
    ) {
        val unsupported = keys - allowed
        if (unsupported.isNotEmpty()) {
            fail(
                manifestPath,
                "$location contains unsupported fields: " +
                        unsupported.sorted().joinToString(),
            )
        }
    }

    private fun JsonObject.requiredString(
        manifestPath: Path,
        field: String,
    ): String {
        val primitive = this[field] as? JsonPrimitive
            ?: fail(
                manifestPath,
                "required field '$field' must be a string",
            )

        if (!primitive.isString) {
            fail(
                manifestPath,
                "required field '$field' must be a string",
            )
        }

        return primitive.content
    }

    private fun JsonObject.requiredInt(
        manifestPath: Path,
        field: String,
    ): Int {
        val primitive = this[field] as? JsonPrimitive
            ?: fail(
                manifestPath,
                "required field '$field' must be an integer",
            )

        if (primitive.isString) {
            fail(
                manifestPath,
                "required field '$field' must be an integer",
            )
        }

        return primitive.intOrNull
            ?: fail(
                manifestPath,
                "required field '$field' must be an integer",
            )
    }

    private fun JsonObject.requiredBoolean(
        manifestPath: Path,
        field: String,
    ): Boolean {
        val primitive = this[field] as? JsonPrimitive
            ?: fail(
                manifestPath,
                "required field '$field' must be a boolean",
            )

        if (primitive.isString) {
            fail(
                manifestPath,
                "required field '$field' must be a boolean",
            )
        }

        return primitive.booleanOrNull
            ?: fail(
                manifestPath,
                "required field '$field' must be a boolean",
            )
    }

    private fun JsonObject.requiredObject(
        manifestPath: Path,
        field: String,
    ): JsonObject {
        return this[field] as? JsonObject
            ?: fail(
                manifestPath,
                "required field '$field' must be an object",
            )
    }

    private fun JsonObject.requiredArray(
        manifestPath: Path,
        field: String,
    ): JsonArray {
        return this[field] as? JsonArray
            ?: fail(
                manifestPath,
                "required field '$field' must be an array",
            )
    }

    private fun JsonObject.requiredStringSet(
        manifestPath: Path,
        field: String,
    ): Set<String> {
        val values = requiredArray(manifestPath, field).mapIndexed { index, element ->
            val primitive = element as? JsonPrimitive
            if (primitive == null || !primitive.isString) {
                fail(
                    manifestPath,
                    "field '$field[$index]' must be a string",
                )
            }
            primitive.content
        }

        if (values.toSet().size != values.size) {
            fail(manifestPath, "field '$field' must not contain duplicates")
        }

        return values.toSet()
    }

    private fun fail(
        manifestPath: Path,
        reason: String,
        cause: Throwable? = null,
    ): Nothing {
        throw ManifestReadException(
            manifestPath = manifestPath,
            reason = reason,
            cause = cause,
        )
    }

    companion object {
        private val rootFields = setOf(
            "schema_version",
            "task_id",
            "paper_id",
            "source_result_id",
            "task_type",
            "protocol",
            "status",
            "condition",
            "context_id",
            "artefacts",
            "submission",
            "evaluation",
        )
        private val artefactFields = setOf("role", "path", "sha256")
        private val submissionFields = setOf(
            "kind",
            "allow_local_helpers",
            "allow_top_level_declarations",
        )
        private val evaluationFields = setOf(
            "kind",
            "timeout",
            "allowed_axioms",
            "forbidden_mechanisms",
            "forbidden_identifiers",
            "contract",
        )
    }
}
