package dk.cbavf.studio.draw.model

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** Predicted beat between two gratings: fringe spacing and the direction of the fringe lines. */
data class Beat(val spacingPx: Double, val angleDeg: Double)

/**
 * Moiré formulas from docs/ARCHITECTURE.md §10.
 *
 * General case: a grating is a spatial frequency vector k (|k| = 1/p, pointing along the line normal).
 * The visible beat is k1 − k2 (or k1 + k2, since k and −k describe the same grating — whichever is
 * smaller). The parallel and rotated formulas in the spec are special cases of this.
 */
object MoireMath {
    private const val EPS = 1e-9

    fun parallelBeat(p1: Double, p2: Double): Double = p1 * p2 / abs(p1 - p2)

    fun rotatedBeat(p: Double, thetaDeg: Double): Double = p / (2 * sin(rad(thetaDeg) / 2))

    /** Beat along a line between two dash trains of period d1 and d2. Infinite when equal. */
    fun dashBeat(d1: Double, d2: Double): Double = d1 * d2 / abs(d1 - d2)

    /** Barrier grid: slit spacing for N interleaved frames of a given strip width. */
    fun barrierSlitSpacing(frames: Int, stripWidthPx: Double): Double = frames * stripWidthPx

    /** Max shift per frame before the animation aliases. */
    fun aliasingLimit(spacingPx: Double): Double = spacingPx / 2

    fun beat(a: Grating, b: Grating): Beat {
        val (ax, ay) = frequency(a)
        val (bx, by) = frequency(b)
        val (dx, dy) = ax - bx to ay - by
        val (sx, sy) = ax + bx to ay + by
        val (kx, ky) = if (hypot(dx, dy) <= hypot(sx, sy)) dx to dy else sx to sy
        val k = hypot(kx, ky)
        if (k < EPS) return Beat(Double.POSITIVE_INFINITY, a.angleDeg.toDouble())
        // Fringe lines run perpendicular to the beat frequency vector.
        val fringeAngle = deg(atan2(ky, kx)) - 90
        return Beat(1 / k, normalizeLineAngle(fringeAngle))
    }

    /**
     * How far the fringes move per pixel that [moved] is shifted along its normal.
     * One period of shift moves the fringes one beat period.
     */
    fun shiftAmplification(beat: Beat, moved: Grating): Double = beat.spacingPx / moved.spacingPx

    /** Smallest angle between two line directions, in [0, 90]. */
    fun angleBetween(a: Grating, b: Grating): Double {
        val d = abs(normalizeLineAngle((a.angleDeg - b.angleDeg).toDouble()))
        return d
    }

    /** Line directions are periodic in 180°; map to (−90, 90]. */
    fun normalizeLineAngle(deg: Double): Double {
        var d = deg % 180.0
        if (d <= -90) d += 180
        if (d > 90) d -= 180
        return d
    }

    private fun frequency(g: Grating): Pair<Double, Double> {
        val n = rad(g.angleDeg + 90.0)
        val f = 1.0 / g.spacingPx
        return f * cos(n) to f * sin(n)
    }

    private fun rad(deg: Double) = deg * PI / 180
    private fun deg(rad: Double) = rad * 180 / PI
}
