package dk.cbavf.studio.math

import org.junit.Assert.*
import org.junit.Test

class EnvelopeTest {
    private val eps = 1e-9
    @Test fun shapesHitEndpoints() {
        for (c in Curve.values()) {
            assertEquals(0.0, Envelope.shape(0.0, c), eps)
            assertEquals(1.0, Envelope.shape(1.0, c), eps)
        }
    }
    @Test fun shapesAreMonotonic() {
        for (c in Curve.values()) {
            var prev = -1.0
            var x = 0.0
            while (x <= 1.0001) { val v = Envelope.shape(x, c); assertTrue(v >= prev - 1e-12); prev = v; x += 0.05 }
        }
    }
    @Test fun shapeMidpoints() {
        assertEquals(0.5, Envelope.shape(0.5, Curve.Linear), eps)
        assertEquals(0.5, Envelope.shape(0.5, Curve.SCurve), eps)
        assertEquals(0.1192, Envelope.shape(0.5, Curve.Exponential, 4.0), 1e-4)
        assertEquals(1.0 - 0.1192, Envelope.shape(0.5, Curve.Logarithmic, 4.0), 1e-4)
        assertEquals(0.3, Envelope.shape(0.3, Curve.Exponential, 0.0), eps)
        assertEquals(0.0, Envelope.shape(-1.0, Curve.Linear), eps)
        assertEquals(1.0, Envelope.shape(2.0, Curve.Linear), eps)
    }
    private fun lvl(t: Double, off: Double? = null) = Envelope.adsr(t, 100.0, 100.0, 0.5, 200.0, Curve.Linear, 4.0, off)
    @Test fun heldAdsr() {
        assertEquals(0.0, lvl(0.0), eps)
        assertEquals(0.5, lvl(50.0), eps)
        assertEquals(1.0, lvl(100.0), eps)
        assertEquals(0.75, lvl(150.0), eps)
        assertEquals(0.5, lvl(200.0), eps)
        assertEquals(0.5, lvl(1000.0), eps)
        assertEquals(0.0, lvl(-5.0), eps)
    }
    @Test fun releaseFromSustain() {
        assertEquals(0.5, lvl(400.0, 400.0), eps)
        assertEquals(0.25, lvl(500.0, 400.0), eps)
        assertEquals(0.0, lvl(600.0, 400.0), eps)
        assertEquals(0.0, lvl(700.0, 400.0), eps)
    }
    @Test fun releaseDuringAttack() {
        assertEquals(0.25, lvl(150.0, 50.0), eps)
    }
    @Test fun zeroLengthSegmentsAreSkipped() {
        assertEquals(1.0, Envelope.adsr(0.0, 0.0, 100.0, 0.5, 100.0), eps)
        assertEquals(0.5, Envelope.adsr(50.0, 0.0, 0.0, 0.5, 100.0), eps)
        assertEquals(0.0, Envelope.adsr(10.0, 10.0, 10.0, 0.5, 0.0, gateOffMs = 5.0), eps)
    }
}
