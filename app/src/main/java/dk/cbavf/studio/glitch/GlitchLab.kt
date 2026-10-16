package dk.cbavf.studio.glitch

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import dk.cbavf.studio.glitch.model.Glitch
import dk.cbavf.studio.glitch.model.GlitchDoc
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * Glitches [source] (ARGB_8888, never modified): reads its raw RGBA bytes back with the doc's
 * misreadings, then runs the JPEG generations. A generation that no longer decodes ends the
 * chain with the last image that did.
 */
fun renderGlitch(source: Bitmap, doc: GlitchDoc, checkCancel: () -> Unit = {}): Bitmap {
    val src = if (source.config == Bitmap.Config.ARGB_8888) source else source.copy(Bitmap.Config.ARGB_8888, false)
    val bytes = ByteBuffer.allocate(src.byteCount).also { src.copyPixelsToBuffer(it) }.array()
    val px = Glitch.apply(bytes, src.width, src.height, doc, checkCancel)
    if (src !== source) src.recycle()
    var image = Bitmap.createBitmap(px.argb, px.width, px.height, Bitmap.Config.ARGB_8888)
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
    }
    return image
}

const val MAX_GENERATIONS = 50
