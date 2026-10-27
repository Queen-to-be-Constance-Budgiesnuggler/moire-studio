package dk.cbavf.studio.channels

import kotlin.math.cbrt
import kotlin.math.pow

enum class ColorSpace(val channelNames: List<String>) {
    RGBA(listOf("R", "G", "B", "A")),
    CMYK(listOf("C", "M", "Y", "K")),
    LAB(listOf("L", "a", "b"))
}

object ColorConv {
    /**
     * Converts sRGB color values to CMYK color values.
     *
     * @param r red channel value in sRGB (0..1)
     * @param g green channel value in sRGB (0..1)
     * @param b blue channel value in sRGB (0..1)
     * @return array of [c, m, y, k] values each in range 0..1
     */
    fun rgbToCmyk(r: Double, g: Double, b: Double): DoubleArray {
        val k = 1.0 - maxOf(r, g, b)
        if (k == 1.0) return doubleArrayOf(0.0, 0.0, 0.0, 1.0)
        
        val c = (1.0 - r - k) / (1.0 - k)
        val m = (1.0 - g - k) / (1.0 - k)
        val y = (1.0 - b - k) / (1.0 - k)
        
        return doubleArrayOf(c, m, y, k)
    }

    /**
     * Converts CMYK color values to sRGB color values.
     *
     * @param c cyan channel value (0..1)
     * @param m magenta channel value (0..1)
     * @param y yellow channel value (0..1)
     * @param k key (black) channel value (0..1)
     * @return array of [r, g, b] values each in range 0..1
     */
    fun cmykToRgb(c: Double, m: Double, y: Double, k: Double): DoubleArray {
        val r = (1.0 - c) * (1.0 - k)
        val g = (1.0 - m) * (1.0 - k)
        val b = (1.0 - y) * (1.0 - k)
        
        return doubleArrayOf(r, g, b)
    }

    /**
     * Converts sRGB color values to CIELAB color values using D65 illuminant.
     *
     * @param r red channel value in sRGB (0..1)
     * @param g green channel value in sRGB (0..1)
     * @param b blue channel value in sRGB (0..1)
     * @return array of [L, a, b] values where L is 0..100 and a,b are approximately -128..127
     */
    fun rgbToLab(r: Double, g: Double, b: Double): DoubleArray {
        // Linearize RGB
        val rLin = if (r <= 0.04045) r / 12.92 else ((r + 0.055) / 1.055).pow(2.4)
        val gLin = if (g <= 0.04045) g / 12.92 else ((g + 0.055) / 1.055).pow(2.4)
        val bLin = if (b <= 0.04045) b / 12.92 else ((b + 0.055) / 1.055).pow(2.4)

        // Convert to XYZ using sRGB to XYZ matrix
        val x = 0.4124564 * rLin + 0.3575761 * gLin + 0.1804375 * bLin
        val y = 0.2126729 * rLin + 0.7151522 * gLin + 0.0721750 * bLin
        val z = 0.0193339 * rLin + 0.1191920 * gLin + 0.9503041 * bLin

        // Normalize by illuminant
        val xn = 0.95047
        val yn = 1.0
        val zn = 1.08883

        val xNorm = x / xn
        val yNorm = y / yn
        val zNorm = z / zn

        // Convert to Lab
        fun f(t: Double): Double {
            return if (t > 216.0 / 24389.0) cbrt(t) else (24389.0 / 27.0 * t + 16.0) / 116.0
        }

        val l = 116.0 * f(yNorm) - 16.0
        val a = 500.0 * (f(xNorm) - f(yNorm))
        val b = 200.0 * (f(yNorm) - f(zNorm))

        return doubleArrayOf(l, a, b)
    }

    /**
     * Converts CIELAB color values to sRGB color values using D65 illuminant.
     *
     * @param l lightness channel value (0..100)
     * @param a green-red chromaticity channel value (approximately -128..127)
     * @param b blue-yellow chromaticity channel value (approximately -128..127)
     * @return array of [r, g, b] values each clamped to range 0..1
     */
    fun labToRgb(l: Double, a: Double, b: Double): DoubleArray {
        // Convert Lab to XYZ
        val yNorm = (l + 16.0) / 116.0
        val xNorm = a / 500.0 + yNorm
        val zNorm = yNorm - b / 200.0

        fun finv(t: Double): Double {
            return if (t * t * t > 216.0 / 24389.0) t * t * t else (116.0 * t - 16.0) / (24389.0 / 27.0)
        }

        val x = finv(xNorm) * 0.95047
        val y = finv(yNorm) * 1.0
        val z = finv(zNorm) * 1.08883

        // Convert XYZ to linear RGB using inverse matrix
        val rLin = 3.2404542 * x - 1.5371385 * y - 0.4985314 * z
        val gLin = -0.9692660 * x + 1.8760108 * y + 0.0415560 * z
        val bLin = 0.0556434 * x - 0.2040259 * y + 1.0572252 * z

        // Apply sRGB gamma correction
        fun gamma(t: Double): Double {
            return if (t <= 0.0031308) 12.92 * t else 1.055 * t.pow(1.0 / 2.4) - 0.055
        }

        val r = gamma(rLin).coerceIn(0.0, 1.0)
        val g = gamma(gLin).coerceIn(0.0, 1.0)
        val b = gamma(bLin).coerceIn(0.0, 1.0)

        return doubleArrayOf(r, g, b)
    }

    /**
     * Converts RGBA color values to the specified color space.
     *
     * @param space target color space
     * @param r red channel value (0..1)
     * @param g green channel value (0..1)
     * @param b blue channel value (0..1)
     * @param a alpha channel value (0..1)
     * @return array of color values in the specified space
     */
    fun fromRgba(space: ColorSpace, r: Double, g: Double, b: Double, a: Double): DoubleArray {
        return when (space) {
            ColorSpace.RGBA -> doubleArrayOf(r, g, b, a)
            ColorSpace.CMYK -> rgbToCmyk(r, g, b)
            ColorSpace.LAB -> rgbToLab(r, g, b)
        }
    }

    /**
     * Converts color values from the specified color space to RGBA.
     *
     * @param space source color space
     * @param values array of color values in the specified space
     * @return array of [r, g, b, a] values each in range 0..1
     */
    fun toRgba(space: ColorSpace, values: DoubleArray): DoubleArray {
        return when (space) {
            ColorSpace.RGBA -> values
            ColorSpace.CMYK -> {
                val rgb = cmykToRgb(values[0], values[1], values[2], values[3])
                doubleArrayOf(rgb[0], rgb[1], rgb[2], 1.0)
            }
            ColorSpace.LAB -> {
                val rgb = labToRgb(values[0], values[1], values[2])
                doubleArrayOf(rgb[0], rgb[1], rgb[2], 1.0)
            }
        }
    }
}
