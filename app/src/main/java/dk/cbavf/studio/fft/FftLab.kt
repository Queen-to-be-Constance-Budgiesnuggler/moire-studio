package dk.cbavf.studio.fft

import android.graphics.Bitmap
import dk.cbavf.studio.fft.model.Colormap
import dk.cbavf.studio.fft.model.Colormaps
import dk.cbavf.studio.fft.model.Fft
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** Forward spectra of one image at n×n: one per RGB channel, plus luminance for display. */
class Spectra(val n: Int, val re: Array<FloatArray>, val im: Array<FloatArray>, val lumRe: FloatArray, val lumIm: FloatArray)

/** Centre-crops [source] to a square and scales it to n×n (n a power of two). */
fun squareCrop(source: Bitmap, n: Int): Bitmap {
    val side = minOf(source.width, source.height)
    val crop = Bitmap.createBitmap(source, (source.width - side) / 2, (source.height - side) / 2, side, side)
    return Bitmap.createScaledBitmap(crop, n, n, true).also { if (crop !== source && crop !== it) crop.recycle() }
}

fun forward(square: Bitmap): Spectra {
    val n = square.width
    val px = IntArray(n * n).also { square.getPixels(it, 0, n, 0, 0, n, n) }
    val re = Array(3) { FloatArray(n * n) }
    val im = Array(3) { FloatArray(n * n) }
    val lumRe = FloatArray(n * n)
    for (i in px.indices) {
        val c = px[i]
        val r = (c shr 16 and 0xFF) / 255f
        val g = (c shr 8 and 0xFF) / 255f
        val b = (c and 0xFF) / 255f
        re[0][i] = r; re[1][i] = g; re[2][i] = b
        lumRe[i] = 0.2126f * r + 0.7152f * g + 0.0722f * b
    }
    val lumIm = FloatArray(n * n)
    for (c in 0..2) Fft.transform2d(re[c], im[c], n, inverse = false)
    Fft.transform2d(lumRe, lumIm, n, inverse = false)
    return Spectra(n, re, im, lumRe, lumIm)
}

/**
 * Inverse transform after applying [gain] per frequency. With [phaseFrom], each channel keeps
 * its own magnitude but takes the other image's phase (the classic swap: phase carries the
 * shapes, magnitude the texture).
 */
fun inverse(s: Spectra, gain: FloatArray, phaseFrom: Spectra?, checkCancel: () -> Unit = {}): Bitmap {
    val n = s.n
    val out = IntArray(n * n) { 0xFF shl 24 }
    val re = FloatArray(n * n)
    val im = FloatArray(n * n)
    for (c in 0..2) {
        checkCancel()
        for (i in 0 until n * n) {
            var r = s.re[c][i]
            var m = s.im[c][i]
            if (phaseFrom != null) {
                val mag = hypot(r, m)
                val ph = atan2(phaseFrom.im[c][i], phaseFrom.re[c][i])
                r = mag * cos(ph)
                m = mag * sin(ph)
            }
            re[i] = r * gain[i]
            im[i] = m * gain[i]
        }
        Fft.transform2d(re, im, n, inverse = true)
        val shift = 16 - c * 8
        for (i in 0 until n * n) out[i] = out[i] or ((re[i] * 255f + 0.5f).toInt().coerceIn(0, 255) shl shift)
    }
    return Bitmap.createBitmap(out, n, n, Bitmap.Config.ARGB_8888)
}

/** Centred spectrum picture: log magnitude (dimmed where the gain removes it) or phase. */
fun spectrumBitmap(s: Spectra, gain: FloatArray, phase: Boolean, map: Colormap): Bitmap {
    val n = s.n
    val values = if (phase) {
        FloatArray(n * n) { ((atan2(s.lumIm[it], s.lumRe[it]) + PI) / (2 * PI)).toFloat() }
    } else {
        Fft.logMagnitude(s.lumRe, s.lumIm)
    }
    val px = IntArray(n * n)
    for (y in 0 until n) for (x in 0 until n) {
        val i = Fft.shifted(x, y, n)
        val g = gain[i]
        val v = when {
            phase -> values[i]
            g > 1f -> (values[i] * kotlin.math.sqrt(g)).coerceAtMost(1f)
            else -> values[i] * (0.15f + 0.85f * g)
        }
        px[y * n + x] = Colormaps.argb(map, v)
    }
    return Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888)
}
