package dk.cbavf.studio.slitscan

import org.junit.Assert.*
import org.junit.Test

class SlitScanTest {
    private fun frame(w: Int, h: Int, v: Int) = IntArray(w * h) { v }

    @Test fun verticalSlitsTakeFramesInOrder() {
        val f = listOf(frame(6, 2, 10), frame(6, 2, 20), frame(6, 2, 30))
        val out = SlitScan.scan(f, 6, 2, 2, true)
        assertEquals(listOf(10, 10, 20, 20, 30, 30), out.take(6))
        assertEquals(listOf(10, 10, 20, 20, 30, 30), out.drop(6))
    }
    @Test fun wrapsAroundWhenThereAreMoreSlitsThanFrames() {
        val f = listOf(frame(8, 1, 1), frame(8, 1, 2))
        val out = SlitScan.scan(f, 8, 1, 2, true)
        assertEquals(listOf(1, 1, 2, 2, 1, 1, 2, 2), out.toList())
    }
    @Test fun horizontalSlits() {
        val f = listOf(frame(2, 4, 5), frame(2, 4, 6))
        val out = SlitScan.scan(f, 2, 4, 2, false)
        assertEquals(listOf(5, 5, 5, 5, 6, 6, 6, 6), out.toList())
    }
    @Test fun offsetsShiftTheFrameAndHandleNegatives() {
        val f = listOf(frame(4, 1, 1), frame(4, 1, 2), frame(4, 1, 3))
        val out = SlitScan.scan(f, 4, 1, 1, true, intArrayOf(1, -2))
        assertEquals(listOf(2, 3, 3, 1), out.toList())
    }
    @Test fun pixelsKeepTheirOwnPosition() {
        val a = IntArray(4) { it }
        val b = IntArray(4) { 100 + it }
        val out = SlitScan.scan(listOf(a, b), 2, 2, 1, true)
        assertEquals(listOf(0, 101, 2, 103), out.toList())
    }
    @Test fun counts() {
        assertEquals(3, SlitScan.slitCount(5, 9, 2, true))
        assertEquals(5, SlitScan.slitCount(5, 9, 2, false))
        assertEquals(1, SlitScan.slitCount(5, 9, 50, true))
    }
    @Test fun badInputThrows() {
        val f = listOf(frame(2, 2, 1))
        assertThrows(IllegalArgumentException::class.java) { SlitScan.scan(emptyList(), 2, 2, 1, true) }
        assertThrows(IllegalArgumentException::class.java) { SlitScan.scan(f, 2, 2, 0, true) }
        assertThrows(IllegalArgumentException::class.java) { SlitScan.scan(f, 3, 2, 1, true) }
        assertThrows(IllegalArgumentException::class.java) { SlitScan.slitCount(2, 2, 0, true) }
    }
}
