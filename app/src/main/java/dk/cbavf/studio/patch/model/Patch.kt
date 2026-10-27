package dk.cbavf.studio.patch.model

import dk.cbavf.studio.draw.model.Content
import dk.cbavf.studio.draw.model.EllipseHatch
import dk.cbavf.studio.draw.model.ImageLayer
import dk.cbavf.studio.draw.model.Placed
import dk.cbavf.studio.draw.model.Grating
import dk.cbavf.studio.draw.model.Layer
import dk.cbavf.studio.draw.model.RadialBurst
import dk.cbavf.studio.draw.model.Stroke
import kotlinx.serialization.Serializable
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin

/**
 * Patch bay sources (spec §4). Each reads as a raw number in its own unit; [range] is the span
 * that maps to 0..1 before the route's own range and curve.
 */
enum class SourceKind(val label: String, val unit: String, val range: ClosedFloatingPointRange<Float>) {
    Azimuth("Rotation: heading", "°", -180f..180f),
    Pitch("Rotation: pitch", "°", -90f..90f),
    Roll("Rotation: roll", "°", -180f..180f),
    AccelX("Accelerometer X", "m/s²", -10f..10f),
    AccelY("Accelerometer Y", "m/s²", -10f..10f),
    AccelZ("Accelerometer Z", "m/s²", -10f..10f),
    Magnet("Magnetometer strength", "µT", 0f..100f),
    Light("Ambient light", "lx", 0f..1000f),
    Mic("Microphone level", "dB", -60f..0f),
    Proximity("Proximity (near = 1)", "", 0f..1f),
    TouchX("Touch pad X", "", 0f..1f),
    TouchY("Touch pad Y", "", 0f..1f),
    Lfo1("LFO 1", "", 0f..1f),
    Lfo2("LFO 2", "", 0f..1f),
    ;

    val isLfo: Boolean get() = this == Lfo1 || this == Lfo2
    val isTouch: Boolean get() = this == TouchX || this == TouchY
}

/** Layer parameters a route can drive. Which ones apply depends on the layer's content. */
enum class Param(val label: String, val unit: String, val defaultRange: ClosedFloatingPointRange<Float>) {
    Spacing("Spacing", "px", 4f..30f),
    Angle("Angle", "°", -45f..45f),
    Offset("Offset", "px", 0f..40f),
    Duty("Line width", "", 0.1f..0.9f),
    Rotation("Rotation", "°", -180f..180f),
    Rays("Rays", "", 30f..360f),
    CenterX("Centre X", "px", -300f..300f),
    CenterY("Centre Y", "px", -300f..300f),
    MoveX("Position X", "px", 0f..1080f),
    MoveY("Position Y", "px", 0f..2000f),
    StretchX("Stretch X", "", 0.5f..2f),
    StretchY("Stretch Y", "", 0.5f..2f),
    Aspect("Aspect", "", 0.2f..2f),
    Opacity("Opacity", "", 0f..1f),
    ;

    companion object {
        fun forContent(c: Content): List<Param> = when (c) {
            is Grating -> listOf(Spacing, Angle, Offset, Duty)
            is RadialBurst -> listOf(Rotation, Rays, Duty, CenterX, CenterY)
            is EllipseHatch -> listOf(Spacing, Aspect, Rotation, Offset, Duty, CenterX, CenterY)
            is Stroke -> listOf(MoveX, MoveY, Rotation, StretchX, StretchY)
            is ImageLayer -> listOf(MoveX, MoveY, Rotation, StretchX, StretchY, Opacity)
        }
    }
}

/** How 0..1 bends before it is scaled to the route's range. */
enum class Curve { Linear, Exponential, Logarithmic, Steps }

enum class LfoShape { Sine, Triangle, Square, Saw, Random }

@Serializable
data class Lfo(val shape: LfoShape = LfoShape.Sine, val rateHz: Float = 0.2f)

/** One patch cable: source → layer parameter. */
@Serializable
data class Route(
    val id: Int,
    val source: SourceKind,
    /** Read the source on the other phone (over the Loop link) instead of this one. */
    val remote: Boolean = false,
    val layerId: Int,
    val param: Param,
    val min: Float,
    val max: Float,
    val curve: Curve = Curve.Linear,
    /** 0 = follows instantly, towards 1 = slow and smooth (time constant up to ~2 s). */
    val smoothing: Float = 0.3f,
    val enabled: Boolean = true,
)

@Serializable
data class PatchDoc(
    val routes: List<Route> = emptyList(),
    val lfo1: Lfo = Lfo(),
    val lfo2: Lfo = Lfo(LfoShape.Triangle, 0.05f),
)

object PatchMath {
    /** Raw source value → 0..1, clamped. */
    fun normalize(kind: SourceKind, raw: Float): Float =
        ((raw - kind.range.start) / (kind.range.endInclusive - kind.range.start)).coerceIn(0f, 1f)

    fun curve(x: Float, curve: Curve): Float = when (curve) {
        Curve.Linear -> x
        Curve.Exponential -> x * x * x
        Curve.Logarithmic -> x.toDouble().pow(1.0 / 3).toFloat()
        Curve.Steps -> floor(x * 8 + 0.0001f).coerceAtMost(7f) / 7f
    }

    /** Normalized source → parameter value. */
    fun map(route: Route, normalized: Float): Float = route.min + (route.max - route.min) * curve(normalized, route.curve)

    /**
     * One-pole low-pass: moves [previous] towards [target] for [dtSec]. The time constant grows
     * with smoothing (0 → instant, 1 → 2 s), so the result does not depend on the frame rate.
     */
    fun smooth(previous: Float, target: Float, smoothing: Float, dtSec: Float): Float {
        if (smoothing <= 0f) return target
        val tau = 2f * smoothing * smoothing
        val k = 1f - exp(-dtSec / tau)
        return previous + (target - previous) * k
    }

    /** LFO output 0..1 at time [tSec]. Random holds a new value each cycle (sample and hold). */
    fun lfo(lfo: Lfo, tSec: Double, seed: Int = 0): Float {
        val phase = tSec * lfo.rateHz
        val p = (phase - floor(phase)).toFloat()
        return when (lfo.shape) {
            LfoShape.Sine -> (0.5 + 0.5 * sin(2 * PI * phase)).toFloat()
            LfoShape.Triangle -> 1f - abs(2f * p - 1f)
            LfoShape.Square -> if (p < 0.5f) 1f else 0f
            LfoShape.Saw -> p
            LfoShape.Random -> java.util.Random(floor(phase).toLong() * 31 + seed).nextFloat()
        }
    }

    /** Position, rotation and stretch, shared by strokes and images. */
    private fun placed(c: Placed, param: Param, v: Float): Content = when (param) {
        Param.MoveX -> c.withTransform(c.transform.copy(tx = v))
        Param.MoveY -> c.withTransform(c.transform.copy(ty = v))
        Param.Rotation -> c.withTransform(c.transform.copy(rotationDeg = v))
        Param.StretchX -> c.withTransform(c.transform.copy(sx = v.coerceIn(0.05f, 20f)))
        Param.StretchY -> c.withTransform(c.transform.copy(sy = v.coerceIn(0.05f, 20f)))
        else -> c
    }

    /** Sets one parameter on a layer; parameters that don't fit the content are ignored. */
    fun apply(layer: Layer, param: Param, v: Float): Layer = layer.copy(
        content = when (val c = layer.content) {
            is Grating -> when (param) {
                Param.Spacing -> c.copy(spacingPx = v.coerceIn(2f, 80f))
                Param.Angle -> c.copy(angleDeg = v)
                Param.Offset -> c.copy(offsetPx = v)
                Param.Duty -> c.copy(duty = v.coerceIn(0.02f, 0.98f))
                else -> c
            }
            is RadialBurst -> when (param) {
                Param.Rotation -> c.copy(rotationDeg = v)
                Param.Rays -> c.copy(rays = v.toInt().coerceAtLeast(2))
                Param.Duty -> c.copy(duty = v.coerceIn(0.02f, 0.98f))
                Param.CenterX -> c.copy(centerXPx = v)
                Param.CenterY -> c.copy(centerYPx = v)
                else -> c
            }
            is EllipseHatch -> when (param) {
                Param.Spacing -> c.copy(spacingPx = v.coerceIn(2f, 80f))
                Param.Aspect -> c.copy(aspect = v.coerceIn(0.05f, 20f))
                Param.Rotation -> c.copy(rotationDeg = v)
                Param.Offset -> c.copy(offsetPx = v)
                Param.Duty -> c.copy(duty = v.coerceIn(0.02f, 0.98f))
                Param.CenterX -> c.copy(centerXPx = v)
                Param.CenterY -> c.copy(centerYPx = v)
                else -> c
            }
            is ImageLayer -> when (param) {
                Param.Opacity -> c.copy(opacity = v.coerceIn(0f, 1f))
                else -> placed(c, param, v)
            }
            is Stroke -> when (param) {
                Param.MoveX -> c.copy(transform = c.transform.copy(tx = v))
                Param.MoveY -> c.copy(transform = c.transform.copy(ty = v))
                Param.Rotation -> c.copy(transform = c.transform.copy(rotationDeg = v))
                Param.StretchX -> c.copy(transform = c.transform.copy(sx = v.coerceIn(0.05f, 20f)))
                Param.StretchY -> c.copy(transform = c.transform.copy(sy = v.coerceIn(0.05f, 20f)))
                else -> c
            }
        }
    )
}
