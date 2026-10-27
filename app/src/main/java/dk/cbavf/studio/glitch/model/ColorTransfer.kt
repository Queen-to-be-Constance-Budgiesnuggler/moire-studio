package dk.cbavf.studio.glitch.model

import kotlin.math.cbrt
import kotlin.math.pow
import kotlin.math.sqrt

/** The space colour statistics are matched in: CIELAB (lightness and colour apart) or plain RGB channels. */
enum class TransferSpace { Lab, Rgb }

/** Mean and spread (standard deviation) of each of the three channels of a picture. */
class ColorStats(val mean: FloatArray, val std: FloatArray)

/**
 * Colour transfer (after Reinhard et al.): every pixel of a picture is moved so the mean and
 * spread of each channel match those of a second picture. In Lab the channels are close to
 * independent, so this carries over the mood of the palette without strange casts.
 */
object ColorTransfer {
    /** Statistics of [argb], from at most about [maxSamples] evenly spread pixels. */
    fun stats(argb: IntArray, space: TransferSpace, maxSamples: Int = 262_144): ColorStats {
        val step = (argb.size / maxSamples.coerceAtLeast(1)).coerceAtLeast(1)
        val sum = DoubleArray(3)
        val sq = DoubleArray(3)
        val v = FloatArray(3)
        var n = 0
        var i = 0
        while (i < argb.size) {
            toSpace(argb[i], space, v)
            for (c in 0 until 3) {
                sum[c] += v[c]
                sq[c] += v[c].toDouble() * v[c]
            }
            n++
            i += step
        }
        if (n == 0) return ColorStats(FloatArray(3), FloatArray(3))
        val mean = FloatArray(3) { (sum[it] / n).toFloat() }
        val std = FloatArray(3) { sqrt((sq[it] / n - (sum[it] / n).let { m -> m * m }).coerceAtLeast(0.0)).toFloat() }
        return ColorStats(mean, std)
    }

    /**
     * [target] with the colour statistics of [palette] laid over it, [amount] of the way
     * (0 = unchanged, 1 = full match). Alpha is kept.
     */
    fun transfer(target: IntArray, palette: IntArray, space: TransferSpace, amount: Float, checkCancel: () -> Unit = {}): IntArray =
        apply(target, stats(target, space), stats(palette, space), space, amount, checkCancel)

    /** Maps [argb] from statistics [from] to [to]. */
    fun apply(argb: IntArray, from: ColorStats, to: ColorStats, space: TransferSpace, amount: Float, checkCancel: () -> Unit = {}): IntArray {
        val a = amount.coerceIn(0f, 1f)
        if (a == 0f) return argb.copyOf()
        // A flat channel has no spread to scale: shift it only.
        val scale = FloatArray(3) { if (from.std[it] > 1e-6f) to.std[it] / from.std[it] else 1f }
        val out = IntArray(argb.size)
        val v = FloatArray(3)
        for (i in argb.indices) {
            if (i % 65536 == 0) checkCancel()
            val p = argb[i]
            toSpace(p, space, v)
            for (c in 0 until 3) {
                val mapped = (v[c] - from.mean[c]) * scale[c] + to.mean[c]
                v[c] += a * (mapped - v[c])
            }
            out[i] = (p and 0xFF000000.toInt()) or fromSpace(v, space)
        }
        return out
    }

    private fun toSpace(p: Int, space: TransferSpace, out: FloatArray) {
        when (space) {
            TransferSpace.Rgb -> {
                out[0] = (p shr 16 and 0xFF).toFloat()
                out[1] = (p shr 8 and 0xFF).toFloat()
                out[2] = (p and 0xFF).toFloat()
            }
            TransferSpace.Lab -> toLab(p, out)
        }
    }

    private fun fromSpace(v: FloatArray, space: TransferSpace): Int = when (space) {
        TransferSpace.Rgb -> (clip(v[0]) shl 16) or (clip(v[1]) shl 8) or clip(v[2])
        TransferSpace.Lab -> fromLab(v[0], v[1], v[2])
    }

    private fun clip(v: Float) = (v + 0.5f).toInt().coerceIn(0, 255)

    // --- sRGB (D65) <-> CIELAB ----------------------------------------------------------------

    private val toLinear = FloatArray(256) { srgbToLinear(it / 255f) }

    private fun srgbToLinear(c: Float) = if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)

    /** Linear light in 1/[LIN_STEPS] steps to an sRGB byte: a table, as pow per channel per pixel is slow. */
    private const val LIN_STEPS = 16384
    private val fromLinear = IntArray(LIN_STEPS + 1) { i ->
        val l = i.toFloat() / LIN_STEPS
        val s = if (l <= 0.0031308f) l * 12.92f else 1.055f * l.pow(1 / 2.4f) - 0.055f
        (s * 255 + 0.5f).toInt().coerceIn(0, 255)
    }

    private fun linearToSrgb(c: Float): Int = fromLinear[(c.coerceIn(0f, 1f) * LIN_STEPS + 0.5f).toInt()]

    private const val XN = 0.95047f
    private const val ZN = 1.08883f
    private const val EPS = 216f / 24389f
    private const val KAPPA = 24389f / 27f

    private fun f(t: Float) = if (t > EPS) cbrt(t) else (KAPPA * t + 16) / 116
    private fun fInv(t: Float) = (t * t * t).let { if (it > EPS) it else (116 * t - 16) / KAPPA }

    /** L in 0..100, a and b roughly -128..127. */
    fun toLab(p: Int, out: FloatArray) {
        val r = toLinear[p shr 16 and 0xFF]
        val g = toLinear[p shr 8 and 0xFF]
        val b = toLinear[p and 0xFF]
        val fx = f((0.4124564f * r + 0.3575761f * g + 0.1804375f * b) / XN)
        val fy = f(0.2126729f * r + 0.7151522f * g + 0.0721750f * b)
        val fz = f((0.0193339f * r + 0.1191920f * g + 0.9503041f * b) / ZN)
        out[0] = 116 * fy - 16
        out[1] = 500 * (fx - fy)
        out[2] = 200 * (fy - fz)
    }

    /** Back to RGB, with the alpha bits zero; colours outside sRGB are clipped. */
    fun fromLab(l: Float, a: Float, b: Float): Int {
        val fy = (l + 16) / 116
        val x = XN * fInv(fy + a / 500)
        val y = if (l > KAPPA * EPS) fy * fy * fy else l / KAPPA
        val z = ZN * fInv(fy - b / 200)
        val r = 3.2404542f * x - 1.5371385f * y - 0.4985314f * z
        val g = -0.9692660f * x + 1.8760108f * y + 0.0415560f * z
        val bl = 0.0556434f * x - 0.2040259f * y + 1.0572252f * z
        return (linearToSrgb(r) shl 16) or (linearToSrgb(g) shl 8) or linearToSrgb(bl)
    }
}
