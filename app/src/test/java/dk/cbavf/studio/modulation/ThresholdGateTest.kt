package dk.cbavf.studio.modulation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThresholdGateTest {
    // Level 0.5 with a band of 0.2: the band runs from 0.4 to 0.6.
    private fun rising(vararg xs: Float): List<GateEdge> = ThresholdGate().let { g -> xs.map { g.next(it, 0.5f, 0.2f) } }
    private fun falling(vararg xs: Float): List<GateEdge> = ThresholdGate().let { g -> xs.map { g.next(it, 0.5f, 0.2f, falling = true) } }

    @Test fun aRisingGateOpensAtTheTopOfTheBand() {
        assertEquals(listOf(GateEdge.None, GateEdge.None, GateEdge.Opened), rising(0.3f, 0.55f, 0.6f))
    }

    @Test fun aRisingGateStaysOpenInsideTheBand() {
        val g = ThresholdGate()
        g.next(0.7f, 0.5f, 0.2f)
        assertEquals(GateEdge.None, g.next(0.45f, 0.5f, 0.2f))
        assertEquals(GateEdge.None, g.next(0.4f, 0.5f, 0.2f))
        assertTrue(g.open)
    }

    @Test fun aRisingGateClosesBelowTheBand() {
        assertEquals(listOf(GateEdge.Opened, GateEdge.None, GateEdge.Closed), rising(0.7f, 0.45f, 0.39f))
    }

    @Test fun noiseInsideTheBandDoesNotFlickerTheGate() {
        val edges = rising(0.7f, 0.42f, 0.58f, 0.41f, 0.59f, 0.43f)
        assertEquals(listOf(GateEdge.Opened, GateEdge.None, GateEdge.None, GateEdge.None, GateEdge.None, GateEdge.None), edges)
    }

    @Test fun eachEdgeIsReportedOnce() {
        val edges = rising(0.1f, 0.9f, 0.9f, 0.1f, 0.1f, 0.9f)
        assertEquals(listOf(GateEdge.None, GateEdge.Opened, GateEdge.None, GateEdge.Closed, GateEdge.None, GateEdge.Opened), edges)
    }

    @Test fun aFallingGateOpensAtTheBottomOfTheBand() {
        assertEquals(listOf(GateEdge.None, GateEdge.None, GateEdge.Opened), falling(0.8f, 0.45f, 0.4f))
    }

    @Test fun aFallingGateClosesAboveTheBand() {
        assertEquals(listOf(GateEdge.Opened, GateEdge.None, GateEdge.None, GateEdge.Closed), falling(0.2f, 0.55f, 0.6f, 0.61f))
    }

    @Test fun aValueAlreadyPastTheLevelOpensTheGateAtOnce() {
        assertEquals(listOf(GateEdge.Opened), rising(0.9f))
        assertEquals(listOf(GateEdge.Opened), falling(0.1f))
    }

    @Test fun withNoBandTheLevelItselfOpensTheGate() {
        val g = ThresholdGate()
        assertEquals(GateEdge.None, g.next(0.49f, 0.5f, 0f))
        assertEquals(GateEdge.Opened, g.next(0.5f, 0.5f, 0f))
        assertEquals(GateEdge.None, g.next(0.5f, 0.5f, 0f))
        assertEquals(GateEdge.Closed, g.next(0.49f, 0.5f, 0f))
    }

    @Test fun aNegativeBandCountsAsNoBand() {
        val g = ThresholdGate()
        assertEquals(GateEdge.Opened, g.next(0.5f, 0.5f, -0.3f))
        assertEquals(GateEdge.Closed, g.next(0.45f, 0.5f, -0.3f))
    }

    @Test fun aReadingThatIsNotANumberChangesNothing() {
        val g = ThresholdGate()
        g.next(0.9f, 0.5f, 0.2f)
        assertEquals(GateEdge.None, g.next(Float.NaN, 0.5f, 0.2f))
        assertTrue(g.open)
    }

    @Test fun switchingTheSensorsOffClosesTheGate() {
        val g = ThresholdGate()
        g.next(0.9f, 0.5f, 0.2f)
        assertEquals(GateEdge.Closed, g.off())
        assertEquals(GateEdge.None, g.off())
        assertFalse(g.open)
    }

    @Test fun theTriggerIsTheClockThenTheSensorThenTheButton() {
        val m = ControlModulation()
        assertTrue(m.byButton)
        assertTrue(m.copy(sensor = "light").bySensor)
        assertFalse(m.copy(sensor = "light").byButton)
        val clocked = m.copy(sensor = "light", repeatHz = 1f)
        assertTrue(clocked.byClock)
        assertFalse(clocked.bySensor)
    }

    @Test fun everySensorTriggerIsAJackOnTheSensorsUnit() {
        assertEquals("sensors.light", SensorTriggers.jack("light"))
        assertEquals("Shake", SensorTriggers.label("shake"))
        assertTrue("seed" !in SensorTriggers.names)
    }
}
