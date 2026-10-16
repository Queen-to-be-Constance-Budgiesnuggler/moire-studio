package dk.cbavf.studio.develop.model

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/** The eight hue bands of the mixer, centred every 45° (red at 0°). */
enum class HueBand(val centerDeg: Float) {
    Red(0f), Orange(30f), Yellow(60f), Green(120f), Aqua(180f), Blue(225f), Purple(270f), Magenta(315f)
}

/** Per-band change: hue shift in degrees, saturation and lightness in −1..1. */
@Serializable
data class BandAdjust(val hue: Float = 0f, val saturation: Float = 0f, val lightness: Float = 0f) {
    val isNeutral: Boolean get() = hue == 0f && saturation == 0f && lightness == 0f
}

/** A point on the tone curve, both axes 0..1. */
@Serializable
data class CurvePoint(val x: Float, val y: Float)

enum class MaskKind { None, Lasso, Brush, Spot }

/** A brush dab: centre (0..1 of the image) and radius as a fraction of the image width. */
@Serializable
data class Dab(val x: Float, val y: Float, val radius: Float)

/**
 * Where the edit applies. Lasso and brush coordinates are fractions of the image, so the same
 * mask fits the preview and the full-resolution render.
 */
@Serializable
data class Mask(
    val kind: MaskKind = MaskKind.None,
    val lasso: List<CurvePoint> = emptyList(),
    val dabs: List<Dab> = emptyList(),
    /** Spot colour: hue (degrees) and how far around it counts, with a soft edge. */
    val spotHue: Float = 0f,
    val spotRange: Float = 20f,
    /** Soft edge: degrees for spot, fraction of the image width for lasso and brush. */
    val feather: Float = 0.02f,
    val invert: Boolean = false,
)

/** Everything a Develop edit consists of; also the recipe of the result. */
@Serializable
data class DevelopDoc(
    val sourceAssetId: String? = null,
    val exposureEv: Float = 0f,
    /** Warm/cool, −1..1. */
    val temperature: Float = 0f,
    /** Green/magenta, −1..1. */
    val tint: Float = 0f,
    val black: Float = 0f,
    val white: Float = 1f,
    val gamma: Float = 1f,
    val curve: List<CurvePoint> = listOf(CurvePoint(0f, 0f), CurvePoint(1f, 1f)),
    val bands: Map<HueBand, BandAdjust> = emptyMap(),
    /** Imported .cube file (project-relative path) and how much of it to apply. */
    val lutFile: String? = null,
    val lutAmount: Float = 1f,
    val mask: Mask = Mask(),
    /** Style mix: another Library image (e.g. back from Samsung AI Style), blended in by [styleAmount]. */
    val styleAssetId: String? = null,
    val styleAmount: Float = 0f,
) {
    /** The parts a preset carries: the look, not the image, mask or style source. */
    fun asPreset(): DevelopDoc = copy(sourceAssetId = null, mask = Mask(), styleAssetId = null, styleAmount = 0f)

    fun withPreset(p: DevelopDoc): DevelopDoc = p.copy(sourceAssetId = sourceAssetId, mask = mask, styleAssetId = styleAssetId, styleAmount = styleAmount)

    val summary: String
        get() = buildList {
            if (exposureEv != 0f) add("${fmt1(exposureEv)} EV")
            if (temperature != 0f || tint != 0f) add("white balance")
            if (black != 0f || white != 1f || gamma != 1f) add("levels")
            if (curve.size > 2 || curve.any { it.x != it.y }) add("curve")
            if (bands.values.any { !it.isNeutral }) add("hue mixer")
            if (lutFile != null) add("LUT")
            if (mask.kind != MaskKind.None) add("${mask.kind.name.lowercase()} mask")
            if (styleAssetId != null && styleAmount > 0f) add("style ${(styleAmount * 100).roundToInt()}%")
        }.ifEmpty { listOf("no changes") }.joinToString(" · ")
}

@Serializable
data class Preset(val name: String, val look: DevelopDoc)

private fun fmt1(x: Float) = (if (x > 0) "+" else "") + ((x * 10).roundToInt() / 10f)

/** Colour math on floats 0..1. */
object ColorMath {
    /** RGB → HSL (h in degrees 0..360). */
    fun rgbToHsl(r: Float, g: Float, b: Float, out: FloatArray) {
        val mx = max(r, max(g, b))
        val mn = min(r, min(g, b))
        val l = (mx + mn) / 2
        val d = mx - mn
        if (d < 1e-6f) {
            out[0] = 0f; out[1] = 0f; out[2] = l
            return
        }
        val s = if (l > 0.5f) d / (2 - mx - mn) else d / (mx + mn)
        var h = when (mx) {
            r -> (g - b) / d + (if (g < b) 6 else 0)
            g -> (b - r) / d + 2
            else -> (r - g) / d + 4
        } * 60
        if (h >= 360f) h -= 360f
        out[0] = h; out[1] = s; out[2] = l
    }

    fun hslToRgb(h: Float, s: Float, l: Float, out: FloatArray) {
        if (s <= 0f) {
            out[0] = l; out[1] = l; out[2] = l
            return
        }
        val q = if (l < 0.5f) l * (1 + s) else l + s - l * s
        val p = 2 * l - q
        val hk = ((h % 360f) + 360f) % 360f / 360f
        out[0] = hue(p, q, hk + 1f / 3)
        out[1] = hue(p, q, hk)
        out[2] = hue(p, q, hk - 1f / 3)
    }

    private fun hue(p: Float, q: Float, t0: Float): Float {
        var t = t0
        if (t < 0) t += 1
        if (t > 1) t -= 1
        return when {
            t < 1f / 6 -> p + (q - p) * 6 * t
            t < 1f / 2 -> q
            t < 2f / 3 -> p + (q - p) * (2f / 3 - t) * 6
            else -> p
        }
    }

    /** Shortest angle between two hues, 0..180. */
    fun hueDistance(a: Float, b: Float): Float {
        val d = abs(a - b) % 360f
        return if (d > 180f) 360f - d else d
    }

    /**
     * How much a hue belongs to [band]: 1 at the centre, falling to 0 at the neighbouring
     * centres, so adjacent bands blend smoothly instead of switching.
     */
    fun bandWeight(h: Float, band: HueBand): Float {
        val i = band.ordinal
        val bands = HueBand.entries
        val prev = bands[(i + bands.size - 1) % bands.size].centerDeg
        val next = bands[(i + 1) % bands.size].centerDeg
        val c = band.centerDeg
        val toNext = hueDistance(c, next)
        val toPrev = hueDistance(c, prev)
        val d = hueDistance(h, c)
        // Which side of the centre are we on?
        val forward = ((h - c + 360f) % 360f) < 180f
        val width = if (forward) toNext else toPrev
        return (1f - d / width).coerceIn(0f, 1f)
    }

    /** Levels: black/white points and gamma (mid-tone), output clamped 0..1. */
    fun levels(x: Float, black: Float, white: Float, gamma: Float): Float {
        val t = ((x - black) / (white - black).coerceAtLeast(1e-4f)).coerceIn(0f, 1f)
        return t.toDouble().pow(1.0 / gamma.coerceAtLeast(0.05f)).toFloat()
    }

    /**
     * Monotone cubic (Fritsch–Carlson) through the curve points, so the tone curve never
     * overshoots or reverses between points.
     */
    class ToneCurve(points: List<CurvePoint>) {
        private val xs: FloatArray
        private val ys: FloatArray
        private val ms: FloatArray

        init {
            val p = points.sortedBy { it.x }.distinctBy { it.x }.ifEmpty { listOf(CurvePoint(0f, 0f), CurvePoint(1f, 1f)) }
            xs = FloatArray(p.size) { p[it].x }
            ys = FloatArray(p.size) { p[it].y }
            val n = p.size
            val d = FloatArray((n - 1).coerceAtLeast(0)) { (ys[it + 1] - ys[it]) / (xs[it + 1] - xs[it]) }
            ms = FloatArray(n)
            if (n >= 2) {
                ms[0] = d[0]
                ms[n - 1] = d[n - 2]
                for (i in 1 until n - 1) ms[i] = if (d[i - 1] * d[i] <= 0) 0f else (d[i - 1] + d[i]) / 2
                for (i in 0 until n - 1) {
                    if (d[i] == 0f) {
                        ms[i] = 0f; ms[i + 1] = 0f
                        continue
                    }
                    val a = ms[i] / d[i]
                    val b = ms[i + 1] / d[i]
                    val h = a * a + b * b
                    if (h > 9) {
                        val t = 3 / kotlin.math.sqrt(h)
                        ms[i] = t * a * d[i]
                        ms[i + 1] = t * b * d[i]
                    }
                }
            }
        }

        operator fun invoke(x: Float): Float {
            val n = xs.size
            if (n == 1) return ys[0]
            if (x <= xs[0]) return ys[0]
            if (x >= xs[n - 1]) return ys[n - 1]
            var i = 0
            while (i < n - 2 && x > xs[i + 1]) i++
            val h = xs[i + 1] - xs[i]
            val t = (x - xs[i]) / h
            val t2 = t * t
            val t3 = t2 * t
            return ((2 * t3 - 3 * t2 + 1) * ys[i] + (t3 - 2 * t2 + t) * h * ms[i] +
                (-2 * t3 + 3 * t2) * ys[i + 1] + (t3 - t2) * h * ms[i + 1]).coerceIn(0f, 1f)
        }
    }
}
