package domain

/** A task file together with its role and expected content digest. */
data class TaskArtefact(
    val role: ArtefactRole,
    val path: ArtefactPath,
    val sha256Digest: Sha256Digest,
)
