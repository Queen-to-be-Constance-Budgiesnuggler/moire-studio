package dk.cbavf.studio.animate

import android.graphics.Bitmap
import dk.cbavf.studio.animate.model.Animation
import dk.cbavf.studio.animate.model.AnimationDoc
import dk.cbavf.studio.library.renderBitmap

/**
 * Scanimation image: column x shows frame floor(x / strip) mod N. All frames render at the
 * first frame's size, one at a time, so memory stays at two frames whatever N is.
 */
fun renderInterleaved(anim: AnimationDoc, checkCancel: () -> Unit = {}): Bitmap? {
    val first = anim.frames.firstOrNull()?.doc ?: return null
    val w = first.widthPx
    val h = first.heightPx
    if (w <= 0 || h <= 0) return null
    val n = anim.frames.size
    val strip = anim.stripPx.coerceAtLeast(1)
    val out = IntArray(w * h)
    val tmp = IntArray(w * h)
    anim.frames.forEachIndexed { k, frame ->
        checkCancel()
        val bitmap = renderBitmap(frame.doc.copy(widthPx = w, heightPx = h))
        bitmap.getPixels(tmp, 0, w, 0, 0, w, h)
        bitmap.recycle()
        for (x in 0 until w) {
            if (Animation.interleaveFrame(x, strip, n) != k) continue
            var i = x
            while (i < tmp.size) {
                out[i] = tmp[i]
                i += w
            }
        }
    }
    return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
}

/** The printable overlay: opaque black with one clear slit of strip width every N strips. */
fun renderBarrier(widthPx: Int, heightPx: Int, stripPx: Int, frames: Int): Bitmap {
    val row = IntArray(widthPx) { x ->
        if (Animation.barrierOpen(x, stripPx.coerceAtLeast(1), frames, 0)) 0 else BLACK
    }
    val pixels = IntArray(widthPx * heightPx)
    for (y in 0 until heightPx) System.arraycopy(row, 0, pixels, y * widthPx, widthPx)
    return Bitmap.createBitmap(pixels, widthPx, heightPx, Bitmap.Config.ARGB_8888)
}

private const val BLACK = 0xFF000000.toInt()
