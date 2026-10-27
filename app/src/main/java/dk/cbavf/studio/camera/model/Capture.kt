package dk.cbavf.studio.camera.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.floor
import kotlin.math.roundToInt

/** White balance presets (Camera2 AWB modes). */
enum class WhiteBalance { Auto, Daylight, Cloudy, Tungsten, Fluorescent, Shade }

/**
 * How a burst becomes one image (spec §3 Camera, "software optical stack").
 * All work on 8-bit sRGB values, as a long exposure or double exposure on film would not:
 * this is the digital version, with explicit gain and clipping.
 */
@Serializable
sealed interface StackMode {
    /** Additive: sum × gain, clipped (multiple exposure). */
    @Serializable
    @SerialName("additive")
    data class Additive(val gain: Float = 0.5f) : StackMode

    /** Average of all frames (long exposure without blowing out). */
    @Serializable
    @SerialName("mean")
    data object Mean : StackMode

    /**
     * Rolling-shutter model: row r sees frame floor((t0 + r·t_row) / T_frame).
     * [rowsPerFrame] = T_frame / t_row (rows read out during one frame), [phaseRows] = t0 / t_row.
     */
    @Serializable
    @SerialName("interlace")
    data class Interlace(val rowsPerFrame: Float = 64f, val phaseRows: Float = 0f) : StackMode
}

/** Camera settings recorded with every capture (the camera part of the lineage). */
@Serializable
data class CaptureInfo(
    val cameraId: String,
    /** Null = auto exposure. */
    val exposureNs: Long? = null,
    val iso: Int? = null,
    /** Diopters; null = autofocus, 0 = infinity. */
    val focusDiopters: Float? = null,
    val aperture: Float? = null,
    val whiteBalance: WhiteBalance = WhiteBalance.Auto,
    val torch: Boolean = false,
    val zoom: Float = 1f,
    val burst: Int = 1,
    /** How the burst was combined; null for a single frame. */
    val stack: StackMode? = null,
    /** What was overlaid in the preview while shooting (see [overlayBurned] for whether it is in the pixels). */
    val overlay: String? = null,
    /** True when the overlay was composited into the saved image with [overlayBlend] at [overlayOpacity]. */
    val overlayBurned: Boolean = false,
    val overlayBlend: String? = null,
    val overlayOpacity: Float? = null,
    val overlaySpacing: Float? = null,
    val overlayAngle: Float? = null,
    /** Library image used by the Image overlay. */
    val overlayAssetId: String? = null,
    /** Asset that was on the other phone's screen (Loop), if any. */
    val projectedAssetId: String? = null,
) {
    val summary: String
        get() = buildList {
            add(exposureNs?.let { shutterLabel(it) } ?: "auto exp")
            iso?.let { add("ISO $it") }
            aperture?.let { add("f/$it") }
            if (zoom != 1f) add("${"%.1f".format(zoom)}×")
            if (burst > 1) add("$burst× " + (stack?.label ?: "burst"))
        }.joinToString(" · ")

    companion object {
        /** "1/125", "0.008", "0.008s", "8ms", "2s" → nanoseconds; null if unreadable. */
        fun parseShutterNs(text: String): Double? {
            val t = text.trim().lowercase().replace(',', '.').replace(" ", "")
            if (t.contains('/')) {
                val (a, b) = t.removeSuffix("s").split('/', limit = 2)
                val num = a.toDoubleOrNull() ?: return null
                val den = b.toDoubleOrNull()?.takeIf { it > 0 } ?: return null
                return num / den * 1e9
            }
            return when {
                t.endsWith("ms") -> t.removeSuffix("ms").toDoubleOrNull()?.times(1e6)
                t.endsWith("s") -> t.removeSuffix("s").toDoubleOrNull()?.times(1e9)
                else -> t.toDoubleOrNull()?.times(1e9)
            }?.takeIf { it > 0 }
        }

        fun shutterLabel(ns: Long): String =
            if (ns >= 500_000_000) "${(ns / 1e9 * 10).roundToInt() / 10.0} s" else "1/${(1e9 / ns).roundToInt()}"
    }
}

val StackMode.label: String
    get() = when (this) {
        is StackMode.Additive -> "additive ×$gain"
        StackMode.Mean -> "mean"
        is StackMode.Interlace -> "interlace ${rowsPerFrame.roundToInt()} rows"
    }

/** Pixel math for the stack, one row at a time so full-resolution bursts fit in memory. */
object Stack {
    /** Which burst frame row [row] shows under the rolling-shutter model. */
    fun interlaceFrame(row: Int, mode: StackMode.Interlace, frames: Int): Int =
        floor((mode.phaseRows + row) / mode.rowsPerFrame.coerceAtLeast(1f)).toInt().mod(frames)

    /**
     * Combines one row from every frame into [out] (ARGB, opaque). Additive: sum × gain,
     * clipped at 255. Mean: sum / n. Interlace rows are picked whole by [interlaceFrame].
     */
    fun combineRow(rows: List<IntArray>, mode: StackMode, out: IntArray) {
        val n = rows.size
        val scale = when (mode) {
            is StackMode.Additive -> mode.gain
            StackMode.Mean -> 1f / n
            is StackMode.Interlace -> error("interlace picks whole rows")
        }
        for (x in out.indices) {
            var r = 0
            var g = 0
            var b = 0
            for (row in rows) {
                val c = row[x]
                r += c shr 16 and 0xFF
                g += c shr 8 and 0xFF
                b += c and 0xFF
            }
            out[x] = (0xFF shl 24) or
                (clip(r * scale) shl 16) or
                (clip(g * scale) shl 8) or
                clip(b * scale)
        }
    }

    private fun clip(v: Float): Int = v.roundToInt().coerceIn(0, 255)
}
