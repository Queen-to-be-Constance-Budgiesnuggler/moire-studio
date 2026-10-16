package dk.cbavf.studio.glitch.model

import kotlinx.serialization.Serializable
import kotlin.random.Random

/** The order the three colour bytes are read in. */
enum class ChannelOrder { RGB, RBG, GRB, GBR, BRG, BGR }

/** What a plane fill does to its byte range. */
enum class FillMode { None, Zero, Repeat, Invert, Max }

/**
 * A Glitch lab edit; also the recipe of its output. Works on the raw bytes of the decoded image
 * (RGBA, row after row), never on floats: the point is to misread real data.
 */
@Serializable
data class GlitchDoc(
    val sourceAssetId: String? = null,
    /** How many bytes are read per pixel: 4 = as stored, 3 = packed RGB, 2 = RGB565, 1 = grey. */
    val bytesPerPixel: Int = 4,
    /** Pixels added to (or taken from) the row width when reading back. */
    val widthDelta: Int = 0,
    /** Extra bytes skipped (or re-read, if negative) after every row. */
    val strideDelta: Int = 0,
    /** Bytes skipped at the start; the image wraps around. */
    val offset: Int = 0,
    val channels: ChannelOrder = ChannelOrder.RGB,
    /** Chroma planes moved against luma, in pixels. */
    val chromaX: Int = 0,
    val chromaY: Int = 0,
    val fill: FillMode = FillMode.None,
    /** Fill range as fractions of the byte stream. */
    val fillStart: Float = 0.4f,
    val fillLength: Float = 0.05f,
    /** JPEG re-encode generations after everything else, with optional corruption. */
    val generations: Int = 0,
    val quality: Int = 60,
    val corruptBytes: Int = 0,
    val seed: Int = 1,
) {
    val summary: String
        get() = buildList {
            if (bytesPerPixel != 4) add("$bytesPerPixel bpp")
            if (widthDelta != 0) add("width ${signed(widthDelta)}")
            if (strideDelta != 0) add("stride ${signed(strideDelta)}")
            if (offset != 0) add("offset $offset")
            if (channels != ChannelOrder.RGB) add(channels.name)
            if (chromaX != 0 || chromaY != 0) add("chroma $chromaX,$chromaY")
            if (fill != FillMode.None) add("${fill.name.lowercase()} fill")
            if (generations > 0) add("$generations× JPEG q$quality" + if (corruptBytes > 0) " +$corruptBytes bytes" else "")
        }.ifEmpty { listOf("no glitch") }.joinToString(" · ")

    private fun signed(v: Int) = if (v > 0) "+$v" else "$v"
}

/** Parameters the sweep wall can vary. */
enum class SweepParam(val label: String) {
    Width("Width"), Stride("Stride"), Offset("Offset"), ChromaX("Chroma X"), Bpp("Bytes/pixel"), Corrupt("Corruption seed");

    /** [count] variants of [doc] spread around its current value of this parameter. */
    fun variants(doc: GlitchDoc, count: Int, step: Int): List<GlitchDoc> = List(count) { i ->
        val k = i - count / 2
        when (this) {
            Width -> doc.copy(widthDelta = doc.widthDelta + k * step)
            Stride -> doc.copy(strideDelta = doc.strideDelta + k * step)
            Offset -> doc.copy(offset = (doc.offset + k * step * 97).coerceAtLeast(0))
            ChromaX -> doc.copy(chromaX = doc.chromaX + k * step)
            Bpp -> doc.copy(bytesPerPixel = listOf(1, 2, 3, 4)[i % 4])
            Corrupt -> doc.copy(seed = doc.seed + i, generations = doc.generations.coerceAtLeast(1), corruptBytes = doc.corruptBytes.coerceAtLeast(8))
        }
    }
}

/** A reinterpreted image: ARGB pixels and their size. */
class Pixels(val argb: IntArray, val width: Int, val height: Int)

object Glitch {
    /**
     * Runs the byte stages on [rgba] (width × height × 4, as decoded): plane fill, then reading
     * back with the doc's bytes per pixel, width, stride and offset, then channel order and
     * chroma shift. The output keeps at most the input's pixel count.
     */
    fun apply(rgba: ByteArray, width: Int, height: Int, doc: GlitchDoc, checkCancel: () -> Unit = {}): Pixels {
        // Only a fill writes to the bytes; otherwise read the caller's array as is.
        val bytes = if (doc.fill == FillMode.None) rgba else fill(rgba.copyOf(), doc)
        val bpp = doc.bytesPerPixel.coerceIn(1, 4)
        val w = (width + doc.widthDelta).coerceIn(1, width * 4)
        val stride = (w * bpp + doc.strideDelta).coerceAtLeast(1)
        // As many rows as the bytes hold, but never more pixels than the source had.
        val h = ((bytes.size - doc.offset.coerceAtLeast(0)) / stride).coerceIn(1, ((width * height) / w).coerceAtLeast(1))
        val out = IntArray(w * h)
        val n = bytes.size
        for (y in 0 until h) {
            if (y % 64 == 0) checkCancel()
            var p = Math.floorMod(doc.offset + y * stride, n)
            for (x in 0 until w) {
                out[y * w + x] = readPixel(bytes, p, bpp, n, doc.channels)
                p += bpp
                if (p >= n) p -= n
            }
        }
        val shifted = if (doc.chromaX != 0 || doc.chromaY != 0) chromaShift(out, w, h, doc.chromaX, doc.chromaY) else out
        return Pixels(shifted, w, h)
    }

    private fun byteAt(b: ByteArray, i: Int, n: Int) = b[if (i < n) i else i - n].toInt() and 0xFF

    private fun readPixel(b: ByteArray, p: Int, bpp: Int, n: Int, order: ChannelOrder): Int {
        val (r, g, bl) = when (bpp) {
            1 -> byteAt(b, p, n).let { Triple(it, it, it) }
            2 -> {
                // Little-endian RGB565.
                val v = byteAt(b, p, n) or (byteAt(b, p + 1, n) shl 8)
                Triple((v shr 11 and 0x1F) * 255 / 31, (v shr 5 and 0x3F) * 255 / 63, (v and 0x1F) * 255 / 31)
            }
            else -> Triple(byteAt(b, p, n), byteAt(b, p + 1, n), byteAt(b, p + 2, n))
        }
        val (a, c, d) = when (order) {
            ChannelOrder.RGB -> Triple(r, g, bl)
            ChannelOrder.RBG -> Triple(r, bl, g)
            ChannelOrder.GRB -> Triple(g, r, bl)
            ChannelOrder.GBR -> Triple(g, bl, r)
            ChannelOrder.BRG -> Triple(bl, r, g)
            ChannelOrder.BGR -> Triple(bl, g, r)
        }
        return (0xFF shl 24) or (a shl 16) or (c shl 8) or d
    }

    /** Overwrites a range of the byte stream, before it is read back as pixels. */
    fun fill(bytes: ByteArray, doc: GlitchDoc): ByteArray {
        if (doc.fill == FillMode.None) return bytes
        val n = bytes.size
        val start = (doc.fillStart.coerceIn(0f, 1f) * n).toInt()
        val len = (doc.fillLength.coerceIn(0f, 1f) * n).toInt().coerceAtMost(n - start)
        when (doc.fill) {
            FillMode.Zero -> bytes.fill(0, start, start + len)
            FillMode.Max -> bytes.fill(-1, start, start + len)
            FillMode.Invert -> for (i in start until start + len) bytes[i] = bytes[i].toInt().inv().toByte()
            // Repeat the block just before the range, like a stuck buffer.
            FillMode.Repeat -> {
                val block = len.coerceAtMost(start).coerceAtLeast(1)
                for (i in 0 until len) bytes[start + i] = bytes[(start - block + i % block).coerceAtLeast(0)]
            }
            FillMode.None -> Unit
        }
        return bytes
    }

    /** Moves the chroma (Cb, Cr) of every pixel by (dx, dy), keeping luma in place (BT.601). */
    fun chromaShift(argb: IntArray, w: Int, h: Int, dx: Int, dy: Int): IntArray {
        val out = IntArray(argb.size)
        for (y in 0 until h) for (x in 0 until w) {
            val c = argb[y * w + x]
            val sx = Math.floorMod(x - dx, w)
            val sy = Math.floorMod(y - dy, h)
            val s = argb[sy * w + sx]
            val luma = 0.299f * (c shr 16 and 0xFF) + 0.587f * (c shr 8 and 0xFF) + 0.114f * (c and 0xFF)
            val sr = (s shr 16 and 0xFF).toFloat()
            val sg = (s shr 8 and 0xFF).toFloat()
            val sb = (s and 0xFF).toFloat()
            val sl = 0.299f * sr + 0.587f * sg + 0.114f * sb
            val cb = 0.564f * (sb - sl)
            val cr = 0.713f * (sr - sl)
            val r = luma + 1.403f * cr
            val g = luma - 0.344f * cb - 0.714f * cr
            val b = luma + 1.773f * cb
            out[y * w + x] = (0xFF shl 24) or (clip(r) shl 16) or (clip(g) shl 8) or clip(b)
        }
        return out
    }

    private fun clip(v: Float) = (v + 0.5f).toInt().coerceIn(0, 255)

    /**
     * Changes [count] bytes of a JPEG's entropy-coded data (after the start-of-scan header),
     * never writing 0xFF, so markers stay intact and the file still decodes, wrongly.
     */
    fun corruptJpeg(jpeg: ByteArray, count: Int, seed: Int): ByteArray {
        val out = jpeg.copyOf()
        var sos = -1
        var i = 2
        while (i < out.size - 4) {
            if (out[i] == 0xFF.toByte() && out[i + 1] == 0xDA.toByte()) {
                sos = i
                break
            }
            i++
        }
        if (sos < 0) return out
        val headerLen = ((out[sos + 2].toInt() and 0xFF) shl 8) or (out[sos + 3].toInt() and 0xFF)
        val start = sos + 2 + headerLen
        val end = out.size - 2 // keep the end-of-image marker
        if (end - start < 16) return out
        val rnd = Random(seed)
        repeat(count) {
            val p = rnd.nextInt(start, end)
            // Skip marker bytes and the stuffed zero after them.
            if (out[p] == 0xFF.toByte() || out[p - 1] == 0xFF.toByte()) return@repeat
            out[p] = rnd.nextInt(0, 0xFF).toByte()
        }
        return out
    }
}
