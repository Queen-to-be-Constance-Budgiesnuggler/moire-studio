package dk.cbavf.studio.math

import org.junit.Assert.*
import org.junit.Test

class FringeTest {
    @Test fun equalPitchParallelHasNoFringe() {
        assertEquals(Double.POSITIVE_INFINITY, Fringe.beatPeriod(10.0, 10.0, 0.0), 0.0)
    }
    @Test fun pitchMismatchBeat() {
        assertEquals(110.0, Fringe.beatPeriod(10.0, 11.0, 0.0), 1e-9)
    }
    @Test fun smallRotationBeat() {
        assertEquals(1000.0, Fringe.beatPeriod(10.0, 10.0, 0.01), 0.1)
    }
    @Test fun perpendicularGratings() {
        assertEquals(100.0 / Math.sqrt(200.0), Fringe.beatPeriod(10.0, 10.0, Math.PI / 2), 1e-9)
    }
    @Test fun beatVectorValues() {
        val v = Fringe.beatVector(10.0, 10.0, Math.PI / 2)
        assertEquals(0.1, v[0], 1e-12)
        assertEquals(-0.1, v[1], 1e-12)
    }
    @Test fun normalAngle() {
        assertEquals(0.0, Fringe.fringeNormalAngle(10.0, 11.0, 0.0), 1e-12)
        assertEquals(-Math.PI / 4, Fringe.fringeNormalAngle(10.0, 10.0, Math.PI / 2), 1e-12)
        assertEquals(0.0, Fringe.fringeNormalAngle(10.0, 10.0, 0.0), 0.0)
    }
    @Test fun lineAngleIsWrapped() {
        val a = Fringe.fringeLineAngle(10.0, 11.0, 0.0)
        assertEquals(Math.PI / 2, a, 1e-12)
        val b = Fringe.fringeLineAngle(10.0, 10.0, Math.PI / 2)
        assertEquals(Math.PI / 4, b, 1e-12)
        assertTrue(b > -Math.PI / 2 && b <= Math.PI / 2)
    }
}
