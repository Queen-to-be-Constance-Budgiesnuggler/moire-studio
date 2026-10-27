package dk.cbavf.studio.develop.model

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Artistic styles for Develop's Style tab, in the spirit of the style filters in gallery apps, made with
 * classic image processing (no network, no model). Each works on packed ARGB pixels and keeps
 * alpha. Sizes in pixels are given for a ~1000 px image and scaled with the image.
 */
enum class ArtStyle(val label: String) {
    BlueInk("Blue ink"),
    Pencil("Pencil"),
    Charcoal("Charcoal"),
    Marker("Marker"),
    Watercolor("Watercolour"),
    Oil("Oil paint"),
    Comic("Comic"),
    Halftone("Halftone"),
    Engraving("Engraving"),
    Neon("Neon"),
}

object ArtStyles {
    /** [style] applied to [argb] (w × h); a new array, the input is untouched. */
    fun apply(style: ArtStyle, argb: IntArray, w: Int, h: Int, checkCancel: () -> Unit = {}): IntArray {
        val k = max(w, h) / 1000f
        return when (style) {
            ArtStyle.Pencil -> pencil(argb, w, h, k)
            ArtStyle.BlueInk -> blueInk(argb, w, h, k)
            ArtStyle.Charcoal -> charcoal(argb, w, h, k)
            ArtStyle.Marker -> marker(argb, w, h, k, checkCancel)
            ArtStyle.Watercolor -> watercolor(argb, w, h, k, checkCancel)
            ArtStyle.Oil -> kuwahara(argb, w, h, (5 * k).toInt().coerceAtLeast(2), checkCancel)
            ArtStyle.Comic -> comic(argb, w, h, k, checkCancel)
            ArtStyle.Halftone -> halftone(argb, w, h, k)
            ArtStyle.Engraving -> engraving(argb, w, h, k)
            ArtStyle.Neon -> neon(argb, w, h, k)
        }
    }

    // --- Building blocks ---------------------------------------------------------------------

    private fun r(c: Int) = c shr 16 and 0xFF
    private fun g(c: Int) = c shr 8 and 0xFF
    private fun b(c: Int) = c and 0xFF
    private fun pack(a: Int, r: Int, g: Int, b: Int) =
        (a shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

    fun luma(argb: IntArray): IntArray = IntArray(argb.size) { i ->
        val c = argb[i]
        (r(c) * 54 + g(c) * 183 + b(c) * 19) shr 8
    }

    /** Box blur of a single channel, radius [rad], by running sums (two passes). */
    fun boxBlur(v: IntArray, w: Int, h: Int, rad: Int): IntArray {
        if (rad <= 0) return v.copyOf()
        val tmp = IntArray(v.size)
        val out = IntArray(v.size)
        for (y in 0 until h) {
            var sum = 0
            val row = y * w
            for (x in -rad..rad) sum += v[row + x.coerceIn(0, w - 1)]
            for (x in 0 until w) {
                tmp[row + x] = sum / (2 * rad + 1)
                sum += v[row + (x + rad + 1).coerceAtMost(w - 1)] - v[row + (x - rad).coerceAtLeast(0)]
            }
        }
        for (x in 0 until w) {
            var sum = 0
            for (y in -rad..rad) sum += tmp[y.coerceIn(0, h - 1) * w + x]
            for (y in 0 until h) {
                out[y * w + x] = sum / (2 * rad + 1)
                sum += tmp[(y + rad + 1).coerceAtMost(h - 1) * w + x] - tmp[(y - rad).coerceAtLeast(0) * w + x]
            }
        }
        return out
    }

    /** Sobel gradient magnitude of a channel, 0..255. */
    fun edges(l: IntArray, w: Int, h: Int): IntArray {
        val out = IntArray(l.size)
        for (y in 0 until h) for (x in 0 until w) {
            fun p(dx: Int, dy: Int) = l[(y + dy).coerceIn(0, h - 1) * w + (x + dx).coerceIn(0, w - 1)]
            val gx = -p(-1, -1) - 2 * p(-1, 0) - p(-1, 1) + p(1, -1) + 2 * p(1, 0) + p(1, 1)
            val gy = -p(-1, -1) - 2 * p(0, -1) - p(1, -1) + p(-1, 1) + 2 * p(0, 1) + p(1, 1)
            out[y * w + x] = min(255, (sqrt((gx * gx + gy * gy).toFloat()) / 4).toInt())
        }
        return out
    }

    /** Colour dodge of the grey image by its blurred negative: the classic pencil sketch. */
    private fun sketch(l: IntArray, w: Int, h: Int, rad: Int): IntArray {
        val blurredInv = boxBlur(IntArray(l.size) { 255 - l[it] }, w, h, rad)
        return IntArray(l.size) { i ->
            val d = 255 - blurredInv[i]
            if (d <= 0) 255 else min(255, l[i] * 255 / d)
        }
    }

    /**
     * Kuwahara filter: each pixel takes the mean colour of the least varied of the four
     * squares meeting at it, so areas flatten into paint strokes while edges stay.
     */
    fun kuwahara(argb: IntArray, w: Int, h: Int, rad: Int, checkCancel: () -> Unit = {}): IntArray {
        val n = (w + 1) * (h + 1)
        val sr = IntArray(n); val sg = IntArray(n); val sb = IntArray(n)
        val sl = LongArray(n); val sl2 = LongArray(n)
        for (y in 0 until h) {
            var ar = 0; var ag = 0; var ab = 0; var al = 0L; var al2 = 0L
            for (x in 0 until w) {
                val c = argb[y * w + x]
                val l = (r(c) * 54 + g(c) * 183 + b(c) * 19) shr 8
                ar += r(c); ag += g(c); ab += b(c); al += l; al2 += (l * l).toLong()
                val i = (y + 1) * (w + 1) + x + 1
                val up = y * (w + 1) + x + 1
                sr[i] = sr[up] + ar; sg[i] = sg[up] + ag; sb[i] = sb[up] + ab
                sl[i] = sl[up] + al; sl2[i] = sl2[up] + al2
            }
        }
        val out = IntArray(argb.size)
        for (y in 0 until h) {
            if (y % 64 == 0) checkCancel()
            for (x in 0 until w) {
                var best = Long.MAX_VALUE
                var br = 0; var bg = 0; var bb = 0
                for (q in 0 until 4) {
                    val x0 = (if (q % 2 == 0) x - rad else x).coerceAtLeast(0)
                    val x1 = (if (q % 2 == 0) x else x + rad).coerceAtMost(w - 1)
                    val y0 = (if (q < 2) y - rad else y).coerceAtLeast(0)
                    val y1 = (if (q < 2) y else y + rad).coerceAtMost(h - 1)
                    val a = y0 * (w + 1) + x0
                    val bIdx = y0 * (w + 1) + x1 + 1
                    val c = (y1 + 1) * (w + 1) + x0
                    val d = (y1 + 1) * (w + 1) + x1 + 1
                    val cnt = (x1 - x0 + 1) * (y1 - y0 + 1)
                    val s = sl[d] - sl[bIdx] - sl[c] + sl[a]
                    val s2 = sl2[d] - sl2[bIdx] - sl2[c] + sl2[a]
                    val variance = s2 * cnt - s * s
                    if (variance < best) {
                        best = variance
                        br = (sr[d] - sr[bIdx] - sr[c] + sr[a]) / cnt
                        bg = (sg[d] - sg[bIdx] - sg[c] + sg[a]) / cnt
                        bb = (sb[d] - sb[bIdx] - sb[c] + sb[a]) / cnt
                    }
                }
                out[y * w + x] = pack(argb[y * w + x] ushr 24, br, bg, bb)
            }
        }
        return out
    }

    private fun posterize(v: Int, levels: Int): Int {
        val step = 255f / (levels - 1)
        return (Math.round(v / step) * step).toInt()
    }

    private fun saturate(c: Int, k: Float): Int {
        val l = (r(c) * 54 + g(c) * 183 + b(c) * 19) shr 8
        return pack(c ushr 24, (l + (r(c) - l) * k).toInt(), (l + (g(c) - l) * k).toInt(), (l + (b(c) - l) * k).toInt())
    }

    /** A cheap deterministic grain, −1..1, for paper and charcoal texture. */
    private fun grain(x: Int, y: Int): Float {
        var n = x * 374761393 + y * 668265263
        n = (n xor (n ushr 13)) * 1274126177
        return ((n xor (n ushr 16)) and 0xFFFF) / 32767.5f - 1f
    }

    // --- Styles --------------------------------------------------------------------------------

    private fun pencil(argb: IntArray, w: Int, h: Int, k: Float): IntArray {
        val s = sketch(luma(argb), w, h, (8 * k).toInt().coerceAtLeast(2))
        return IntArray(argb.size) { i -> pack(argb[i] ushr 24, s[i], s[i], s[i]) }
    }

    /** Pen sketch plus cross-hatching where it is dark, in blue ink on blue-grey paper. */
    private fun blueInk(argb: IntArray, w: Int, h: Int, k: Float): IntArray {
        val l = luma(argb)
        val s = sketch(l, w, h, (6 * k).toInt().coerceAtLeast(2))
        val tone = boxBlur(l, w, h, (3 * k).toInt().coerceAtLeast(1))
        val period = (7 * k).coerceAtLeast(3f)
        val paper = intArrayOf(222, 226, 236)
        val ink = intArrayOf(36, 44, 92)
        return IntArray(argb.size) { i ->
            val x = i % w
            val y = i / w
            val dark = 1f - tone[i] / 255f
            // Hatching: one direction from mid-tones, the cross direction in the shadows.
            fun line(u: Float) = (u / period - kotlin.math.floor(u / period)) < 0.28f
            var inkAmount = 1f - s[i] / 255f
            if (dark > 0.35f && line(x + y.toFloat())) inkAmount = max(inkAmount, (dark - 0.35f) * 1.6f)
            if (dark > 0.6f && line(x - y.toFloat() + 10000)) inkAmount = max(inkAmount, (dark - 0.6f) * 2.2f)
            val a = (inkAmount * 1.4f).coerceIn(0f, 1f)
            val p = 1f + grain(x, y) * 0.02f
            pack(argb[i] ushr 24,
                ((paper[0] * p) + (ink[0] - paper[0]) * a).toInt(),
                ((paper[1] * p) + (ink[1] - paper[1]) * a).toInt(),
                ((paper[2] * p) + (ink[2] - paper[2]) * a).toInt())
        }
    }

    private fun charcoal(argb: IntArray, w: Int, h: Int, k: Float): IntArray {
        val l = boxBlur(luma(argb), w, h, (1.5f * k).toInt().coerceAtLeast(1))
        val e = edges(l, w, h)
        return IntArray(argb.size) { i ->
            val x = i % w
            val y = i / w
            val v = (l[i] * 0.85f + 30 - e[i] * 1.8f + grain(x, y) * 18).toInt()
            pack(argb[i] ushr 24, v, v, v)
        }
    }

    /** Flat felt-tip colour: smoothed, posterized, saturated, with dark outlines. */
    private fun marker(argb: IntArray, w: Int, h: Int, k: Float, checkCancel: () -> Unit): IntArray {
        val smooth = kuwahara(argb, w, h, (3 * k).toInt().coerceAtLeast(2), checkCancel)
        val e = edges(luma(smooth), w, h)
        return IntArray(argb.size) { i ->
            val c = saturate(smooth[i], 1.45f)
            val ink = (e[i] / 90f).coerceIn(0f, 1f)
            pack(c ushr 24,
                (posterize(r(c), 6) * (1 - ink * 0.85f)).toInt(),
                (posterize(g(c), 6) * (1 - ink * 0.85f)).toInt(),
                (posterize(b(c), 6) * (1 - ink * 0.85f)).toInt())
        }
    }

    /** Soft washes: smoothed and lightened, darker at the edges where the wash dries, on grainy paper. */
    private fun watercolor(argb: IntArray, w: Int, h: Int, k: Float, checkCancel: () -> Unit): IntArray {
        val smooth = kuwahara(argb, w, h, (4 * k).toInt().coerceAtLeast(2), checkCancel)
        val e = edges(luma(smooth), w, h)
        return IntArray(argb.size) { i ->
            val c = smooth[i]
            val x = i % w
            val y = i / w
            val wash = 0.82f
            val edge = (e[i] / 160f).coerceIn(0f, 0.35f)
            val paper = 1f + grain(x / 2, y / 2) * 0.04f
            fun ch(v: Int) = ((255 - (255 - posterize(v, 10)) * wash) * (1 - edge) * paper).toInt()
            pack(c ushr 24, ch(r(c)), ch(g(c)), ch(b(c)))
        }
    }

    /** Posterized colour with bold black outlines. */
    private fun comic(argb: IntArray, w: Int, h: Int, k: Float, checkCancel: () -> Unit): IntArray {
        val smooth = kuwahara(argb, w, h, (2 * k).toInt().coerceAtLeast(1), checkCancel)
        val e = boxBlur(edges(luma(smooth), w, h), w, h, (1 * k).toInt().coerceAtLeast(1))
        return IntArray(argb.size) { i ->
            val c = saturate(smooth[i], 1.3f)
            if (e[i] > 40) pack(c ushr 24, 15, 15, 20)
            else pack(c ushr 24, posterize(r(c), 4), posterize(g(c), 4), posterize(b(c), 4))
        }
    }

    /** Black dots on a 45° grid, bigger where it is darker (a newspaper halftone screen). */
    private fun halftone(argb: IntArray, w: Int, h: Int, k: Float): IntArray {
        val cell = (9 * k).coerceAtLeast(4f)
        val l = boxBlur(luma(argb), w, h, (cell / 2).toInt())
        val c45 = cos(Math.PI / 4).toFloat()
        val s45 = sin(Math.PI / 4).toFloat()
        return IntArray(argb.size) { i ->
            val x = i % w
            val y = i / w
            val u = (x * c45 + y * s45) / cell
            val v = (-x * s45 + y * c45) / cell
            val du = u - kotlin.math.floor(u) - 0.5f
            val dv = v - kotlin.math.floor(v) - 0.5f
            val radius = sqrt(1f - l[i] / 255f) * 0.62f
            val inside = sqrt(du * du + dv * dv) < radius
            if (inside) pack(argb[i] ushr 24, 20, 20, 24) else pack(argb[i] ushr 24, 246, 244, 236)
        }
    }

    /** Horizontal engraved lines, thicker where it is darker (a line screen: moiré's cousin). */
    private fun engraving(argb: IntArray, w: Int, h: Int, k: Float): IntArray {
        val period = (6 * k).coerceAtLeast(3f)
        val l = boxBlur(luma(argb), w, h, (1 * k).toInt().coerceAtLeast(1))
        return IntArray(argb.size) { i ->
            val x = i % w
            val y = i / w
            // Lines bend slightly with the tone, as a burin follows the form.
            val phase = (y + l[i] / 255f * period * 0.5f + sin(x / (40f * k)) * period * 0.3f) / period
            val f = abs(phase - kotlin.math.floor(phase) - 0.5f) * 2f // 0 at the line centre
            val thick = 1f - l[i] / 255f
            if (f < thick) pack(argb[i] ushr 24, 28, 24, 22) else pack(argb[i] ushr 24, 240, 234, 220)
        }
    }

    /** Glowing edges in the image's own colours on black. */
    private fun neon(argb: IntArray, w: Int, h: Int, k: Float): IntArray {
        val e = edges(luma(argb), w, h)
        val glow = boxBlur(e, w, h, (4 * k).toInt().coerceAtLeast(2))
        return IntArray(argb.size) { i ->
            val c = saturate(argb[i], 2f)
            val a = ((e[i] * 2 + glow[i] * 3) / 255f).coerceIn(0f, 1.6f)
            pack(argb[i] ushr 24, (r(c) * a).toInt(), (g(c) * a).toInt(), (b(c) * a).toInt())
        }
    }
}
