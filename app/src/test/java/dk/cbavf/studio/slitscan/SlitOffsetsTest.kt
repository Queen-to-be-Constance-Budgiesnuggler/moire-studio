package dk.cbavf.studio.slitscan

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SlitOffsetsTest {
    @Test fun noneIsAllZero() {
        assertArrayEquals(intArrayOf(0, 0, 0, 0), slitOffsets(OffsetMode.None, 4, 5f))
    }

    @Test fun rampGrowsByShiftPerSlit() {
        assertArrayEquals(intArrayOf(0, 2, 4, 6), slitOffsets(OffsetMode.Ramp, 4, 2f))
        assertArrayEquals(intArrayOf(0, 1, 1, 2), slitOffsets(OffsetMode.Ramp, 4, 0.7f))
    }

    @Test fun waveStartsAtZeroAndPeaksAtAQuarterTurn() {
        val w = slitOffsets(OffsetMode.Wave, 33, 3f)
        assertEquals(0, w[0])
        assertEquals(3, w[8])
        assertEquals(0, w[16])
        assertEquals(-3, w[24])
        assertEquals(0, w[32])
    }

    @Test fun randomStaysInRangeAndIsReproducible() {
        val a = slitOffsets(OffsetMode.Random, 200, 4f, seed = 7)
        val b = slitOffsets(OffsetMode.Random, 200, 4f, seed = 7)
        val c = slitOffsets(OffsetMode.Random, 200, 4f, seed = 8)
        assertArrayEquals(a, b)
        assertTrue(a.all { it in -4..4 })
        assertTrue(a.toSet().size > 3)
        assertTrue(!a.contentEquals(c))
    }

    @Test fun emptyWhenThereAreNoSlits() {
        assertEquals(0, slitOffsets(OffsetMode.Ramp, 0, 1f).size)
    }
}
