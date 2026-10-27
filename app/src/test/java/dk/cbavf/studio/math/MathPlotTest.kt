package dk.cbavf.studio.math

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class MathPlotTest {
    private fun argb(a: Int, r: Int, g: Int, b: Int) = (a shl 24) or (r shl 16) or (g shl 8) or b

    @Test fun samplesIncludeBothEnds() {
        val s = MathPlot.samples({ it }, 5)
        assertArrayEquals(doubleArrayOf(0.0, 0.25, 0.5, 0.75, 1.0), s, 1e-12)
    }

    @Test(expected = IllegalArgumentException::class) fun oneSampleIsNotEnough() {
        MathPlot.samples({ it }, 1)
    }

    @Test fun pointsPutOneAtTheTopAndZeroAtTheBottom() {
        val p = MathPlot.points(doubleArrayOf(0.0, 1.0), 100f, 50f, pad = 5f)
        assertArrayEquals(floatArrayOf(5f, 45f, 95f, 5f), p, 1e-4f)
    }

    @Test fun pointsKeepValuesOutsideZeroToOneAtTheEdge() {
        val p = MathPlot.points(doubleArrayOf(-2.0, 3.0), 10f, 10f)
        assertEquals(10f, p[1], 1e-4f)
        assertEquals(0f, p[3], 1e-4f)
    }

    @Test fun identityTableMapsEachLevelToItself() {
        val t = MathPlot.table { it }
        for (v in 0..255) assertEquals(v, t[v])
    }

    @Test fun tableIsKeptInsideTheLevelRange() {
        val t = MathPlot.table { it * 3 - 1 }
        assertEquals(0, t[0])
        assertEquals(255, t[255])
    }

    @Test fun applyKeepsAlpha() {
        val invert = MathPlot.table { 1 - it }
        val out = MathPlot.apply(intArrayOf(argb(0x80, 0, 100, 255)), invert)
        assertEquals(argb(0x80, 255, 155, 0), out[0])
    }

    @Test fun applyOnOneChannelLeavesTheOthers() {
        val invert = MathPlot.table { 1 - it }
        val out = MathPlot.apply(intArrayOf(argb(255, 10, 20, 30)), invert, channel = MathChannel.Green)
        assertEquals(argb(255, 10, 235, 30), out[0])
    }

    @Test fun mixZeroLeavesThePictureAsItWas() {
        val px = intArrayOf(argb(255, 10, 20, 30), argb(255, 200, 100, 0))
        assertArrayEquals(px, MathPlot.apply(px, MathPlot.table { 1 - it }, mix = 0f))
    }

    @Test fun halfMixLandsHalfway() {
        val black = MathPlot.table { 0.0 }
        val out = MathPlot.apply(intArrayOf(argb(255, 200, 100, 50)), black, mix = 0.5f)
        assertEquals(argb(255, 100, 50, 25), out[0])
    }
}
