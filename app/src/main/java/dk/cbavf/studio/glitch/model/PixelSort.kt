package dk.cbavf.studio.glitch.model

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** What pixels are sorted by. */
enum class SortKey { Brightness, Hue, Saturation }

/** Where the sort direction comes from: one angle for the whole picture, or along the edges of the image. */
enum class SortField { Off, Angle, Edges }

/**
 * A direction for every pixel, kept on a coarse grid of [cell]-pixel squares (a full-size field
 * would cost as much memory as the picture). Each direction is a unit vector (dx, dy), y down.
 */
class OrientationField(val cols: Int, val rows: Int, val cell: Int, val dx: FloatArray, val dy: FloatArray) {
    fun index(x: Int, y: Int) = (y / cell).coerceIn(0, rows - 1) * cols + (x / cell).coerceIn(0, cols - 1)

    companion object {
        /** The same direction everywhere: 0° = left to right, 90° = top to bottom. */
        fun constant(degrees: Float): OrientationField {
            val a = degrees * PI / 180
            return OrientationField(1, 1, Int.MAX_VALUE, floatArrayOf(cos(a).toFloat()), floatArrayOf(sin(a).toFloat()))
        }

        /**
         * Directions that follow the edges of [argb] (along lines of equal brightness), from the
         * image's smoothed structure tensor on a grid of about [gridPx] cells along the long side.
         * [smooth] is the blur radius in cells; [degrees] turns every direction, and is used
         * as is where the image is flat and has no edge to follow.
         */
        fun edges(argb: IntArray, w: Int, h: Int, degrees: Float = 0f, smooth: Int = 2, gridPx: Int = 256): OrientationField {
            val cell = ((maxOf(w, h) + gridPx - 1) / gridPx).coerceAtLeast(1)
            val cols = (w + cell - 1) / cell
            val rows = (h + cell - 1) / cell
            // Mean brightness per cell.
            val luma = FloatArray(cols * rows)
            val count = IntArray(cols * rows)
            for (y in 0 until h) for (x in 0 until w) {
                val i = (y / cell) * cols + x / cell
                luma[i] += PixelSort.brightness(argb[y * w + x])
                count[i]++
            }
            for (i in luma.indices) if (count[i] > 0) luma[i] /= count[i]
            // Structure tensor from central differences.
            val jxx = FloatArray(cols * rows)
            val jxy = FloatArray(cols * rows)
            val jyy = FloatArray(cols * rows)
            fun l(x: Int, y: Int) = luma[y.coerceIn(0, rows - 1) * cols + x.coerceIn(0, cols - 1)]
            for (y in 0 until rows) for (x in 0 until cols) {
                val gx = (l(x + 1, y) - l(x - 1, y)) * 0.5f
                val gy = (l(x, y + 1) - l(x, y - 1)) * 0.5f
                val i = y * cols + x
                jxx[i] = gx * gx
                jxy[i] = gx * gy
                jyy[i] = gy * gy
            }
            boxBlur(jxx, cols, rows, smooth)
            boxBlur(jxy, cols, rows, smooth)
            boxBlur(jyy, cols, rows, smooth)
            val turn = degrees * PI / 180
            val dx = FloatArray(cols * rows)
            val dy = FloatArray(cols * rows)
            for (i in dx.indices) {
                val a = jxx[i] - jyy[i]
                val b = 2 * jxy[i]
                // The gradient's orientation, then a quarter turn to run along the edge.
                val angle = if (abs(a) + abs(b) < FLAT) turn else 0.5 * atan2(b.toDouble(), a.toDouble()) + PI / 2 + turn
                dx[i] = cos(angle).toFloat()
                dy[i] = sin(angle).toFloat()
            }
            return OrientationField(cols, rows, cell, dx, dy)
        }

        /** Below this tensor strength (brightness 0..1 per cell, squared) a cell counts as flat. */
        private const val FLAT = 1e-6f

        /** In-place box blur of radius [r], rows then columns, edges clamped. */
        internal fun boxBlur(v: FloatArray, w: Int, h: Int, r: Int) {
            if (r <= 0) return
            val tmp = FloatArray(maxOf(w, h))
            val n = 2 * r + 1
            for (y in 0 until h) {
                var sum = 0f
                for (k in -r..r) sum += v[y * w + k.coerceIn(0, w - 1)]
                for (x in 0 until w) {
                    tmp[x] = sum / n
                    sum += v[y * w + (x + r + 1).coerceAtMost(w - 1)] - v[y * w + (x - r).coerceAtLeast(0)]
                }
                for (x in 0 until w) v[y * w + x] = tmp[x]
            }
            for (x in 0 until w) {
                var sum = 0f
                for (k in -r..r) sum += v[k.coerceIn(0, h - 1) * w + x]
                for (y in 0 until h) {
                    tmp[y] = sum / n
                    sum += v[(y + r + 1).coerceAtMost(h - 1) * w + x] - v[(y - r).coerceAtLeast(0) * w + x]
                }
                for (y in 0 until h) v[y * w + x] = tmp[y]
            }
        }
    }
}

/**
 * Pixel sorting: the picture is cut into lines that follow an [OrientationField] (straight rows
 * for 0°, columns for 90°, curves along the edges otherwise). Along each line, every run of
 * pixels whose key lies in [low]..[high] is sorted, low key first in the line's direction;
 * pixels outside the range stay where they are and break the runs.
 */
object PixelSort {
    fun sort(argb: IntArray, w: Int, h: Int, field: OrientationField, key: SortKey, low: Float, high: Float, checkCancel: () -> Unit = {}): IntArray {
        val out = argb.copyOf()
        val n = w * h
        if (n == 0) return out
        val lo = minOf(low, high).coerceIn(0f, 1f)
        val hi = maxOf(low, high).coerceIn(0f, 1f)
        val visited = BooleanArray(n)
        var path = IntArray(maxOf(w, h) * 2)
        var back = IntArray(64)
        var fwd = IntArray(64)
        var runs = LongArray(64)
        var seeds = 0

        // Walks from pixel [start] along the field (or against it), adding unvisited pixels to [buf];
        // stops at the edge, at a pixel already used, or when the field turns back on itself.
        fun walk(start: Int, sign: Float, bufIn: IntArray, emit: (IntArray, Int) -> Unit) {
            var buf = bufIn
            var len = 0
            var px = (start % w).toFloat()
            var py = (start / w).toFloat()
            var last = start
            var pdx = Float.NaN
            var pdy = 0f
            var stalls = 0
            while (true) {
                val fi = field.index(last % w, last / w)
                var ddx = field.dx[fi] * sign
                var ddy = field.dy[fi] * sign
                // An edge direction has no head or tail: keep heading the way the line was going.
                if (!pdx.isNaN() && ddx * pdx + ddy * pdy < 0) { ddx = -ddx; ddy = -ddy }
                pdx = ddx; pdy = ddy
                px += ddx; py += ddy
                val x = px.roundToInt()
                val y = py.roundToInt()
                if (x !in 0 until w || y !in 0 until h) break
                val i = y * w + x
                if (i == last) {
                    if (++stalls > 2) break
                    continue
                }
                stalls = 0
                if (visited[i]) break
                visited[i] = true
                if (len == buf.size) buf = buf.copyOf(buf.size * 2)
                buf[len++] = i
                last = i
            }
            emit(buf, len)
        }

        for (s in 0 until n) {
            if (visited[s]) continue
            if (++seeds % 256 == 0) checkCancel()
            visited[s] = true
            // Back first (reversed), then the seed, then forward: one whole line through the field.
            var backLen = 0
            walk(s, -1f, back) { b, l -> back = b; backLen = l }
            if (path.size < backLen + 1) path = path.copyOf(backLen + 1 + path.size)
            for (k in 0 until backLen) path[k] = back[backLen - 1 - k]
            path[backLen] = s
            var total = backLen + 1
            walk(s, 1f, fwd) { b, l ->
                fwd = b
                if (path.size < total + l) path = path.copyOf(total + l + path.size)
                System.arraycopy(b, 0, path, total, l)
                total += l
            }
            // Sort each run of in-range pixels along the line.
            var k = 0
            while (k < total) {
                val c = argb[path[k]]
                val v = keyOf(c, key)
                if (v < lo || v > hi) { k++; continue }
                var end = k
                while (end < total && keyOf(argb[path[end]], key).let { it in lo..hi }) end++
                val len = end - k
                if (len > 1) {
                    if (runs.size < len) runs = LongArray(len * 2)
                    // Key in the high bits, colour in the low: sorting the longs sorts by key.
                    for (j in 0 until len) {
                        val p = argb[path[k + j]]
                        runs[j] = (quantise(keyOf(p, key)).toLong() shl 32) or (p.toLong() and 0xFFFFFFFFL)
                    }
                    java.util.Arrays.sort(runs, 0, len)
                    for (j in 0 until len) out[path[k + j]] = runs[j].toInt()
                }
                k = end
            }
        }
        return out
    }

    private fun quantise(v: Float) = (v.coerceIn(0f, 1f) * 65535).toInt()

    fun keyOf(argb: Int, key: SortKey): Float = when (key) {
        SortKey.Brightness -> brightness(argb)
        SortKey.Hue -> hue(argb)
        SortKey.Saturation -> saturation(argb)
    }

    /** BT.601 luma, 0..1. */
    fun brightness(c: Int) = ((0.299f * (c shr 16 and 0xFF) + 0.587f * (c shr 8 and 0xFF) + 0.114f * (c and 0xFF)) / 255f).coerceIn(0f, 1f)

    /** Hue as a fraction of the colour wheel (red 0, green 1/3, blue 2/3); 0 for greys. */
    fun hue(c: Int): Float {
        val r = (c shr 16 and 0xFF) / 255f
        val g = (c shr 8 and 0xFF) / 255f
        val b = (c and 0xFF) / 255f
        val max = maxOf(r, g, b)
        val d = max - minOf(r, g, b)
        if (d <= 0f) return 0f
        val h = when (max) {
            r -> ((g - b) / d).let { if (it < 0) it + 6 else it }
            g -> (b - r) / d + 2
            else -> (r - g) / d + 4
        }
        return (h / 6f).coerceIn(0f, 1f)
    }

    /** HSV saturation, 0..1. */
    fun saturation(c: Int): Float {
        val max = maxOf(c shr 16 and 0xFF, c shr 8 and 0xFF, c and 0xFF)
        val min = minOf(c shr 16 and 0xFF, c shr 8 and 0xFF, c and 0xFF)
        return if (max == 0) 0f else (max - min).toFloat() / max
    }
}
