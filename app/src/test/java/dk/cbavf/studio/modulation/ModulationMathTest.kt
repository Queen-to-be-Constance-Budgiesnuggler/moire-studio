package dk.cbavf.studio.modulation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModulationMathTest {
    private val m = ControlModulation(attackMs = 50f, decayMs = 200f, sustain = 0.6f, releaseMs = 400f, depth = 0.5f)

    @Test fun aHeldGateRisesAndSettles() {
        assertEquals(0.0, ModulationMath.level(m, 0.0, null), 1e-9)
        assertEquals(0.5, ModulationMath.level(m, 25.0, null), 1e-6)
        assertEquals(1.0, ModulationMath.level(m, 50.0, null), 1e-6)
        assertEquals(0.6, ModulationMath.level(m, 1000.0, null), 1e-6)
    }

    @Test fun aClosedGateFallsFromWhereItWas() {
        // gate held for 1800 ms (level 0.6), closed 200 ms ago: halfway down a 400 ms release
        assertEquals(0.3, ModulationMath.level(m, 2000.0, 200.0), 1e-6)
        assertEquals(0.0, ModulationMath.level(m, 2400.0, 600.0), 1e-6)
    }

    @Test fun aClockOpensTheGateForHalfOfEachPeriod() {
        val r = m.copy(repeatHz = 2f)           // period 500 ms, open for 250 ms
        assertEquals(0.0, ModulationMath.repeatLevel(r, 0.0), 1e-6)
        assertEquals(0.5, ModulationMath.repeatLevel(r, 0.025), 1e-6)
        assertEquals(0.525, ModulationMath.repeatLevel(r, 0.3), 1e-6)   // 50 ms into the release from 0.6
        assertEquals(0.5, ModulationMath.repeatLevel(r, 0.525), 1e-6)   // the next period starts again
    }

    @Test fun noRateMeansNoClock() {
        assertEquals(0.0, ModulationMath.repeatLevel(m.copy(repeatHz = 0f), 1.0), 0.0)
    }

    @Test fun theValueMovesByDepthTimesRangeAndStaysInside() {
        assertEquals(0.7f, ModulationMath.apply(0.2f, 0f..1f, m, 1.0), 1e-6f)
        assertEquals(1f, ModulationMath.apply(0.9f, 0f..1f, m, 1.0), 1e-6f)
        assertEquals(0f, ModulationMath.apply(0.5f, 0f..1f, m.copy(depth = -1f), 0.5), 1e-6f)
        assertEquals(35f, ModulationMath.apply(10f, 0f..100f, m, 0.5), 1e-4f)
    }

    @Test fun theHubKeepsAndSavesModulations() {
        var saved: Map<String, ControlModulation>? = null
        val hub = ModulationHub(emptyMap()) { saved = it }
        assertNull(hub["Draw.Spacing"])
        hub.set("Draw.Spacing", m)
        assertEquals(m, hub["Draw.Spacing"])
        assertEquals(mapOf("Draw.Spacing" to m), saved)
        hub.set("Draw.Spacing", null)
        assertNull(hub["Draw.Spacing"])
        assertEquals(emptyMap<String, ControlModulation>(), saved)
    }

    @Test fun theClockDutyIsAMathParameter() {
        val r = m.copy(repeatHz = 2f)   // period 500 ms
        try {
            dk.cbavf.studio.math.MathParams.set("gate.duty", 0.25)   // open for 125 ms
            // 200 ms in: the gate closed at 125 ms at level 0.85, and 75 ms of a 400 ms release have passed
            assertEquals(0.85 * (1 - 75.0 / 400.0), ModulationMath.repeatLevel(r, 0.2), 1e-6)
        } finally {
            dk.cbavf.studio.math.MathParams.reset("gate.duty")
        }
        assertEquals(0.525, ModulationMath.repeatLevel(r, 0.3), 1e-6)   // back to half
    }

    @Test fun everyMathParameterDefaultIsInsideItsRange() {
        for (p in dk.cbavf.studio.math.MathParams.all) {
            assertEquals(p.key, true, p.default >= p.min && p.default <= p.max)
        }
    }
}

