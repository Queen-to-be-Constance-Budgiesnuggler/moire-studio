package dk.cbavf.studio.animate

import android.graphics.Bitmap
import dk.cbavf.studio.animate.model.Animation
import dk.cbavf.studio.animate.model.AnimationDoc
import dk.cbavf.studio.animate.model.PrintPlan
import dk.cbavf.studio.animate.model.PrintSize
import dk.cbavf.studio.library.renderBitmap

/**
 * Scanimation image: column x shows frame floor(x / strip) mod N. All frames render at the
 * first frame's size, one at a time, so memory stays at two frames whatever N is.
 * With a [plan] the frames are scaled onto a sheet of paper; strips count from the sheet's left
 * edge, as the barrier's do, and the margin stays white.
 */
fun renderInterleaved(anim: AnimationDoc, plan: PrintPlan? = null, checkCancel: () -> Unit = {}): Bitmap? {
    val first = anim.frames.firstOrNull()?.doc ?: return null
    if (first.widthPx <= 0 || first.heightPx <= 0) return null
    val p = plan ?: PrintSize.screen(first.widthPx, first.heightPx, anim.stripPx)
    val strip = p.stripPx.coerceAtLeast(1)
    val out = Bitmap.createBitmap(p.widthPx, p.heightPx, Bitmap.Config.ARGB_8888)
    if (p.offsetX > 0 || p.offsetY > 0) out.eraseColor(WHITE)
    // One strip of pixels at a time, so the only big buffers are the two bitmaps.
    try {
        fillInterleaved(out, anim, p, strip, first.widthPx, first.heightPx, checkCancel)
    } catch (t: Throwable) {
        // A failed or cancelled render must not leave a sheet-size bitmap behind.
        out.recycle()
        throw t
    }
    return out
}

private fun fillInterleaved(out: Bitmap, anim: AnimationDoc, p: PrintPlan, strip: Int, frameW: Int, frameH: Int, checkCancel: () -> Unit) {
    val n = anim.frames.size
    val buf = IntArray(strip * p.imageHeightPx)
    anim.frames.forEachIndexed { k, frame ->
        checkCancel()
        val bitmap = renderBitmap(frame.doc.copy(widthPx = frameW, heightPx = frameH), scale = p.scale)
        try {
            val left = p.offsetX
            val right = p.offsetX + minOf(bitmap.width, p.imageWidthPx)
            val rows = minOf(bitmap.height, p.imageHeightPx)
            var s = k
            while (s.toLong() * strip < p.widthPx) {
                val from = maxOf(s * strip, left)
                val to = minOf((s + 1) * strip, right)
                if (to > from) {
                    val cols = to - from
                    bitmap.getPixels(buf, 0, cols, from - left, 0, cols, rows)
                    out.setPixels(buf, 0, cols, from, p.offsetY, cols, rows)
                }
                s += n
            }
        } finally {
            bitmap.recycle()
        }
    }
}

/** The printable overlay: opaque black with one clear slit of strip width every N strips. */
fun renderBarrier(widthPx: Int, heightPx: Int, stripPx: Int, frames: Int): Bitmap {
    val row = IntArray(widthPx) { x ->
        if (Animation.barrierOpen(x, stripPx.coerceAtLeast(1), frames, 0)) 0 else BLACK
    }
    val out = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
    // Row by row: a print-size sheet would not fit in one pixel array.
    for (y in 0 until heightPx) out.setPixels(row, 0, widthPx, 0, y, widthPx, 1)
    return out
}

private const val BLACK = 0xFF000000.toInt()
private const val WHITE = 0xFFFFFFFF.toInt()
