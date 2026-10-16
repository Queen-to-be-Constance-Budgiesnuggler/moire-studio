package dk.cbavf.studio.develop.model

import kotlin.math.pow

/**
 * A 3D colour lookup table: [size]³ entries of RGB, red varying fastest (the .cube order).
 * Every global Develop adjustment is baked into one of these, so the preview and the full
 * render apply a single table, and exporting the look as .cube is exact.
 */
class Lut(val size: Int, val data: FloatArray) {
    init {
        require(data.size == size * size * size * 3)
    }

    /** Trilinear lookup of r, g, b (0..1) into [out]. */
    fun apply(r: Float, g: Float, b: Float, out: FloatArray) {
        val n = size - 1
        val fr = r.coerceIn(0f, 1f) * n
        val fg = g.coerceIn(0f, 1f) * n
        val fb = b.coerceIn(0f, 1f) * n
        val r0 = fr.toInt().coerceAtMost(n - 1)
        val g0 = fg.toInt().coerceAtMost(n - 1)
        val b0 = fb.toInt().coerceAtMost(n - 1)
        val dr = fr - r0
        val dg = fg - g0
        val db = fb - b0
        // Corner offsets of the surrounding cube, in floats.
        val i000 = ((b0 * size + g0) * size + r0) * 3
        val sr = 3
        val sg = size * 3
        val sb = size * size * 3
        val d = data
        for (c in 0..2) {
            val i = i000 + c
            val c00 = d[i] * (1 - dr) + d[i + sr] * dr
            val c10 = d[i + sg] * (1 - dr) + d[i + sg + sr] * dr
            val c01 = d[i + sb] * (1 - dr) + d[i + sb + sr] * dr
            val c11 = d[i + sb + sg] * (1 - dr) + d[i + sb + sg + sr] * dr
            out[c] = (c00 * (1 - dg) + c10 * dg) * (1 - db) + (c01 * (1 - dg) + c11 * dg) * db
        }
    }

    /** Writes the table as an Adobe/Resolve .cube file. */
    fun toCube(title: String): String = buildString {
        append("TITLE \"").append(title.replace("\"", "'")).append("\"\n")
        append("LUT_3D_SIZE ").append(size).append('\n')
        append("DOMAIN_MIN 0.0 0.0 0.0\nDOMAIN_MAX 1.0 1.0 1.0\n")
        for (i in 0 until size * size * size) {
            append(String.format(java.util.Locale.US, "%.6f %.6f %.6f\n", data[i * 3], data[i * 3 + 1], data[i * 3 + 2]))
        }
    }

    companion object {
        const val SIZE = 33

        fun identity(size: Int = SIZE) = Lut(size, FloatArray(size * size * size * 3).also { d ->
            for (b in 0 until size) for (g in 0 until size) for (r in 0 until size) {
                val i = ((b * size + g) * size + r) * 3
                d[i] = r / (size - 1f); d[i + 1] = g / (size - 1f); d[i + 2] = b / (size - 1f)
            }
        })

        /** Parses a 3D .cube file (DOMAIN_MIN/MAX honoured; 1D tables are rejected). */
        fun parseCube(text: String): Lut {
            var size = 0
            var min = floatArrayOf(0f, 0f, 0f)
            var max = floatArrayOf(1f, 1f, 1f)
            val values = ArrayList<Float>()
            for (raw in text.lineSequence()) {
                val line = raw.substringBefore('#').trim()
                if (line.isEmpty()) continue
                val parts = line.split(Regex("\\s+"))
                when (parts[0].uppercase()) {
                    "TITLE" -> Unit
                    "LUT_3D_SIZE" -> size = parts[1].toInt()
                    "LUT_1D_SIZE" -> error("1D LUTs are not supported")
                    "DOMAIN_MIN" -> min = FloatArray(3) { parts[it + 1].toFloat() }
                    "DOMAIN_MAX" -> max = FloatArray(3) { parts[it + 1].toFloat() }
                    else -> if (parts.size >= 3 && parts[0].first().let { it.isDigit() || it == '-' || it == '.' }) {
                        values += parts[0].toFloat(); values += parts[1].toFloat(); values += parts[2].toFloat()
                    }
                }
            }
            require(size in 2..256) { "No LUT_3D_SIZE in file" }
            require(values.size == size * size * size * 3) { "Expected ${size * size * size} entries, found ${values.size / 3}" }
            val data = FloatArray(values.size) { i ->
                val c = i % 3
                ((values[i] - min[c]) / (max[c] - min[c])).coerceIn(0f, 1f)
            }
            return Lut(size, data)
        }

        /** Bakes every global adjustment of [doc] into a table. */
        fun build(doc: DevelopDoc, size: Int = SIZE): Lut {
            val curve = ColorMath.ToneCurve(doc.curve)
            val gain = 2f.pow(doc.exposureEv)
            // White balance as channel gains, normalised so mid-grey keeps its brightness.
            val rg = 1f + 0.25f * doc.temperature + 0.1f * doc.tint
            val gg = 1f - 0.2f * doc.tint
            val bg = 1f - 0.25f * doc.temperature + 0.1f * doc.tint
            val norm = 3f / (rg + gg + bg)
            val bands = doc.bands.filterValues { !it.isNeutral }
            val hsl = FloatArray(3)
            val rgb = FloatArray(3)
            val data = FloatArray(size * size * size * 3)
            for (b in 0 until size) for (g in 0 until size) for (r in 0 until size) {
                // Exposure and white balance act on linear light.
                val lr = toLinear(r / (size - 1f)) * gain * rg * norm
                val lg = toLinear(g / (size - 1f)) * gain * gg * norm
                val lb = toLinear(b / (size - 1f)) * gain * bg * norm
                var cr = toSrgb(lr.coerceIn(0f, 1f))
                var cg = toSrgb(lg.coerceIn(0f, 1f))
                var cb = toSrgb(lb.coerceIn(0f, 1f))
                cr = curve(ColorMath.levels(cr, doc.black, doc.white, doc.gamma))
                cg = curve(ColorMath.levels(cg, doc.black, doc.white, doc.gamma))
                cb = curve(ColorMath.levels(cb, doc.black, doc.white, doc.gamma))
                if (bands.isNotEmpty()) {
                    ColorMath.rgbToHsl(cr, cg, cb, hsl)
                    var h = hsl[0]
                    var s = hsl[1]
                    var l = hsl[2]
                    val s0 = s
                    for ((band, adj) in bands) {
                        val w = ColorMath.bandWeight(hsl[0], band)
                        if (w <= 0f) continue
                        h += w * adj.hue
                        s *= 1f + w * adj.saturation
                        // Lightness only moves coloured pixels: greys have no hue to belong to a band.
                        l += w * adj.lightness * 0.5f * s0
                    }
                    ColorMath.hslToRgb(h, s.coerceIn(0f, 1f), l.coerceIn(0f, 1f), rgb)
                    cr = rgb[0]; cg = rgb[1]; cb = rgb[2]
                }
                val i = ((b * size + g) * size + r) * 3
                data[i] = cr; data[i + 1] = cg; data[i + 2] = cb
            }
            return Lut(size, data)
        }

        private fun toLinear(c: Float): Float = if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)
        private fun toSrgb(c: Float): Float = if (c <= 0.0031308f) c * 12.92f else 1.055f * c.pow(1 / 2.4f) - 0.055f
    }
}
