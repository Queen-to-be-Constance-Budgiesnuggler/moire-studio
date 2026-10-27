package dk.cbavf.studio.sonify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class SonifyTest {
    private fun zeroCrossings(s: ShortArray, from: Int, to: Int): Int {
        var n = 0
        for (i in from + 1 until to) if ((s[i - 1] < 0) != (s[i] < 0)) n++
        return n
    }

    @Test fun pitchesRiseByEvenStepsFromLowToHigh() {
        assertEquals(110.0, Sonify.bandHz(0, 48), 1e-9)
        assertEquals(3520.0, Sonify.bandHz(47, 48), 1e-6)
        // 110 to 3520 is five octaves (a factor of 32): the middle of five equal steps doubles each time
        assertEquals(220.0, Sonify.bandHz(1, 6), 1e-6)
        assertEquals(1.0, Sonify.bandHz(3, 6) / Sonify.bandHz(2, 6) / (Sonify.bandHz(2, 6) / Sonify.bandHz(1, 6)), 1e-9)
    }

    @Test fun bandsAreMeansOfEqualParts() {
        val row = floatArrayOf(0f, 0f, 1f, 1f, 0.5f, 0.5f, 0f, 1f)
        assertEquals(listOf(0f, 1f, 0.5f, 0.5f), Sonify.bandLevels(row, 4).toList())
    }

    @Test fun aShortRowRepeatsItsValues() {
        val b = Sonify.bandLevels(floatArrayOf(0.25f, 0.75f), 4)
        assertEquals(4, b.size)
        assertTrue(b.all { it == 0.25f || it == 0.75f })
    }

    @Test fun aBlackRowIsSilenceAndAnEmptyRowToo() {
        assertTrue(Sonify.render(FloatArray(100), seconds = 0.2).all { it.toInt() == 0 })
        assertTrue(Sonify.render(FloatArray(0), seconds = 0.2).all { it.toInt() == 0 })
    }

    @Test fun nothingIsLouderThanEightyPercent() {
        val s = Sonify.render(FloatArray(100) { 1f }, seconds = 0.3)
        val peak = s.maxOf { abs(it.toInt()) }
        assertTrue(peak <= 0.8 * Short.MAX_VALUE + 1)
        // and it is not left quiet: the fades take only the edges
        assertTrue(peak > 0.3 * Short.MAX_VALUE)
    }

    @Test fun theLengthFollowsTheSeconds() {
        assertEquals((0.5 * Sonify.SAMPLE_RATE).toInt(), Sonify.render(FloatArray(10) { 1f }, seconds = 0.5).size)
    }

    @Test fun aBrightRightSideIsHigherThanABrightLeftSide() {
        val left = FloatArray(96) { if (it < 12) 1f else 0f }
        val right = FloatArray(96) { if (it >= 84) 1f else 0f }
        val a = Sonify.render(left, seconds = 0.5)
        val b = Sonify.render(right, seconds = 0.5)
        val from = 2000
        val to = 8000
        assertTrue(zeroCrossings(b, from, to) > 4 * zeroCrossings(a, from, to))
    }

    @Test fun theEndsFadeToSilence() {
        val s = Sonify.render(FloatArray(100) { 1f }, seconds = 0.3)
        assertEquals(0, s.first().toInt())
        assertEquals(0, s.last().toInt())
    }
}
