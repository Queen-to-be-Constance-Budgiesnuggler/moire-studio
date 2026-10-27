package dk.cbavf.studio.physics

import dk.cbavf.studio.draw.model.Layer
import dk.cbavf.studio.draw.model.Pt
import dk.cbavf.studio.draw.model.Stroke
import dk.cbavf.studio.draw.model.SubStroke
import dk.cbavf.studio.library.CanvasDoc
import dk.cbavf.studio.physics.model.Gravity
import dk.cbavf.studio.physics.model.GravitySource
import dk.cbavf.studio.physics.model.WorldSettings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class PhysicsStateTest {
    private val ball = Stroke.fromScreen(
        List(41) { i -> Pt(500f + 40f * cos(2 * PI * i / 40).toFloat(), 200f + 40f * sin(2 * PI * i / 40).toFloat()) },
        listOf(SubStroke(widthPx = 0f)),
    )
    private val doc = CanvasDoc(1000, 1000, listOf(Layer(1, ball)))

    private fun loaded() = PhysicsState().apply {
        load(doc)
        gravitySource = GravitySource.Down
    }

    private fun stepOnce(s: PhysicsState): Float? = s.accept(s.computeStep(s.worldOrNew()!!, doc, Gravity.down, tilt = false))

    @Test fun recordKeepsEachStepWhileOn() {
        val s = loaded()
        s.recording = true
        repeat(5) { stepOnce(s) }
        assertEquals(5, s.recordedCount)
    }

    @Test fun recordStopsWhenFull() {
        val s = loaded()
        s.recording = true
        repeat(PhysicsState.MAX_RECORDED + 5) { s.applyStep(doc, emptyMap(), emptyList(), Gravity.down, false) }
        assertEquals(PhysicsState.MAX_RECORDED, s.recordedCount)
        assertTrue(s.recordFull)
        assertFalse(s.recording)
    }

    @Test fun takeRecordedHandsOverAndClears() {
        val s = loaded()
        s.recording = true
        repeat(3) { stepOnce(s) }
        assertEquals(3, s.takeRecorded().size)
        assertEquals(0, s.recordedCount)
        assertTrue(s.takeRecorded().isEmpty())
        assertFalse(s.recordFull)
    }

    @Test fun aBakeIsItsLengthTimesTheFrameRatePlusTheStart() = runBlocking {
        val s = loaded()
        s.updateSettings(WorldSettings(fps = 10))
        s.bakeSeconds = 2f
        assertEquals(21, s.bakeFrames)
        assertEquals(21, s.bake().size)
        assertFalse(s.bakeCut)
    }

    @Test fun aLongBakeIsCutToTheMostItKeeps() = runBlocking {
        val s = loaded()
        s.updateSettings(WorldSettings(fps = 60))
        s.bakeSeconds = 20f
        assertTrue(s.bakeCut)
        assertEquals(PhysicsState.MAX_RECORDED, s.bakeFrames)
        assertEquals(PhysicsState.MAX_RECORDED, s.bake().size)
    }

    @Test fun bakeStepsIsAtLeastOne() {
        assertEquals(1, PhysicsState.bakeSteps(0f, 30))
        assertEquals(PhysicsState.MAX_RECORDED - 1, PhysicsState.bakeSteps(1000f, 60))
    }

    @Test fun aStepShowsTheNewFrame() {
        val s = loaded()
        assertNotNull(stepOnce(s))
        assertTrue(s.poses.getValue(1).y > 200f)
    }

    @Test fun aStepFromBeforeAResetIsThrownAway() {
        val s = loaded()
        val r = s.computeStep(s.worldOrNew()!!, doc, Gravity.down, tilt = false)
        s.reset()
        assertNull(s.accept(r))
        assertSame(doc, s.frame)
        assertTrue(s.poses.isEmpty())
    }
}
