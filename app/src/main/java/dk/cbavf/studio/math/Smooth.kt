package dk.cbavf.studio.math

import kotlin.math.exp

/** One-pole smoothing: a value that follows its target with a time constant. */
object OnePole {
    /**
     * Moves [previous] towards [target] for [dtSec]. After [tauSec] it has gone about 63 % of the
     * way, so the result does not depend on the frame rate. A time of 0 follows at once.
     */
    fun step(previous: Float, target: Float, tauSec: Float, dtSec: Float): Float {
        if (tauSec <= 0f) return target
        if (dtSec <= 0f) return previous
        val k = 1f - exp(-dtSec / tauSec)
        return previous + (target - previous) * k
    }
}

/**
 * The Smooth node's memory: a one-pole smoother with its own rise and fall times (a rising
 * target uses [update]'s riseSec, a falling one fallSec). The first value, and a reset, jump
 * straight to the target.
 */
class Smoother {
    private var value: Float? = null
    private var lastSec = 0.0

    fun update(nowSec: Double, target: Float, riseSec: Float, fallSec: Float, reset: Boolean = false): Float {
        val prev = value
        val out = if (prev == null || reset) target else {
            val dt = (nowSec - lastSec).toFloat().coerceAtLeast(0f)
            OnePole.step(prev, target, if (target >= prev) riseSec else fallSec, dt)
        }
        value = out
        lastSec = nowSec
        return out
    }
}
