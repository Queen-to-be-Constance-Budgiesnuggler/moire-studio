package dk.cbavf.studio.physics.model

import dk.cbavf.studio.draw.model.Grating
import dk.cbavf.studio.draw.model.ImageLayer
import dk.cbavf.studio.draw.model.Layer
import dk.cbavf.studio.draw.model.Pt
import dk.cbavf.studio.draw.model.Stroke
import dk.cbavf.studio.draw.model.SubStroke
import dk.cbavf.studio.draw.model.Transform
import dk.cbavf.studio.library.CanvasDoc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class ShapeMapperTest {
    private val thin = listOf(SubStroke(widthPx = 0f))

    private fun stroke(points: List<Pt>, stack: List<SubStroke> = thin) = Stroke.fromScreen(points, stack)

    private fun ring(cx: Float, cy: Float, r: Float, n: Int = 40) =
        List(n + 1) { i -> Pt(cx + r * cos(2 * PI * i / n).toFloat(), cy + r * sin(2 * PI * i / n).toFloat()) }

    private fun square(x0: Float, y0: Float, side: Float, perSide: Int = 10): List<Pt> {
        val pts = ArrayList<Pt>()
        for (i in 0 until perSide) pts += Pt(x0 + side * i / perSide, y0)
        for (i in 0 until perSide) pts += Pt(x0 + side, y0 + side * i / perSide)
        for (i in 0 until perSide) pts += Pt(x0 + side - side * i / perSide, y0 + side)
        for (i in 0..perSide) pts += Pt(x0, y0 + side - side * i / perSide)
        return pts
    }

    @Test fun aClosedRoundStrokeIsACircle() {
        val (shape, centre, _) = ShapeMapper.shapeOf(stroke(ring(300f, 200f, 50f)))!!
        shape as BodyShape.Circle
        assertEquals(50f, shape.radius, 1f)
        assertEquals(300f, centre.x, 1f)
        assertEquals(200f, centre.y, 1f)
    }

    @Test fun theCircleGrowsByHalfTheLineWidth() {
        val (shape, _, _) = ShapeMapper.shapeOf(stroke(ring(0f, 0f, 50f), listOf(SubStroke(widthPx = 10f))))!!
        assertEquals(55f, (shape as BodyShape.Circle).radius, 1f)
    }

    @Test fun aClosedSquareStrokeIsABox() {
        val (shape, centre, _) = ShapeMapper.shapeOf(stroke(square(100f, 100f, 80f)))!!
        shape as BodyShape.Box
        assertEquals(80f, shape.width, 1f)
        assertEquals(80f, shape.height, 1f)
        assertEquals(140f, centre.x, 1f)
        assertEquals(140f, centre.y, 1f)
    }

    @Test fun aClosedTriangleIsAPolygon() {
        val pts = listOf(Pt(0f, 0f), Pt(100f, 0f), Pt(50f, 80f), Pt(2f, 3f))
        val (shape, _, _) = ShapeMapper.shapeOf(stroke(pts))!!
        shape as BodyShape.Polygon
        assertEquals(3, shape.points.size)
        assertTrue(ShapeMapper.area(shape.points) > 0f)
    }

    @Test fun anOpenStrokeIsAFixedLine() {
        val doc = CanvasDoc(1000, 1000, listOf(Layer(1, stroke(listOf(Pt(0f, 500f), Pt(500f, 600f), Pt(1000f, 500f))))))
        val spec = ShapeMapper.specs(doc).single()
        assertTrue(spec.shape is BodyShape.Chain)
        assertEquals(BodyKind.Fixed, spec.kind)
    }

    @Test fun aLineCannotBeMadeMoving() {
        val doc = CanvasDoc(1000, 1000, listOf(Layer(1, stroke(listOf(Pt(0f, 500f), Pt(1000f, 500f))))))
        assertEquals(BodyKind.Fixed, ShapeMapper.specs(doc, mapOf(1 to BodyKind.Moving)).single().kind)
        assertEquals(listOf(BodyKind.Fixed, BodyKind.Off), ShapeMapper.kindsFor(BodyShape.Chain(emptyList())))
    }

    @Test fun anImageIsABoxOfItsShownSize() {
        val img = ImageLayer("a", 200, 100, Transform(tx = 50f, ty = 60f, rotationDeg = 30f, sx = 0.5f, sy = 2f))
        val (shape, centre, angle) = ShapeMapper.shapeOf(img)!!
        assertEquals(BodyShape.Box(100f, 200f), shape)
        assertEquals(Pt(50f, 60f), centre)
        assertEquals(30f, angle, 0.001f)
    }

    @Test fun gratingsAndHiddenLayersMakeNoBody() {
        assertNull(ShapeMapper.shapeOf(Grating()))
        val doc = CanvasDoc(100, 100, listOf(Layer(1, stroke(ring(50f, 50f, 20f)), visible = false)))
        assertTrue(ShapeMapper.specs(doc).isEmpty())
    }

    @Test fun aTinyStrokeMakesNoBody() {
        assertNull(ShapeMapper.shapeOf(stroke(listOf(Pt(10f, 10f), Pt(11f, 11f)))))
    }

    @Test fun thePickedKindIsKept() {
        val doc = CanvasDoc(100, 100, listOf(Layer(4, stroke(ring(50f, 50f, 20f)))))
        assertEquals(BodyKind.Moving, ShapeMapper.specs(doc).single().kind)
        assertEquals(BodyKind.Off, ShapeMapper.specs(doc, mapOf(4 to BodyKind.Off)).single().kind)
    }

    @Test fun theHullIsAnticlockwiseAndDropsInsidePoints() {
        val hull = ShapeMapper.convexHull(listOf(Pt(0f, 0f), Pt(10f, 0f), Pt(10f, 10f), Pt(0f, 10f), Pt(5f, 5f)))
        assertEquals(4, hull.size)
        assertEquals(100f, ShapeMapper.area(hull), 0.001f)
    }

    @Test fun theTightestRectangleOfATurnedSquareIsTurned() {
        val a = PI / 6
        val corners = listOf(Pt(-1f, -1f), Pt(1f, -1f), Pt(1f, 1f), Pt(-1f, 1f)).map {
            Pt((it.x * cos(a) - it.y * sin(a)).toFloat() * 20, (it.x * sin(a) + it.y * cos(a)).toFloat() * 20)
        }
        val r = ShapeMapper.minAreaRect(ShapeMapper.convexHull(corners))
        assertEquals(1600f, r.width * r.height, 1f)
        // Any side may be the rectangle's first: its angle is 30° give or take quarter turns.
        val d = ((r.angleDeg - 30f) % 90f + 90f) % 90f
        assertEquals(0f, minOf(d, 90f - d), 0.1f)
    }

    @Test fun simplifyingKeepsAtMostTheAskedCorners() {
        val hull = ShapeMapper.convexHull(ring(0f, 0f, 50f, 30))
        assertEquals(8, ShapeMapper.simplifyHull(hull, 8).size)
    }

    @Test fun decimateKeepsBothEnds() {
        val pts = List(100) { Pt(it.toFloat(), 0f) }
        val d = ShapeMapper.decimate(pts, 10)
        assertEquals(10, d.size)
        assertEquals(pts.first(), d.first())
        assertEquals(pts.last(), d.last())
    }
}
