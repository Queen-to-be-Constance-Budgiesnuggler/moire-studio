package dk.cbavf.studio.fft.model

import kotlinx.serialization.Serializable
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.sin

/**
 * In-place radix-2 FFT on float arrays. 2D transforms run rows then columns; sizes must be
 * powers of two (the lab crops or pads images to [FftDoc.size]).
 */
object Fft {
    fun isPowerOfTwo(n: Int) = n > 0 && n and (n - 1) == 0

    /** 1D transform of [n] points spaced [step] apart starting at [offset]. Inverse is unscaled. */
    fun transform(re: FloatArray, im: FloatArray, offset: Int, step: Int, n: Int, inverse: Boolean) {
        // Bit-reversal permutation.
        var j = 0
        for (i in 0 until n - 1) {
            if (i < j) {
                val a = offset + i * step
                val b = offset + j * step
                val tr = re[a]; re[a] = re[b]; re[b] = tr
                val ti = im[a]; im[a] = im[b]; im[b] = ti
            }
            var k = n shr 1
            while (k <= j) {
                j -= k
                k = k shr 1
            }
            j += k
        }
        var len = 2
        val sign = if (inverse) 1.0 else -1.0
        while (len <= n) {
            val angle = sign * 2 * PI / len
            val wr = cos(angle).toFloat()
            val wi = sin(angle).toFloat()
            var start = 0
            while (start < n) {
                var cr = 1f
                var ci = 0f
                for (k in 0 until len / 2) {
                    val a = offset + (start + k) * step
                    val b = offset + (start + k + len / 2) * step
                    val xr = re[b] * cr - im[b] * ci
                    val xi = re[b] * ci + im[b] * cr
                    re[b] = re[a] - xr
                    im[b] = im[a] - xi
                    re[a] += xr
                    im[a] += xi
                    val ncr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr
                    cr = ncr
                }
                start += len
            }
            len = len shl 1
        }
    }

    /** 2D transform of an [n]×[n] grid (row-major). The inverse divides by n² so it round-trips. */
    fun transform2d(re: FloatArray, im: FloatArray, n: Int, inverse: Boolean) {
        require(isPowerOfTwo(n) && re.size == n * n && im.size == n * n)
        for (row in 0 until n) transform(re, im, row * n, 1, n, inverse)
        for (col in 0 until n) transform(re, im, col, n, n, inverse)
        if (inverse) {
            val s = 1f / (n.toFloat() * n)
            for (i in re.indices) {
                re[i] *= s
                im[i] *= s
            }
        }
    }

    /** Index with the zero frequency moved to the centre (fftshift), for display and painting. */
    fun shifted(x: Int, y: Int, n: Int): Int = ((y + n / 2) % n) * n + (x + n / 2) % n

    fun magnitude(re: Float, im: Float): Float = hypot(re, im)
    fun phase(re: Float, im: Float): Float = atan2(im, re)

    /** log(1 + |F|) for display, normalised to 0..1 over the whole spectrum. */
    fun logMagnitude(re: FloatArray, im: FloatArray): FloatArray {
        val out = FloatArray(re.size) { ln(1f + hypot(re[it], im[it])) }
        val max = out.max().coerceAtLeast(1e-6f)
        for (i in out.indices) out[i] /= max
        return out
    }
}

/** What painting on the spectrum does. */
enum class SpectrumBrush { Erase, Keep, Boost }

/** A dab on the spectrum, in centred coordinates (−0.5..0.5 of the size, 0 = the DC term). */
@Serializable
data class SpectrumDab(val x: Float, val y: Float, val radius: Float, val brush: SpectrumBrush)

/** Shaped filters as starting points for painting. */
enum class FilterShape { None, LowPass, HighPass, BandPass }

enum class Colormap { Gray, Heat, Twilight }

enum class FftView { Magnitude, Phase, Result }

/** An FFT lab edit; also the recipe of its output. */
@Serializable
data class FftDoc(
    val sourceAssetId: String? = null,
    /** Working size (power of two); the image is centre-cropped and scaled to fit. */
    val size: Int = 512,
    val filter: FilterShape = FilterShape.None,
    /** Cut-off radius (fraction of the size) for the low / high pass, inner radius for band pass. */
    val radius: Float = 0.1f,
    val bandWidth: Float = 0.05f,
    val dabs: List<SpectrumDab> = emptyList(),
    val boost: Float = 3f,
    /** Magnitude/phase swap: take the phase from this image instead of the source. */
    val phaseFromAssetId: String? = null,
) {
    val summary: String
        get() = buildList {
            add("FFT ${this@FftDoc.size}")
            if (filter != FilterShape.None) add(filter.name.lowercase())
            if (dabs.isNotEmpty()) add("${dabs.size} dabs")
            if (phaseFromAssetId != null) add("phase swap")
        }.joinToString(" · ")
}

object SpectrumMask {
    /**
     * Gain per frequency (row-major, unshifted, like the FFT output). Dabs are mirrored through
     * the centre, because a real image's spectrum is point-symmetric: painting one side alone
     * would make the result complex.
     */
    fun build(doc: FftDoc): FloatArray {
        val n = doc.size
        val gain = FloatArray(n * n)
        for (y in 0 until n) for (x in 0 until n) {
            // Centred frequency coordinates of this (unshifted) bin.
            val fx = (if (x < n / 2) x else x - n).toFloat() / n
            val fy = (if (y < n / 2) y else y - n).toFloat() / n
            val r = hypot(fx, fy)
            var g = when (doc.filter) {
                FilterShape.None -> 1f
                FilterShape.LowPass -> if (r <= doc.radius) 1f else 0f
                FilterShape.HighPass -> if (r >= doc.radius) 1f else 0f
                FilterShape.BandPass -> if (r >= doc.radius && r <= doc.radius + doc.bandWidth) 1f else 0f
            }
            for (d in doc.dabs) {
                val inside = hypot(fx - d.x, fy - d.y) <= d.radius || hypot(fx + d.x, fy + d.y) <= d.radius
                if (!inside) continue
                g = when (d.brush) {
                    SpectrumBrush.Erase -> 0f
                    SpectrumBrush.Keep -> 1f
                    // Boost works on filtered-out areas too: it brings them back, amplified.
                    SpectrumBrush.Boost -> (if (g == 0f) 1f else g) * doc.boost
                }
            }
            // The DC term (average brightness) survives a high-pass, or the result is a black void.
            if (x == 0 && y == 0 && doc.filter != FilterShape.None) g = 1f
            gain[y * n + x] = g
        }
        return gain
    }
}

/** Colour maps for spectrum display, value 0..1 → ARGB. */
object Colormaps {
    fun argb(map: Colormap, v0: Float): Int {
        val v = v0.coerceIn(0f, 1f)
        val (r, g, b) = when (map) {
            Colormap.Gray -> Triple(v, v, v)
            // Black → red → yellow → white.
            Colormap.Heat -> Triple((v * 3).coerceAtMost(1f), (v * 3 - 1).coerceIn(0f, 1f), (v * 3 - 2).coerceIn(0f, 1f))
            // Cyclic, for phase: equal ends.
            Colormap.Twilight -> Triple(
                (0.5f + 0.5f * cos(2 * PI * v).toFloat()),
                (0.5f + 0.5f * cos(2 * PI * (v - 1.0 / 3)).toFloat()),
                (0.5f + 0.5f * cos(2 * PI * (v - 2.0 / 3)).toFloat()),
            )
        }
        return (0xFF shl 24) or ((r * 255).toInt() shl 16) or ((g * 255).toInt() shl 8) or (b * 255).toInt()
    }
}
