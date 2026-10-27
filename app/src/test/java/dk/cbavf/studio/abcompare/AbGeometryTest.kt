package dk.cbavf.studio.abcompare

import org.junit.Assert.*
import org.junit.Test

class AbGeometryTest {
    @Test fun quadrants() {
        assertEquals(AbMode.Input, AbGeometry.quadrantMode(10.0, 10.0, 100.0, 80.0))
        assertEquals(AbMode.SplitVertical, AbGeometry.quadrantMode(60.0, 10.0, 100.0, 80.0))
        assertEquals(AbMode.SplitHorizontal, AbGeometry.quadrantMode(10.0, 50.0, 100.0, 80.0))
        assertEquals(AbMode.Full, AbGeometry.quadrantMode(90.0, 70.0, 100.0, 80.0))
        assertEquals(AbMode.SplitVertical, AbGeometry.quadrantMode(50.0, 39.0, 100.0, 80.0))
        assertEquals(AbMode.SplitHorizontal, AbGeometry.quadrantMode(49.0, 40.0, 100.0, 80.0))
    }
    @Test fun splits() {
        val (a, b) = AbGeometry.splitBoxes(AbMode.SplitVertical, 100.0, 80.0, 0.25)
        assertEquals(Box(0.0, 0.0, 25.0, 80.0), a)
        assertEquals(Box(25.0, 0.0, 100.0, 80.0), b)
        val (c, d) = AbGeometry.splitBoxes(AbMode.SplitHorizontal, 100.0, 80.0, 0.5)
        assertEquals(Box(0.0, 0.0, 100.0, 40.0), c)
        assertEquals(Box(0.0, 40.0, 100.0, 80.0), d)
        val (e, f) = AbGeometry.splitBoxes(AbMode.Full, 100.0, 80.0, 0.5)
        assertEquals(Box(0.0, 0.0, 100.0, 80.0), e)
        assertEquals(e, f)
        val (g, _) = AbGeometry.splitBoxes(AbMode.SplitVertical, 100.0, 80.0, 5.0)
        assertEquals(98.0, g.right, 1e-9)
        val (h, _) = AbGeometry.splitBoxes(AbMode.SplitVertical, 100.0, 80.0, -1.0)
        assertEquals(2.0, h.right, 1e-9)
    }
    @Test fun fit() {
        val b = AbGeometry.fitCenter(200.0, 100.0, 100.0, 100.0)
        assertEquals(Box(0.0, 25.0, 100.0, 75.0), b)
        val c = AbGeometry.fitCenter(100.0, 200.0, 100.0, 100.0)
        assertEquals(Box(25.0, 0.0, 75.0, 100.0), c)
        assertEquals(Box(0.0, 0.0, 0.0, 0.0), AbGeometry.fitCenter(0.0, 10.0, 10.0, 10.0))
        assertEquals(50.0, b.height, 1e-9)
    }
    @Test fun hold() {
        assertTrue(AbGeometry.holdStarts(500, 7.9))
        assertFalse(AbGeometry.holdStarts(499, 0.0))
        assertFalse(AbGeometry.holdStarts(900, 8.0))
    }
}
