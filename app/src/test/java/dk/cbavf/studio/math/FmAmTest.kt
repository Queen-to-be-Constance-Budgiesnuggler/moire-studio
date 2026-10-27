package dk.cbavf.studio.math

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class FmAmTest {
    private val am = FmAmSetup(FmAmMode.AM, carrier = 5.0, modulator = 0.5, depth = 0.5, index = 2.0)

    @Test fun theCarrierIsASineAtItsFrequency() {
        assertEquals(1.0, FmAm.carrier(0.05, am), 1e-9)       // a quarter of a 5 Hz cycle
        assertEquals(0.0, FmAm.carrier(0.1, am), 1e-9)
    }

    @Test fun phaseMovesTheWavesAlong() {
        val moved = am.copy(phase = 0.05)
        assertEquals(FmAm.carrier(0.1, am), FmAm.carrier(0.05, moved), 1e-9)
        assertEquals(FmAm.modulator(0.6, am), FmAm.modulator(0.55, moved), 1e-9)
    }

    @Test fun amAtDepthZeroIsTheCarrier() {
        val s = am.copy(depth = 0.0)
        for (i in 0..50) assertEquals(FmAm.carrier(i / 50.0, s), FmAm.sample(i / 50.0, s), 1e-12)
    }

    @Test fun amEnvelopeRunsFromOneMinusDepthToOne() {
        assertEquals(1.0, FmAm.amEnvelope(1.0, 0.7), 1e-12)
        assertEquals(0.3, FmAm.amEnvelope(-1.0, 0.7), 1e-12)
        assertEquals(0.0, FmAm.amEnvelope(-1.0, 1.0), 1e-12)
    }

    @Test fun amDepthOutsideZeroToOneIsHeld() {
        assertEquals(FmAm.amEnvelope(-1.0, 1.0), FmAm.amEnvelope(-1.0, 4.0), 0.0)
        assertEquals(1.0, FmAm.amEnvelope(-1.0, -2.0), 0.0)
    }

    @Test fun amScalesTheCarrierByTheModulator() {
        // At t = 0.05 the carrier peaks and the 0.5 Hz modulator is sin(0.05 pi).
        val m = sin(2 * PI * 0.5 * 0.05)
        assertEquals(FmAm.amEnvelope(m, 0.5), FmAm.sample(0.05, am), 1e-9)
    }

    @Test fun fmAtIndexZeroIsTheCarrier() {
        val s = am.copy(mode = FmAmMode.FM, index = 0.0)
        for (i in 0..50) assertEquals(FmAm.carrier(i / 50.0, s), FmAm.sample(i / 50.0, s), 1e-12)
    }

    @Test fun fmBendsThePhaseByIndexTimesModulator() {
        val s = am.copy(mode = FmAmMode.FM, index = 3.0)
        val t = 0.3
        val expected = sin(2 * PI * 5.0 * t + 3.0 * FmAm.modulator(t, s))
        assertEquals(expected, FmAm.sample(t, s), 1e-12)
    }

    @Test fun ringMultipliesCarrierAndModulator() {
        val s = am.copy(mode = FmAmMode.Ring)
        val t = 0.37
        assertEquals(FmAm.carrier(t, s) * FmAm.modulator(t, s), FmAm.sample(t, s), 1e-12)
    }

    @Test fun everyModeStaysInsideMinusOneToOne() {
        for (mode in FmAmMode.entries) {
            val s = FmAmSetup(mode, carrier = 7.3, modulator = 1.9, depth = 1.0, index = 15.0)
            for (i in 0..1000) {
                val v = FmAm.sample(i / 100.0, s)
                assertTrue("$mode at $i: $v", v in -1.0..1.0)
            }
        }
    }

    @Test fun scaledMapsMinusOneToMinAndOneToMax() {
        assertEquals(10.0, FmAm.scaled(-1.0, 10.0, 20.0), 1e-12)
        assertEquals(15.0, FmAm.scaled(0.0, 10.0, 20.0), 1e-12)
        assertEquals(20.0, FmAm.scaled(1.0, 10.0, 20.0), 1e-12)
    }

    @Test fun aProfileSamplesLineCentresAcrossOneUnit() {
        val s = am.copy(mode = FmAmMode.Ring)
        val p = FmAm.profile(4, s)
        assertEquals(4, p.size)
        assertEquals(FmAm.sample(0.125, s).toFloat(), p[0], 1e-6f)
        assertEquals(FmAm.sample(0.875, s).toFloat(), p[3], 1e-6f)
    }

    @Test fun anEmptyProfileHasNoLines() {
        assertEquals(0, FmAm.profile(0, am).size)
    }

    // The image modulation

    private val grey = 0xFF808080.toInt()

    @Test fun brightnessAtAmountZeroLeavesThePicture() {
        val px = IntArray(6) { grey }
        assertArrayEquals(px, FmAmImage.brightness(px, 3, 2, floatArrayOf(-1f, 1f), down = true, amount = 0f))
    }

    @Test fun brightnessDarkensTheLowRowsAndKeepsTheHighOnes() {
        val px = IntArray(6) { grey }
        val out = FmAmImage.brightness(px, 3, 2, floatArrayOf(-1f, 1f), down = true, amount = 1f)
        assertEquals(0xFF000000.toInt(), out[0])        // row 0: the lowest point, black
        assertEquals(grey, out[3])                       // row 1: the top of the wave, unchanged
    }

    @Test fun brightnessKeepsAlpha() {
        val px = intArrayOf(0x40FFFFFF)
        val out = FmAmImage.brightness(px, 1, 1, floatArrayOf(-1f), down = true, amount = 1f)
        assertEquals(0x40000000, out[0])
    }

    @Test fun brightnessAcrossWorksByColumn() {
        val px = IntArray(4) { grey }
        val out = FmAmImage.brightness(px, 2, 2, floatArrayOf(-1f, 1f), down = false, amount = 1f)
        assertEquals(0xFF000000.toInt(), out[0])
        assertEquals(0xFF000000.toInt(), out[2])
        assertEquals(grey, out[1])
    }

    @Test fun shiftSlidesEachRowAndWrapsRound() {
        val px = intArrayOf(1, 2, 3, 4, 5, 6)                    // two rows of three
        val out = FmAmImage.shift(px, 3, 2, floatArrayOf(1f, 0f), down = true, shiftPx = 1f)
        assertArrayEquals(intArrayOf(3, 1, 2, 4, 5, 6), out)
    }

    @Test fun shiftAcrossSlidesColumnsUpAndDown() {
        val px = intArrayOf(1, 2, 3, 4)                          // two rows of two
        val out = FmAmImage.shift(px, 2, 2, floatArrayOf(-1f, 0f), down = false, shiftPx = 1f)
        assertArrayEquals(intArrayOf(3, 2, 1, 4), out)
    }

    @Test fun shiftOfZeroLeavesThePicture() {
        val px = intArrayOf(1, 2, 3, 4)
        assertArrayEquals(px, FmAmImage.shift(px, 2, 2, floatArrayOf(1f, -1f), down = true, shiftPx = 0f))
    }
}
