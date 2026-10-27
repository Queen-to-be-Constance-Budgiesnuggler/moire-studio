package dk.cbavf.studio.camera.model

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Waits for something that a callback hands over once it is open (a camera device, a capture
 * session). If the wait is cancelled, whatever arrives, before or after, is [release]d, so a
 * camera opened for a preview that has gone away is closed again instead of being kept.
 *
 * [start] begins the opening; it calls `deliver` with the opened thing or `fail` with an error,
 * from any thread.
 */
suspend fun <T : Any> awaitOpened(release: (T) -> Unit, start: (deliver: (T) -> Unit, fail: (Throwable) -> Unit) -> Unit): T {
    // Holds the opened thing until the caller has it; whoever takes it out last releases it.
    val holder = AtomicReference<T?>(null)
    try {
        val opened = suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { holder.getAndSet(null)?.let(release) }
            start(
                { v ->
                    holder.set(v)
                    if (cont.isActive) cont.resume(v) else holder.getAndSet(null)?.let(release)
                },
                { e -> if (cont.isActive) cont.resumeWithException(e) },
            )
        }
        holder.set(null)
        return opened
    } catch (e: CancellationException) {
        holder.getAndSet(null)?.let(release)
        throw e
    }
}
