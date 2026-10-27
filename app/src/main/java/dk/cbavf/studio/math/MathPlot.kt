package dk.cbavf.studio.math

import kotlin.math.roundToInt

/** Which colour channels a function is applied to. */
enum class MathChannel(val label: String) { All("RGB"), Red("R"), Green("G"), Blue("B") }

/** Plotting a [MathFunction] and using it as a tone curve on pixels. No Android types: the room turns the results into drawing. */
object MathPlot {
    /** [n] values of [f] at evenly spaced x from 0 to 1, both ends included. */
    fun samples(f: (Double) -> Double, n: Int): DoubleArray {
        require(n >= 2) { "need at least two samples" }
        return DoubleArray(n) { i -> f(i.toDouble() / (n - 1)) }
    }

    /**
     * The samples as x, y pairs in a [w] × [h] box inset by [pad]: x runs left to right, y = 1 is at the top.
     * Values outside 0..1 are kept at the edge.
     */
    fun points(samples: DoubleArray, w: Float, h: Float, pad: Float = 0f): FloatArray {
        val out = FloatArray(samples.size * 2)
        val iw = w - 2 * pad
        val ih = h - 2 * pad
        val last = (samples.size - 1).coerceAtLeast(1)
        for (i in samples.indices) {
            out[2 * i] = pad + iw * i / last
            out[2 * i + 1] = pad + ih * (1f - samples[i].coerceIn(0.0, 1.0).toFloat())
        }
        return out
    }

    /** A 256-entry table: level v in gives f(v / 255) × 255 out, rounded and kept inside 0..255. */
    fun table(f: (Double) -> Double): IntArray = IntArray(256) { v -> (f(v / 255.0) * 255).roundToInt().coerceIn(0, 255) }

    /**
     * Each ARGB pixel through [table] on the [channel]s asked for; alpha is kept. [mix] 0..1 blends from the original
     * (0) to the mapped level (1).
     */
    fun apply(pixels: IntArray, table: IntArray, mix: Float = 1f, channel: MathChannel = MathChannel.All): IntArray {
        val m = mix.coerceIn(0f, 1f)
        fun map(v: Int, on: Boolean): Int = if (!on) v else (v + (table[v] - v) * m).roundToInt().coerceIn(0, 255)
        val r = channel == MathChannel.All || channel == MathChannel.Red
        val g = channel == MathChannel.All || channel == MathChannel.Green
        val b = channel == MathChannel.All || channel == MathChannel.Blue
        return IntArray(pixels.size) { i ->
            val p = pixels[i]
            (p and 0xFF000000.toInt()) or
                (map((p shr 16) and 0xFF, r) shl 16) or
                (map((p shr 8) and 0xFF, g) shl 8) or
                map(p and 0xFF, b)
        }
    }
}
