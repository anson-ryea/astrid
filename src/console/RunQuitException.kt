package console

import kotlin.coroutines.cancellation.CancellationException

/** An explicit quit request, handled by the command after run and terminal cleanup. */
internal class RunQuitException : CancellationException("Benchmark stopped by user (q)")
