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
import dk.cbavf.studio.compose.model.CloneMode
import dk.cbavf.studio.compose.model.PaintLayerSource
import dk.cbavf.studio.compose.model.SolidLayerSource
import dk.cbavf.studio.compose.model.TextLayerSource
import dk.cbavf.studio.develop.decodeAsset
import dk.cbavf.studio.develop.model.DevelopDoc
import dk.cbavf.studio.develop.model.Lut
import dk.cbavf.studio.blend.isSubtract
import dk.cbavf.studio.draw.model.Blend
import dk.cbavf.studio.library.Lineage
import dk.cbavf.studio.library.ProjectStore
import dk.cbavf.studio.library.renderBitmap
import android.graphics.BlendMode as AndroidBlend

/**
 * Layer pixels, decoded and adjusted once per (source, adjustment, size) and reused while
 * the layer only moves. Shared by the live view and the full-size save.
 */
class LayerPixels(
    private val store: ProjectStore,
    private val lineage: () -> Lineage,
    /** A rack input as pixels (live layers), and a number that changes whenever it does. */
    private val routed: (input: String, maxPx: Int) -> Bitmap? = { _, _ -> null },
    private val routeVersion: (input: String) -> Int = { 0 },
    cacheBytes: Int = 160 * 1024 * 1024,
) {
    private val cache = object : LruCache<Any, Bitmap>(cacheBytes) {
        override fun sizeOf(key: Any, value: Bitmap) = value.byteCount
    }

    /** The layer's pixels with its adjustments and filters, at most [maxPx] on the long side. */
    fun get(layer: ComposeLayer, maxPx: Int, checkCancel: () -> Unit = {}): Bitmap? {
        val live = (layer.source as? dk.cbavf.studio.compose.model.RouteLayerSource)?.let { routeVersion(it.input) }
        val key = listOf(layer.source, layer.adjust, layer.filters, maxPx, live)
        cache.get(key)?.let { return it }
        val base = base(layer.source, layer.adjust, maxPx) ?: return null
        if (layer.filters.isEmpty()) return base
        val filtered = applyFilters(base, layer.filters, base.width.toFloat() / layer.sourceWidth.coerceAtLeast(1), checkCancel)
        cache.put(key, filtered)
        return filtered
    }

    /** Source pixels with adjustments, cached on their own so changing a filter doesn't redo them. */
    private fun base(source: LayerSource, adjust: DevelopDoc?, maxPx: Int): Bitmap? {
        val key = listOf(source, adjust, maxPx, (source as? dk.cbavf.studio.compose.model.RouteLayerSource)?.let { routeVersion(it.input) })
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
            // A group has no pixels of its own: the compositor draws its members.
            is dk.cbavf.studio.compose.model.GroupLayerSource -> null
            // A copy: the router's bitmap is shared, and adjustments recycle what they replace.
            is dk.cbavf.studio.compose.model.RouteLayerSource -> routed(source.input, maxPx)?.copy(Bitmap.Config.ARGB_8888, false)
        } ?: return null
        val adjusted = if (adjust == null) raw else applyLut(raw, layerLut(adjust)).also { raw.recycle() }
        cache.put(key, adjusted)
        return adjusted
    }

    /**
     * A layer's look as one table: light, colour, mixer and curve, then its imported .cube by
     * its amount. Masks and styles are the Develop room's; a layer has its own mask.
     */
    private fun layerLut(adjust: DevelopDoc): Lut {
        val look = Lut.build(adjust)
        val cube = cubeOf(adjust)
        return if (cube == null) look else look.then(cube, adjust.lutAmount)
    }

    private fun cubeOf(adjust: DevelopDoc): Lut? =
        adjust.lutFile?.let { path -> cubes.get(path) ?: dk.cbavf.studio.develop.loadCube(store, adjust)?.also { cubes.put(path, it) } }

    /** Imported .cube files by project path, read once. */
    private val cubes = LruCache<String, Lut>(8)

    /**
     * Reads the .cube files [doc]'s layers use, so the render finds them ready. It reads files:
     * call it on Dispatchers.IO, before rendering.
     */
    fun loadCubes(doc: ComposeDoc) {
        doc.layers.forEach { l -> l.adjust?.let { runCatching { cubeOf(it) } } }
    }

    fun clear() {
        cache.evictAll()
        cubes.evictAll()
    }

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
    doc.background.argb?.let { Canvas(out).drawColor(it.toInt()) }
    drawStack(doc, doc.children(null), out, scale, pixels, checkCancel, 0)
    return out
}

/**
 * Draws [stack] (bottom first) onto [out]: each layer transformed, masked, clipped and blended.
 * A group layer's members are drawn the same way into a buffer of their own first.
 */
private fun drawStack(doc: ComposeDoc, stack: List<ComposeLayer>, out: Bitmap, scale: Float, pixels: LayerPixels, checkCancel: () -> Unit, depth: Int) {
    val w = out.width
    val h = out.height
    val canvas = Canvas(out)
    val tmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val tmpCanvas = Canvas(tmp)
    // The last unclipped layer, as drawn: what clipped layers above it are confined to.
    var clipBase: Bitmap? = null
    stack.forEachIndexed { i, layer ->
        checkCancel()
        val nextClips = stack.getOrNull(i + 1)?.clip == true
        if (!layer.visible || layer.opacity <= 0f) {
            if (!layer.clip) clipBase = clipBase?.let { it.recycle(); null }
            return@forEachIndexed
        }
        tmp.eraseColor(0)
        if (layer.isGroup) {
            if (depth < 16) drawStack(doc, doc.children(layer.id), tmp, scale, pixels, checkCancel, depth + 1)
        } else {
            // Decode only as big as the layer is drawn.
            val drawnLong = (maxOf(layer.sourceWidth, layer.sourceHeight) * maxOf(kotlin.math.abs(layer.transform.sx), kotlin.math.abs(layer.transform.sy)) * scale)
                .toInt().coerceIn(16, MAX_LAYER_PX)
            val bmp = pixels.get(layer, drawnLong, checkCancel) ?: return@forEachIndexed
            tmpCanvas.drawBitmap(bmp, layerMatrix(layer, bmp, scale), Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        }
        layer.mask?.takeIf { it.enabled }?.let { applyMask(tmpCanvas, it, w, h, scale) }
        if (layer.clip) {
            clipBase?.let { tmpCanvas.drawBitmap(it, 0f, 0f, Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }) }
        } else {
            clipBase?.recycle()
            clipBase = if (nextClips) tmp.copy(Bitmap.Config.ARGB_8888, false) else null
        }
        layer.blendIf?.let { applyBlendIf(tmp, out, it) }
        if (layer.blend.isSubtract) {
            dk.cbavf.studio.blend.SubtractMath.blendOnto(out, tmp, layer.opacity, reverse = layer.blend == Blend.ReverseSubtract)
        } else canvas.drawBitmap(tmp, 0f, 0f, Paint().apply {
            alpha = (layer.opacity.coerceIn(0f, 1f) * 255).toInt()
            blendMode = layer.blend.android
        })
    }
    clipBase?.recycle()
    tmp.recycle()
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
    // The clip keeps only what lies inside it, whatever the shapes and dabs did.
    mask.clip?.takeIf { it.points.size >= 3 }?.let { sh ->
        val c = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
        val path = android.graphics.Path().apply {
            moveTo(sh.points[0].x * scale, sh.points[0].y * scale)
            sh.points.drop(1).forEach { lineTo(it.x * scale, it.y * scale) }
            close()
        }
        Canvas(c).drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF000000.toInt()
            if (sh.feather * scale >= 1f) maskFilter = BlurMaskFilter(sh.feather * scale, BlurMaskFilter.Blur.NORMAL)
        })
        mc.drawBitmap(c, 0f, 0f, Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) })
        c.recycle()
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
            // Clone / heal strokes: applied to the pixel buffer after vector strokes are drawn.
            // A snapshot is taken before applying so that source reads do not see earlier writes.
            if (source.cloneStrokes.isNotEmpty()) {
                val px = IntArray(w * h)
                bmp.getPixels(px, 0, w, 0, 0, w, h)
                val snapshot = px.copyOf()
                for (cs in source.cloneStrokes) {
                    for (pt in cs.points) {
                        val dstX = pt.x * k
                        val dstY = pt.y * k
                        val srcX = (pt.x + cs.offsetX) * k
                        val srcY = (pt.y + cs.offsetY) * k
                        val dab = CloneBrush.plan(srcX, srcY, dstX, dstY, cs.radius * k, w, h) ?: continue
                        when (cs.mode) {
                            CloneMode.Clone -> CloneBrush.applyClone(px, w, h, dab, snapshot)
                            CloneMode.Heal -> CloneBrush.applyHeal(px, w, h, dab, snapshot)
                        }
                    }
                }
                bmp.setPixels(px, 0, w, 0, 0, w, h)
            }
        }
        is TextLayerSource -> {
            c.scale(k, k)
            val paint = textPaint(source)
            val fm = paint.fontMetrics
            source.text.split('\n').forEachIndexed { i, line ->
                c.drawText(line, source.lineX(paint.measureText(line)), -fm.ascent + i * (fm.descent - fm.ascent), paint)
            }
        }
        else -> Unit
    }
    return bmp
}

/** The paint a text layer is drawn with: its font family, bold / italic, size, colour and letter spacing. */
fun textPaint(src: TextLayerSource) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    textSize = src.sizePx
    color = src.argb.toInt()
    // An unknown family name falls back to the default font, so a missing one never fails.
    typeface = android.graphics.Typeface.create(src.font.family, src.styleBits)
    letterSpacing = src.letterSpacing
}

/** Size of the layer's text (several lines allowed) in pixels; slanted letters get a little room on the right. */
fun measureText(src: TextLayerSource): Pair<Int, Int> {
    val p = textPaint(src)
    val lines = src.text.split('\n')
    val fm = p.fontMetrics
    val slant = if (src.italic || src.font == dk.cbavf.studio.compose.model.TextFont.Script) src.sizePx * 0.25f else 0f
    val w = (lines.maxOf { p.measureText(it) } + slant).toInt() + 2
    val h = ((fm.descent - fm.ascent) * lines.size).toInt() + 2
    return w.coerceAtLeast(1) to h.coerceAtLeast(1)
}

/** [src] with its size measured again (after the text, font or spacing changed). */
fun remeasured(src: TextLayerSource): TextLayerSource = measureText(src).let { (w, h) -> src.copy(widthPx = w, heightPx = h) }

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
        // Handled by blend/SubtractMath.kt before this is asked; the plain composite is the safe fallback.
        Blend.Subtract, Blend.ReverseSubtract -> AndroidBlend.SRC_OVER
    }
