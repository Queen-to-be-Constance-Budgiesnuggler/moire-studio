package dk.cbavf.studio.calibration

import java.util.Locale
import kotlin.math.floor

/**
 * A printable pitch test sheet as SVG, drawn in millimetres so it prints at exact size: horizontal bands of black bars, each band with
 * its own pitch (bar plus gap) and labelled with it. Hold a barrier grid or a lenticular sheet against the print to find which pitch
 * your screen or optics match.
 */
object CalibrationSheet {
    /** A number with at most three decimals and no trailing zeros ("2.5", "4", "0.125"). */
    fun fmt(v: Double): String = String.format(Locale.US, "%.3f", v).trimEnd('0').trimEnd('.').ifEmpty { "0" }

    /**
     * The sheet [widthMm] by [heightMm], cut into one band per entry of [pitchesMm] (top to bottom). A band of pitch p holds
     * floor(width / p) black bars, each p/2 wide; a pitch of zero or less draws no bars.
     */
    fun svg(widthMm: Double, heightMm: Double, pitchesMm: List<Double>): String {
        val w = fmt(widthMm)
        val h = fmt(heightMm)
        val lines = ArrayList<String>()
        lines += "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"${w}mm\" height=\"${h}mm\" viewBox=\"0 0 $w $h\">"
        lines += "<rect x=\"0\" y=\"0\" width=\"$w\" height=\"$h\" fill=\"#fff\"/>"
        if (pitchesMm.isNotEmpty()) {
            val band = heightMm / pitchesMm.size
            pitchesMm.forEachIndexed { b, p ->
                val top = b * band
                if (p > 0.0) {
                    val bars = floor(widthMm / p).toInt()
                    for (i in 0 until bars) {
                        lines += "<rect x=\"${fmt(i * p)}\" y=\"${fmt(top)}\" width=\"${fmt(p / 2)}\" height=\"${fmt(band)}\" fill=\"#000\"/>"
                    }
                }
                lines += "<text x=\"1\" y=\"${fmt(top + 4)}\" font-size=\"3\" fill=\"#f00\">${fmt(p)} mm</text>"
            }
        }
        lines += "</svg>"
        return lines.joinToString("\n")
    }
}
