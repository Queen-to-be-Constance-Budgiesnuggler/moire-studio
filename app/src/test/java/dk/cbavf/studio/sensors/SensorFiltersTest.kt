package dk.cbavf.studio.sensors

import org.junit.Assert.*
import org.junit.Test

class SensorFiltersTest {
    @Test fun lowPassStepResponse() {
        val f = LowPass(0.5)
        assertEquals(10.0, f.next(10.0), 1e-12)
        assertEquals(5.0, f.next(0.0), 1e-12)
        assertEquals(2.5, f.next(0.0), 1e-12)
        f.reset()
        assertEquals(7.0, f.next(7.0), 1e-12)
    }
    @Test fun lowPassAlphaIsClamped() {
        val f = LowPass(5.0)
        f.next(1.0)
        assertEquals(9.0, f.next(9.0), 1e-12)
        val g = LowPass(-1.0)
        g.next(1.0)
        assertEquals(1.0, g.next(9.0), 1e-12)
    }
    @Test fun highPassRemovesConstants() {
        val f = HighPass(0.5)
        assertEquals(0.0, f.next(4.0), 1e-12)
        assertEquals(0.0, f.next(4.0), 1e-12)
        assertEquals(2.0, f.next(8.0), 1e-12)
    }
    @Test fun derivative() {
        val d = Derivative()
        assertEquals(0.0, d.next(5.0, 0.1), 0.0)
        assertEquals(10.0, d.next(6.0, 0.1), 1e-9)
        assertEquals(0.0, d.next(9.0, 0.0), 0.0)
        assertEquals(-20.0, d.next(7.0, 0.1), 1e-9)
        d.reset()
        assertEquals(0.0, d.next(100.0, 0.1), 0.0)
    }
    @Test fun hysteresis() {
        val h = Hysteresis(0.8, 0.2)
        assertFalse(h.next(0.5))
        assertTrue(h.next(0.9))
        assertTrue(h.next(0.5))
        assertFalse(h.next(0.1))
        assertFalse(h.rising(0.5))
        assertTrue(h.rising(0.85))
        assertFalse(h.rising(0.9))
    }
    @Test fun splitMixKnownValue() {
        assertEquals(0xE220A8397B1DCDAFuL.toLong(), SeedHash.splitMix(0L))
        assertNotEquals(SeedHash.splitMix(1L), SeedHash.splitMix(2L))
    }
    @Test fun seedFromFloats() {
        assertEquals(0L, SeedHash.fromFloats(floatArrayOf()))
        val a = SeedHash.fromFloats(floatArrayOf(1f, 2f))
        assertEquals(a, SeedHash.fromFloats(floatArrayOf(1f, 2f)))
        assertNotEquals(a, SeedHash.fromFloats(floatArrayOf(2f, 1f)))
        assertNotEquals(a, SeedHash.fromFloats(floatArrayOf(1f, 2.0001f)))
    }
}
