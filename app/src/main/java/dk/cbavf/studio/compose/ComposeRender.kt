package dk.cbavf.studio.compose

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.util.LruCache
import dk.cbavf.studio.compose.model.AssetLayerSource
import dk.cbavf.studio.compose.model.CanvasLayerSource
import dk.cbavf.studio.compose.model.ComposeDoc
import dk.cbavf.studio.compose.model.ComposeLayer
import dk.cbavf.studio.compose.model.LayerMask
import dk.cbavf.studio.compose.model.LayerSource
import dk.cbavf.studio.compose.model.GradientLayerSource
import dk.cbavf.studio.compose.model.PaintLayerSource
import dk.cbavf.studio.compose.model.SolidLayerSource
import dk.cbavf.studio.compose.model.TextLayerSource
import dk.cbavf.studio.develop.decodeAsset
import dk.cbavf.studio.develop.model.DevelopDoc
import dk.cbavf.studio.develop.model.Lut
import dk.cbavf.studio.draw.model.Blend
import dk.cbavf.studio.library.Lineage
import dk.cbavf.studio.library.ProjectStore
import dk.cbavf.studio.library.renderBitmap
import android.graphics.BlendMode as AndroidBlend

/**
 * Layer pixels, decoded and adjusted once per (source, adjustment, size) and reused while
 * the layer only moves. Shared by the live view and the full-size save.
 */
class LayerPixels(private val store: ProjectStore, private val lineage: () -> Lineage) {
    private val cache = object : LruCache<Any, Bitmap>(160 * 1024 * 1024) {
        override fun sizeOf(key: Any, value: Bitmap) = value.byteCount
    }

    /** The layer's pixels with its adjustments and filters, at most [maxPx] on the long side. */
    fun get(layer: ComposeLayer, maxPx: Int, checkCancel: () -> Unit = {}): Bitmap? {
        val key = listOf(layer.source, layer.adjust, layer.filters, maxPx)
        cache.get(key)?.let { return it }
        val base = base(layer.source, layer.adjust, maxPx) ?: return null
        if (layer.filters.isEmpty()) return base
        val filtered = applyFilters(base, layer.filters, base.width.toFloat() / layer.sourceWidth.coerceAtLeast(1), checkCancel)
        cache.put(key, filtered)
        return filtered
    }

    /** Source pixels with adjustments, cached on their own so changing a filter doesn't redo them. */
    private fun base(source: LayerSource, adjust: DevelopDoc?, maxPx: Int): Bitmap? {
        val key = Triple(source, adjust, maxPx)
        cache.get(key)?.let { return it }
        val raw = when (source) {
            is AssetLayerSource -> lineage()[source.assetId]?.let { decodeAsset(store, it, maxPx) }
            is CanvasLayerSource -> renderBitmap(source.doc, sheet = source.paper).let { full ->
                val long = maxOf(full.width, full.height)
                if (long <= maxPx) full else {
                    val k = maxPx.toFloat() / long
                    Bitmap.createScaledBitmap(full, (full.width * k).toInt().coerceAtLeast(1), (full.height * k).toInt().coerceAtLeast(1), true)
                        .also { full.recycle() }
                }
            }
            is SolidLayerSource, is GradientLayerSource, is PaintLayerSource, is TextLayerSource -> drawSource(source, maxPx)
        } ?: return null
        val adjusted = if (adjust == null) raw else applyLut(raw, Lut.build(adjust)).also { raw.recycle() }
        cache.put(key, adjusted)
        return adjusted
    }

    fun clear() = cache.evictAll()

    /** Thumbnail for the layer strip. */
    fun thumb(layer: ComposeLayer): Bitmap? = runCatching { get(layer, 96) }.getOrNull()

}

/** A copy of [src] with every pixel run through [lut] (alpha kept). */
fun applyLut(src: Bitmap, lut: Lut): Bitmap {
    val w = src.width
    val h = src.height
    val out = src.copy(Bitmap.Config.ARGB_8888, true)
    val row = IntArray(w)
    val c = FloatArray(3)
    for (y in 0 until h) {
        out.getPixels(row, 0, w, 0, y, w, 1)
        for (x in 0 until w) {
            val p = row[x]
            lut.apply((p shr 16 and 0xFF) / 255f, (p shr 8 and 0xFF) / 255f, (p and 0xFF) / 255f, c)
            row[x] = (p and 0xFF000000.toInt()) or (to8(c[0]) shl 16) or (to8(c[1]) shl 8) or to8(c[2])
        }
        out.setPixels(row, 0, w, 0, y, w, 1)
    }
    return out
}

private fun to8(v: Float) = (v * 255f + 0.5f).toInt().coerceIn(0, 255)

/**
 * The composite at [scale] × the document size: background, then every visible layer bottom
 * to top, each transformed, masked, and blended with its opacity.
 */
fun renderComposite(doc: ComposeDoc, scale: Float, pixels: LayerPixels, checkCancel: () -> Unit = {}): Bitmap {
    val w = (doc.widthPx * scale).toInt().coerceAtLeast(1)
    val h = (doc.heightPx * scale).toInt().coerceAtLeast(1)
    val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(out)
    doc.background.argb?.let { canvas.drawColor(it.toInt()) }
    val tmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val tmpCanvas = Canvas(tmp)
    // The last unclipped layer, as drawn: what clipped layers above it are confined to.
    var clipBase: Bitmap? = null
    doc.layers.forEachIndexed { i, layer ->
        checkCancel()
        val nextClips = doc.layers.getOrNull(i + 1)?.clip == true
        if (!layer.visible || layer.opacity <= 0f) {
            if (!layer.clip) clipBase = clipBase?.let { it.recycle(); null }
            return@forEachIndexed
        }
        // Decode only as big as the layer is drawn.
        val drawnLong = (maxOf(layer.sourceWidth, layer.sourceHeight) * maxOf(kotlin.math.abs(layer.transform.sx), kotlin.math.abs(layer.transform.sy)) * scale)
            .toInt().coerceIn(16, MAX_LAYER_PX)
        val bmp = pixels.get(layer, drawnLong, checkCancel) ?: return@forEachIndexed
        tmp.eraseColor(0)
        tmpCanvas.drawBitmap(bmp, layerMatrix(layer, bmp, scale), Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        layer.mask?.takeIf { it.enabled }?.let { applyMask(tmpCanvas, it, w, h, scale) }
        if (layer.clip) {
            clipBase?.let { tmpCanvas.drawBitmap(it, 0f, 0f, Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }) }
        } else {
            clipBase?.recycle()
            clipBase = if (nextClips) tmp.copy(Bitmap.Config.ARGB_8888, false) else null
        }
        layer.blendIf?.let { applyBlendIf(tmp, out, it) }
        canvas.drawBitmap(tmp, 0f, 0f, Paint().apply {
            alpha = (layer.opacity.coerceIn(0f, 1f) * 255).toInt()
            blendMode = layer.blend.android
        })
    }
    clipBase?.recycle()
    tmp.recycle()
    return out
}

/** Source pixels → output pixels: centre, scale to the source's own size, the layer transform, the output scale. */
fun layerMatrix(layer: ComposeLayer, bmp: Bitmap, scale: Float): Matrix {
    val t = layer.transform
    return Matrix().apply {
        postTranslate(-bmp.width / 2f, -bmp.height / 2f)
        postScale(layer.sourceWidth.toFloat() / bmp.width, layer.sourceHeight.toFloat() / bmp.height)
        postScale(t.sx, t.sy)
        postRotate(t.rotationDeg)
        postTranslate(t.tx, t.ty)
        postScale(scale, scale)
    }
}

/** Keeps only the masked part of what is in [target]. */
private fun applyMask(target: Canvas, mask: LayerMask, w: Int, h: Int, scale: Float) {
    val m = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
    val mc = Canvas(m)
    if (!mask.invert) mc.drawColor(0xFF000000.toInt())
    // Selection shapes first, then brush dabs, each revealing or hiding.
    for (sh in mask.shapes) {
        if (sh.points.size < 3) continue
        val path = android.graphics.Path().apply {
            moveTo(sh.points[0].x * scale, sh.points[0].y * scale)
            sh.points.drop(1).forEach { lineTo(it.x * scale, it.y * scale) }
            close()
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF000000.toInt()
            if (!sh.reveal) xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
            if (sh.feather * scale >= 1f) maskFilter = BlurMaskFilter(sh.feather * scale, BlurMaskFilter.Blur.NORMAL)
        }
        mc.drawPath(path, paint)
    }
    val reveal = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF000000.toInt() }
    // DST_OUT removes as much as the dab covers, so soft edges fade instead of cutting.
    val hide = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF000000.toInt()
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
    }
    for (d in mask.dabs) {
        val r = d.radius * scale
        val p = if (d.reveal) reveal else hide
        val blur = r * d.softness.coerceIn(0f, 1f)
        p.maskFilter = if (blur >= 1f) BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL) else null
        mc.drawCircle(d.x * scale, d.y * scale, (r - blur / 2).coerceAtLeast(0.5f), p)
    }
    target.drawBitmap(m, 0f, 0f, Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) })
    m.recycle()
}

/**
 * Blending option: fades the layer where brightness (of the layer, or of what is below it in
 * [below]) leaves the low–high range, over the feather.
 */
private fun applyBlendIf(layer: Bitmap, below: Bitmap, b: dk.cbavf.studio.compose.model.BlendIf) {
    val w = layer.width
    val h = layer.height
    val px = IntArray(w)
    val under = IntArray(w)
    val f = b.feather.coerceAtLeast(1e-3f)
    for (y in 0 until h) {
        layer.getPixels(px, 0, w, 0, y, w, 1)
        if (b.underlying) below.getPixels(under, 0, w, 0, y, w, 1)
        for (x in 0 until w) {
            val c = px[x]
            val a = c ushr 24
            if (a == 0) continue
            val l = luma(if (b.underlying) under[x] else c)
            val k = (minOf((l - b.low) / f + 1f, (b.high - l) / f + 1f)).coerceIn(0f, 1f)
            px[x] = ((a * k).toInt() shl 24) or (c and 0xFFFFFF)
        }
        layer.setPixels(px, 0, w, 0, y, w, 1)
    }
}

/** Pixels for the generated layer kinds, at most [maxPx] on the long side. */
private fun drawSource(source: LayerSource, maxPx: Int): Bitmap {
    val (sw, sh) = when (source) {
        is SolidLayerSource -> source.widthPx to source.heightPx
        is GradientLayerSource -> source.widthPx to source.heightPx
        is PaintLayerSource -> source.widthPx to source.heightPx
        is TextLayerSource -> source.widthPx to source.heightPx
        else -> 1 to 1
    }
    val k = minOf(1f, maxPx.toFloat() / maxOf(sw, sh).coerceAtLeast(1))
    val w = (sw * k).toInt().coerceAtLeast(1)
    val h = (sh * k).toInt().coerceAtLeast(1)
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    when (source) {
        is SolidLayerSource -> c.drawColor(source.argb.toInt())
        is GradientLayerSource -> {
            val shader = if (source.radial) {
                android.graphics.RadialGradient(w / 2f, h / 2f, maxOf(w, h) / 2f, source.fromArgb.toInt(), source.toArgb.toInt(), android.graphics.Shader.TileMode.CLAMP)
            } else {
                // Across the layer along the angle, edge to edge.
                val a = Math.toRadians(source.angleDeg.toDouble())
                val dx = (kotlin.math.cos(a) * w / 2).toFloat()
                val dy = (kotlin.math.sin(a) * h / 2).toFloat()
                android.graphics.LinearGradient(w / 2f - dx, h / 2f - dy, w / 2f + dx, h / 2f + dy, source.fromArgb.toInt(), source.toArgb.toInt(), android.graphics.Shader.TileMode.CLAMP)
            }
            c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply { this.shader = shader })
        }
        is PaintLayerSource -> {
            c.scale(k, k)
            for (st in source.strokes) {
                if (st.points.isEmpty()) continue
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = if (st.fill) Paint.Style.FILL else Paint.Style.STROKE
                    strokeWidth = st.width
                    strokeCap = Paint.Cap.ROUND
                    strokeJoin = Paint.Join.ROUND
                    color = st.argb.toInt()
                    if (st.erase) xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
                    val blur = st.width / 2 * st.softness
                    if (blur >= 1f) maskFilter = BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL)
                }
                if (st.points.size == 1) {
                    c.drawPoint(st.points[0].x, st.points[0].y, paint)
                } else {
                    val path = android.graphics.Path().apply {
                        moveTo(st.points[0].x, st.points[0].y)
                        st.points.drop(1).forEach { lineTo(it.x, it.y) }
                        if (st.fill) close()
                    }
                    c.drawPath(path, paint)
                }
            }
        }
        is TextLayerSource -> {
            c.scale(k, k)
            val paint = textPaint(source.sizePx, source.argb, source.bold)
            val fm = paint.fontMetrics
            source.text.split('\n').forEachIndexed { i, line ->
                c.drawText(line, 0f, -fm.ascent + i * (fm.descent - fm.ascent), paint)
            }
        }
        else -> Unit
    }
    return bmp
}

fun textPaint(sizePx: Float, argb: Long, bold: Boolean) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    textSize = sizePx
    color = argb.toInt()
    typeface = if (bold) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT
}

/** Size of [text] (several lines allowed) in pixels. */
fun measureText(text: String, sizePx: Float, bold: Boolean): Pair<Int, Int> {
    val p = textPaint(sizePx, 0xFF000000, bold)
    val lines = text.split('\n')
    val fm = p.fontMetrics
    val w = lines.maxOf { p.measureText(it) }.toInt() + 2
    val h = ((fm.descent - fm.ascent) * lines.size).toInt() + 2
    return w.coerceAtLeast(1) to h.coerceAtLeast(1)
}

/** Layers are kept at most this big; a full-size save of a 12 MP photo uses its full width. */
const val MAX_LAYER_PX = 4096

val Blend.android: AndroidBlend
    get() = when (this) {
        Blend.Normal -> AndroidBlend.SRC_OVER
        Blend.Lighten -> AndroidBlend.LIGHTEN
        Blend.Multiply -> AndroidBlend.MULTIPLY
        Blend.Difference -> AndroidBlend.DIFFERENCE
        Blend.Screen -> AndroidBlend.SCREEN
        Blend.Darken -> AndroidBlend.DARKEN
        Blend.Overlay -> AndroidBlend.OVERLAY
        Blend.SoftLight -> AndroidBlend.SOFT_LIGHT
        Blend.HardLight -> AndroidBlend.HARD_LIGHT
        Blend.Exclusion -> AndroidBlend.EXCLUSION
        Blend.Add -> AndroidBlend.PLUS
        Blend.ColorDodge -> AndroidBlend.COLOR_DODGE
        Blend.ColorBurn -> AndroidBlend.COLOR_BURN
        Blend.Hue -> AndroidBlend.HUE
        Blend.Saturation -> AndroidBlend.SATURATION
        Blend.Color -> AndroidBlend.COLOR
        Blend.Luminosity -> AndroidBlend.LUMINOSITY
    }
