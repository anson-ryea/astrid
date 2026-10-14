import com.github.ajalt.clikt.command.main
import com.github.ajalt.clikt.core.subcommands
import command.EntryCommand
import command.RunCommand
import command.ValidateLeanCommand

suspend fun main(args: Array<String>) = EntryCommand()
    .subcommands(
        RunCommand(),
        ValidateLeanCommand(),
    )
    .main(args)
