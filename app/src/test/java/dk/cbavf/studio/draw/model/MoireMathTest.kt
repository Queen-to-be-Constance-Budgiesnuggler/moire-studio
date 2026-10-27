package dk.cbavf.studio.draw.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MoireMathTest {
    private val tol = 1e-6

    @Test
    fun parallelGratingsMatchSpecFormula() {
        val beat = MoireMath.beat(Grating(spacingPx = 10f), Grating(spacingPx = 11f))
        assertEquals(MoireMath.parallelBeat(10.0, 11.0), beat.spacingPx, tol)
        assertEquals(110.0, beat.spacingPx, tol)
        assertEquals(0.0, beat.angleDeg, tol)
    }

    @Test
    fun rotatedGratingsMatchSpecFormula() {
        val beat = MoireMath.beat(Grating(spacingPx = 10f, angleDeg = 0f), Grating(spacingPx = 10f, angleDeg = 5f))
        assertEquals(MoireMath.rotatedBeat(10.0, 5.0), beat.spacingPx, 1e-4)
        // Fringes of two equal rotated gratings run ~90° to the bisector of the lines.
        assertEquals(-87.5, beat.angleDeg, 1e-3)
    }

    @Test
    fun nearlyAntiparallelLinesAreTreatedAsNearlyParallel() {
        val a = Grating(spacingPx = 10f, angleDeg = 0f)
        val b = Grating(spacingPx = 10f, angleDeg = 175f) // same as −5°
        assertEquals(MoireMath.rotatedBeat(10.0, 5.0), MoireMath.beat(a, b).spacingPx, 1e-4)
        assertEquals(5.0, MoireMath.angleBetween(a, b), 1e-4)
    }

    @Test
    fun identicalGratingsHaveNoBeat() {
        val beat = MoireMath.beat(Grating(spacingPx = 8f), Grating(spacingPx = 8f, offsetPx = 3f))
        assertTrue(beat.spacingPx.isInfinite())
    }

    @Test
    fun shiftAmplificationMatchesSpec() {
        val moved = Grating(spacingPx = 11f)
        val beat = MoireMath.beat(Grating(spacingPx = 10f), moved)
        // Spec: fringe moves Δ · p_other / |p1 − p2| when one grating moves by Δ.
        assertEquals(10.0 / 1.0, MoireMath.shiftAmplification(beat, moved), tol)

        val g = Grating(spacingPx = 10f, angleDeg = 4f)
        val rotated = MoireMath.beat(Grating(spacingPx = 10f), g)
        assertEquals(1 / (2 * kotlin.math.sin(Math.toRadians(2.0))), MoireMath.shiftAmplification(rotated, g), 1e-4)
    }

    @Test
    fun lineAnglesNormalizeToHalfOpenRange() {
        assertEquals(90.0, MoireMath.normalizeLineAngle(-90.0), tol)
        assertEquals(-80.0, MoireMath.normalizeLineAngle(100.0), tol)
        assertEquals(10.0, MoireMath.normalizeLineAngle(370.0), tol)
    }

    @Test
    fun barrierGridAndAliasingLimits() {
        assertEquals(24.0, MoireMath.barrierSlitSpacing(6, 4.0), tol)
        assertEquals(5.0, MoireMath.aliasingLimit(10.0), tol)
    }
}
