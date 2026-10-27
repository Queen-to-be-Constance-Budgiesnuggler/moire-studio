package dk.cbavf.studio.glitch

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import dk.cbavf.studio.glitch.model.Glitch
import dk.cbavf.studio.glitch.model.GlitchDoc
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * Glitches [source] (ARGB_8888, never modified): reads its raw RGBA bytes back with the doc's
 * misreadings, then the colour transfer from [palette] and the pixel sort, then the JPEG
 * generations. A generation that no longer decodes ends the chain with the last image that did.
 */
fun renderGlitch(source: Bitmap, doc: GlitchDoc, palette: IntArray? = null, checkCancel: () -> Unit = {}): Bitmap {
    val src = if (source.config == Bitmap.Config.ARGB_8888) source else source.copy(Bitmap.Config.ARGB_8888, false)
    val bytes = ByteBuffer.allocate(src.byteCount).also { src.copyPixelsToBuffer(it) }.array()
    val px = Glitch.pixelStages(Glitch.apply(bytes, src.width, src.height, doc, checkCancel), doc, palette, checkCancel)
    if (src !== source) src.recycle()
    var image = Bitmap.createBitmap(px.argb, px.width, px.height, Bitmap.Config.ARGB_8888)
    // What error correction pulls back towards: the misread image, at the correction scale.
    val reference = if (doc.correction > 0f && doc.generations > 0) lowPass(px.argb, px.width, px.height, doc.correctScale) else null
    repeat(doc.generations.coerceIn(0, MAX_GENERATIONS)) { gen ->
        checkCancel()
        val jpeg = ByteArrayOutputStream().use { out ->
            image.compress(Bitmap.CompressFormat.JPEG, doc.quality.coerceIn(1, 100), out)
            out.toByteArray()
        }
        val damaged = if (doc.corruptBytes > 0) Glitch.corruptJpeg(jpeg, doc.corruptBytes, doc.seed * 1000 + gen) else jpeg
        val next = BitmapFactory.decodeByteArray(damaged, 0, damaged.size) ?: return image
        image.recycle()
        image = next.copy(Bitmap.Config.ARGB_8888, false).also { if (it !== next) next.recycle() }
        if (reference != null && (gen + 1) % doc.correctEvery.coerceAtLeast(1) == 0) {
            image = correct(image, reference, doc.correction, doc.correctScale)
        }
    }
    return image
}

/** [argb] blurred by shrinking to 1/[scale] and growing back (bilinear); scale 1 = unchanged. */
private fun lowPass(argb: IntArray, w: Int, h: Int, scale: Int): IntArray {
    if (scale <= 1) return argb.copyOf()
    val full = Bitmap.createBitmap(argb, w, h, Bitmap.Config.ARGB_8888)
    val small = Bitmap.createScaledBitmap(full, (w / scale).coerceAtLeast(1), (h / scale).coerceAtLeast(1), true)
    val back = Bitmap.createScaledBitmap(small, w, h, true)
    val out = IntArray(w * h).also { back.getPixels(it, 0, w, 0, 0, w, h) }
    full.recycle(); small.recycle(); back.recycle()
    return out
}

/**
 * One error-correction step: current + amount × (reference − current), both seen at the
 * correction scale, so only what that scale can see is corrected.
 */
private fun correct(image: Bitmap, reference: IntArray, amount: Float, scale: Int): Bitmap {
    val w = image.width
    val h = image.height
    if (reference.size != w * h) return image
    val cur = IntArray(w * h).also { image.getPixels(it, 0, w, 0, 0, w, h) }
    val curLow = lowPass(cur, w, h, scale)
    val a = amount.coerceIn(0f, 1f)
    for (i in cur.indices) {
        val c = cur[i]
        val r = reference[i]
        val l = curLow[i]
        fun ch(shift: Int): Int {
            val v = (c shr shift and 0xFF) + a * ((r shr shift and 0xFF) - (l shr shift and 0xFF))
            return v.toInt().coerceIn(0, 255)
        }
        cur[i] = (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
    val out = Bitmap.createBitmap(cur, w, h, Bitmap.Config.ARGB_8888)
    image.recycle()
    return out
}

/** The pixels of the doc's colour image, small (only its statistics are used), or null. Call off the main thread. */
fun decodePalette(store: dk.cbavf.studio.library.ProjectStore, lineage: dk.cbavf.studio.library.Lineage, doc: GlitchDoc): IntArray? =
    doc.colorFromAssetId?.let { decodePalette(store, lineage, it) }

/** The pixels of Library image [assetId], small, or null when it is gone or unreadable. Call off the main thread. */
fun decodePalette(store: dk.cbavf.studio.library.ProjectStore, lineage: dk.cbavf.studio.library.Lineage, assetId: String): IntArray? {
    val bmp = lineage[assetId]?.let { dk.cbavf.studio.develop.decodeAsset(store, it, PALETTE_PX) } ?: return null
    val out = IntArray(bmp.width * bmp.height).also { bmp.getPixels(it, 0, bmp.width, 0, 0, bmp.width, bmp.height) }
    bmp.recycle()
    return out
}

const val MAX_GENERATIONS = 50
private const val PALETTE_PX = 512
