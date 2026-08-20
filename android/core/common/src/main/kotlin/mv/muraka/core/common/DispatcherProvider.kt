package mv.muraka.core.common

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Dispatchers, injected rather than referenced directly.
 *
 * The point is testability: a test swaps in a single deterministic dispatcher and the
 * outbox drain — which is otherwise the hardest thing in this app to test, being a loop
 * over network calls with backoff — becomes ordinary synchronous code.
 */
interface DispatcherProvider {
    val io: CoroutineDispatcher
    val default: CoroutineDispatcher
    val main: CoroutineDispatcher
}

/** The real dispatchers. */
class StandardDispatcherProvider : DispatcherProvider {
    override val io: CoroutineDispatcher get() = Dispatchers.IO
    override val default: CoroutineDispatcher get() = Dispatchers.Default
    override val main: CoroutineDispatcher get() = Dispatchers.Main
}
