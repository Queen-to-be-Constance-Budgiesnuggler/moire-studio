package dk.cbavf.studio.physics.model

import dk.cbavf.studio.draw.model.ImageLayer
import dk.cbavf.studio.draw.model.Layer
import dk.cbavf.studio.draw.model.Pt
import dk.cbavf.studio.draw.model.Transform
import dk.cbavf.studio.library.CanvasDoc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class PhysicsFramesTest {
    private val img = ImageLayer("a", 100, 100, Transform(tx = 200f, ty = 300f))
    private val doc = CanvasDoc(1000, 1000, listOf(Layer(1, img), Layer(2, img)))
    private fun spec(id: Int, kind: BodyKind) = BodySpec(id, BodyShape.Box(100f, 100f), Pt(200f, 300f), 0f, kind)

    @Test fun aMovingBodysLayerFollowsItsPose() {
        val out = PhysicsFrames.frame(doc, listOf(spec(1, BodyKind.Moving)), mapOf(1 to Pose(250f, 500f, 45f)))
        val t = (out.layers[0].content as ImageLayer).transform
        assertEquals(250f, t.tx, 0.001f)
        assertEquals(500f, t.ty, 0.001f)
        assertEquals(45f, t.rotationDeg, 0.001f)
    }

    @Test fun otherLayersStayAsTheyWere() {
        val out = PhysicsFrames.frame(doc, listOf(spec(1, BodyKind.Moving), spec(2, BodyKind.Fixed)), mapOf(1 to Pose(0f, 0f, 0f), 2 to Pose(9f, 9f, 9f)))
        assertSame(doc.layers[1], out.layers[1])
        assertEquals(doc.widthPx, out.widthPx)
    }

    @Test fun aStrokeTurnsRoundTheBodyCentreNotItsOwn() {
        // A layer whose own centre is 10 px right of the body centre swings round it.
        val off = ImageLayer("a", 10, 10, Transform(tx = 210f, ty = 300f))
        val d = CanvasDoc(1000, 1000, listOf(Layer(1, off)))
        val out = PhysicsFrames.frame(d, listOf(spec(1, BodyKind.Moving)), mapOf(1 to Pose(200f, 300f, 90f)))
        val t = (out.layers[0].content as ImageLayer).transform
        assertEquals(200f, t.tx, 0.01f)
        assertEquals(310f, t.ty, 0.01f)
    }

    @Test fun wallsSitJustOutsideTheCanvas() {
        val w = PhysicsFrames.walls(400, 300, 20f)
        assertEquals(4, w.size)
        assertEquals(WallRect(Pt(200f, 310f), 440f, 20f), w[0])
        assertEquals(WallRect(Pt(-10f, 150f), 20f, 340f), w[2])
    }

    @Test fun aBoxOutlineIsTurnedAndPlaced() {
        val o = PhysicsFrames.outline(BodyShape.Box(20f, 10f), Pose(100f, 100f, 90f))
        assertEquals(4, o.size)
        assertEquals(105f, o[0].x, 0.01f)
        assertEquals(90f, o[0].y, 0.01f)
    }

    @Test fun aCircleOutlineHasTheAskedSides() {
        val o = PhysicsFrames.outline(BodyShape.Circle(5f), Pose(0f, 0f, 0f), circleSides = 12)
        assertEquals(12, o.size)
        assertEquals(5f, o[0].x, 0.001f)
    }
}
