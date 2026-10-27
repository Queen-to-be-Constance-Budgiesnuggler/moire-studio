package dk.cbavf.studio.math

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MathParamsTest {
    @After fun clean() = MathParams.resetAll()

    @Test fun defaultsComeThrough() {
        assertEquals(400.0, MathParams.get("slider.holdMs"), 0.0)
        assertFalse(MathParams.isChanged("slider.holdMs"))
    }

    @Test fun overridesAreClampedAndSetToDefaultClearsThem() {
        MathParams.set("slider.holdMs", 99999.0)
        assertEquals(1500.0, MathParams.get("slider.holdMs"), 0.0)
        assertTrue(MathParams.isChanged("slider.holdMs"))
        MathParams.set("slider.holdMs", 400.0)
        assertFalse(MathParams.isChanged("slider.holdMs"))
        assertTrue(MathParams.snapshot().isEmpty())
    }

    @Test fun snapshotAndLoadRoundTripAndIgnoreUnknownKeys() {
        MathParams.set("slider.repeatMs", 200.0)
        val saved = MathParams.snapshot()
        MathParams.resetAll()
        MathParams.load(saved + ("nope.nothing" to 5.0))
        assertEquals(200.0, MathParams.get("slider.repeatMs"), 0.0)
        assertEquals(1, MathParams.snapshot().size)
    }

    @Test fun everyDefaultIsInsideItsOwnRange() {
        for (p in MathParams.all) assertTrue(p.key, p.default in p.min..p.max)
        assertEquals("slider", MathParams.all.first().group)
    }
}
