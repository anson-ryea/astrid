package backend.lean.validation

import domain.ArtefactRole
import domain.TaskId

data class LeanArtefactComparison(val taskId: TaskId, val roles: Set<ArtefactRole>)
