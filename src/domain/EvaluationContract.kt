package domain

/** A public digest and safe relative locator for runner-only evaluation material. */
data class EvaluationContract(val path: ArtefactPath, val sha256: Sha256Digest)
