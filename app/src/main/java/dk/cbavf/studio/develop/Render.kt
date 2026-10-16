package dk.cbavf.studio.develop

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import dk.cbavf.studio.develop.model.ColorMath
import dk.cbavf.studio.develop.model.DevelopDoc
import dk.cbavf.studio.develop.model.Lut
import dk.cbavf.studio.develop.model.Mask
import dk.cbavf.studio.develop.model.MaskKind

/**
 * Applies a Develop edit to [source] (never modified): the baked [lut], then the imported
 * [cube] by its amount, limited to the mask, then the [style] image mixed in. Works a row at a
 * time, so full resolution needs only the source and the result in memory.
 */
fun renderDevelop(
    source: Bitmap,
    doc: DevelopDoc,
    lut: Lut,
    cube: Lut?,
    style: Bitmap?,
    /** Called every row; throw (e.g. ensureActive) to abandon a render nobody needs any more. */
    checkCancel: () -> Unit = {},
): Bitmap {
    val w = source.width
    val h = source.height
    val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val mask = maskBitmap(doc.mask, w, h)
    // The whole mask as bytes once (rows may be padded to [stride]); per-pixel getPixel is far too slow.
    val stride = mask?.rowBytes ?: 0
    val maskBytes = mask?.let { m ->
        java.nio.ByteBuffer.allocate(m.rowBytes * h).also { m.copyPixelsToBuffer(it) }.array()
    }
    mask?.recycle()
    val styleScaled = style?.takeIf { doc.styleAmount > 0f }?.let { Bitmap.createScaledBitmap(it, w, h, true) }

    val row = IntArray(w)
    val styleRow = IntArray(w)
    val graded = FloatArray(3)
    val cubed = FloatArray(3)
    val hsl = FloatArray(3)
    for (y in 0 until h) {
        checkCancel()
        source.getPixels(row, 0, w, 0, y, w, 1)
        styleScaled?.getPixels(styleRow, 0, w, 0, y, w, 1)
        val maskBase = y * stride
        for (x in 0 until w) {
            val c = row[x]
            val r = (c shr 16 and 0xFF) / 255f
            val g = (c shr 8 and 0xFF) / 255f
            val b = (c and 0xFF) / 255f
            lut.apply(r, g, b, graded)
            if (cube != null && doc.lutAmount > 0f) {
                cube.apply(graded[0], graded[1], graded[2], cubed)
                for (k in 0..2) graded[k] += (cubed[k] - graded[k]) * doc.lutAmount
            }
            val m = when (doc.mask.kind) {
                MaskKind.None -> 1f
                MaskKind.Spot -> spotWeight(doc.mask, r, g, b, hsl)
                else -> if (maskBytes == null) 0f else (maskBytes[maskBase + x].toInt() and 0xFF) / 255f
            }.let { if (doc.mask.invert) 1f - it else it }
            var outR = r + (graded[0] - r) * m
            var outG = g + (graded[1] - g) * m
            var outB = b + (graded[2] - b) * m
            if (styleScaled != null) {
                val s = styleRow[x]
                val a = doc.styleAmount
                outR += ((s shr 16 and 0xFF) / 255f - outR) * a
                outG += ((s shr 8 and 0xFF) / 255f - outG) * a
                outB += ((s and 0xFF) / 255f - outB) * a
            }
            row[x] = (c and 0xFF000000.toInt()) or (to8(outR) shl 16) or (to8(outG) shl 8) or to8(outB)
        }
        out.setPixels(row, 0, w, 0, y, w, 1)
    }
    if (styleScaled != null && styleScaled !== style) styleScaled.recycle()
    return out
}

private fun to8(v: Float) = (v * 255f + 0.5f).toInt().coerceIn(0, 255)

/** Spot colour: full inside the hue range, fading over the feather, and ignoring greys. */
private fun spotWeight(mask: Mask, r: Float, g: Float, b: Float, hsl: FloatArray): Float {
    ColorMath.rgbToHsl(r, g, b, hsl)
    val d = ColorMath.hueDistance(hsl[0], mask.spotHue)
    val edge = (mask.feather * 360f).coerceAtLeast(1f)
    val byHue = ((mask.spotRange + edge - d) / edge).coerceIn(0f, 1f)
    val bySaturation = (hsl[1] / 0.15f).coerceIn(0f, 1f)
    return byHue * bySaturation
}

/** Lasso and brush masks drawn at image size with a blurred (feathered) edge; null for none/spot. */
private fun maskBitmap(mask: Mask, w: Int, h: Int): Bitmap? {
    if (mask.kind != MaskKind.Lasso && mask.kind != MaskKind.Brush) return null
    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
    val canvas = Canvas(bitmap)
    val feather = mask.feather * w
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        if (feather >= 1f) maskFilter = BlurMaskFilter(feather, BlurMaskFilter.Blur.NORMAL)
    }
    when (mask.kind) {
        MaskKind.Lasso -> if (mask.lasso.size >= 3) {
            val path = Path().apply {
                moveTo(mask.lasso[0].x * w, mask.lasso[0].y * h)
                mask.lasso.drop(1).forEach { lineTo(it.x * w, it.y * h) }
                close()
            }
            canvas.drawPath(path, paint)
        }
        MaskKind.Brush -> mask.dabs.forEach { canvas.drawCircle(it.x * w, it.y * h, it.radius * w, paint) }
        else -> Unit
    }
    return bitmap
}
