package dk.cbavf.studio.vector3d

import java.util.Locale

/**
 * A [Frame] as an SVG file: the background, then each face as a filled polygon followed by its grating as one path, far to
 * near, so any vector editor shows the same picture. Numbers use a dot and at most two decimals.
 */
object SvgWriter {
    const val MIME = "image/svg+xml"

    fun write(frame: Frame): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"${frame.width}\" height=\"${frame.height}\" viewBox=\"0 0 ${frame.width} ${frame.height}\">\n")
        append("<rect width=\"${frame.width}\" height=\"${frame.height}\"").append(paint("fill", frame.background)).append("/>\n")
        for (f in frame.faces) {
            append("<polygon points=\"")
            for (i in 0 until f.outline.size / 2) {
                if (i > 0) append(' ')
                append(num(f.outline[2 * i])).append(',').append(num(f.outline[2 * i + 1]))
            }
            append('"').append(paint("fill", f.fill)).append("/>\n")
            if (f.lines.isNotEmpty()) {
                append("<path d=\"")
                for (i in 0 until f.lines.size / 4) {
                    if (i > 0) append(' ')
                    append('M').append(num(f.lines[4 * i])).append(' ').append(num(f.lines[4 * i + 1]))
                    append('L').append(num(f.lines[4 * i + 2])).append(' ').append(num(f.lines[4 * i + 3]))
                }
                append("\" fill=\"none\"").append(paint("stroke", f.lineColor))
                append(" stroke-width=\"").append(num(f.lineWidth)).append("\"/>\n")
            }
        }
        append("</svg>\n")
    }

    /** `#rrggbb` for an ARGB colour. */
    fun hex(argb: Int): String = String.format(Locale.US, "#%06x", argb and 0xFFFFFF)

    /** A fill or stroke attribute, with its opacity only when the colour is not solid. */
    private fun paint(attr: String, argb: Int): String {
        val alpha = argb ushr 24
        val opacity = if (alpha == 0xFF) "" else " $attr-opacity=\"${num(alpha / 255f)}\""
        return " $attr=\"${hex(argb)}\"$opacity"
    }

    /** A number with at most two decimals and no trailing zeros. */
    fun num(v: Float): String {
        val s = String.format(Locale.US, "%.2f", v).trimEnd('0').trimEnd('.')
        return if (s == "-0") "0" else s
    }
}
