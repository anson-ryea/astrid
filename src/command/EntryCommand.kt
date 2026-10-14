package command

import com.github.ajalt.clikt.command.SuspendingCliktCommand

class EntryCommand : SuspendingCliktCommand("astrid") {
    override suspend fun run() = Unit
}
