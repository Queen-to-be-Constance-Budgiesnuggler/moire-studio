package dk.cbavf.studio.animate

import dk.cbavf.studio.animate.model.Animation
import dk.cbavf.studio.animate.model.AnimationDoc
import dk.cbavf.studio.animate.model.Frame
import dk.cbavf.studio.animate.model.Step
import dk.cbavf.studio.draw.model.Grating
import dk.cbavf.studio.draw.model.Layer
import dk.cbavf.studio.draw.model.Pt
import dk.cbavf.studio.draw.model.Stroke
import dk.cbavf.studio.draw.model.SubStroke
import dk.cbavf.studio.library.CanvasDoc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnimateStateTest {
    private val doc = CanvasDoc(
        100, 100,
        listOf(
            Layer(1, Grating(spacingPx = 10f, angleDeg = 0f)),
            Layer(2, Grating(spacingPx = 10f, angleDeg = 90f)),
        ),
    )

    private fun offsetOf(d: CanvasDoc, id: Int) = (d.layers.first { it.id == id }.content as Grating).offsetPx

    @Test
    fun playbackOrderExpandsHolds() {
        val a = AnimationDoc(listOf(Frame(doc), Frame(doc, hold = 3), Frame(doc)))
        assertEquals(listOf(0, 1, 1, 1, 2), a.playbackOrder())
    }

    @Test
    fun stepFramesMovesOnlyTheTargetLayer() {
        // Vertical lines (angle 90) move with x; horizontal lines ignore it.
        val frames = Animation.stepFrames(doc, 2, Step(dx = 2f), 3)
        assertEquals(3, frames.size)
        assertEquals(-6f, offsetOf(frames.last().doc, 2), 1e-4f)
        assertEquals(0f, offsetOf(frames.last().doc, 1), 0f)
    }

    @Test
    fun strokeTurnsAroundItsOwnCentre() {
        val stroke = Stroke(listOf(Pt(-10f, 0f), Pt(10f, 0f)), dk.cbavf.studio.draw.model.Transform(tx = 50f, ty = 50f), listOf(SubStroke()))
        val d = CanvasDoc(100, 100, listOf(Layer(1, stroke)))
        val turned = Animation.stepped(d, 1, Step(turnDeg = 90f)).layers[0].content as Stroke
        assertEquals(50f, turned.transform.tx, 1e-4f)
        assertEquals(90f, turned.transform.rotationDeg, 1e-4f)
    }

    @Test
    fun aliasingCheckUsesHalfThePeriod() {
        val (shift, limit) = Animation.aliasingCheck(doc, 2, Step(dx = 6f))!!
        assertEquals(6.0, shift, 1e-6)
        assertEquals(5.0, limit, 1e-6)
    }

    @Test
    fun interleaveAndBarrierLineUp() {
        val strip = 3
        val n = 4
        for (shift in 0 until n) {
            for (x in 0 until 60) {
                // Through the slit you see exactly the frame the barrier is shifted to.
                if (Animation.barrierOpen(x, strip, n, shift)) assertEquals(shift, Animation.interleaveFrame(x, strip, n))
            }
        }
        assertEquals(12, AnimationDoc(List(n) { Frame(doc) }, stripPx = strip).slitSpacingPx)
        assertFalse(Animation.barrierOpen(3, strip, n, 0))
        assertTrue(Animation.barrierOpen(12, strip, n, 0))
    }

    @Test
    fun insertGoesAfterCurrentAndUndoRemovesIt() {
        val s = AnimateState()
        s.insert(doc)
        s.insert(doc.copy(widthPx = 50))
        s.current = 0
        s.insert(doc.copy(widthPx = 70))
        assertEquals(listOf(100, 70, 50), s.frames.map { it.doc.widthPx })
        assertEquals(1, s.current)
        s.undo()
        assertEquals(listOf(100, 50), s.frames.map { it.doc.widthPx })
    }

    @Test
    fun recordingDragCapturesOneFramePerInterval() {
        val s = AnimateState()
        s.insert(doc)
        s.targetId = 2
        s.fps = 10                      // one frame per 100 ms
        s.recording = true
        s.beginDrag(0)
        for (t in 10L..250L step 10) s.drag(Pt(1f, 0f), 1f, 0f, t)
        // Original + the dragged copy + captures at 100 and 200 ms.
        assertEquals(4, s.frames.size)
        assertEquals(0f, offsetOf(s.frames[0].doc, 2), 0f)
        // Offsets keep growing frame to frame: the drag carries on across captures.
        val offsets = s.frames.map { offsetOf(it.doc, 2) }
        assertTrue(offsets.zipWithNext().all { (a, b) -> b < a })
    }

    @Test
    fun dragWithoutRecordingEditsCurrentFrame() {
        val s = AnimateState()
        s.insert(doc)
        s.targetId = 2
        s.beginDrag(0)
        s.drag(Pt(4f, 0f), 1f, 0f, 10)
        assertEquals(1, s.frames.size)
        assertEquals(-4f, offsetOf(s.frames[0].doc, 2), 1e-4f)
    }
}
