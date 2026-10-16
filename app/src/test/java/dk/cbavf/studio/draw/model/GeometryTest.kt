package dk.cbavf.studio.draw.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeometryTest {
    private val tol = 1e-4f

    private fun assertPt(expected: Pt, actual: Pt) {
        assertEquals(expected.x, actual.x, tol)
        assertEquals(expected.y, actual.y, tol)
    }

    @Test
    fun offsetIsToTheRightOfDrawingDirection() {
        // Drawing left→right on a y-down screen: "right" is down.
        val line = listOf(Pt(0f, 0f), Pt(10f, 0f), Pt(20f, 0f))
        Geometry.offsetPolyline(line, 5f).forEach { assertEquals(5f, it.y, tol) }
        Geometry.offsetPolyline(line, -5f).forEach { assertEquals(-5f, it.y, tol) }
    }

    @Test
    fun zeroOffsetReturnsSamePoints() {
        val line = listOf(Pt(0f, 0f), Pt(3f, 4f))
        assertEquals(line, Geometry.offsetPolyline(line, 0f))
    }

    @Test
    fun pointInPolygon() {
        val square = listOf(Pt(0f, 0f), Pt(10f, 0f), Pt(10f, 10f), Pt(0f, 10f))
        assertTrue(Geometry.insidePolygon(Pt(5f, 5f), square))
        assertFalse(Geometry.insidePolygon(Pt(15f, 5f), square))
        assertFalse(Geometry.insidePolygon(Pt(5f, 5f), square.take(2)))
    }

    @Test
    fun transformScalesThenRotatesThenTranslates() {
        val t = Transform(tx = 100f, ty = 50f, rotationDeg = 90f, sx = 2f, sy = 1f)
        // (1,0) → scale (2,0) → rotate 90° clockwise on screen (0,2) → translate.
        assertPt(Pt(100f, 52f), t.apply(Pt(1f, 0f)))
    }

    @Test
    fun strokeFromScreenRoundTrips() {
        val pts = listOf(Pt(10f, 10f), Pt(30f, 10f), Pt(20f, 40f))
        val s = Stroke.fromScreen(pts, Pen.Plain.stack)
        assertPt(Pt(20f, 20f), Pt(s.transform.tx, s.transform.ty))
        assertPt(Pt(0f, 0f), Geometry.centroid(s.points))
        s.screenPoints().zip(pts).forEach { (a, b) -> assertPt(b, a) }
    }

    @Test
    fun dashBeatMatchesSpec() {
        assertEquals(180.0, MoireMath.dashBeat(18.0, 20.0), 1e-9)
    }
}
