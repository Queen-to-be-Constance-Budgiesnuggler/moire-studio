package dk.cbavf.studio.compose.model

import dk.cbavf.studio.develop.model.DevelopDoc
import dk.cbavf.studio.draw.model.Blend
import dk.cbavf.studio.draw.model.Geometry
import dk.cbavf.studio.draw.model.Pt
import dk.cbavf.studio.draw.model.Transform
import dk.cbavf.studio.library.CanvasDoc
import dk.cbavf.studio.library.imageAssetIds
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** What a Compose layer shows. */
@Serializable
sealed interface LayerSource

/** A Library image at its own pixel size. */
@Serializable
@SerialName("asset")
data class AssetLayerSource(val assetId: String, val widthPx: Int, val heightPx: Int) : LayerSource

/**
 * A Draw-style canvas (gratings, bursts, ellipses, strokes, images), rendered at its own size.
 * Without [paper] it is transparent between the lines, so it overlays what is below.
 */
@Serializable
@SerialName("canvas")
data class CanvasLayerSource(val doc: CanvasDoc, val paper: Boolean = false) : LayerSource

/** A flat colour, [widthPx]×[heightPx] (a fill layer). */
@Serializable
@SerialName("solid")
data class SolidLayerSource(val argb: Long, val widthPx: Int, val heightPx: Int) : LayerSource

/** A two-colour gradient across the layer: linear at [angleDeg], or radial from the centre. */
@Serializable
@SerialName("gradient")
data class GradientLayerSource(
    val fromArgb: Long,
    val toArgb: Long,
    val angleDeg: Float = 90f,
    val radial: Boolean = false,
    val widthPx: Int,
    val heightPx: Int,
) : LayerSource

/** One brush stroke on a paint layer, in the layer's own pixels. [erase] removes paint instead. */
@Serializable
data class PaintStroke(
    val points: List<Pt>,
    val argb: Long,
    val width: Float,
    val softness: Float = 0f,
    val erase: Boolean = false,
    /** A filled polygon (Fill / Erase selection) instead of a line. */
    val fill: Boolean = false,
)

/** A transparent sheet painted with brush strokes (vector, so it stays sharp at any size). */
@Serializable
@SerialName("paint")
data class PaintLayerSource(val widthPx: Int, val heightPx: Int, val strokes: List<PaintStroke> = emptyList()) : LayerSource

/** A line (or lines) of text; its size is measured when it is made. */
@Serializable
@SerialName("text")
data class TextLayerSource(
    val text: String,
    val sizePx: Float,
    val argb: Long,
    val bold: Boolean = false,
    val widthPx: Int,
    val heightPx: Int,
) : LayerSource

/** Per-layer filters, applied in order after the layer's adjustments. */
@Serializable
sealed interface LayerFilter {
    @Serializable @SerialName("blur")
    data class Blur(val radius: Float = 8f) : LayerFilter

    @Serializable @SerialName("sharpen")
    data class Sharpen(val amount: Float = 1f, val radius: Float = 2f) : LayerFilter

    @Serializable @SerialName("noise")
    data class Noise(val amount: Float = 0.1f, val seed: Int = 1, val mono: Boolean = true) : LayerFilter

    @Serializable @SerialName("invert")
    data object Invert : LayerFilter

    @Serializable @SerialName("desaturate")
    data object Desaturate : LayerFilter

    @Serializable @SerialName("posterize")
    data class Posterize(val levels: Int = 4) : LayerFilter

    @Serializable @SerialName("threshold")
    data class Threshold(val level: Float = 0.5f) : LayerFilter

    /** FFT low/high/band pass on the layer (the FFT lab's filters, live). */
    @Serializable @SerialName("fft")
    data class FftPass(
        val filter: dk.cbavf.studio.fft.model.FilterShape = dk.cbavf.studio.fft.model.FilterShape.LowPass,
        val radius: Float = 0.1f,
        val band: Float = 0.05f,
    ) : LayerFilter

    /** The Glitch lab's byte misreadings, live. */
    @Serializable @SerialName("glitch")
    data class Glitch(val doc: dk.cbavf.studio.glitch.model.GlitchDoc = dk.cbavf.studio.glitch.model.GlitchDoc(bytesPerPixel = 3)) : LayerFilter
}

val LayerFilter.label: String
    get() = when (this) {
        is LayerFilter.Blur -> "Blur"
        is LayerFilter.Sharpen -> "Sharpen"
        is LayerFilter.Noise -> "Noise"
        LayerFilter.Invert -> "Invert"
        LayerFilter.Desaturate -> "Desaturate"
        is LayerFilter.Posterize -> "Posterize"
        is LayerFilter.Threshold -> "Threshold"
        is LayerFilter.FftPass -> "FFT"
        is LayerFilter.Glitch -> "Glitch"
    }

/**
 * Blending option: the layer only shows where brightness is between [low] and [high] (fading
 * over [feather]), measured on this layer or, with [underlying], on what is below it.
 */
@Serializable
data class BlendIf(val low: Float = 0f, val high: Float = 1f, val feather: Float = 0.1f, val underlying: Boolean = false)

/** A filled polygon on a mask (from a selection), in document pixels. */
@Serializable
data class MaskShape(val points: List<Pt>, val reveal: Boolean, val feather: Float = 0f)

/** A brush dab on a mask, in document pixels. [reveal] paints the layer back in; otherwise it hides. */
@Serializable
data class MaskDab(val x: Float, val y: Float, val radius: Float, val reveal: Boolean, val softness: Float = 0.5f)

/** Painted layer mask: starts fully visible (hidden with [invert]); dabs apply in order. */
@Serializable
data class LayerMask(
    val dabs: List<MaskDab> = emptyList(),
    val invert: Boolean = false,
    val enabled: Boolean = true,
    /** Selection shapes, applied before the dabs. */
    val shapes: List<MaskShape> = emptyList(),
)

@Serializable
data class ComposeLayer(
    val id: Int,
    val name: String,
    val source: LayerSource,
    /** Centre position (document pixels), rotation and scale of the source's own pixels. */
    val transform: Transform,
    val opacity: Float = 1f,
    val blend: Blend = Blend.Normal,
    val visible: Boolean = true,
    val mask: LayerMask? = null,
    /** Develop adjustments for this layer only (exposure, white balance, levels, curve, mixer). */
    val adjust: DevelopDoc? = null,
    val filters: List<LayerFilter> = emptyList(),
    /** Clipping mask: show only where the layer below (the nearest unclipped one) has pixels. */
    val clip: Boolean = false,
    val blendIf: BlendIf? = null,
) {
    val sourceWidth: Int
        get() = when (source) {
            is AssetLayerSource -> source.widthPx
            is CanvasLayerSource -> source.doc.widthPx
            is SolidLayerSource -> source.widthPx
            is GradientLayerSource -> source.widthPx
            is PaintLayerSource -> source.widthPx
            is TextLayerSource -> source.widthPx
        }
    val sourceHeight: Int
        get() = when (source) {
            is AssetLayerSource -> source.heightPx
            is CanvasLayerSource -> source.doc.heightPx
            is SolidLayerSource -> source.heightPx
            is GradientLayerSource -> source.heightPx
            is PaintLayerSource -> source.heightPx
            is TextLayerSource -> source.heightPx
        }

    /** A document point in this layer's own pixels (origin at the top-left of the source). */
    fun toLocal(p: Pt): Pt {
        val t = transform
        val a = -t.rotationDeg * kotlin.math.PI.toFloat() / 180
        val dx = p.x - t.tx
        val dy = p.y - t.ty
        val rx = dx * kotlin.math.cos(a) - dy * kotlin.math.sin(a)
        val ry = dx * kotlin.math.sin(a) + dy * kotlin.math.cos(a)
        return Pt(rx / (if (t.sx == 0f) 1f else t.sx) + sourceWidth / 2f, ry / (if (t.sy == 0f) 1f else t.sy) + sourceHeight / 2f)
    }

    /** Corners of the layer in document pixels. */
    fun corners(): List<Pt> {
        val w = sourceWidth / 2f
        val h = sourceHeight / 2f
        return listOf(Pt(-w, -h), Pt(w, -h), Pt(w, h), Pt(-w, h)).map(transform::apply)
    }

    fun contains(p: Pt): Boolean = Geometry.insidePolygon(p, corners())
}

enum class Background(val argb: Long?) { White(0xFFFFFFFF), Black(0xFF000000), Transparent(null) }

/** A layered composite: the main image the studio builds. Also the recipe of what it saves. */
@Serializable
data class ComposeDoc(
    val widthPx: Int = 1080,
    val heightPx: Int = 1920,
    val background: Background = Background.White,
    /** Bottom first. */
    val layers: List<ComposeLayer> = emptyList(),
) {
    val nextId: Int get() = (layers.maxOfOrNull { it.id } ?: 0) + 1

    /** Library images this composite uses (directly or inside canvas layers): its parents. */
    fun assetIds(): List<String> = layers.flatMap { l ->
        when (val s = l.source) {
            is AssetLayerSource -> listOf(s.assetId)
            is CanvasLayerSource -> s.doc.imageAssetIds()
            else -> emptyList()
        }
    }.distinct()

    val summary: String get() = "${layers.size} layers · ${widthPx}×$heightPx"

    /**
     * Crops the document to the rectangle between ([x0], [y0]) and ([x1], [y1]) in document pixels:
     * every layer keeps its place in the picture, and masks move with it.
     */
    fun cropped(x0: Float, y0: Float, x1: Float, y1: Float): ComposeDoc {
        val l = minOf(x0, x1).coerceAtLeast(0f)
        val t = minOf(y0, y1).coerceAtLeast(0f)
        val r = maxOf(x0, x1).coerceAtMost(widthPx.toFloat())
        val b = maxOf(y0, y1).coerceAtMost(heightPx.toFloat())
        if (r - l < 2 || b - t < 2) return this
        fun shift(p: Pt) = Pt(p.x - l, p.y - t)
        return copy(
            widthPx = (r - l).toInt(),
            heightPx = (b - t).toInt(),
            layers = layers.map { layer ->
                layer.copy(
                    transform = layer.transform.copy(tx = layer.transform.tx - l, ty = layer.transform.ty - t),
                    mask = layer.mask?.let { m ->
                        m.copy(
                            dabs = m.dabs.map { it.copy(x = it.x - l, y = it.y - t) },
                            shapes = m.shapes.map { sh -> sh.copy(points = sh.points.map(::shift)) },
                        )
                    },
                )
            },
        )
    }

    companion object {
        /** A layer for a source, centred and fitted inside the document (never enlarged past [fraction]). */
        fun fitted(id: Int, name: String, source: LayerSource, w: Int, h: Int, docW: Int, docH: Int, fraction: Float = 1f): ComposeLayer {
            val k = if (w > 0 && h > 0) minOf(docW.toFloat() / w, docH.toFloat() / h) * fraction else 1f
            return ComposeLayer(id, name, source, Transform(tx = docW / 2f, ty = docH / 2f, sx = k, sy = k))
        }
    }
}
