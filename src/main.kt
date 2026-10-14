import com.github.ajalt.clikt.command.main
import com.github.ajalt.clikt.core.subcommands
import command.EntryCommand
import command.RunCommand

suspend fun main(args: Array<String>) = EntryCommand()
    .subcommands(
        RunCommand(),
    )
    .main(args)
