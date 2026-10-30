package console

import com.github.ajalt.mordant.animation.animation
import com.github.ajalt.mordant.rendering.AnsiLevel
import com.github.ajalt.mordant.rendering.Widget
import com.github.ajalt.mordant.terminal.Terminal
import domain.EvaluationResult
import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.oshai.kotlinlogging.KotlinLoggingConfiguration
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import run.RunEvent
import run.RunPhase

enum class RunConsoleMode { Auto, Live, Plain }

/** Owns the event consumer and display for one run; no coroutines survive its completion. */
class RunConsole(
    terminal: Terminal,
    context: RunDashboardContext,
    private val startedAt: TimeMark = TimeSource.Monotonic.markNow(),
    mode: RunConsoleMode = RunConsoleMode.Auto,
) {
    private val liveRequested = when (mode) {
        RunConsoleMode.Auto -> terminal.terminalInfo.outputInteractive
        RunConsoleMode.Live -> true
        RunConsoleMode.Plain -> false
    }
    // Mordant 3.1 uses this flag for IntelliJ's Run console, where Animation
    // deliberately emits carriage returns without moving up to previous lines.
    private val canRedraw = !terminal.terminalInfo.supportsAnsiCursor
    private val live = liveRequested && canRedraw
    private val explainFallback = mode == RunConsoleMode.Live && !canRedraw
    private val output = when {
        live && terminal.terminalInfo.outputInteractive -> terminal
        else -> Terminal(
            ansiLevel = if (live) terminal.terminalInfo.ansiLevel.takeUnless { it == AnsiLevel.NONE }
                ?: AnsiLevel.TRUECOLOR else AnsiLevel.NONE,
            theme = terminal.theme,
            tabWidth = terminal.tabWidth,
            hyperlinks = live,
            interactive = live,
            terminalInterface = terminal.terminalInterface,
        )
    }
    private val dashboard = RunDashboard(context)

    /** The supplied callback accepts concurrent execution events and suspends when its queue fills. */
    suspend fun <T> run(block: suspend (onEvent: suspend (RunEvent) -> Unit) -> T): T = coroutineScope {
        // Kotlin Logging's first use prints directly to stdout, outside Mordant's redraw tracking.
        // Initialise it and its SLF4J provider before drawing; only disable the startup banner.
        KotlinLoggingConfiguration.logStartupMessage = false
        KotlinLogging.logger("astrid.console")
        val events = RunEventProcessor(this, onEventProcessed = { if (!live) printMilestone(it) })
        val animation = if (live) output.animation<Widget> { it } else null
        fun frame(status: RunDashboardStatus) = dashboard.render(
            events.state.value, startedAt.elapsedNow(), output.size.width, output.size.height, status,
        )
        var status = RunDashboardStatus.Running
        var runFailure: Throwable? = null
        var painter: Job? = null
        try {
            if (explainFallback) output.println(
                "This console cannot redraw a multi-line dashboard. Use a terminal tab for live output; showing plain progress.",
            )
            if (animation != null) {
                output.cursor.hide()
                // Paint synchronously so even preflight work starts with a visible dashboard.
                animation.update(frame(status))
                painter = launch {
                    while (isActive) {
                        delay(250.milliseconds)
                        output.updateSize()
                        animation.update(frame(RunDashboardStatus.Running))
                    }
                }
            }
            block(events::emit).also { status = RunDashboardStatus.Complete }
        } catch (failure: Throwable) {
            runFailure = failure
            status = if (failure is CancellationException) RunDashboardStatus.Cancelled else RunDashboardStatus.Failed
            throw failure
        } finally {
            // Preserve execution failures even if output or another cleanup operation also fails.
            var cleanupFailure: Throwable? = null
            suspend fun cleanup(action: suspend () -> Unit) {
                try {
                    action()
                } catch (failure: Throwable) {
                    val previous = cleanupFailure
                    if (previous == null) cleanupFailure = failure
                    else if (previous !== failure) previous.addSuppressed(failure)
                }
            }
            withContext(NonCancellable) {
                cleanup { events.closeAndJoin() }
                cleanup { painter?.cancelAndJoin() }
                cleanup {
                    if (animation != null) animation.update(frame(status)) else output.println(frame(status))
                }
                cleanup { animation?.stop() }
                if (live) cleanup { output.cursor.show() }
            }
            cleanupFailure?.let { failure ->
                if (runFailure == null) throw failure
                if (runFailure !== failure) runFailure.addSuppressed(failure)
            }
        }
    }

    private fun printMilestone(event: RunEvent) {
        when (event) {
            is RunEvent.RunPhaseChanged -> output.println(when (event.phase) {
                RunPhase.Preparing -> "Preparing tasks"
                RunPhase.ResolvingModel -> "Resolving model limits"
                RunPhase.Running -> "Running benchmark"
            })
            is RunEvent.SampleStarted -> output.println("Started ${event.taskId} · sample ${event.sampleIndex}")
            is RunEvent.SampleFinished -> {
                val result = when (event.result) {
                    EvaluationResult.Accepted -> "success"
                    is EvaluationResult.Rejected -> "failed"
                    is EvaluationResult.NeedsReview -> "needs review"
                    is EvaluationResult.InfrastructureFailure -> "error"
                }
                output.println("Finished ${event.taskId} · sample ${event.sampleIndex}: $result")
            }
            is RunEvent.PhaseChanged -> Unit
        }
    }
}
