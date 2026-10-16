package dk.cbavf.studio.compose

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import dk.cbavf.studio.compose.model.LayerFilter
import dk.cbavf.studio.fft.forward
import dk.cbavf.studio.fft.inverse
import dk.cbavf.studio.fft.model.FftDoc
import dk.cbavf.studio.fft.model.SpectrumMask
import dk.cbavf.studio.glitch.renderGlitch
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Applies [filters] in order to a mutable ARGB copy of [src]. [pxScale] converts sizes given in
 * the layer's own pixels (blur radius) to this bitmap's pixels, so a preview looks like the save.
 */
fun applyFilters(src: Bitmap, filters: List<LayerFilter>, pxScale: Float, checkCancel: () -> Unit = {}): Bitmap {
    var bmp = src.copy(Bitmap.Config.ARGB_8888, true)
    for (f in filters) {
        checkCancel()
        bmp = when (f) {
            is LayerFilter.Blur -> pixels(bmp) { px, w, h -> boxBlur3(px, w, h, (f.radius * pxScale).roundToInt()) }
            is LayerFilter.Sharpen -> pixels(bmp) { px, w, h ->
                val blurred = px.copyOf()
                boxBlur3(blurred, w, h, (f.radius * pxScale).roundToInt().coerceAtLeast(1))
                for (i in px.indices) px[i] = perChannel(px[i], blurred[i]) { a, b -> a + (a - b) * f.amount }
            }
            is LayerFilter.Noise -> pixels(bmp) { px, _, _ ->
                val rnd = Random(f.seed)
                val amp = f.amount * 255
                for (i in px.indices) {
                    if (px[i] ushr 24 == 0) continue
                    val n = (rnd.nextFloat() * 2 - 1) * amp
                    px[i] = if (f.mono) perChannel(px[i], 0) { a, _ -> a + n }
                    else perChannel(px[i], 0) { a, _ -> a + (rnd.nextFloat() * 2 - 1) * amp }
                }
            }
            LayerFilter.Invert -> pixels(bmp) { px, _, _ -> for (i in px.indices) px[i] = perChannel(px[i], 0) { a, _ -> 255 - a } }
            LayerFilter.Desaturate -> pixels(bmp) { px, _, _ ->
                for (i in px.indices) {
                    val l = luma(px[i]) * 255
                    px[i] = perChannel(px[i], 0) { _, _ -> l }
                }
            }
            is LayerFilter.Posterize -> pixels(bmp) { px, _, _ ->
                val n = (f.levels.coerceIn(2, 64) - 1).toFloat()
                for (i in px.indices) px[i] = perChannel(px[i], 0) { a, _ -> (a / 255 * n).roundToInt() / n * 255 }
            }
            is LayerFilter.Threshold -> pixels(bmp) { px, _, _ ->
                for (i in px.indices) {
                    val v = if (luma(px[i]) >= f.level) 255f else 0f
                    px[i] = perChannel(px[i], 0) { _, _ -> v }
                }
            }
            is LayerFilter.FftPass -> fftPass(bmp, f)
            is LayerFilter.Glitch -> {
                val g = renderGlitch(bmp, f.doc, checkCancel)
                // Misreads can change the size: back to the layer's, so it stays in place.
                val out = if (g.width == bmp.width && g.height == bmp.height) g else Bitmap.createScaledBitmap(g, bmp.width, bmp.height, true)
                if (out !== bmp) bmp.recycle()
                out.copy(Bitmap.Config.ARGB_8888, true)
            }
        }
    }
    return bmp
}

/** Runs [block] on the bitmap's pixels and writes them back. */
private inline fun pixels(bmp: Bitmap, block: (IntArray, Int, Int) -> Unit): Bitmap {
    val w = bmp.width
    val h = bmp.height
    val px = IntArray(w * h)
    bmp.getPixels(px, 0, w, 0, 0, w, h)
    block(px, w, h)
    bmp.setPixels(px, 0, w, 0, 0, w, h)
    return bmp
}

private inline fun perChannel(c: Int, d: Int, f: (Float, Float) -> Float): Int {
    val r = f((c shr 16 and 0xFF).toFloat(), (d shr 16 and 0xFF).toFloat())
    val g = f((c shr 8 and 0xFF).toFloat(), (d shr 8 and 0xFF).toFloat())
    val b = f((c and 0xFF).toFloat(), (d and 0xFF).toFloat())
    return (c and 0xFF000000.toInt()) or (clip(r) shl 16) or (clip(g) shl 8) or clip(b)
}

private fun clip(v: Float) = v.roundToInt().coerceIn(0, 255)

fun luma(c: Int): Float = (0.2126f * (c shr 16 and 0xFF) + 0.7152f * (c shr 8 and 0xFF) + 0.0722f * (c and 0xFF)) / 255f

/** Three box blurs ≈ a Gaussian; alpha is blurred with the colour (premultiplied), so edges stay clean. */
fun boxBlur3(px: IntArray, w: Int, h: Int, radius: Int) {
    if (radius < 1) return
    val r = radius / 2 + 1
    val a = FloatArray(px.size)
    val rr = FloatArray(px.size)
    val gg = FloatArray(px.size)
    val bb = FloatArray(px.size)
    for (i in px.indices) {
        val c = px[i]
        val al = (c ushr 24) / 255f
        a[i] = al
        rr[i] = (c shr 16 and 0xFF) * al
        gg[i] = (c shr 8 and 0xFF) * al
        bb[i] = (c and 0xFF) * al
    }
    val tmp = FloatArray(maxOf(w, h))
    repeat(3) {
        for (ch in listOf(a, rr, gg, bb)) {
            boxPass(ch, w, h, r, horizontal = true, tmp)
            boxPass(ch, w, h, r, horizontal = false, tmp)
        }
    }
    for (i in px.indices) {
        val al = a[i]
        px[i] = if (al <= 1e-4f) 0 else ((clip(al * 255) shl 24) or (clip(rr[i] / al) shl 16) or (clip(gg[i] / al) shl 8) or clip(bb[i] / al))
    }
}

/** Running-sum box filter along rows or columns, clamped at the edges. */
private fun boxPass(ch: FloatArray, w: Int, h: Int, r: Int, horizontal: Boolean, tmp: FloatArray) {
    val lines = if (horizontal) h else w
    val len = if (horizontal) w else h
    val stride = if (horizontal) 1 else w
    val norm = 1f / (2 * r + 1)
    for (line in 0 until lines) {
        val base = if (horizontal) line * w else line
        var sum = 0f
        for (k in -r..r) sum += ch[base + k.coerceIn(0, len - 1) * stride]
        for (i in 0 until len) {
            tmp[i] = sum * norm
            sum += ch[base + (i + r + 1).coerceAtMost(len - 1) * stride] - ch[base + (i - r).coerceAtLeast(0) * stride]
        }
        for (i in 0 until len) ch[base + i * stride] = tmp[i]
    }
}

/** FFT pass: stretched onto a power-of-two square, filtered per channel, stretched back; alpha kept. */
private fun fftPass(bmp: Bitmap, f: LayerFilter.FftPass): Bitmap {
    var n = 64
    while (n < maxOf(bmp.width, bmp.height) && n < FFT_MAX) n *= 2
    val square = Bitmap.createScaledBitmap(bmp, n, n, true)
    val spectra = forward(square)
    square.recycle()
    val gain = SpectrumMask.build(FftDoc(size = n, filter = f.filter, radius = f.radius, bandWidth = f.band))
    val filtered = inverse(spectra, gain, null)
    val back = Bitmap.createScaledBitmap(filtered, bmp.width, bmp.height, true)
    filtered.recycle()
    // Put the original alpha back: the transform only filters colour.
    val out = back.copy(Bitmap.Config.ARGB_8888, true)
    back.recycle()
    val w = bmp.width
    val h = bmp.height
    val a = IntArray(w * h)
    val c = IntArray(w * h)
    bmp.getPixels(a, 0, w, 0, 0, w, h)
    out.getPixels(c, 0, w, 0, 0, w, h)
    for (i in c.indices) c[i] = (a[i] and 0xFF000000.toInt()) or (c[i] and 0xFFFFFF)
    out.setPixels(c, 0, w, 0, 0, w, h)
    bmp.recycle()
    return out
}

private const val FFT_MAX = 1024
