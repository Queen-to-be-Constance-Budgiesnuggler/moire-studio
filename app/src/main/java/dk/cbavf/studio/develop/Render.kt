package dk.cbavf.studio.develop

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import dk.cbavf.studio.develop.model.ArtStyle
import dk.cbavf.studio.develop.model.ArtStyles
import dk.cbavf.studio.develop.model.ColorMath
import dk.cbavf.studio.develop.model.DevelopDoc
import dk.cbavf.studio.develop.model.EditBlend
import dk.cbavf.studio.develop.model.Lut
import dk.cbavf.studio.develop.model.Mask
import dk.cbavf.studio.develop.model.MaskKind

/** Styles that look at neighbouring pixels run at most this size; bigger saves are scaled up from it. */
private const val ART_MAX_PX = 1600

/**
 * Applies a Develop edit to [source] (never modified). First the edited image: the baked [lut],
 * the imported [cube] by its amount, the artistic style, the [style] image mixed in. Then it is
 * laid over the original through the mask stack, with the edit's blend mode and opacity.
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
    val edited = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val row = IntArray(w)
    val graded = FloatArray(3)
    val cubed = FloatArray(3)

    // 1. Grade every pixel (LUT, then the imported cube).
    for (y in 0 until h) {
        checkCancel()
        source.getPixels(row, 0, w, 0, y, w, 1)
        for (x in 0 until w) {
            val c = row[x]
            lut.apply((c shr 16 and 0xFF) / 255f, (c shr 8 and 0xFF) / 255f, (c and 0xFF) / 255f, graded)
            if (cube != null && doc.lutAmount > 0f) {
                cube.apply(graded[0], graded[1], graded[2], cubed)
                for (k in 0..2) graded[k] += (cubed[k] - graded[k]) * doc.lutAmount
            }
            row[x] = (c and 0xFF000000.toInt()) or (to8(graded[0]) shl 16) or (to8(graded[1]) shl 8) or to8(graded[2])
        }
        edited.setPixels(row, 0, w, 0, y, w, 1)
    }

    // 2. The artistic style, then another image mixed in.
    doc.art?.takeIf { doc.artAmount > 0f }?.let { applyArt(edited, it, doc.artAmount, checkCancel) }
    style?.takeIf { doc.styleAmount > 0f }?.let { mixImage(edited, it, doc.styleAmount, checkCancel) }

    // 3. Over the original: mask stack, blend mode, opacity. Written back into [edited].
    val layers = doc.maskLayers.filter { it.enabled }
    val drawn = layers.map { maskBytes(it.mask, w, h) }
    val src = IntArray(w)
    val hsl = FloatArray(3)
    for (y in 0 until h) {
        checkCancel()
        source.getPixels(src, 0, w, 0, y, w, 1)
        edited.getPixels(row, 0, w, 0, y, w, 1)
        for (x in 0 until w) {
            val o = src[x]
            val e = row[x]
            val or = (o shr 16 and 0xFF) / 255f
            val og = (o shr 8 and 0xFF) / 255f
            val ob = (o and 0xFF) / 255f
            var m = 1f
            layers.forEachIndexed { i, layer ->
                val mk = layer.mask
                val bytes = drawn[i]
                var wgt = when (mk.kind) {
                    MaskKind.None -> 1f
                    MaskKind.Spot -> spotWeight(mk, or, og, ob, hsl)
                    MaskKind.Luminance -> mk.lumaWeight(0.2126f * or + 0.7152f * og + 0.0722f * ob)
                    else -> if (bytes == null) 0f else (bytes.second[y * bytes.first + x].toInt() and 0xFF) / 255f
                }
                if (mk.invert) wgt = 1f - wgt
                wgt *= layer.opacity
                m = if (i == 0) wgt else layer.combine.apply(m, wgt)
            }
            m *= doc.opacity
            if (m <= 0f) { row[x] = o; continue }
            var er = (e shr 16 and 0xFF) / 255f
            var eg = (e shr 8 and 0xFF) / 255f
            var eb = (e and 0xFF) / 255f
            when (doc.blend) {
                EditBlend.Normal -> Unit
                EditBlend.Luminosity -> {
                    // The edit's brightness on the original's colour.
                    val d = luma(er, eg, eb) - luma(or, og, ob)
                    er = or + d; eg = og + d; eb = ob + d
                }
                EditBlend.Color -> {
                    // The edit's colour at the original's brightness.
                    val d = luma(or, og, ob) - luma(er, eg, eb)
                    er += d; eg += d; eb += d
                }
                else -> {
                    er = doc.blend.channel(or, er); eg = doc.blend.channel(og, eg); eb = doc.blend.channel(ob, eb)
                }
            }
            row[x] = (o and 0xFF000000.toInt()) or
                (to8(or + (er - or) * m) shl 16) or (to8(og + (eg - og) * m) shl 8) or to8(ob + (eb - ob) * m)
        }
        edited.setPixels(row, 0, w, 0, y, w, 1)
    }
    return edited
}

private fun luma(r: Float, g: Float, b: Float) = 0.2126f * r + 0.7152f * g + 0.0722f * b

private fun to8(v: Float) = (v * 255f + 0.5f).toInt().coerceIn(0, 255)

/** The artistic style mixed into [img] in place by [amount]; worked out at most [ART_MAX_PX] on the long side. */
private fun applyArt(img: Bitmap, art: ArtStyle, amount: Float, checkCancel: () -> Unit) {
    val w = img.width
    val h = img.height
    val k = minOf(1f, ART_MAX_PX.toFloat() / maxOf(w, h))
    val small = if (k < 1f) Bitmap.createScaledBitmap(img, (w * k).toInt().coerceAtLeast(1), (h * k).toInt().coerceAtLeast(1), true) else img
    val sw = small.width
    val sh = small.height
    val px = IntArray(sw * sh).also { small.getPixels(it, 0, sw, 0, 0, sw, sh) }
    if (small !== img) small.recycle()
    val styled = ArtStyles.apply(art, px, sw, sh, checkCancel)
    var styledBmp = Bitmap.createBitmap(styled, sw, sh, Bitmap.Config.ARGB_8888)
    if (k < 1f) styledBmp = Bitmap.createScaledBitmap(styledBmp, w, h, true).also { if (it !== styledBmp) styledBmp.recycle() }
    mixImage(img, styledBmp, amount, checkCancel)
    styledBmp.recycle()
}

/** [other] (scaled to fit) mixed into [img] in place by [amount]. */
private fun mixImage(img: Bitmap, other: Bitmap, amount: Float, checkCancel: () -> Unit) {
    val w = img.width
    val h = img.height
    val scaled = if (other.width == w && other.height == h) other else Bitmap.createScaledBitmap(other, w, h, true)
    val a = IntArray(w)
    val b = IntArray(w)
    val t = amount.coerceIn(0f, 1f)
    for (y in 0 until h) {
        checkCancel()
        img.getPixels(a, 0, w, 0, y, w, 1)
        scaled.getPixels(b, 0, w, 0, y, w, 1)
        for (x in 0 until w) {
            val p = a[x]
            val q = b[x]
            fun ch(s: Int) = ((p shr s and 0xFF) + ((q shr s and 0xFF) - (p shr s and 0xFF)) * t).toInt().coerceIn(0, 255)
            a[x] = (p and 0xFF000000.toInt()) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }
        img.setPixels(a, 0, w, 0, y, w, 1)
    }
    if (scaled !== other) scaled.recycle()
}

/** Spot colour: full inside the hue range, fading over the feather, and ignoring greys. */
private fun spotWeight(mask: Mask, r: Float, g: Float, b: Float, hsl: FloatArray): Float {
    ColorMath.rgbToHsl(r, g, b, hsl)
    val d = ColorMath.hueDistance(hsl[0], mask.spotHue)
    val edge = (mask.feather * 360f).coerceAtLeast(1f)
    val byHue = ((mask.spotRange + edge - d) / edge).coerceIn(0f, 1f)
    val bySaturation = (hsl[1] / 0.15f).coerceIn(0f, 1f)
    return byHue * bySaturation
}

/** A lasso or brush mask drawn at image size with a feathered edge, as (row stride, bytes); null for the other kinds. */
private fun maskBytes(mask: Mask, w: Int, h: Int): Pair<Int, ByteArray>? {
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
    // Rows may be padded to rowBytes: keep the stride with the bytes.
    val bytes = java.nio.ByteBuffer.allocate(bitmap.rowBytes * h).also { bitmap.copyPixelsToBuffer(it) }.array()
    val stride = bitmap.rowBytes
    bitmap.recycle()
    return stride to bytes
}
