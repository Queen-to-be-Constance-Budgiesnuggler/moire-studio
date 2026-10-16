package dk.cbavf.studio.sequence.export

import java.io.OutputStream

/**
 * Animated GIF writer with one global palette and no dithering: every pixel maps to its exact
 * colour (or the nearest palette entry), so line patterns survive as drawn.
 *
 * Each frame after the first is cropped to the rectangle that changed and drawn over the
 * previous one ("do not dispose"), which keeps moiré animations with a moving layer small.
 */
class GifEncoder(
    private val out: OutputStream,
    private val width: Int,
    private val height: Int,
    palette: IntArray,
    loop: Boolean,
) {
    private val bits = paletteBits(palette.size)
    private var previous: ByteArray? = null

    init {
        require(palette.size in 1..256)
        out.write("GIF89a".toByteArray())
        short(width)
        short(height)
        out.write(0x80 or ((bits - 1) shl 4) or (bits - 1)) // global table, colour resolution, size
        out.write(0) // background index
        out.write(0) // aspect
        for (i in 0 until (1 shl bits)) {
            val c = palette.getOrElse(i) { 0 }
            out.write(c shr 16 and 0xFF)
            out.write(c shr 8 and 0xFF)
            out.write(c and 0xFF)
        }
        if (loop) {
            out.write(byteArrayOf(0x21, 0xFF.toByte(), 11))
            out.write("NETSCAPE2.0".toByteArray())
            out.write(byteArrayOf(3, 1, 0, 0, 0)) // loop forever
        }
    }

    /** Adds a frame of palette indices (row-major, width × height) shown for [delayCs] centiseconds. */
    fun addFrame(indices: ByteArray, delayCs: Int) {
        require(indices.size == width * height)
        val rect = previous?.let { changedRect(it, indices) } ?: intArrayOf(0, 0, width, height)
        // Nothing changed: still needs a frame for the delay; a 1×1 redraw is the smallest.
        val (x0, y0, x1, y1) = if (rect[2] <= rect[0]) intArrayOf(0, 0, 1, 1) else rect
        val w = x1 - x0
        val h = y1 - y0

        // Graphic control extension: disposal 1 (leave in place), no transparency.
        out.write(byteArrayOf(0x21, 0xF9.toByte(), 4, (1 shl 2).toByte()))
        short(delayCs.coerceIn(0, 0xFFFF))
        out.write(0)
        out.write(0)

        out.write(0x2C)
        short(x0)
        short(y0)
        short(w)
        short(h)
        out.write(0) // no local table, not interlaced

        val sub = if (w == width && h == height) indices else ByteArray(w * h).also { s ->
            for (y in 0 until h) System.arraycopy(indices, (y0 + y) * width + x0, s, y * w, w)
        }
        LzwEncoder(maxOf(2, bits)).encode(sub, out)
        previous = indices.copyOf()
    }

    fun finish() {
        out.write(0x3B)
        out.flush()
    }

    /** [x0, y0, x1, y1) of the pixels that differ; empty (x1 <= x0) when none do. */
    private fun changedRect(a: ByteArray, b: ByteArray): IntArray {
        var x0 = width
        var y0 = height
        var x1 = 0
        var y1 = 0
        for (y in 0 until height) {
            val row = y * width
            var first = -1
            var last = -1
            for (x in 0 until width) {
                if (a[row + x] != b[row + x]) {
                    if (first < 0) first = x
                    last = x
                }
            }
            if (first >= 0) {
                if (first < x0) x0 = first
                if (last + 1 > x1) x1 = last + 1
                if (y < y0) y0 = y
                y1 = y + 1
            }
        }
        return intArrayOf(x0, y0, x1, y1)
    }

    private fun short(v: Int) {
        out.write(v and 0xFF)
        out.write(v shr 8 and 0xFF)
    }

    companion object {
        /** Bits per index for a palette of [size] colours (GIF tables hold 2..256 entries). */
        fun paletteBits(size: Int): Int {
            var b = 1
            while ((1 shl b) < size) b++
            return b
        }
    }
}

/**
 * GIF variable-width LZW (codes up to 12 bits), with the classic open-addressing hash table so
 * the dictionary needs no allocation per code.
 */
internal class LzwEncoder(private val minCodeSize: Int) {
    private val clearCode = 1 shl minCodeSize
    private val eoiCode = clearCode + 1
    private val htab = IntArray(HSIZE)
    private val codetab = IntArray(HSIZE)
    private var nextCode = 0
    private var codeBits = 0
    private var maxCode = 0
    private var clearFlag = false

    private var accum = 0
    private var accumBits = 0
    private val block = ByteArray(255)
    private var blockLen = 0
    private lateinit var out: OutputStream

    fun encode(pixels: ByteArray, out: OutputStream) {
        this.out = out
        out.write(minCodeSize)
        codeBits = minCodeSize + 1
        maxCode = (1 shl codeBits) - 1
        nextCode = clearCode + 2
        htab.fill(-1)
        output(clearCode)

        var ent = pixels[0].toInt() and 0xFF
        var p = 1
        outer@ while (p < pixels.size) {
            val c = pixels[p++].toInt() and 0xFF
            val fcode = (c shl MAX_BITS) + ent
            var i = (c shl HSHIFT) xor ent
            if (htab[i] == fcode) {
                ent = codetab[i]
                continue
            }
            if (htab[i] >= 0) {
                val disp = if (i == 0) 1 else HSIZE - i
                do {
                    i -= disp
                    if (i < 0) i += HSIZE
                    if (htab[i] == fcode) {
                        ent = codetab[i]
                        continue@outer
                    }
                } while (htab[i] >= 0)
            }
            output(ent)
            ent = c
            if (nextCode < MAX_CODES) {
                codetab[i] = nextCode++
                htab[i] = fcode
            } else {
                htab.fill(-1)
                nextCode = clearCode + 2
                clearFlag = true
                output(clearCode)
            }
        }
        output(ent)
        output(eoiCode)
        flushBits()
        out.write(0) // block terminator
    }

    private fun output(code: Int) {
        accum = accum or (code shl accumBits)
        accumBits += codeBits
        while (accumBits >= 8) {
            byte(accum and 0xFF)
            accum = accum ushr 8
            accumBits -= 8
        }
        // The decoder's dictionary lags one code behind, so widen once the next code no longer fits.
        if (nextCode > maxCode || clearFlag) {
            if (clearFlag) {
                codeBits = minCodeSize + 1
                clearFlag = false
            } else {
                codeBits++
            }
            maxCode = if (codeBits == MAX_BITS) MAX_CODES else (1 shl codeBits) - 1
        }
    }

    private fun flushBits() {
        while (accumBits > 0) {
            byte(accum and 0xFF)
            accum = accum ushr 8
            accumBits -= 8
        }
        accumBits = 0
        accum = 0
        if (blockLen > 0) {
            out.write(blockLen)
            out.write(block, 0, blockLen)
            blockLen = 0
        }
    }

    private fun byte(b: Int) {
        block[blockLen++] = b.toByte()
        if (blockLen == 255) {
            out.write(255)
            out.write(block)
            blockLen = 0
        }
    }

    private companion object {
        const val MAX_BITS = 12
        const val MAX_CODES = 1 shl MAX_BITS
        const val HSIZE = 5003
        const val HSHIFT = 4
    }
}

/**
 * Builds a ≤256-colour palette from sampled pixels: exact when there are few enough colours
 * (the usual case for line art), otherwise the most common 5-bit-per-channel buckets.
 */
class PaletteBuilder {
    private val exact = HashSet<Int>()
    private var overflow = false
    private val buckets = HashMap<Int, LongArray>() // key → [count, r, g, b]

    fun add(argb: IntArray) {
        var last = 0
        var first = true
        for (px in argb) {
            val c = px and 0xFFFFFF
            if (!first && c == last) continue
            first = false
            last = c
            if (!overflow) {
                exact += c
                if (exact.size > 256) overflow = true
            }
            val key = (c shr 19 and 0x1F shl 10) or (c shr 11 and 0x1F shl 5) or (c shr 3 and 0x1F)
            val b = buckets.getOrPut(key) { LongArray(4) }
            b[0]++
            b[1] += (c shr 16 and 0xFF).toLong()
            b[2] += (c shr 8 and 0xFF).toLong()
            b[3] += (c and 0xFF).toLong()
        }
    }

    /** Adds black, used by black-frame insertion. */
    fun addBlack() = add(intArrayOf(0))

    fun build(): IntArray {
        if (!overflow) return if (exact.isEmpty()) intArrayOf(0) else exact.sorted().toIntArray()
        return buckets.values.sortedByDescending { it[0] }.take(256).map { b ->
            val n = b[0]
            ((b[1] / n).toInt() shl 16) or ((b[2] / n).toInt() shl 8) or (b[3] / n).toInt()
        }.toIntArray()
    }
}

/** Maps ARGB pixels to palette indices: exact hits first, nearest colour (cached) otherwise. */
class PaletteMapper(private val palette: IntArray) {
    private val cache = HashMap<Int, Byte>().apply {
        palette.forEachIndexed { i, c -> put(c, i.toByte()) }
    }

    fun map(argb: IntArray, out: ByteArray) {
        var lastC = -1
        var lastI: Byte = 0
        for (i in argb.indices) {
            val c = argb[i] and 0xFFFFFF
            if (c != lastC) {
                lastC = c
                lastI = cache.getOrPut(c) { nearest(c) }
            }
            out[i] = lastI
        }
    }

    private fun nearest(c: Int): Byte {
        val r = c shr 16 and 0xFF
        val g = c shr 8 and 0xFF
        val b = c and 0xFF
        var best = 0
        var bestD = Int.MAX_VALUE
        palette.forEachIndexed { i, p ->
            val dr = (p shr 16 and 0xFF) - r
            val dg = (p shr 8 and 0xFF) - g
            val db = (p and 0xFF) - b
            val d = dr * dr + dg * dg + db * db
            if (d < bestD) {
                bestD = d
                best = i
            }
        }
        return best.toByte()
    }
}
