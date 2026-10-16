package dk.cbavf.studio.draw.model

import kotlin.math.hypot

object Geometry {
    fun centroid(points: List<Pt>): Pt {
        if (points.isEmpty()) return Pt(0f, 0f)
        var x = 0f
        var y = 0f
        for (p in points) {
            x += p.x
            y += p.y
        }
        return Pt(x / points.size, y / points.size)
    }

    /**
     * Parallel copy of a polyline, [d] pixels to the right of the drawing direction (y down).
     * Each vertex moves along the normal of the chord through its neighbours, which keeps
     * hand-drawn curves smooth; tight corners pinch, as with any simple offset.
     */
    fun offsetPolyline(points: List<Pt>, d: Float): List<Pt> {
        if (d == 0f || points.size < 2) return points
        return points.indices.map { i ->
            val a = points[(i - 1).coerceAtLeast(0)]
            val b = points[(i + 1).coerceAtMost(points.lastIndex)]
            val tx = b.x - a.x
            val ty = b.y - a.y
            val len = hypot(tx, ty)
            if (len == 0f) points[i] else Pt(points[i].x - ty / len * d, points[i].y + tx / len * d)
        }
    }

    /** Even-odd point-in-polygon test; the polygon is closed implicitly. */
    fun insidePolygon(p: Pt, polygon: List<Pt>): Boolean {
        if (polygon.size < 3) return false
        var inside = false
        var j = polygon.lastIndex
        for (i in polygon.indices) {
            val a = polygon[i]
            val b = polygon[j]
            if ((a.y > p.y) != (b.y > p.y) && p.x < (b.x - a.x) * (p.y - a.y) / (b.y - a.y) + a.x) {
                inside = !inside
            }
            j = i
        }
        return inside
    }
}
