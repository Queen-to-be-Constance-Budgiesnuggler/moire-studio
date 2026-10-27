package dk.cbavf.studio.math

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.exp

class SmoothTest {
    @Test fun afterOneTimeConstantItHasGoneAboutTwoThirdsOfTheWay() {
        assertEquals(1f - exp(-1f), OnePole.step(0f, 1f, tauSec = 0.5f, dtSec = 0.5f), 1e-5f)
    }

    @Test fun aTimeOfZeroFollowsAtOnce() {
        assertEquals(7f, OnePole.step(0f, 7f, tauSec = 0f, dtSec = 0.016f), 0f)
    }

    @Test fun noTimePassingMeansNoMove() {
        assertEquals(2f, OnePole.step(2f, 7f, tauSec = 1f, dtSec = 0f), 0f)
    }

    @Test fun manySmallStepsEqualOneBigStep() {
        var v = 0f
        repeat(60) { v = OnePole.step(v, 1f, tauSec = 0.4f, dtSec = 1f / 60) }
        assertEquals(OnePole.step(0f, 1f, tauSec = 0.4f, dtSec = 1f), v, 1e-4f)
    }

    @Test fun theFirstValueJumpsToTheTarget() {
        assertEquals(5f, Smoother().update(0.0, 5f, riseSec = 1f, fallSec = 1f), 0f)
    }

    @Test fun riseAndFallUseTheirOwnTimes() {
        val s = Smoother()
        s.update(0.0, 0f, riseSec = 1f, fallSec = 0f)
        assertEquals(1f - exp(-1f), s.update(1.0, 1f, riseSec = 1f, fallSec = 0f), 1e-5f)  // rising: slow
        assertEquals(0f, s.update(1.1, 0f, riseSec = 1f, fallSec = 0f), 0f)                // falling: at once
    }

    @Test fun resetJumpsToTheTarget() {
        val s = Smoother()
        s.update(0.0, 0f, riseSec = 10f, fallSec = 10f)
        assertEquals(3f, s.update(0.1, 3f, riseSec = 10f, fallSec = 10f, reset = true), 0f)
    }

    @Test fun theStepIsTheTimeSinceTheLastUpdate() {
        // Not pulled for a second: it catches up by a whole second, not by one frame.
        val s = Smoother()
        s.update(0.0, 0f, riseSec = 1f, fallSec = 1f)
        assertEquals(1f - exp(-1f), s.update(1.0, 1f, riseSec = 1f, fallSec = 1f), 1e-5f)
    }
}
