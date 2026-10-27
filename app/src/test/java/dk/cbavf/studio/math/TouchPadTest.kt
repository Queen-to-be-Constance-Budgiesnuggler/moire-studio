package dk.cbavf.studio.math

import org.junit.Assert.assertEquals
import org.junit.Test

class TouchPadTest {
    @Test fun aFingerIsAPointInZeroToOne() {
        assertEquals(0.25f to 0.75f, TouchPad.position(50f, 150f, 200f, 200f))
    }

    @Test fun aFingerOffThePadIsHeldAtTheEdge() {
        assertEquals(0f to 1f, TouchPad.position(-30f, 500f, 200f, 200f))
    }

    @Test fun aPadWithNoSizeGivesTheMiddle() {
        assertEquals(0.5f to 0.5f, TouchPad.position(10f, 10f, 0f, 0f))
    }

    @Test fun aSideMapsFromMinToMax() {
        assertEquals(-10f, TouchPad.map(0f, -10f, 30f), 0f)
        assertEquals(10f, TouchPad.map(0.5f, -10f, 30f), 1e-5f)
        assertEquals(30f, TouchPad.map(1f, -10f, 30f), 0f)
    }
}
