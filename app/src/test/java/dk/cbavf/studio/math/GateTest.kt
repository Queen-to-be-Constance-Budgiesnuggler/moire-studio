package dk.cbavf.studio.math

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GateTest {
    @Test fun edgesAreReportedOnce() {
        val e = EdgeDetector()
        assertEquals(Edge.None, e.update(false))
        assertEquals(Edge.Rising, e.update(true))
        assertEquals(Edge.None, e.update(true))
        assertEquals(Edge.Falling, e.update(false))
        assertEquals(Edge.None, e.update(false))
    }

    @Test fun aGateThatStartsHighRisesAtOnce() {
        assertEquals(Edge.Rising, EdgeDetector().update(true))
    }

    @Test fun resetForgetsTheLastGate() {
        val e = EdgeDetector()
        e.update(true)
        e.reset()
        assertEquals(Edge.Rising, e.update(true))
    }

    @Test fun logicTables() {
        assertTrue(GateLogic.and(true, true)); assertFalse(GateLogic.and(true, false))
        assertTrue(GateLogic.or(false, true)); assertFalse(GateLogic.or(false, false))
        assertTrue(GateLogic.xor(true, false)); assertFalse(GateLogic.xor(true, true)); assertFalse(GateLogic.xor(false, false))
        assertTrue(GateLogic.not(false)); assertFalse(GateLogic.not(true))
    }

    @Test fun aTriggerHoldsTheGateForItsLength() {
        val g = TriggerToGate(1.0)
        assertFalse(g.update(0.0, false))
        assertTrue(g.update(0.5, true))
        assertTrue(g.update(1.0, false))
        assertTrue(g.update(1.49, false))
        assertFalse(g.update(1.5, false))
    }

    @Test fun aNewTriggerRestartsTheLength() {
        val g = TriggerToGate(1.0)
        g.update(0.0, true)
        assertTrue(g.update(0.9, true))
        assertTrue(g.update(1.8, false))
        assertFalse(g.update(1.9, false))
    }

    @Test fun aZeroLengthNeverOpens() {
        val g = TriggerToGate(0.0)
        assertFalse(g.update(0.0, true))
    }

    @Test fun theDelayShiftsTheGate() {
        val d = GateDelay(0.5)
        assertFalse(d.update(0.0, true))
        assertFalse(d.update(0.4, true))
        assertTrue(d.update(0.5, true))
        assertTrue(d.update(0.9, true))
        assertTrue(d.update(1.0, false))   // the input at 0.5 was high
        assertTrue(d.update(1.4, false))   // the input at 0.9 was high
        assertFalse(d.update(1.5, false))  // the input at 1.0 was low
    }

    @Test fun everySecondRisePasses() {
        val d = GateDivide(2)
        val out = ArrayList<Boolean>()
        for (g in listOf(true, true, false, true, true, false, true, false, true)) out += d.update(g)
        // rises: #1 at index 0 (blocked), #2 at index 3 (passes while high), #3 at index 6 (blocked), #4 at index 8 (passes)
        assertEquals(listOf(false, false, false, true, true, false, false, false, true), out)
    }

    @Test fun divideByOneIsThePlainGate() {
        val d = GateDivide(1)
        assertEquals(listOf(true, false, true), listOf(true, false, true).map { d.update(it) })
    }

    @Test fun probabilityOneAlwaysPassesAndZeroNever() {
        val one = GateProbability(1.0, 7L)
        val zero = GateProbability(0.0, 7L)
        for (g in listOf(true, true, false, true)) {
            assertEquals(g, one.update(g))
            assertFalse(zero.update(g))
        }
    }

    @Test fun theSameSeedGivesTheSameDraws() {
        val a = GateProbability(0.5, 42L)
        val b = GateProbability(0.5, 42L)
        val input = List(40) { it % 2 == 0 }
        assertEquals(input.map { a.update(it) }, input.map { b.update(it) })
    }

    @Test fun aWholeHighPhaseIsDecidedOnce() {
        val p = GateProbability(0.5, 3L)
        // within one high phase the output never changes
        val phase = List(5) { p.update(true) }
        assertEquals(1, phase.toSet().size)
    }

    @Test fun sampleAndHoldTakesTheInputOnTheRisingEdge() {
        val s = SampleAndHold()
        assertEquals(0.0, s.update(false, 5.0), 1e-12)
        assertEquals(7.0, s.update(true, 7.0), 1e-12)
        assertEquals(7.0, s.update(true, 9.0), 1e-12)   // still high: no new sample
        assertEquals(7.0, s.update(false, 11.0), 1e-12)
        assertEquals(13.0, s.update(true, 13.0), 1e-12)
    }
}
