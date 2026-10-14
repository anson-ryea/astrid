package protocol

import domain.BenchmarkTask
import domain.ProtocolId

/** Selects a task protocol by the identifier declared in its manifest. */
class TaskProtocolRegistry(protocols: Collection<TaskProtocol>) {
    private val protocolsById: Map<ProtocolId, TaskProtocol>

    init {
        val duplicateIds = protocols
            .groupingBy(TaskProtocol::id)
            .eachCount()
            .filterValues { count -> count > 1 }
            .keys

        require(duplicateIds.isEmpty()) {
            "duplicate task protocols: ${duplicateIds.sortedBy(ProtocolId::value).joinToString()}"
        }

        protocolsById = protocols.associateBy(TaskProtocol::id)
    }

    /** Finds the protocol selected by [task], failing if it is not registered. */
    fun findFor(task: BenchmarkTask): TaskProtocol {
        val id = task.manifest.protocol
        return protocolsById[id]
            ?: throw IllegalArgumentException("unsupported task protocol: $id")
    }
}
