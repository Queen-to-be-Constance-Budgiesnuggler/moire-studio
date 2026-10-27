package dk.cbavf.studio.blend

import android.graphics.Bitmap
import dk.cbavf.studio.draw.model.Blend

/**
 * Subtract and reverse-subtract blending. Android and Compose have no such blend mode, so layers that use them are
 * combined here, pixel by pixel, on the CPU. The same code serves the screen and the saved file, so they agree.
 *
 * The separable blend function B(d, s) works on straight (not premultiplied) channel values in 0..1, d from the layer
 * below (the backdrop), s from the layer above:
 *   Subtract         B = max(0, d - s)
 *   ReverseSubtract  B = max(0, s - d)
 * and the result is composited the way the W3C compositing spec describes, with the layer's alpha times [opacity]:
 *   aR = a + da (1 - a)
 *   cR = ((1 - a) da d + a ((1 - da) s + da B(d, s))) / aR
 */
object SubtractMath {
    private val SHIFTS = intArrayOf(16, 8, 0)

    /** One pixel: [dst] is the backdrop, [src] the layer above, both straight ARGB. */
    fun blendPixel(dst: Int, src: Int, opacity: Float, reverse: Boolean): Int {
        val a = ((src ushr 24) / 255f) * opacity.coerceIn(0f, 1f)
        if (a <= 0f) return dst
        val da = (dst ushr 24) / 255f
        val aR = a + da * (1f - a)
        if (aR <= 0f) return 0
        var out = (aR * 255f + 0.5f).toInt().coerceIn(0, 255) shl 24
        for (shift in SHIFTS) {
            val d = (dst shr shift and 0xFF) / 255f
            val s = (src shr shift and 0xFF) / 255f
            val b = if (reverse) maxOf(0f, s - d) else maxOf(0f, d - s)
            val c = ((1f - a) * da * d + a * ((1f - da) * s + da * b)) / aR
            out = out or ((c * 255f + 0.5f).toInt().coerceIn(0, 255) shl shift)
        }
        return out
    }

    /** Blends [layer] onto [target] in place. Both must be the same size and ARGB_8888; [target] must be mutable. */
    fun blendOnto(target: Bitmap, layer: Bitmap, opacity: Float, reverse: Boolean) {
        require(target.width == layer.width && target.height == layer.height) { "bitmaps differ in size" }
        val w = target.width
        val d = IntArray(w)
        val s = IntArray(w)
        for (y in 0 until target.height) {
            target.getPixels(d, 0, w, 0, y, w, 1)
            layer.getPixels(s, 0, w, 0, y, w, 1)
            for (x in 0 until w) d[x] = blendPixel(d[x], s[x], opacity, reverse)
            target.setPixels(d, 0, w, 0, y, w, 1)
        }
    }
}

/** True for the blends that [SubtractMath] handles instead of an Android blend mode. */
val Blend.isSubtract: Boolean get() = this == Blend.Subtract || this == Blend.ReverseSubtract
