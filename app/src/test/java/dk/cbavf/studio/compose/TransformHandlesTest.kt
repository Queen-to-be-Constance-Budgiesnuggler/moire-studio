package dk.cbavf.studio.compose

import dk.cbavf.studio.compose.TransformHandles.Kind
import dk.cbavf.studio.compose.model.ComposeDoc
import dk.cbavf.studio.compose.model.ComposeLayer
import dk.cbavf.studio.compose.model.SolidLayerSource
import dk.cbavf.studio.draw.model.Pt
import dk.cbavf.studio.draw.model.Transform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TransformHandlesTest {
    /** A 200 × 100 layer centred at (500, 400), not turned or scaled. */
    private fun layer(t: Transform = Transform(tx = 500f, ty = 400f)) =
        ComposeLayer(1, "x", SolidLayerSource(0, 200, 100), t)

    @Test
    fun thereAreFourCornersFourEdgesAndOneKnob() {
        val hs = TransformHandles.handles(layer(), rotateArm = 30f)
        assertEquals(4, hs.count { it.kind == Kind.Corner })
        assertEquals(4, hs.count { it.kind == Kind.Edge })
        assertEquals(1, hs.count { it.kind == Kind.Rotate })
    }

    @Test
    fun edgeHandlesSitInTheMiddleOfEachSide() {
        val edges = TransformHandles.handles(layer(), 30f).filter { it.kind == Kind.Edge }
        assertEquals(Pt(500f, 350f), edges[0].at) // top
        assertEquals(Pt(600f, 400f), edges[1].at) // right
        assertEquals(Pt(500f, 450f), edges[2].at) // bottom
        assertEquals(Pt(400f, 400f), edges[3].at) // left
    }

    @Test
    fun theKnobSitsAboveTheTopEdge() {
        val knob = TransformHandles.handles(layer(), 30f).single { it.kind == Kind.Rotate }
        assertEquals(500f, knob.at.x, 1e-3f)
        assertEquals(320f, knob.at.y, 1e-3f)
    }

    @Test
    fun theKnobTurnsWithTheLayer() {
        val knob = TransformHandles.handles(layer(Transform(tx = 500f, ty = 400f, rotationDeg = 90f)), 30f).single { it.kind == Kind.Rotate }
        // A quarter turn clockwise: the top edge now faces right.
        assertEquals(500f + 50f + 30f, knob.at.x, 1e-3f)
        assertEquals(400f, knob.at.y, 1e-3f)
    }

    @Test
    fun hitFindsTheNearestHandleWithinReach() {
        val hs = TransformHandles.handles(layer(), 30f)
        val h = TransformHandles.hit(hs, Pt(603f, 352f), slop = 10f)!!
        assertEquals(Kind.Corner, h.kind)
        assertEquals(1, h.index) // top-right
    }

    @Test
    fun hitMissesWhenTooFar() {
        val hs = TransformHandles.handles(layer(), 30f)
        assertNull(TransformHandles.hit(hs, Pt(520f, 400f), slop = 10f))
    }

    @Test
    fun draggingACornerOutwardsScalesBothAxesEvenly() {
        val base = Transform(tx = 500f, ty = 400f, sx = 1f, sy = 2f)
        val corner = TransformHandles.Handle(Kind.Corner, 2, Pt(600f, 500f))
        val t = TransformHandles.drag(corner, base, from = Pt(600f, 500f), to = Pt(700f, 600f))
        assertEquals(2f, t.sx, 1e-3f)
        assertEquals(4f, t.sy, 1e-3f)
        assertEquals(500f, t.tx, 0f)
    }

    @Test
    fun draggingTheRightEdgeStretchesOnlyX() {
        val base = Transform(tx = 500f, ty = 400f)
        val edge = TransformHandles.Handle(Kind.Edge, 1, Pt(600f, 400f))
        val t = TransformHandles.drag(edge, base, from = Pt(600f, 400f), to = Pt(650f, 470f))
        assertEquals(1.5f, t.sx, 1e-3f)
        assertEquals(1f, t.sy, 0f)
    }

    @Test
    fun stretchingFollowsTheLayersOwnAxesWhenTurned() {
        // Turned a quarter: the top edge faces right, so moving right stretches y.
        val base = Transform(tx = 500f, ty = 400f, rotationDeg = 90f)
        val top = TransformHandles.Handle(Kind.Edge, 0, Pt(550f, 400f))
        val t = TransformHandles.drag(top, base, from = Pt(550f, 400f), to = Pt(600f, 400f))
        assertEquals(1f, t.sx, 1e-3f)
        assertEquals(2f, t.sy, 1e-3f)
    }

    @Test
    fun stretchingPastTheCentreStopsAtTheSmallestSizeInsteadOfFlipping() {
        val base = Transform(tx = 500f, ty = 400f)
        val edge = TransformHandles.Handle(Kind.Edge, 1, Pt(600f, 400f))
        val t = TransformHandles.drag(edge, base, from = Pt(600f, 400f), to = Pt(300f, 400f))
        assertEquals(TransformHandles.MIN_SCALE, t.sx, 1e-6f)
    }

    @Test
    fun aFlippedLayerStaysFlippedWhenScaled() {
        val base = Transform(tx = 500f, ty = 400f, sx = -1f)
        val corner = TransformHandles.Handle(Kind.Corner, 0, Pt(600f, 350f))
        val t = TransformHandles.drag(corner, base, from = Pt(600f, 450f), to = Pt(700f, 500f))
        assertTrue(t.sx < 0f)
        assertTrue(t.sy > 0f)
    }

    @Test
    fun theKnobTurnsByTheFingersAngleAroundTheCentre() {
        val base = Transform(tx = 500f, ty = 400f, rotationDeg = 10f)
        val knob = TransformHandles.Handle(Kind.Rotate, 0, Pt(500f, 320f))
        // From straight above the centre to straight right of it: a quarter turn clockwise.
        val t = TransformHandles.drag(knob, base, from = Pt(500f, 300f), to = Pt(600f, 400f))
        assertEquals(100f, t.rotationDeg, 1e-3f)
    }

    @Test
    fun turningSnapsNearFortyFiveDegreeSteps() {
        assertEquals(45f, TransformHandles.snap(43.5f), 0f)
        assertEquals(-90f, TransformHandles.snap(-91f), 0f)
        assertEquals(30f, TransformHandles.snap(30f), 0f)
    }

    @Test
    fun anglesStayBetweenMinusAndPlusHalfATurn() {
        assertEquals(-170f, TransformHandles.normalize(190f), 1e-3f)
        assertEquals(170f, TransformHandles.normalize(-190f), 1e-3f)
        assertEquals(180f, TransformHandles.normalize(-180f), 1e-3f)
    }

    @Test
    fun noHandlesForAGroupOrSeveralPickedLayers() {
        val s = ComposeState().apply { edit.load(ComposeDoc(1000, 800)) }
        s.addSolid(0xFF000000)
        s.addSolid(0xFFFFFFFF)
        assertTrue(s.handleLayer != null)
        s.togglePicked(s.doc.layers.first().id)
        assertNull(s.handleLayer)
        s.groupPicked()
        assertNull(s.handleLayer)
    }

    @Test
    fun aHandleDragIsOneUndoStep() {
        val s = ComposeState().apply { edit.load(ComposeDoc(1000, 800)) }
        s.addSolid(0xFF000000)
        val base = s.selected!!.transform
        val corner = s.handleAt(Pt(1000f, 800f), slop = 10f, rotateArm = 30f)!!
        for (k in 1..10) s.dragHandle(corner, base, Pt(1000f, 800f), Pt(1000f + k * 10, 800f + k * 8), "g1")
        assertTrue(s.selected!!.transform.sx > base.sx)
        s.edit.undo()
        assertEquals(base, s.selected!!.transform)
    }
}
