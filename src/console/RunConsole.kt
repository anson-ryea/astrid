package console

import com.github.ajalt.mordant.animation.animation
import com.github.ajalt.mordant.input.RawModeScope
import com.github.ajalt.mordant.input.enterRawModeOrNull
import com.github.ajalt.mordant.input.isCtrlC
import com.github.ajalt.mordant.rendering.AnsiLevel
import com.github.ajalt.mordant.rendering.TextColors
import com.github.ajalt.mordant.rendering.Widget
import com.github.ajalt.mordant.terminal.Terminal
import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.oshai.kotlinlogging.KotlinLoggingConfiguration
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import run.RunEvent
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

private enum class QuitPrompt(val text: String, val discardResults: Boolean) {
    Quit("Quit? [y/n]", false),
    Discard("Quit and discard results? [y/n]", true),
}

/** Owns the event consumer and display for one run; no coroutines survive its completion. */
class RunConsole(
    terminal: Terminal,
    context: RunDashboardContext,
    private val startedAt: TimeMark = TimeSource.Monotonic.markNow(),
    mode: RunConsoleMode = RunConsoleMode.Auto,
) {
    private val allowDiscard = context.allowDiscard
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
    private val info = RunInfo(context)

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
        val logs = RunLogs(allowDiscard)
        val quitting = MutableStateFlow(false)
        val discarding = MutableStateFlow(false)
        val page = MutableStateFlow(RunConsolePage.Dashboard)
        val quitPrompt = MutableStateFlow<QuitPrompt?>(null)
        val events = RunEventProcessor(this, onEventProcessed = { event ->
            event.logEntry()?.let { entry ->
                if (live) logs.append(entry) else output.println(entry.renderMessage())
            }
        })
        val animation = if (live) output.animation<Widget> { it } else null
        var rawMode: RawModeScope? = null
        fun frame(status: RunDashboardStatus): Widget {
            val visibleStatus = if (quitting.value && status == RunDashboardStatus.Running)
                RunDashboardStatus.Quitting else status
            val controls = rawMode != null && visibleStatus == RunDashboardStatus.Running
            val prompt = quitPrompt.value?.text
            return when (page.value) {
                RunConsolePage.Logs -> logs.render(
                    output.size.width,
                    output.size.height,
                    controls,
                    visibleStatus,
                    prompt
                )

                RunConsolePage.Info -> info.render(
                    output.size.width,
                    output.size.height,
                    controls,
                    visibleStatus,
                    prompt
                )
                RunConsolePage.Dashboard -> dashboard.render(
                    events.state.value, startedAt.elapsedNow(), output.size.width,
                    output.size.height, visibleStatus, controlsEnabled = controls, discardResults = discarding.value,
                    prompt = prompt,
                )
            }
        }

        val paintMutex = Mutex()
        suspend fun paint(status: RunDashboardStatus) {
            paintMutex.withLock { animation?.update(frame(status)) }
        }

        var status = RunDashboardStatus.Running
        var runFailure: Throwable? = null
        var painter: Job? = null
        var keyboard: Job? = null
        // Keep the event consumer alive when quitting so saved milestones can still drain.
        val execution = async(start = CoroutineStart.LAZY) { runCatching { block(events::emit) } }
        suspend fun quit(cause: CancellationException) {
            val discard = cause is RunQuitException && cause.discardResults
            discarding.value = discard
            quitting.value = true
            logs.append(
                RunLogEntry(
                    if (discard) "QUITTING: Stopping active tasks; current run output will be discarded"
                    else "QUITTING: Stopping active tasks and finalising run metadata"
                )
            )
            // Acknowledge before cancellation, then keep painting during worker cleanup.
            try {
                paint(RunDashboardStatus.Running)
            } finally {
                execution.cancel(cause)
            }
        }
        try {
            if (explainFallback) printMessage(
                "This console cannot redraw a multi-line dashboard. Use a terminal tab for live output; showing plain progress.",
            )
            if (animation != null) {
                // Use the actual stdin capability, even when --ui=live forces output interactivity.
                rawMode = if (inputInteractive) output.enterRawModeOrNull() else null
                output.cursor.hide()
                // Paint synchronously so even preflight work starts with a visible dashboard.
                paint(status)
                rawMode?.let { input ->
                    keyboard = launch {
                        while (isActive) {
                            val key = withContext(Dispatchers.IO) { input.readKeyOrNull(100.milliseconds) }
                            if (key == null) {
                                delay(10.milliseconds)
                                continue
                            }
                            if (quitting.value) continue
                            if (key.isCtrlC) {
                                quit(CancellationException("Run interrupted"))
                                continue
                            }
                            if (key.ctrl || key.alt) continue

                            val pending = quitPrompt.value
                            if (pending != null) {
                                when (key.key) {
                                    "y", "Y" -> quit(RunQuitException(discardResults = pending.discardResults))
                                    "n", "N" -> {
                                        quitPrompt.value = null
                                        paint(RunDashboardStatus.Running)
                                    }
                                }
                                continue
                            }

                            when {
                                key.key == "q" || key.key == "Q" -> {
                                    quitPrompt.value = if (allowDiscard && (key.key == "Q" || key.shift))
                                        QuitPrompt.Discard else QuitPrompt.Quit
                                    paint(RunDashboardStatus.Running)
                                }

                                key.key == "l" -> page.update {
                                    if (it == RunConsolePage.Logs) RunConsolePage.Dashboard else RunConsolePage.Logs
                                }

                                key.key == "i" -> page.update {
                                    if (it == RunConsolePage.Info) RunConsolePage.Dashboard else RunConsolePage.Info
                                }

                                page.value == RunConsolePage.Info -> when (key.key) {
                                    "ArrowDown" -> info.scrollBy(1)
                                    "ArrowUp" -> info.scrollBy(-1)
                                    "PageDown" -> info.scrollBy((output.size.height - 4).coerceAtLeast(1))
                                    "PageUp" -> info.scrollBy(-(output.size.height - 4).coerceAtLeast(1))
                                    "Home" -> info.home()
                                    "End" -> info.end()
                                }
                            }
                        }
                    }
                }
                painter = launch {
                    while (isActive) {
                        delay(250.milliseconds)
                        output.updateSize()
                        paint(RunDashboardStatus.Running)
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
                    if (live) logs.append(
                        RunLogEntry(
                            "${status.name.uppercase()}: " + if (discarding.value) "Run output will be discarded"
                            else "${events.state.value.completedSamples} samples saved",
                            when (status) {
                                RunDashboardStatus.Complete -> TextColors.green
                                RunDashboardStatus.Failed -> TextColors.red
                                RunDashboardStatus.Cancelled -> TextColors.green
                                RunDashboardStatus.Running -> TextColors.blue
                                RunDashboardStatus.Quitting -> TextColors.blue
                            },
                        )
                    )
                }
                cleanup {
                    if (animation != null) paint(status) else output.println(frame(status))
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
