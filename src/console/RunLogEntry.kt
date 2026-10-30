package console

import com.github.ajalt.mordant.rendering.TextColors
import domain.EvaluationResult
import run.RunEvent
import run.RunPhase
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** A progress milestone shared by plain output and the interactive log page. */
internal data class RunLogEntry(val message: String, val colour: TextColors = TextColors.blue)

private val messageTimeFormat = DateTimeFormatter.ofPattern("HH:mm:ss")

internal fun RunLogEntry.renderMessage(time: LocalTime = LocalTime.now()): String =
    TextColors.gray(time.format(messageTimeFormat)) + "  " + colour("◇") + " " + consoleSingleLine(message)

internal fun RunEvent.logEntry(): RunLogEntry? = when (this) {
    is RunEvent.RunPhaseChanged -> RunLogEntry(when (phase) {
        RunPhase.Preparing -> "Preparing tasks"
        RunPhase.ResolvingModel -> "Resolving model limits"
        RunPhase.Running -> "Running benchmark"
    })
    is RunEvent.SampleStarted -> RunLogEntry("Started $taskId · sample $sampleIndex")
    is RunEvent.SampleFinished -> {
        val (label, colour) = when (result) {
            EvaluationResult.Accepted -> "success" to TextColors.green
            is EvaluationResult.Rejected -> "failed" to TextColors.red
            is EvaluationResult.NeedsReview -> "needs review" to TextColors.yellow
            is EvaluationResult.InfrastructureFailure -> "error" to TextColors.red
        }
        RunLogEntry("Finished $taskId · sample $sampleIndex: $label", colour)
    }
    is RunEvent.PhaseChanged -> null
}

internal fun consoleSingleLine(text: String): String = text.map {
    if (it.isISOControl() || it == '\u2028' || it == '\u2029') ' ' else it
}.joinToString("")
