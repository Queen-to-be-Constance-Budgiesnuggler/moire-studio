package dk.cbavf.studio.math

import org.junit.Assert.assertEquals
import org.junit.Test

class SwitchTest {
    @Test fun aClosedGatePicksAAndAnOpenOnePicksB() {
        assertEquals(10f, SwitchMath.pick(10f, 20f, select = 0f), 0f)
        assertEquals(20f, SwitchMath.pick(10f, 20f, select = 1f), 0f)
    }

    @Test fun theThresholdItselfPicksB() {
        assertEquals(20f, SwitchMath.pick(10f, 20f, select = 3f, threshold = 3f), 0f)
        assertEquals(10f, SwitchMath.pick(10f, 20f, select = 2.9f, threshold = 3f), 0f)
    }

    @Test fun fadeBlendsBetweenAAndB() {
        assertEquals(15f, SwitchMath.fade(10f, 20f, select = 0.5f), 1e-5f)
    }

    @Test fun fadeIsHeldAtTheEnds() {
        assertEquals(10f, SwitchMath.fade(10f, 20f, select = -2f), 0f)
        assertEquals(20f, SwitchMath.fade(10f, 20f, select = 5f), 0f)
    }
}
