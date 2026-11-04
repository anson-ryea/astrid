package console

import kotlin.coroutines.cancellation.CancellationException

/** An explicit quit request, handled by the command after run and terminal cleanup. */
internal class RunQuitException(val discardResults: Boolean = false) : CancellationException(
    if (discardResults) "Benchmark stopped by user (Shift+Q; discard output)" else "Benchmark stopped by user (q)",
)
