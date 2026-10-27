package dk.cbavf.studio.develop

import dk.cbavf.studio.develop.model.Analysis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalysisTest {
    private val black = 0xFF000000.toInt()
    private val white = 0xFFFFFFFF.toInt()

    @Test
    fun labOfKnownColours() {
        val w = Analysis.lab(255, 255, 255)
        assertEquals(100f, w[0], 0.1f)
        assertEquals(0f, w[1], 0.5f)
        val k = Analysis.lab(0, 0, 0)
        assertEquals(0f, k[0], 0.1f)
        // sRGB red: L 53.2, a 80.1, b 67.2.
        val r = Analysis.lab(255, 0, 0)
        assertEquals(53.2f, r[0], 0.3f)
        assertEquals(80.1f, r[1], 0.5f)
        assertEquals(67.2f, r[2], 0.5f)
    }

    @Test
    fun regionStatsOnHalfBlackHalfWhite() {
        val w = 10
        val px = IntArray(100) { if (it % w < 5) black else white }
        val s = Analysis.regionStats(px, w, 10, 0, 0, 9, 9)
        assertEquals(100, s.count)
        assertEquals(127.5f, s.mean[0], 0.01f)
        assertEquals(127.5f, s.std[0], 0.01f)
        assertEquals(0, s.min[3])
        assertEquals(255, s.max[3])
        assertEquals(0.5f, s.clippedHigh, 1e-6f)
        assertEquals(0.5f, s.clippedLow, 1e-6f)
        assertEquals(2, s.colours)
        assertEquals(1f, s.entropy, 1e-4f)
        // Just the black half.
        assertEquals(0f, Analysis.regionStats(px, w, 10, 0, 0, 4, 9).entropy, 1e-6f)
    }

    @Test
    fun lineProfileWaveformAndHeights() {
        val w = 8
        val px = IntArray(64) { if (it % w < 4) black else white }
        val line = Analysis.lineProfile(px, w, 8, 0f, 0f, 7f, 0f, 8)
        assertEquals(listOf(black, black, black, black, white, white, white, white), line.toList())
        val wave = Analysis.waveform(px, w, 8, cols = 2, bins = 4)
        assertEquals(32, wave[0])          // left column: all black, bin 0
        assertEquals(32, wave[1 * 4 + 3])  // right column: all white, top bin
        val hm = Analysis.heights(px, w, 8, 4)
        assertEquals(4, hm.gw)
        assertEquals(0f, hm.height[0], 1e-6f)
        assertEquals(1f, hm.height[3], 1e-6f)
    }

    @Test
    fun projectionLiftsHeightUpTheScreen() {
        val low = Analysis.project(2f, 2f, 0f, 5, 5, 0f, 60f, 1f)
        val high = Analysis.project(2f, 2f, 1f, 5, 5, 0f, 60f, 1f)
        assertEquals(low[0], high[0], 1e-6f)
        assertTrue(high[1] < low[1])
    }
}
