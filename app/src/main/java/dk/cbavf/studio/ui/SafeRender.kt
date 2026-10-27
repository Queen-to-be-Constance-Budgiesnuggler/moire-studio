package dk.cbavf.studio.ui

import dk.cbavf.studio.debug.Debug
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Runs a preview render off the main thread. A failure (out of memory on a big image, a bad
 * file) becomes a message for the room instead of crashing the whole app; cancellation by a
 * newer edit passes through untouched.
 */
suspend fun <T> renderSafely(onError: (String) -> Unit, block: suspend CoroutineScope.() -> T): T? = try {
    withContext(Dispatchers.Default, block)
} catch (e: CancellationException) {
    throw e
} catch (e: Throwable) {
    Debug.w("Studio", "preview render failed", e)
    onError(if (e is OutOfMemoryError) "Not enough memory for this preview: try a smaller size." else e.message ?: e.javaClass.simpleName)
    null
}
