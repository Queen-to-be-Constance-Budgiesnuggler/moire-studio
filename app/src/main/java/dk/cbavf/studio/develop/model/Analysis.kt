package dk.cbavf.studio.develop.model

import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Image analysis for Develop's deep analysis card: pixel probes, line profiles, region
 * statistics, clipping, waveforms and height maps, all on packed ARGB pixels (w × h).
 */
object Analysis {
    fun r(c: Int) = c shr 16 and 0xFF
    fun g(c: Int) = c shr 8 and 0xFF
    fun b(c: Int) = c and 0xFF
    fun luma(c: Int) = (r(c) * 0.2126f + g(c) * 0.7152f + b(c) * 0.0722f) / 255f

    /** CIE L*a*b* (D65) of an sRGB colour given as 0..255 channels. */
    fun lab(rr: Int, gg: Int, bb: Int): FloatArray {
        fun lin(v: Int): Double { val c = v / 255.0; return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4) }
        val rl = lin(rr); val gl = lin(gg); val bl = lin(bb)
        val x = (0.4124 * rl + 0.3576 * gl + 0.1805 * bl) / 0.95047
        val y = 0.2126 * rl + 0.7152 * gl + 0.0722 * bl
        val z = (0.0193 * rl + 0.1192 * gl + 0.9505 * bl) / 1.08883
        fun f(t: Double) = if (t > 0.008856) cbrt(t) else 7.787 * t + 16.0 / 116
        return floatArrayOf((116 * f(y) - 16).toFloat(), (500 * (f(x) - f(y))).toFloat(), (200 * (f(y) - f(z))).toFloat())
    }

    /** [n] samples of the pixels along the line (x0,y0)–(x1,y1), nearest pixel each. */
    fun lineProfile(px: IntArray, w: Int, h: Int, x0: Float, y0: Float, x1: Float, y1: Float, n: Int = 256): IntArray =
        IntArray(n) { i ->
            val t = if (n == 1) 0f else i / (n - 1f)
            val x = (x0 + (x1 - x0) * t).toInt().coerceIn(0, w - 1)
            val y = (y0 + (y1 - y0) * t).toInt().coerceIn(0, h - 1)
            px[y * w + x]
        }

    /** Statistics of a region: per channel (R, G, B, luma) mean, standard deviation, min, max, median. */
    class Stats(
        val count: Int,
        val mean: FloatArray, val std: FloatArray, val min: IntArray, val max: IntArray, val median: IntArray,
        /** Fraction of pixels with a channel at 254+ (highlights) or every channel at 1 or less (shadows). */
        val clippedHigh: Float, val clippedLow: Float,
        /** Distinct colours at 5 bits a channel, and the brightness entropy in bits (0–8). */
        val colours: Int, val entropy: Float,
    )

    fun regionStats(px: IntArray, w: Int, h: Int, x0: Int, y0: Int, x1: Int, y1: Int): Stats {
        val l = min(x0, x1).coerceIn(0, w - 1); val r = max(x0, x1).coerceIn(0, w - 1)
        val t = min(y0, y1).coerceIn(0, h - 1); val bt = max(y0, y1).coerceIn(0, h - 1)
        val hist = Array(4) { IntArray(256) }
        val sum = DoubleArray(4); val sq = DoubleArray(4)
        var high = 0; var low = 0; var n = 0
        val seen = java.util.BitSet(32768)
        for (y in t..bt) for (x in l..r) {
            val c = px[y * w + x]
            val ch = intArrayOf(r(c), g(c), b(c), (luma(c) * 255 + 0.5f).toInt())
            for (k in 0..3) { hist[k][ch[k]]++; sum[k] += ch[k].toDouble(); sq[k] += ch[k].toDouble() * ch[k] }
            if (ch[0] >= 254 || ch[1] >= 254 || ch[2] >= 254) high++
            if (ch[0] <= 1 && ch[1] <= 1 && ch[2] <= 1) low++
            seen.set((ch[0] shr 3 shl 10) or (ch[1] shr 3 shl 5) or (ch[2] shr 3))
            n++
        }
        val cnt = n.coerceAtLeast(1)
        fun pick(k: Int, rank: Int): Int { var acc = 0; for (v in 0..255) { acc += hist[k][v]; if (acc > rank) return v }; return 255 }
        var entropy = 0.0
        for (v in 0..255) { val p = hist[3][v].toDouble() / cnt; if (p > 0) entropy -= p * ln(p) / ln(2.0) }
        return Stats(
            n,
            FloatArray(4) { (sum[it] / cnt).toFloat() },
            FloatArray(4) { sqrt(max(0.0, sq[it] / cnt - (sum[it] / cnt).pow(2))).toFloat() },
            IntArray(4) { k -> (0..255).firstOrNull { hist[k][it] > 0 } ?: 0 },
            IntArray(4) { k -> (255 downTo 0).firstOrNull { hist[k][it] > 0 } ?: 0 },
            IntArray(4) { k -> pick(k, cnt / 2) },
            high.toFloat() / cnt, low.toFloat() / cnt, seen.cardinality(), entropy.toFloat(),
        )
    }

    /**
     * A luma waveform: for each of [cols] columns, how many pixels sit at each of [bins]
     * brightness levels (bin 0 = black). Row-major [cols] × [bins].
     */
    fun waveform(px: IntArray, w: Int, h: Int, cols: Int, bins: Int, channel: Int = 3): IntArray {
        val out = IntArray(cols * bins)
        for (y in 0 until h) for (x in 0 until w) {
            val c = px[y * w + x]
            val v = when (channel) { 0 -> r(c) / 255f; 1 -> g(c) / 255f; 2 -> b(c) / 255f; else -> luma(c) }
            val col = (x * cols / w).coerceIn(0, cols - 1)
            val bin = (v * (bins - 1) + 0.5f).toInt().coerceIn(0, bins - 1)
            out[col * bins + bin]++
        }
        return out
    }

    /** A height map: brightness (0..1) and colour averaged over a grid at most [n] cells on the long side. */
    class Heights(val gw: Int, val gh: Int, val height: FloatArray, val colour: IntArray)

    /** With a [space], the height is that space's [channel] of each cell's mean colour instead of brightness. */
    fun heights(px: IntArray, w: Int, h: Int, n: Int = 48, space: dk.cbavf.studio.channels.ColorSpace? = null, channel: Int = 0): Heights {
        val gw = if (w >= h) n else max(2, n * w / h)
        val gh = if (h >= w) n else max(2, n * h / w)
        val sum = DoubleArray(gw * gh); val sr = IntArray(gw * gh); val sg = IntArray(gw * gh); val sb = IntArray(gw * gh); val cnt = IntArray(gw * gh)
        for (y in 0 until h) for (x in 0 until w) {
            val i = (y * gh / h).coerceIn(0, gh - 1) * gw + (x * gw / w).coerceIn(0, gw - 1)
            val c = px[y * w + x]
            sum[i] += luma(c).toDouble(); sr[i] += r(c); sg[i] += g(c); sb[i] += b(c); cnt[i]++
        }
        val colour = IntArray(gw * gh) { val k = cnt[it].coerceAtLeast(1); (0xFF shl 24) or (sr[it] / k shl 16) or (sg[it] / k shl 8) or (sb[it] / k) }
        val height = if (space == null) FloatArray(gw * gh) { (sum[it] / cnt[it].coerceAtLeast(1)).toFloat() }
        else FloatArray(gw * gh) { dk.cbavf.studio.channels.ChannelValue.normalised(space, channel, colour[it]) }
        return Heights(gw, gh, height, colour)
    }

    /**
     * A grid point (gx, gy) at height [z] (0..1) turned by [yawDeg] around the vertical and tilted
     * by [pitchDeg], projected orthographically: screen x, screen y (up is negative) and depth.
     * The grid spans −1..1 along its long side.
     */
    fun project(gx: Float, gy: Float, z: Float, gw: Int, gh: Int, yawDeg: Float, pitchDeg: Float, heightScale: Float): FloatArray {
        val long = max(gw, gh) - 1f
        val x = (gx - (gw - 1) / 2f) / long * 2f
        val y = (gy - (gh - 1) / 2f) / long * 2f
        val zz = z * heightScale
        val yaw = Math.toRadians(yawDeg.toDouble())
        val pitch = Math.toRadians(pitchDeg.toDouble())
        val rx = (x * cos(yaw) - y * sin(yaw)).toFloat()
        val ry = (x * sin(yaw) + y * cos(yaw)).toFloat()
        // Tilt: the far side (ry > 0) rises up the screen, height lifts towards the viewer.
        val sy = (ry * sin(pitch) - zz * cos(pitch)).toFloat()
        val depth = (ry * cos(pitch) + zz * sin(pitch)).toFloat()
        return floatArrayOf(rx, sy, depth)
    }
}
