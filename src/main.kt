import com.github.ajalt.clikt.command.main
import com.github.ajalt.clikt.core.subcommands
import command.*

suspend fun main(args: Array<String>) = EntryCommand()
    .subcommands(
        RunCommand(),
        ResumeCommand(),
        ValidateCommand(),
        AnalyseCommand(),
    )
    .main(args)
