package dk.cbavf.studio.foreground

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.CopyOnWriteArrayList

/**
 * One flag for "the studio is on screen". The activity sets it on ON_START and clears it on ON_STOP. Every loop, socket, sensor
 * and timer checks it: while it is clear, nothing may run. Loops wait with [awaitStarted] or [sleep]; things that hold hardware
 * (sensors, the phone link) add a [Hook] to let go on stop and take it back on start.
 */
class RunGate(onScreen: Boolean = true) {
    /** What to do when the studio leaves the screen and when it comes back. */
    interface Hook {
        fun onStop()
        fun onStart()
    }

    private val state = MutableStateFlow(onScreen)
    private val hooks = CopyOnWriteArrayList<Hook>()

    /** True while the studio is on screen. */
    val started: StateFlow<Boolean> = state.asStateFlow()

    val isStarted: Boolean get() = state.value

    /** How many times the studio has left the screen, for the debug log. */
    var stops = 0
        private set

    /** The studio came on screen: hooks take their hardware back, waiting loops go on. A second call does nothing. */
    fun onStart() {
        if (state.value) return
        state.value = true
        hooks.forEach { it.onStart() }
    }

    /** The studio left the screen: hooks let go, the last one added first. A second call does nothing. */
    fun onStop() {
        if (!state.value) return
        state.value = false
        stops++
        // asReversed, not reversed: on new SDKs reversed() binds to a Java method older phones do not have.
        hooks.toList().asReversed().forEach { it.onStop() }
    }

    /** Adds [hook]; if the studio is already off screen it is told at once. Returns a function that removes it. */
    fun add(hook: Hook): () -> Unit {
        hooks += hook
        if (!state.value) hook.onStop()
        return { hooks.remove(hook) }
    }

    /** Returns at once while on screen; otherwise suspends, using nothing, until the studio comes back. */
    suspend fun awaitStarted() {
        if (!state.value) state.first { it }
    }

    /**
     * Waits [ms] of time on screen. If the studio leaves the screen during the wait, the wait stops, suspends until it comes back,
     * and then starts again from the beginning. Use it instead of `delay` in any loop that polls.
     */
    suspend fun sleep(ms: Long) {
        while (true) {
            awaitStarted()
            // null: the whole time passed on screen. false: the studio left first.
            if (withTimeoutOrNull(ms.coerceAtLeast(0)) { state.first { !it } } == null) return
        }
    }
}

/** The studio's one gate, set by MainActivity. */
object Foreground {
    val gate = RunGate()
}
