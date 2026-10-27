package dk.cbavf.studio.physics.model

import dk.cbavf.studio.draw.model.Pt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GravityTest {
    private fun assertPt(expected: Pt, actual: Pt?) {
        actual!!
        assertEquals(expected.x, actual.x, 0.001f)
        assertEquals(expected.y, actual.y, 0.001f)
    }

    @Test fun anUprightPhonePullsDownTheScreen() {
        assertPt(Pt(0f, 9.81f), Gravity.fromAccel(0f, 9.81f, 0f))
    }

    @Test fun tippingTheRightEdgeDownPullsRight() {
        // The right edge down: the push that holds the phone up points to its left (−x).
        assertPt(Pt(9.81f, 0f), Gravity.fromAccel(-9.81f, 0f, 0f))
    }

    @Test fun lyingFlatThereIsNoPullAlongTheScreen() {
        assertPt(Pt(0f, 0f), Gravity.fromAccel(0f, 0f, 9.81f))
    }

    @Test fun aLandscapeScreenStillPullsDownItsOwnBottom() {
        // Turned a quarter anticlockwise: the phone's +x now points up the screen.
        assertPt(Pt(0f, 9.81f), Gravity.fromAccel(9.81f, 0f, 0f, rotation = 1))
        assertPt(Pt(0f, 9.81f), Gravity.fromAccel(0f, -9.81f, 0f, rotation = 2))
        assertPt(Pt(0f, 9.81f), Gravity.fromAccel(-9.81f, 0f, 0f, rotation = 3))
    }

    @Test fun theScaleWeakensThePull() {
        assertPt(Pt(0f, 4.905f), Gravity.fromAccel(0f, 9.81f, 0f, scale = 0.5f))
    }

    @Test fun noReadingYetGivesNull() {
        assertNull(Gravity.fromAccel(0f, 0f, 0f))
    }

    @Test fun withoutAReadingTiltFallsBackToDown() {
        assertEquals(Gravity.down, Gravity.pick(GravitySource.Tilt, null, 1f))
        assertEquals(Pt(1f, 2f), Gravity.pick(GravitySource.Tilt, Pt(1f, 2f), 1f))
        assertEquals(Gravity.down * 2f, Gravity.pick(GravitySource.Down, Pt(1f, 2f), 2f))
    }

    @Test fun settingsStepInEqualParts() {
        val s = WorldSettings(fps = 25, substeps = 5)
        assertEquals(0.04, s.stepSeconds, 1e-9)
        assertEquals(0.008, s.substepSeconds, 1e-9)
    }

    @Test fun settingsConvertPixelsAndMetres() {
        val s = WorldSettings(pxPerMetre = 50f)
        assertEquals(2.0, s.metres(100f), 1e-9)
        assertEquals(100f, s.pixels(2.0), 1e-4f)
    }

    @Test fun clampedKeepsSettingsInRange() {
        val s = WorldSettings(fps = 1000, substeps = 0, bounce = 3f, friction = -1f).clamped()
        assertEquals(WorldSettings.MAX_FPS, s.fps)
        assertEquals(1, s.substeps)
        assertEquals(1f, s.bounce, 0f)
        assertEquals(0f, s.friction, 0f)
    }
}
