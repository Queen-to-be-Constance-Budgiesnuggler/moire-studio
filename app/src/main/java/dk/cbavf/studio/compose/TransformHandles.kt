package dk.cbavf.studio.compose

import dk.cbavf.studio.compose.model.ComposeLayer
import dk.cbavf.studio.draw.model.Pt
import dk.cbavf.studio.draw.model.Transform
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The handles drawn on the selected layer in Move: corners scale it evenly, edge middles stretch
 * it along one of its own axes, and the knob above the top edge turns it. All in document pixels;
 * the layer always scales and turns about its centre.
 */
object TransformHandles {
    enum class Kind { Corner, Edge, Rotate }

    /**
     * One handle: corners are numbered like [ComposeLayer.corners] (top-left first, clockwise),
     * edges 0 top, 1 right, 2 bottom, 3 left.
     */
    data class Handle(val kind: Kind, val index: Int, val at: Pt)

    /** Smallest size a handle can make a layer, as a scale. */
    const val MIN_SCALE = 0.01f
    const val MAX_SCALE = 100f

    /** Within this many degrees of a multiple of 45°, the turn snaps to it. */
    const val SNAP_DEG = 3f

    /** Every handle of [layer]; the turn knob sits [rotateArm] document pixels above the top edge. */
    fun handles(layer: ComposeLayer, rotateArm: Float): List<Handle> {
        val c = layer.corners()
        val edges = List(4) { i -> mid(c[i], c[(i + 1) % 4]) }
        val centre = Pt(layer.transform.tx, layer.transform.ty)
        val top = edges[0]
        // Straight out from the centre through the top edge (a flipped layer's top is wherever it went).
        val len = hypot(top.x - centre.x, top.y - centre.y)
        val knob = if (len < 1e-3f) Pt(top.x, top.y - rotateArm)
        else Pt(top.x + (top.x - centre.x) / len * rotateArm, top.y + (top.y - centre.y) / len * rotateArm)
        return c.mapIndexed { i, p -> Handle(Kind.Corner, i, p) } +
            edges.mapIndexed { i, p -> Handle(Kind.Edge, i, p) } +
            Handle(Kind.Rotate, 0, knob)
    }

    /** The handle nearest [p] within [slop] document pixels; the turn knob wins a tie with a corner. */
    fun hit(handles: List<Handle>, p: Pt, slop: Float): Handle? =
        handles
            .map { it to hypot(it.at.x - p.x, it.at.y - p.y) }
            .filter { it.second <= slop }
            .minWithOrNull(compareBy<Pair<Handle, Float>> { it.second }.thenBy { if (it.first.kind == Kind.Rotate) 0 else 1 })
            ?.first

    /** The layer's transform after dragging [handle] from [from] to [to], starting from [base]. */
    fun drag(handle: Handle, base: Transform, from: Pt, to: Pt): Transform = when (handle.kind) {
        Kind.Corner -> scaleEvenly(base, from, to)
        Kind.Edge -> stretch(base, handle.index, from, to)
        Kind.Rotate -> turn(base, from, to)
    }

    /** Corner: the finger's distance from the centre, against where it started, scales both axes. */
    fun scaleEvenly(base: Transform, from: Pt, to: Pt): Transform {
        val d0 = hypot(from.x - base.tx, from.y - base.ty)
        if (d0 < 1e-3f) return base
        val k = hypot(to.x - base.tx, to.y - base.ty) / d0
        return base.copy(sx = clampScale(base.sx * k), sy = clampScale(base.sy * k))
    }

    /**
     * Edge: the finger's distance from the centre along the layer's own axis (x for the side
     * edges, y for the top and bottom) scales that axis only. Never flips: crossing the centre
     * stops at the smallest size.
     */
    fun stretch(base: Transform, edge: Int, from: Pt, to: Pt): Transform {
        val a = local(base, from)
        val b = local(base, to)
        val horizontal = edge == 1 || edge == 3
        val f = if (horizontal) a.x else a.y
        val t = if (horizontal) b.x else b.y
        if (abs(f) < 1e-3f) return base
        val k = (t / f).coerceAtLeast(0f)
        return if (horizontal) base.copy(sx = clampScale(base.sx * k)) else base.copy(sy = clampScale(base.sy * k))
    }

    /** Knob: the change in the finger's angle around the centre turns the layer, snapping near 45° steps. */
    fun turn(base: Transform, from: Pt, to: Pt): Transform {
        val a0 = atan2(from.y - base.ty, from.x - base.tx)
        val a1 = atan2(to.y - base.ty, to.x - base.tx)
        val deg = normalize(base.rotationDeg + (a1 - a0) * 180f / PI.toFloat())
        return base.copy(rotationDeg = snap(deg))
    }

    /** An angle in −180..180. */
    fun normalize(deg: Float): Float {
        var d = deg % 360f
        if (d > 180f) d -= 360f
        if (d <= -180f) d += 360f
        return d
    }

    /** Near a multiple of 45° (within [SNAP_DEG]) the angle becomes that multiple. */
    fun snap(deg: Float): Float {
        val step = (deg / 45f).roundToInt() * 45f
        return if (abs(deg - step) <= SNAP_DEG) normalize(step) else deg
    }

    /** A point relative to the centre, along the layer's own (turned) axes. */
    private fun local(t: Transform, p: Pt): Pt {
        val a = -t.rotationDeg * PI.toFloat() / 180
        val dx = p.x - t.tx
        val dy = p.y - t.ty
        return Pt(dx * cos(a) - dy * sin(a), dx * sin(a) + dy * cos(a))
    }

    /** Keeps the size within limits and the sign (a flipped layer stays flipped). */
    private fun clampScale(s: Float): Float {
        val sg = if (s < 0f) -1f else 1f
        return sg * abs(s).coerceIn(MIN_SCALE, MAX_SCALE)
    }

    private fun mid(a: Pt, b: Pt) = Pt((a.x + b.x) / 2, (a.y + b.y) / 2)
}
