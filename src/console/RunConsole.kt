package console

import com.github.ajalt.mordant.animation.animation
import com.github.ajalt.mordant.input.enterRawModeOrNull
import com.github.ajalt.mordant.input.isCtrlC
import com.github.ajalt.mordant.input.RawModeScope
import com.github.ajalt.mordant.rendering.AnsiLevel
import com.github.ajalt.mordant.rendering.Widget
import com.github.ajalt.mordant.rendering.TextColors
import com.github.ajalt.mordant.terminal.Terminal
import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.oshai.kotlinlogging.KotlinLoggingConfiguration
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import run.RunEvent

enum class RunConsoleMode { Auto, Live, Plain }

/** Owns the event consumer and display for one run; no coroutines survive its completion. */
class RunConsole(
    terminal: Terminal,
    context: RunDashboardContext,
    private val startedAt: TimeMark = TimeSource.Monotonic.markNow(),
    mode: RunConsoleMode = RunConsoleMode.Auto,
) {
    private val inputInteractive = terminal.terminalInfo.inputInteractive
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

    /** Prints a status message using the same colour policy as this run's output mode. */
    internal fun printMessage(message: String, colour: TextColors = TextColors.blue) {
        output.println(RunLogEntry(message, colour).renderMessage())
    }

    /** The supplied callback accepts concurrent execution events and suspends when its queue fills. */
    suspend fun <T> run(block: suspend (onEvent: suspend (RunEvent) -> Unit) -> T): T = coroutineScope {
        // Kotlin Logging's first use prints directly to stdout, outside Mordant's redraw tracking.
        // Initialise it and its SLF4J provider before drawing; only disable the startup banner.
        KotlinLoggingConfiguration.logStartupMessage = false
        KotlinLogging.logger("astrid.console")
        val logs = RunLogs()
        val showLogs = MutableStateFlow(false)
        val events = RunEventProcessor(this, onEventProcessed = { event ->
            event.logEntry()?.let { entry ->
                if (live) logs.append(entry) else output.println(entry.renderMessage())
            }
        })
        val animation = if (live) output.animation<Widget> { it } else null
        var rawMode: RawModeScope? = null
        fun frame(status: RunDashboardStatus): Widget {
            val controls = rawMode != null && status == RunDashboardStatus.Running
            return if (showLogs.value) logs.render(output.size.width, output.size.height, controls)
            else dashboard.render(events.state.value, startedAt.elapsedNow(), output.size.width,
                output.size.height, status, controlsEnabled = controls)
        }
        var status = RunDashboardStatus.Running
        var runFailure: Throwable? = null
        var painter: Job? = null
        var keyboard: Job? = null
        // Keep the event consumer alive when quitting so saved milestones can still drain.
        val execution = async(start = CoroutineStart.LAZY) { runCatching { block(events::emit) } }
        try {
            if (explainFallback) printMessage(
                "This console cannot redraw a multi-line dashboard. Use a terminal tab for live output; showing plain progress.",
            )
            if (animation != null) {
                // Use the actual stdin capability, even when --ui=live forces output interactivity.
                rawMode = if (inputInteractive) output.enterRawModeOrNull() else null
                output.cursor.hide()
                // Paint synchronously so even preflight work starts with a visible dashboard.
                animation.update(frame(status))
                rawMode?.let { input ->
                    val runScope = this
                    keyboard = launch {
                        while (isActive) {
                            val key = withContext(Dispatchers.IO) { input.readKeyOrNull(100.milliseconds) }
                            when {
                                key == null -> delay(10.milliseconds)
                                key.isCtrlC -> runScope.cancel(CancellationException("Run interrupted"))
                                key.key == "q" && !key.ctrl && !key.alt -> execution.cancel(RunQuitException())
                                key.key == "l" && !key.ctrl && !key.alt -> showLogs.update { !it }
                            }
                        }
                    }
                }
                painter = launch {
                    while (isActive) {
                        delay(250.milliseconds)
                        output.updateSize()
                        animation.update(frame(RunDashboardStatus.Running))
                    }
                }
            }
            execution.await().getOrThrow().also { status = RunDashboardStatus.Complete }
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
                cleanup { execution.cancelAndJoin() }
                cleanup { events.closeAndJoin() }
                cleanup { keyboard?.cancelAndJoin() }
                cleanup { rawMode?.close() }
                cleanup { painter?.cancelAndJoin() }
                cleanup {
                    if (live) logs.append(RunLogEntry(
                        "${status.name.uppercase()}: ${events.state.value.completedSamples} samples saved",
                        when (status) {
                            RunDashboardStatus.Complete -> TextColors.green
                            RunDashboardStatus.Failed -> TextColors.red
                            RunDashboardStatus.Cancelled -> TextColors.green
                            RunDashboardStatus.Running -> TextColors.blue
                        },
                    ))
                }
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
}
