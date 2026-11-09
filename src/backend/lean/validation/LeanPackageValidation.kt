package backend.lean.validation

import domain.EvaluationReport
import domain.TaskId

data class LeanPackageValidation(val taskId: TaskId, val report: EvaluationReport)
