package dk.cbavf.studio.physics.model

import dk.cbavf.studio.draw.model.Content
import dk.cbavf.studio.draw.model.ImageLayer
import dk.cbavf.studio.draw.model.Pt
import dk.cbavf.studio.draw.model.Stroke
import dk.cbavf.studio.draw.model.wrapDeg
import dk.cbavf.studio.library.CanvasDoc
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/** The outline a body collides with, in canvas pixels around the body's centre (unrotated). */
sealed interface BodyShape {
    data class Circle(val radius: Float) : BodyShape

    /** A rectangle [width] along the body's own x axis. */
    data class Box(val width: Float, val height: Float) : BodyShape

    /** A convex outline, anticlockwise in the usual maths sense (positive area), at most [ShapeMapper.MAX_POLYGON] corners. */
    data class Polygon(val points: List<Pt>) : BodyShape

    /** An open line: a floor, a ramp, a cup. Never moves. */
    data class Chain(val points: List<Pt>) : BodyShape
}

/** What a body does: falls and collides, stays put (others hit it), or is left out. */
enum class BodyKind(val label: String) { Moving("Moving"), Fixed("Fixed"), Off("Off") }

/** One layer as a body: its shape, where its centre is on the canvas and how far it is turned. */
data class BodySpec(val layerId: Int, val shape: BodyShape, val centre: Pt, val angleDeg: Float, val kind: BodyKind)

/**
 * Draw layers to bodies. An image is a box. A hand-drawn stroke that ends near where it began is a closed shape: a circle if it is
 * round enough, a box if it nearly fills its tightest rectangle, else its convex outline. A stroke left open is a fixed line.
 * Gratings, bursts and ellipses are background and make no body.
 */
object ShapeMapper {
    /** A stroke whose ends are closer than this share of its size is closed. */
    const val CLOSED_GAP = 0.25f

    /** A closed stroke whose distance from its centre varies by less than this share is a circle. */
    const val ROUND_SPREAD = 0.12f

    /** A closed stroke whose outline fills this share of its tightest rectangle is a box. */
    const val BOX_FILL = 0.9f

    const val MAX_POLYGON = 8
    const val MAX_CHAIN = 24

    /** Smaller strokes (a dot, a tap) make no body. */
    const val MIN_SIZE_PX = 4f

    /** Every visible layer that can be a body, with the kind picked for it ([kinds]) or its default. */
    fun specs(doc: CanvasDoc, kinds: Map<Int, BodyKind> = emptyMap()): List<BodySpec> =
        doc.layers.filter { it.visible }.mapNotNull { layer ->
            val (shape, centre, angle) = shapeOf(layer.content) ?: return@mapNotNull null
            val kind = kinds[layer.id] ?: defaultKind(shape)
            // A line has no inside to weigh: it can only stay put or be left out.
            BodySpec(layer.id, shape, centre, angle, if (shape is BodyShape.Chain && kind == BodyKind.Moving) BodyKind.Fixed else kind)
        }

    fun defaultKind(shape: BodyShape) = if (shape is BodyShape.Chain) BodyKind.Fixed else BodyKind.Moving

    /** The kinds a body can be switched through, in order. */
    fun kindsFor(shape: BodyShape): List<BodyKind> =
        if (shape is BodyShape.Chain) listOf(BodyKind.Fixed, BodyKind.Off) else BodyKind.entries

    /** The shape, centre and angle of one layer's content, or null when it makes no body. */
    fun shapeOf(content: Content): Triple<BodyShape, Pt, Float>? = when (content) {
        is ImageLayer -> {
            val t = content.transform
            val w = content.widthPx * abs(t.sx)
            val h = content.heightPx * abs(t.sy)
            if (w < MIN_SIZE_PX || h < MIN_SIZE_PX) null else Triple(BodyShape.Box(w, h), Pt(t.tx, t.ty), t.rotationDeg)
        }
        is Stroke -> strokeShape(content)
        else -> null
    }

    private fun strokeShape(s: Stroke): Triple<BodyShape, Pt, Float>? {
        val pts = dedupe(s.screenPoints())
        if (pts.size < 2) return null
        val size = maxOf(pts.maxOf { it.x } - pts.minOf { it.x }, pts.maxOf { it.y } - pts.minOf { it.y })
        if (size < MIN_SIZE_PX) return null
        val pad = s.stack.maxOfOrNull { abs(it.offsetPx) + it.widthPx / 2 } ?: 0f
        val closed = pts.size >= 3 && dist(pts.first(), pts.last()) <= CLOSED_GAP * size
        if (!closed) {
            val c = Pt((pts.minOf { it.x } + pts.maxOf { it.x }) / 2, (pts.minOf { it.y } + pts.maxOf { it.y }) / 2)
            return Triple(BodyShape.Chain(decimate(pts, MAX_CHAIN).map { it - c }), c, 0f)
        }
        val hull = convexHull(pts)
        if (hull.size < 3) return null
        // A box first: a circle fills only π/4 of its rectangle, a square nearly all of it (and is nearly round too).
        val rect = minAreaRect(hull)
        if (rect.width * rect.height > 0f && area(hull) / (rect.width * rect.height) >= BOX_FILL) {
            return Triple(BodyShape.Box(rect.width + 2 * pad, rect.height + 2 * pad), rect.centre, rect.angleDeg)
        }
        // The middle of the tightest rectangle: a mean of the points leans towards where the hand went slowly or overlapped.
        val c = rect.centre
        val radii = pts.map { dist(it, c) }
        val r = radii.average().toFloat()
        val spread = sqrt(radii.sumOf { ((it - r) * (it - r)).toDouble() } / radii.size).toFloat()
        if (r > 0f && spread / r <= ROUND_SPREAD) return Triple(BodyShape.Circle(r + pad), c, 0f)
        val corners = simplifyHull(hull, MAX_POLYGON)
        val pc = mean(corners)
        // Grow the outline by the line's half width so the ink's outer edge is what collides.
        val grown = corners.map { p ->
            val d = dist(p, pc)
            if (d == 0f) p - pc else (p - pc) * ((d + pad) / d)
        }
        return Triple(BodyShape.Polygon(grown), pc, 0f)
    }

    /** A rectangle: centre, [width] along [angleDeg], and height. */
    data class Rect(val centre: Pt, val width: Float, val height: Float, val angleDeg: Float)

    /** Convex hull (monotone chain), anticlockwise in the maths sense: [area] of it is positive. Collinear points are dropped. */
    fun convexHull(points: List<Pt>): List<Pt> {
        val p = points.distinct().sortedWith(compareBy<Pt> { it.x }.thenBy { it.y })
        if (p.size < 3) return p
        fun cross(o: Pt, a: Pt, b: Pt) = (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)
        val lower = ArrayList<Pt>()
        for (q in p) {
            while (lower.size >= 2 && cross(lower[lower.size - 2], lower.last(), q) <= 0f) lower.removeAt(lower.lastIndex)
            lower.add(q)
        }
        val upper = ArrayList<Pt>()
        for (q in p.asReversed()) {
            while (upper.size >= 2 && cross(upper[upper.size - 2], upper.last(), q) <= 0f) upper.removeAt(upper.lastIndex)
            upper.add(q)
        }
        return lower.dropLast(1) + upper.dropLast(1)
    }

    /** Signed area (shoelace): positive for an anticlockwise outline in the maths sense. */
    fun area(poly: List<Pt>): Float {
        var a = 0f
        for (i in poly.indices) {
            val p = poly[i]
            val q = poly[(i + 1) % poly.size]
            a += p.x * q.y - q.x * p.y
        }
        return a / 2
    }

    /** The smallest rectangle round a convex hull: one of its sides lies along a hull edge. */
    fun minAreaRect(hull: List<Pt>): Rect {
        var best: Rect? = null
        for (i in hull.indices) {
            val a = hull[i]
            val b = hull[(i + 1) % hull.size]
            val theta = atan2(b.y - a.y, b.x - a.x)
            val c = cos(theta)
            val s = sin(theta)
            // Each point in the frame of this edge.
            val us = hull.map { it.x * c + it.y * s }
            val vs = hull.map { -it.x * s + it.y * c }
            val w = us.max() - us.min()
            val h = vs.max() - vs.min()
            if (best == null || w * h < best.width * best.height) {
                val cu = (us.max() + us.min()) / 2
                val cv = (vs.max() + vs.min()) / 2
                best = Rect(Pt(cu * c - cv * s, cu * s + cv * c), w, h, wrapDeg((theta * 180 / PI).toFloat()))
            }
        }
        return best ?: Rect(hull.firstOrNull() ?: Pt(0f, 0f), 0f, 0f, 0f)
    }

    /** Drops the corner that adds least area until [max] are left; a convex outline stays convex. */
    fun simplifyHull(hull: List<Pt>, max: Int): List<Pt> {
        val h = hull.toMutableList()
        while (h.size > max.coerceAtLeast(3)) {
            val i = h.indices.minBy { i ->
                val a = h[(i - 1 + h.size) % h.size]
                val b = h[i]
                val c = h[(i + 1) % h.size]
                abs((b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x))
            }
            h.removeAt(i)
        }
        return h
    }

    /** At most [max] points, evenly picked, keeping both ends. */
    fun decimate(points: List<Pt>, max: Int): List<Pt> {
        if (points.size <= max) return points
        return List(max) { i -> points[(i.toLong() * (points.size - 1) / (max - 1)).toInt()] }
    }

    private fun dedupe(points: List<Pt>): List<Pt> =
        points.filterIndexed { i, p -> i == 0 || dist(p, points[i - 1]) > 0.5f }

    private fun mean(points: List<Pt>) = Pt(points.map { it.x }.average().toFloat(), points.map { it.y }.average().toFloat())

    private fun dist(a: Pt, b: Pt) = hypot(a.x - b.x, a.y - b.y)
}
