package dk.cbavf.studio.vector3d

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OrbitCameraTest {
    private val front = OrbitCamera(yawDeg = 0.0, pitchDeg = 0.0, distance = 5.0)

    @Test fun atYawAndPitchZeroTheEyeIsOnThePlusZSide() {
        val e = front.eye
        assertEquals(0.0, e.x, 1e-12)
        assertEquals(0.0, e.y, 1e-12)
        assertEquals(5.0, e.z, 1e-12)
    }

    @Test fun yawNinetyPutsTheEyeOnThePlusXSide() {
        val e = front.copy(yawDeg = 90.0).eye
        assertEquals(5.0, e.x, 1e-9)
        assertEquals(0.0, e.z, 1e-9)
    }

    @Test fun pitchLiftsTheEye() {
        assertTrue(front.copy(pitchDeg = 30.0).eye.y > 0)
        assertEquals(5.0, front.copy(pitchDeg = 30.0).eye.length, 1e-9)
    }

    @Test fun theTargetLandsInTheMiddleOfTheScreen() {
        val p = front.copy(yawDeg = 47.0, pitchDeg = 20.0).project(Vec3.ZERO, 800.0, 600.0)!!
        assertEquals(400.0, p.x, 1e-9)
        assertEquals(300.0, p.y, 1e-9)
        assertEquals(5.0, p.depth, 1e-9)
    }

    @Test fun rightIsRightAndUpIsUpOnTheScreen() {
        val r = front.project(Vec3.X, 800.0, 600.0)!!
        val u = front.project(Vec3.Y, 800.0, 600.0)!!
        assertTrue(r.x > 400)
        assertEquals(300.0, r.y, 1e-9)
        assertTrue(u.y < 300)
    }

    @Test fun fartherPointsLandCloserToTheMiddle() {
        val near = front.project(Vec3(1.0, 0.0, 2.0), 800.0, 600.0)!!
        val far = front.project(Vec3(1.0, 0.0, -2.0), 800.0, 600.0)!!
        assertTrue(far.x - 400 < near.x - 400)
        assertTrue(far.depth > near.depth)
    }

    @Test fun projectionUsesTheFocalLength() {
        // Depth 5, one unit right: f / 5 pixels from the middle.
        val f = front.focal(800.0, 600.0)
        assertEquals(400 + f / 5, front.project(Vec3.X, 800.0, 600.0)!!.x, 1e-9)
    }

    @Test fun pointsBehindTheCameraAreNotProjected() {
        assertNull(front.project(Vec3(0.0, 0.0, 6.0), 800.0, 600.0))
        assertNotNull(front.project(Vec3(0.0, 0.0, 4.0), 800.0, 600.0))
    }

    @Test fun orbitWrapsTheYaw() {
        assertEquals(10.0, front.copy(yawDeg = 350.0).orbit(20.0, 0.0).yawDeg, 1e-9)
        assertEquals(350.0, front.orbit(-10.0, 0.0).yawDeg, 1e-9)
    }

    @Test fun orbitStopsShortOfStraightUp() {
        assertEquals(OrbitCamera.MAX_PITCH, front.orbit(0.0, 500.0).pitchDeg, 0.0)
        assertEquals(-OrbitCamera.MAX_PITCH, front.orbit(0.0, -500.0).pitchDeg, 0.0)
    }

    @Test fun pinchingOutComesCloser() {
        assertEquals(2.5, front.zoom(2.0).distance, 1e-9)
    }

    @Test fun zoomIsHeldInRange() {
        assertEquals(OrbitCamera.MIN_DISTANCE, front.zoom(100.0).distance, 0.0)
        assertEquals(OrbitCamera.MAX_DISTANCE, front.zoom(0.001).distance, 0.0)
        assertEquals(front, front.zoom(0.0))
    }

    @Test fun aFaceTurnedTowardsTheCameraIsSeen() {
        assertTrue(front.faces(Vec3(0.0, 0.0, 0.5), Vec3.Z))
    }

    @Test fun aFaceTurnedAwayIsNotSeen() {
        assertFalse(front.faces(Vec3(0.0, 0.0, -0.5), -Vec3.Z))
    }
}
