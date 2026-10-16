package dk.cbavf.studio.draw.model

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Limits shared by Draw gestures and Animate steps. */
object MotionLimits {
    const val MIN_SPACING = 2f
    const val MAX_SPACING = 80f
    const val MIN_SCALE = 0.05f
    const val MAX_SCALE = 20f
}

/**
 * Moves, turns and scales one object. Strokes turn and scale around [pivot] (their own centre
 * for a single object, the group centre for a selection); gratings only show motion along their
 * normal, and bursts move their centre.
 */
fun Content.transformed(pan: Pt, zoom: Float, rotationDeg: Float, pivot: Pt): Content = when (this) {
    is Grating -> {
        val angle = (angleDeg + rotationDeg) * PI.toFloat() / 180
        // Only motion along the normal is visible on a grating.
        val along = pan.x * -sin(angle) + pan.y * cos(angle)
        copy(
            angleDeg = wrapDeg(angleDeg + rotationDeg),
            spacingPx = (spacingPx * zoom).coerceIn(MotionLimits.MIN_SPACING, MotionLimits.MAX_SPACING),
            offsetPx = offsetPx + along,
        )
    }
    is EllipseHatch -> copy(
        rotationDeg = wrapDeg(this.rotationDeg + rotationDeg),
        spacingPx = (spacingPx * zoom).coerceIn(MotionLimits.MIN_SPACING, MotionLimits.MAX_SPACING),
        centerXPx = centerXPx + pan.x,
        centerYPx = centerYPx + pan.y,
    )
    is RadialBurst -> copy(
        rotationDeg = wrapDeg(this.rotationDeg + rotationDeg),
        centerXPx = centerXPx + pan.x,
        centerYPx = centerYPx + pan.y,
    )
    is Placed -> {
        val t = transform
        val a = rotationDeg * PI.toFloat() / 180
        val rel = Pt(t.tx, t.ty) - pivot
        val moved = Pt(
            rel.x * cos(a) - rel.y * sin(a),
            rel.x * sin(a) + rel.y * cos(a),
        ) * zoom + pivot + pan
        withTransform(
            t.copy(
                tx = moved.x,
                ty = moved.y,
                rotationDeg = wrapDeg(t.rotationDeg + rotationDeg),
                sx = (t.sx * zoom).coerceIn(MotionLimits.MIN_SCALE, MotionLimits.MAX_SCALE),
                sy = (t.sy * zoom).coerceIn(MotionLimits.MIN_SCALE, MotionLimits.MAX_SCALE),
            )
        )
    }
}

/** Where a single object turns around: a stroke's or image's own centre; gratings and bursts ignore it. */
val Content.ownPivot: Pt
    get() = if (this is Placed) Pt(transform.tx, transform.ty) else Pt(0f, 0f)

/** Wrap to (−180, 180] so sliders and readouts stay in range. */
fun wrapDeg(d: Float): Float {
    var x = d % 360f
    if (x <= -180f) x += 360f
    if (x > 180f) x -= 360f
    return x
}
