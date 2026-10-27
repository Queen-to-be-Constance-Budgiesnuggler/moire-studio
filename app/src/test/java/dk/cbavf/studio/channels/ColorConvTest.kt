package dk.cbavf.studio.channels

import org.junit.Assert.*
import org.junit.Test

class ColorConvTest {
    private fun near(e: Double, a: Double, tol: Double) = assertEquals(e, a, tol)

    @Test fun cmykPrimaries() {
        val red = ColorConv.rgbToCmyk(1.0, 0.0, 0.0)
        near(0.0, red[0], 1e-12); near(1.0, red[1], 1e-12); near(1.0, red[2], 1e-12); near(0.0, red[3], 1e-12)
        val black = ColorConv.rgbToCmyk(0.0, 0.0, 0.0)
        near(0.0, black[0], 1e-12); near(1.0, black[3], 1e-12)
        val white = ColorConv.rgbToCmyk(1.0, 1.0, 1.0)
        for (v in white) near(0.0, v, 1e-12)
        val grey = ColorConv.rgbToCmyk(0.5, 0.5, 0.5)
        near(0.0, grey[0], 1e-12); near(0.5, grey[3], 1e-12)
    }

    @Test fun cmykRoundTrip() {
        var r = 0.0
        while (r <= 1.0) { var g = 0.0
            while (g <= 1.0) { var b = 0.0
                while (b <= 1.0) {
                    val c = ColorConv.rgbToCmyk(r, g, b)
                    val back = ColorConv.cmykToRgb(c[0], c[1], c[2], c[3])
                    near(r, back[0], 1e-9); near(g, back[1], 1e-9); near(b, back[2], 1e-9)
                    b += 0.25 }
                g += 0.25 }
            r += 0.25 }
    }

    @Test fun labKnownValues() {
        val white = ColorConv.rgbToLab(1.0, 1.0, 1.0)
        near(100.0, white[0], 0.05); near(0.0, white[1], 0.05); near(0.0, white[2], 0.05)
        val black = ColorConv.rgbToLab(0.0, 0.0, 0.0)
        near(0.0, black[0], 1e-6)
        val red = ColorConv.rgbToLab(1.0, 0.0, 0.0)
        near(53.24, red[0], 0.1); near(80.09, red[1], 0.15); near(67.20, red[2], 0.15)
    }

    @Test fun labRoundTrip() {
        var r = 0.0
        while (r <= 1.0) { var g = 0.0
            while (g <= 1.0) { var b = 0.0
                while (b <= 1.0) {
                    val lab = ColorConv.rgbToLab(r, g, b)
                    val back = ColorConv.labToRgb(lab[0], lab[1], lab[2])
                    near(r, back[0], 1e-4); near(g, back[1], 1e-4); near(b, back[2], 1e-4)
                    b += 0.25 }
                g += 0.25 }
            r += 0.25 }
    }

    @Test fun labToRgbClamps() {
        val out = ColorConv.labToRgb(100.0, 120.0, -120.0)
        for (v in out) assertTrue(v in 0.0..1.0)
    }

    @Test fun spaceWrappers() {
        assertEquals(listOf("C", "M", "Y", "K"), ColorSpace.CMYK.channelNames)
        assertEquals(3, ColorSpace.LAB.channelNames.size)
        val rgba = ColorConv.fromRgba(ColorSpace.RGBA, 0.1, 0.2, 0.3, 0.4)
        assertArrayEquals(doubleArrayOf(0.1, 0.2, 0.3, 0.4), rgba, 0.0)
        assertEquals(4, ColorConv.fromRgba(ColorSpace.CMYK, 0.1, 0.2, 0.3, 0.4).size)
        assertEquals(3, ColorConv.fromRgba(ColorSpace.LAB, 0.1, 0.2, 0.3, 0.4).size)
        val back = ColorConv.toRgba(ColorSpace.CMYK, ColorConv.fromRgba(ColorSpace.CMYK, 0.1, 0.2, 0.3, 0.4))
        near(0.1, back[0], 1e-9); near(0.3, back[2], 1e-9); near(1.0, back[3], 0.0)
        val rgba2 = ColorConv.toRgba(ColorSpace.RGBA, doubleArrayOf(0.1, 0.2, 0.3, 0.4))
        assertArrayEquals(doubleArrayOf(0.1, 0.2, 0.3, 0.4), rgba2, 0.0)
    }
}
