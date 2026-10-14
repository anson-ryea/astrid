package backend

/** Registers only implemented proof assistants and rejects unsupported selections. */
class BackendRegistry(backends: Collection<FormalisationBackend>) {
    private val byId: Map<String, FormalisationBackend>

    init {
        require(backends.all { it.id.matches(Regex("[a-z][a-z0-9_]*")) }) { "invalid backend identifier" }
        require(backends.map { it.id }.distinct().size == backends.size) { "duplicate formalisation backends" }
        byId = backends.associateBy { it.id }
    }

    fun find(id: String): FormalisationBackend = byId[id]
        ?: throw IllegalArgumentException("unsupported formalisation backend: $id")
}
