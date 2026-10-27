package dk.cbavf.studio.math

import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/** How the modulator changes the carrier. */
enum class FmAmMode { AM, FM, Ring }

/**
 * A carrier and a modulator, both sine waves. [carrier] and [modulator] are cycles per unit of time
 * (per second on the FM/AM node, across the picture on the FM/AM Image node); [phase] moves both along.
 */
data class FmAmSetup(
    val mode: FmAmMode = FmAmMode.AM,
    val carrier: Double = 1.0,
    val modulator: Double = 0.25,
    /** AM only: 0 leaves the carrier as it is, 1 lets the modulator pull it all the way to silence. */
    val depth: Double = 0.5,
    /** FM only: how far the modulator pushes the carrier's phase, in radians. */
    val index: Double = 2.0,
    val phase: Double = 0.0,
)

/**
 * The FM/AM module (sprint R04): numbers only, no sound. Every wave goes from -1 to 1. AM scales the
 * carrier by the modulator, FM bends its phase by the modulator, Ring multiplies the two.
 */
object FmAm {
    private const val TAU = 2 * PI

    fun carrier(t: Double, s: FmAmSetup): Double = sin(TAU * s.carrier * (t + s.phase))

    fun modulator(t: Double, s: FmAmSetup): Double = sin(TAU * s.modulator * (t + s.phase))

    /** The modulated wave at time [t], -1..1. */
    fun sample(t: Double, s: FmAmSetup): Double {
        val m = modulator(t, s)
        return when (s.mode) {
            // The envelope runs from 1 - depth to 1, so the peak never passes 1.
            FmAmMode.AM -> amEnvelope(m, s.depth) * carrier(t, s)
            FmAmMode.FM -> sin(TAU * s.carrier * (t + s.phase) + s.index * m)
            FmAmMode.Ring -> carrier(t, s) * m
        }
    }

    /** The AM gain for modulator value [m] (-1..1): 1 at depth 0, from 0 to 1 at depth 1. */
    fun amEnvelope(m: Double, depth: Double): Double {
        val d = depth.coerceIn(0.0, 1.0)
        return 1.0 - d * (1.0 - m) / 2.0
    }

    /** A wave value (-1..1) mapped to [min]..[max]. */
    fun scaled(v: Double, min: Double, max: Double): Double = min + (max - min) * (v + 1.0) / 2.0

    /** The wave at [n] evenly spaced points across one unit (line centres), for the image modulation. */
    fun profile(n: Int, s: FmAmSetup): FloatArray =
        FloatArray(n.coerceAtLeast(0)) { i -> sample((i + 0.5) / n, s).toFloat() }
}

/**
 * The FM/AM Image node's pixel work on ARGB ints, one wave value per line: [down] runs the wave from
 * top to bottom (each row gets one value), otherwise from left to right (each column).
 */
object FmAmImage {
    /**
     * Brightness: each line darkened where the wave is low. [amount] 0 leaves the picture, 1 lets the
     * lowest point go black. Alpha is kept.
     */
    fun brightness(px: IntArray, w: Int, h: Int, wave: FloatArray, down: Boolean, amount: Float): IntArray {
        val a = amount.coerceIn(0f, 1f)
        val out = IntArray(px.size)
        for (y in 0 until h) for (x in 0 until w) {
            val v = wave.getOrElse(if (down) y else x) { 0f }
            val k = 1f - a * (1f - (v + 1f) / 2f)
            val c = px[y * w + x]
            val r = ((c shr 16 and 0xFF) * k).roundToInt()
            val g = ((c shr 8 and 0xFF) * k).roundToInt()
            val b = ((c and 0xFF) * k).roundToInt()
            out[y * w + x] = (c and 0xFF000000.toInt()) or (r shl 16) or (g shl 8) or b
        }
        return out
    }

    /**
     * Shift: each line slid sideways by the wave times [shiftPx], wrapping round the edge. Rows slide
     * left and right when [down], columns slide up and down otherwise.
     */
    fun shift(px: IntArray, w: Int, h: Int, wave: FloatArray, down: Boolean, shiftPx: Float): IntArray {
        val out = IntArray(px.size)
        if (down) for (y in 0 until h) {
            val d = (wave.getOrElse(y) { 0f } * shiftPx).roundToInt()
            for (x in 0 until w) out[y * w + x] = px[y * w + (x - d).mod(w)]
        } else for (x in 0 until w) {
            val d = (wave.getOrElse(x) { 0f } * shiftPx).roundToInt()
            for (y in 0 until h) out[y * w + x] = px[(y - d).mod(h) * w + x]
        }
        return out
    }
}
