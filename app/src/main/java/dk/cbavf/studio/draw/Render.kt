package dk.cbavf.studio.draw

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import dk.cbavf.studio.draw.model.Blend
import dk.cbavf.studio.draw.model.EllipseHatch
import dk.cbavf.studio.draw.model.ImageLayer
import dk.cbavf.studio.draw.model.Placed
import dk.cbavf.studio.draw.model.Profile
import dk.cbavf.studio.draw.model.Cap
import dk.cbavf.studio.draw.model.Geometry
import dk.cbavf.studio.draw.model.Grating
import dk.cbavf.studio.draw.model.Ink
import dk.cbavf.studio.draw.model.Layer
import dk.cbavf.studio.draw.model.Pt
import dk.cbavf.studio.draw.model.RadialBurst
import dk.cbavf.studio.draw.model.Stroke
import dk.cbavf.studio.draw.model.SubStroke
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin
import androidx.compose.ui.graphics.drawscope.Stroke as StrokeStyle

/**
 * Draws layers in order over a white sheet (or over whatever is below, with [sheet] off, as in
 * the camera overlay). The caller must composite offscreen (CompositingStrategy.Offscreen) so
 * blend modes act on the sheet, not the window.
 */
fun DrawScope.drawLayers(
    layers: List<Layer>,
    sheet: Boolean = true,
    /** Pixels of image layers by asset id; on screen they arrive asynchronously. */
    images: (String) -> ImageBitmap? = LayerImages::forScreen,
) {
    if (sheet) drawRect(Color.White)
    for (layer in layers) {
        if (!layer.visible) continue
        val mode = layer.blend.mode
        when (val c = layer.content) {
            is Grating -> drawGrating(c, layer.ink.color, mode)
            is RadialBurst -> drawBurst(c, layer.ink.color, mode)
            is EllipseHatch -> drawEllipses(c, layer.ink.color, mode)
            is Stroke -> drawStroke(c, mode)
            is ImageLayer -> drawImageLayer(c, mode, images)
        }
    }
}

/** An image at its saved transform; a grey box until its pixels have loaded (or if they can't). */
private fun DrawScope.drawImageLayer(l: ImageLayer, mode: BlendMode, images: (String) -> ImageBitmap?) {
    val t = l.transform
    val w = l.widthPx
    val h = l.heightPx
    withTransform({
        translate(t.tx, t.ty)
        rotate(t.rotationDeg, pivot = Offset.Zero)
        scale(t.sx, t.sy, pivot = Offset.Zero)
    }) {
        val img = images(l.assetId)
        if (img == null) {
            drawRect(Color.Gray.copy(alpha = 0.3f), topLeft = Offset(-w / 2f, -h / 2f), size = Size(w.toFloat(), h.toFloat()))
            return@withTransform
        }
        drawImage(
            img,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(img.width, img.height),
            dstOffset = IntOffset(-w / 2, -h / 2),
            dstSize = IntSize(w, h),
            alpha = l.opacity.coerceIn(0f, 1f),
            blendMode = mode,
            filterQuality = FilterQuality.Low,
        )
    }
}

/** The stroke being drawn, previewed with the pen's stack. */
fun DrawScope.drawPenDraft(points: List<Pt>, stack: List<SubStroke>) {
    if (points.size < 2) return
    drawStack(points, stack, BlendMode.SrcOver)
}

fun DrawScope.drawLassoDraft(points: List<Pt>) {
    if (points.size < 2) return
    val path = Path().apply {
        moveTo(points[0].x, points[0].y)
        for (p in points.drop(1)) lineTo(p.x, p.y)
    }
    drawPath(path, SelectionColor, style = StrokeStyle(width = 3f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f))))
}

/** Dashed bounds with corner marks around each selected stroke or image. */
fun DrawScope.drawSelection(layers: List<Layer>, selection: Set<Int>) {
    for (layer in layers) {
        val c = layer.content
        if (layer.id !in selection || c !is Placed) continue
        if (c is Stroke && c.points.isEmpty()) continue
        val (left, top, right, bottom) = DrawState.bounds(c)
        drawRect(
            SelectionColor,
            topLeft = Offset(left, top),
            size = Size(right - left, bottom - top),
            style = StrokeStyle(width = 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 6f))),
        )
        for (corner in listOf(Offset(left, top), Offset(right, top), Offset(left, bottom), Offset(right, bottom))) {
            drawRect(SelectionColor, topLeft = corner - Offset(HANDLE / 2, HANDLE / 2), size = Size(HANDLE, HANDLE))
        }
    }
}

private val SelectionColor = Color(0xFF00B0FF)
private const val HANDLE = 14f

private val Ink.color get() = Color(argb)

private val Blend.mode get() = when (this) {
    Blend.Normal -> BlendMode.SrcOver
    Blend.Lighten -> BlendMode.Lighten
    Blend.Multiply -> BlendMode.Multiply
    Blend.Difference -> BlendMode.Difference
    Blend.Screen -> BlendMode.Screen
    Blend.Darken -> BlendMode.Darken
    Blend.Overlay -> BlendMode.Overlay
    Blend.SoftLight -> BlendMode.Softlight
    Blend.HardLight -> BlendMode.Hardlight
    Blend.Exclusion -> BlendMode.Exclusion
    Blend.Add -> BlendMode.Plus
    Blend.ColorDodge -> BlendMode.ColorDodge
    Blend.ColorBurn -> BlendMode.ColorBurn
    Blend.Hue -> BlendMode.Hue
    Blend.Saturation -> BlendMode.Saturation
    Blend.Color -> BlendMode.Color
    Blend.Luminosity -> BlendMode.Luminosity
}

private val Cap.stroke get() = when (this) {
    Cap.Round -> StrokeCap.Round
    Cap.Butt -> StrokeCap.Butt
    Cap.Square -> StrokeCap.Square
}

private fun DrawScope.drawStroke(s: Stroke, mode: BlendMode) {
    val t = s.transform
    // Uneven stretch scales the line widths too: the object is stretched as drawn.
    withTransform({
        translate(t.tx, t.ty)
        rotate(t.rotationDeg, pivot = Offset.Zero)
        scale(t.sx, t.sy, pivot = Offset.Zero)
    }) {
        drawStack(s.points, s.stack, mode)
    }
}

private fun DrawScope.drawStack(points: List<Pt>, stack: List<SubStroke>, mode: BlendMode) {
    for (sub in stack) {
        val pts = Geometry.offsetPolyline(points, sub.offsetPx)
        val path = Path().apply {
            moveTo(pts[0].x, pts[0].y)
            for (i in 1 until pts.size) lineTo(pts[i].x, pts[i].y)
        }
        val effect = if (sub.dashed) {
            PathEffect.dashPathEffect(floatArrayOf(sub.dashPx, sub.gapPx), sub.dashPhasePx.mod(sub.dashPeriod))
        } else {
            null
        }
        drawPath(
            path,
            sub.ink.color,
            style = StrokeStyle(width = sub.widthPx, cap = sub.cap.stroke, join = StrokeJoin.Round, pathEffect = effect),
            blendMode = mode,
        )
    }
}

private fun DrawScope.drawGrating(g: Grating, color: Color, mode: BlendMode) {
    if (g.profile == Profile.Sine) return drawSineGrating(g, color, mode)
    val p = g.spacingPx.coerceAtLeast(1f)
    val half = hypot(size.width, size.height) / 2
    val a = g.angleDeg * PI.toFloat() / 180
    val dx = cos(a)
    val dy = sin(a)
    // Normal = line direction + 90°, matching MoireMath.
    val nx = -dy
    val ny = dx
    val phase = g.offsetPx.mod(p)
    val width = (g.duty * p).coerceAtLeast(0.5f)
    val n = ceil(half / p).toInt() + 1
    for (i in -n..n) {
        val d = i * p + phase
        val cx = center.x + nx * d
        val cy = center.y + ny * d
        drawLine(
            color = color,
            start = Offset(cx - dx * half, cy - dy * half),
            end = Offset(cx + dx * half, cy + dy * half),
            strokeWidth = width,
            blendMode = mode,
        )
    }
}

private fun DrawScope.drawBurst(b: RadialBurst, color: Color, mode: BlendMode) {
    val rays = b.rays.coerceAtLeast(2)
    val c = Offset(center.x + b.centerXPx, center.y + b.centerYPx)
    // Long enough to reach every corner from any centre on screen.
    val r = hypot(size.width, size.height) * 2
    val step = 2 * PI / rays
    val halfWedge = step * b.duty.coerceIn(0.01f, 0.99f) / 2
    val rot = b.rotationDeg * PI / 180
    // One path, not one draw per ray: overlapping blends would double up at the centre.
    val path = Path()
    for (i in 0 until rays) {
        val mid = rot + i * step
        path.moveTo(c.x, c.y)
        path.lineTo(c.x + (r * cos(mid - halfWedge)).toFloat(), c.y + (r * sin(mid - halfWedge)).toFloat())
        path.lineTo(c.x + (r * cos(mid + halfWedge)).toFloat(), c.y + (r * sin(mid + halfWedge)).toFloat())
        path.close()
    }
    drawPath(path, color, blendMode = mode)
}

/**
 * Sine profile: ink fades in and out once per period along the normal. Drawn as one repeating
 * gradient across the whole canvas. [Grating.duty] shifts the balance between ink and paper.
 */
private fun DrawScope.drawSineGrating(g: Grating, color: Color, mode: BlendMode) {
    val p = g.spacingPx.coerceAtLeast(1f)
    val a = g.angleDeg * PI.toFloat() / 180
    val nx = -sin(a)
    val ny = cos(a)
    val phase = g.offsetPx.mod(p)
    val start = Offset(center.x + nx * phase, center.y + ny * phase)
    val end = Offset(start.x + nx * p, start.y + ny * p)
    // A sampled cosine: 1 at the line centre, 0 halfway between lines.
    val steps = 16
    val ink = g.duty.coerceIn(0.05f, 0.95f)
    val colors = List(steps + 1) { i ->
        val t = i.toFloat() / steps
        val v = (0.5f + 0.5f * cos(2 * PI * t).toFloat())
        // duty 0.5 = pure sine; other values bias towards more ink or more paper.
        val biased = v.toDouble().pow(kotlin.math.ln(0.5) / kotlin.math.ln(ink.toDouble())).toFloat()
        color.copy(alpha = color.alpha * biased)
    }
    drawRect(
        brush = Brush.linearGradient(colors, start = start, end = end, tileMode = TileMode.Repeated),
        blendMode = mode,
    )
}

/** Concentric ellipses as one stroked path (one draw, so blends don't stack where rings meet). */
private fun DrawScope.drawEllipses(e: EllipseHatch, color: Color, mode: BlendMode) {
    val p = e.spacingPx.coerceAtLeast(1f)
    val c = Offset(center.x + e.centerXPx, center.y + e.centerYPx)
    val aspect = e.aspect.coerceIn(0.05f, 20f)
    // Rings until the smaller semi-axis covers every corner, whichever way the ellipse is turned.
    val reach = hypot(size.width, size.height) + hypot(e.centerXPx, e.centerYPx)
    val rings = (reach / (p * minOf(1f, aspect))).toInt() + 2
    val phase = e.offsetPx.mod(p)
    val path = Path()
    for (k in 0 until rings) {
        val rx = k * p + phase
        if (rx <= 0f) continue
        val ry = rx * aspect
        path.addOval(Rect(c.x - rx, c.y - ry, c.x + rx, c.y + ry))
    }
    rotate(e.rotationDeg, pivot = c) {
        drawPath(path, color, style = StrokeStyle(width = (e.duty * p).coerceAtLeast(0.5f)), blendMode = mode)
    }
}
