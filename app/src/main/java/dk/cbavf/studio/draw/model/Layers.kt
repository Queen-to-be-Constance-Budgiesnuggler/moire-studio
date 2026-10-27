package dk.cbavf.studio.draw.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Blend modes from the Draw spec, applied per object. */
enum class Blend {
    Normal, Lighten, Multiply, Difference, Screen,
    Darken, Overlay, SoftLight, HardLight, Exclusion, Add, ColorDodge, ColorBurn,
    Hue, Saturation, Color, Luminosity,
    /** Below minus above, and above minus below, per channel. No Android blend mode does this: see blend/SubtractMath.kt (bitmap pipelines only). */
    Subtract, ReverseSubtract,
}

enum class Ink(val argb: Long) {
    Black(0xFF000000),
    White(0xFFFFFFFF),
    Red(0xFFFF0000),
    Green(0xFF00FF00),
    Blue(0xFF0000FF),
    Cyan(0xFF00FFFF),
    Magenta(0xFFFF00FF),
    Yellow(0xFFFFFF00),
}

/** Canvas point in device pixels. */
@Serializable
data class Pt(val x: Float, val y: Float) {
    operator fun plus(o: Pt) = Pt(x + o.x, y + o.y)
    operator fun minus(o: Pt) = Pt(x - o.x, y - o.y)
    operator fun times(k: Float) = Pt(x * k, y * k)
}

/**
 * What a layer shows. All lengths are in device pixels: moiré is pixel-sensitive,
 * so the default render mode is screen-exact.
 */
@Serializable
sealed interface Content

/**
 * Parallel straight lines.
 * [angleDeg] is the direction of the lines (0 = horizontal, positive = clockwise on screen).
 * [offsetPx] shifts the lines along their normal; only its value modulo [spacingPx] is visible.
 */
@Serializable
@SerialName("grating")
data class Grating(
    val spacingPx: Float = 10f,
    val angleDeg: Float = 0f,
    val duty: Float = 0.5f,
    val offsetPx: Float = 0f,
    /** Hard lines, or a smooth sine profile (two sine gratings beat without harmonics). */
    val profile: Profile = Profile.Lines,
) : Content

enum class Profile { Lines, Sine }

/**
 * Concentric ellipses ([aspect] = height / width; 1 = circles) around a centre given as an
 * offset from the canvas centre. [offsetPx] moves the rings outwards; only its value modulo
 * [spacingPx] is visible.
 */
@Serializable
@SerialName("ellipses")
data class EllipseHatch(
    val spacingPx: Float = 12f,
    val aspect: Float = 0.6f,
    val rotationDeg: Float = 0f,
    val duty: Float = 0.5f,
    val offsetPx: Float = 0f,
    val centerXPx: Float = 0f,
    val centerYPx: Float = 0f,
) : Content

/** Wedge-shaped rays around a centre ([centerXPx], [centerYPx] are offsets from the canvas centre). */
@Serializable
@SerialName("burst")
data class RadialBurst(
    val rays: Int = 180,
    val duty: Float = 0.5f,
    val rotationDeg: Float = 0f,
    val centerXPx: Float = 0f,
    val centerYPx: Float = 0f,
) : Content

enum class Cap { Round, Butt, Square }

/** One line in a stroke stack, drawn along the stroke's base path. */
@Serializable
data class SubStroke(
    val widthPx: Float = 6f,
    val ink: Ink = Ink.Black,
    /** Dash length; 0 = solid. */
    val dashPx: Float = 0f,
    val gapPx: Float = 0f,
    val dashPhasePx: Float = 0f,
    /** Parallel offset from the base path (positive = right of the drawing direction on screen). */
    val offsetPx: Float = 0f,
    val cap: Cap = Cap.Round,
) {
    val dashed get() = dashPx > 0f && gapPx > 0f
    val dashPeriod get() = dashPx + gapPx
}

/** Saved object transform: screen = translate + rotate(scale(local)). Scale may be uneven. */
@Serializable
data class Transform(
    val tx: Float = 0f,
    val ty: Float = 0f,
    val rotationDeg: Float = 0f,
    val sx: Float = 1f,
    val sy: Float = 1f,
) {
    fun apply(p: Pt): Pt {
        val x = p.x * sx
        val y = p.y * sy
        val a = rotationDeg * PI.toFloat() / 180
        val c = cos(a)
        val s = sin(a)
        return Pt(tx + x * c - y * s, ty + x * s + y * c)
    }
}

/**
 * An object placed on the canvas with its own saved transform (move, rotate, uneven stretch):
 * hand-drawn strokes and Library images. Move gestures, stretch handles, Animate steps and Patch
 * routes treat both alike.
 */
@Serializable
sealed interface Placed : Content {
    val transform: Transform

    fun withTransform(t: Transform): Placed

    /** Outline corners in local (untransformed) units. */
    fun localCorners(): List<Pt>
}

/** A hand-drawn object: a base path in local coordinates (centred on its centroid) plus a stroke stack. */
@Serializable
@SerialName("stroke")
data class Stroke(
    val points: List<Pt>,
    override val transform: Transform,
    val stack: List<SubStroke>,
) : Placed {
    fun screenPoints(): List<Pt> = points.map(transform::apply)

    override fun withTransform(t: Transform) = copy(transform = t)

    override fun localCorners(): List<Pt> {
        if (points.isEmpty()) return emptyList()
        val pad = stack.maxOfOrNull { kotlin.math.abs(it.offsetPx) + it.widthPx / 2 } ?: 0f
        val x0 = points.minOf { it.x } - pad
        val x1 = points.maxOf { it.x } + pad
        val y0 = points.minOf { it.y } - pad
        val y1 = points.maxOf { it.y } + pad
        return listOf(Pt(x0, y0), Pt(x1, y0), Pt(x1, y1), Pt(x0, y1))
    }

    companion object {
        fun fromScreen(points: List<Pt>, stack: List<SubStroke>): Stroke {
            val c = Geometry.centroid(points)
            return Stroke(points.map { it - c }, Transform(tx = c.x, ty = c.y), stack)
        }
    }
}

/**
 * A Library image on the canvas. Local units are the image's own pixels (centred on the
 * origin), so a scale of 1 shows it 1:1 on screen.
 */
@Serializable
@SerialName("image")
data class ImageLayer(
    val assetId: String,
    val widthPx: Int,
    val heightPx: Int,
    override val transform: Transform,
    val opacity: Float = 1f,
) : Placed {
    override fun withTransform(t: Transform) = copy(transform = t)

    override fun localCorners(): List<Pt> {
        val w = widthPx / 2f
        val h = heightPx / 2f
        return listOf(Pt(-w, -h), Pt(w, -h), Pt(w, h), Pt(-w, h))
    }

    /** True when screen point [p] falls on the image. */
    fun contains(p: Pt): Boolean = Geometry.insidePolygon(p, localCorners().map(transform::apply))

    companion object {
        /** Centred and scaled to fit [fraction] of a canvas of the given size. */
        fun fitted(assetId: String, w: Int, h: Int, canvasW: Int, canvasH: Int, fraction: Float = 0.9f): ImageLayer {
            val k = if (w > 0 && h > 0 && canvasW > 0 && canvasH > 0) minOf(canvasW.toFloat() / w, canvasH.toFloat() / h) * fraction else 1f
            return ImageLayer(assetId, w, h, Transform(tx = canvasW / 2f, ty = canvasH / 2f, sx = k, sy = k))
        }
    }
}

/** Screen-space corners of a placed object. */
fun Placed.screenCorners(): List<Pt> = localCorners().map(transform::apply)

@Serializable
data class Layer(
    val id: Int,
    val content: Content,
    val blend: Blend = Blend.Normal,
    val ink: Ink = Ink.Black,
    val visible: Boolean = true,
    /** Objects with the same group are picked together (Group / Ungroup in Draw). */
    val group: Int? = null,
)

/**
 * A named group of layers. Groups nest ([parent]), collapse in the layer tabs ([collapsed]) and hide
 * or show everything inside them at once ([visible]). A layer belongs to one group through [Layer.group].
 */
@Serializable
data class LayerGroup(
    val id: Int,
    val name: String = "",
    val parent: Int? = null,
    val collapsed: Boolean = false,
    val visible: Boolean = true,
)

/** Short name of a layer's content, for chips and readouts. */
val Content.label: String
    get() = when (this) {
        is Grating -> if (profile == Profile.Sine) "Sine" else "Grating"
        is RadialBurst -> "Burst"
        is EllipseHatch -> "Ellipses"
        is Stroke -> "Stroke"
        is ImageLayer -> "Image"
    }
