package dk.cbavf.studio.math

import org.junit.Assert.assertEquals
import org.junit.Test

class SlidersTest {
    private val eps = 1e-12

    @Test
    fun fineStepFollowsTheSpanDecade() {
        assertEquals(0.01, Sliders.fineStep(0.0, 1.0), eps)
        assertEquals(1.0, Sliders.fineStep(0.0, 255.0), eps)
        assertEquals(1.0, Sliders.fineStep(0.0, 360.0), eps)
        assertEquals(10.0, Sliders.fineStep(0.0, 1000.0), eps)
        assertEquals(10.0, Sliders.fineStep(0.0, 2000.0), eps)
        assertEquals(0.001, Sliders.fineStep(0.0, 0.5), eps)
        assertEquals(0.01, Sliders.fineStep(-1.0, 1.0), eps)
    }

    @Test
    fun fineStepEdgeCases() {
        assertEquals(1.0, Sliders.fineStep(5.0, 5.0), eps)
        assertEquals(1.0, Sliders.fineStep(5.0, 1.0), eps)
        assertEquals(1.0, Sliders.fineStep(0.0, Double.POSITIVE_INFINITY), eps)
        assertEquals(1.0, Sliders.fineStep(0.0, 50.0, integer = true), eps)
        assertEquals(0.1, Sliders.fineStep(0.0, 50.0), eps)
        assertEquals(10.0, Sliders.fineStep(0.0, 2000.0, integer = true), eps)
    }

    @Test
    fun snapToGridRemovesFloatDriftAndClamps() {
        assertEquals(0.3, Sliders.snapToGrid(0.1 + 0.2, 0.0, 1.0, 0.01), 0.0)
        assertEquals(1.0, Sliders.snapToGrid(1.7, 0.0, 1.0, 0.01), 0.0)
        assertEquals(0.0, Sliders.snapToGrid(-5.0, 0.0, 1.0, 0.01), 0.0)
        assertEquals(0.15, Sliders.snapToGrid(0.126, 0.0, 1.0, 0.05), 0.0)
        // exact ties round up (half-up), not to even
        assertEquals(0.5, Sliders.snapToGrid(0.25, 0.0, 1.0, 0.5), 0.0)
        assertEquals(1.0, Sliders.snapToGrid(0.75, 0.0, 1.0, 0.5), 0.0)
        // the grid starts at min, not at zero
        assertEquals(0.4, Sliders.snapToGrid(0.37, 0.1, 1.0, 0.1), 0.0)
    }

    @Test
    fun nextStepMovesOneStepAndStopsAtTheEnds() {
        assertEquals(0.51, Sliders.nextStep(0.5, 0.0, 1.0, 0.01, 1), 0.0)
        assertEquals(0.49, Sliders.nextStep(0.5, 0.0, 1.0, 0.01, -1), 0.0)
        assertEquals(0.3, Sliders.nextStep(0.29, 0.0, 1.0, 0.01, 1), 0.0)
        assertEquals(1.0, Sliders.nextStep(1.0, 0.0, 1.0, 0.01, 1), 0.0)
        assertEquals(0.0, Sliders.nextStep(0.0, 0.0, 1.0, 0.01, -1), 0.0)
    }

    @Test
    fun linearFractionMapsBothWays() {
        assertEquals(15.0, Sliders.fromFraction(0.5, 10.0, 20.0), eps)
        assertEquals(10.0, Sliders.fromFraction(-1.0, 10.0, 20.0), eps)
        assertEquals(20.0, Sliders.fromFraction(2.0, 10.0, 20.0), eps)
        assertEquals(0.5, Sliders.toFraction(15.0, 10.0, 20.0), eps)
        assertEquals(0.0, Sliders.toFraction(5.0, 10.0, 20.0), eps)
        assertEquals(1.0, Sliders.toFraction(25.0, 10.0, 20.0), eps)
        assertEquals(0.0, Sliders.toFraction(3.0, 3.0, 3.0), eps)
    }

    @Test
    fun logFractionMapsBothWays() {
        assertEquals(10.0, Sliders.fromFractionLog(0.5, 1.0, 100.0), 1e-9)
        assertEquals(1.0, Sliders.fromFractionLog(0.0, 1.0, 100.0), 1e-9)
        assertEquals(100.0, Sliders.fromFractionLog(1.0, 1.0, 100.0), 1e-9)
        assertEquals(0.5, Sliders.toFractionLog(10.0, 1.0, 100.0), 1e-9)
        assertEquals(0.0, Sliders.toFractionLog(-4.0, 1.0, 100.0), eps)
        assertEquals(0.0, Sliders.toFractionLog(5.0, 2.0, 2.0), eps)
        val t = 0.37
        assertEquals(t, Sliders.toFractionLog(Sliders.fromFractionLog(t, 20.0, 20000.0), 20.0, 20000.0), 1e-9)
    }

    @Test(expected = IllegalArgumentException::class)
    fun logNeedsPositiveBoundsForward() {
        Sliders.fromFractionLog(0.5, 0.0, 10.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun logNeedsPositiveBoundsBackward() {
        Sliders.toFractionLog(5.0, -1.0, 10.0)
    }
}
