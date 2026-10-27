package dk.cbavf.studio.vector3d

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PrimitivesTest {
    private fun assertOutward(m: Mesh) {
        for (f in m.faces.indices) assertTrue("face $f points in", m.normal(f) dot m.centroid(f) > 0)
    }

    private fun assertFlat(m: Mesh) {
        for (f in m.faces.indices) {
            val n = m.normal(f)
            val p = m.polygon(f)
            for (v in p) assertEquals("face $f is not flat", 0.0, (v - p[0]) dot n, 1e-9)
        }
    }

    @Test fun aCubeHasSixSquareFaces() {
        val m = Primitives.cube()
        assertEquals(6, m.faces.size)
        assertTrue(m.faces.all { it.size == 4 })
    }

    @Test fun cubeFacesPointOutAlongTheAxes() {
        val m = Primitives.cube()
        assertOutward(m)
        val normals = m.faces.indices.map { m.normal(it) }
        for (axis in listOf(Vec3.X, -Vec3.X, Vec3.Y, -Vec3.Y, Vec3.Z, -Vec3.Z)) {
            assertTrue("no face along $axis", normals.any { (it - axis).length < 1e-9 })
        }
    }

    @Test fun theCubeIsOneUnitAcross() {
        val m = Primitives.cube()
        assertEquals(-0.5, m.vertices.minOf { it.x }, 1e-12)
        assertEquals(0.5, m.vertices.maxOf { it.y }, 1e-12)
    }

    @Test fun aPlaneFacesUpAndIsTwoSided() {
        val m = Primitives.plane()
        assertEquals(1, m.faces.size)
        assertTrue(m.twoSided)
        assertTrue((m.normal(0) - Vec3.Y).length < 1e-9)
    }

    @Test fun eachSphereSplitHasFourTimesTheFaces() {
        assertEquals(20, Primitives.sphere(0).faces.size)
        assertEquals(80, Primitives.sphere(1).faces.size)
        assertEquals(320, Primitives.sphere(2).faces.size)
    }

    @Test fun sphereSplitsAreHeldAtTheMost() {
        assertEquals(320, Primitives.sphere(9).faces.size)
    }

    @Test fun sphereVerticesLieOnTheBall() {
        for (v in Primitives.sphere(2).vertices) assertEquals(0.5, v.length, 1e-12)
    }

    @Test fun sphereSplitsShareTheirNewVertices() {
        // An icosahedron has 30 edges: one split adds one vertex per edge.
        assertEquals(12 + 30, Primitives.sphere(1).vertices.size)
    }

    @Test fun sphereFacesPointOut() = assertOutward(Primitives.sphere(2))

    @Test fun aColumnHasTwoCapsAndASideFacePerSide() {
        val m = Primitives.column(6)
        assertEquals(8, m.faces.size)
        assertOutward(m)
        assertFlat(m)
    }

    @Test fun aConeClosesToOnePoint() {
        val m = Primitives.column(5, topRadius = 0.0)
        assertEquals(6, m.faces.size)
        assertEquals(6, m.vertices.size)
        assertOutward(m)
    }

    @Test fun columnSidesAreHeld() {
        assertEquals(3 + 2, Primitives.column(1).faces.size)
    }

    @Test fun everyShapeMakesFlatFaces() {
        for (s in Shape.entries) for (d in 0..Shape.MAX_DETAIL) assertFlat(s.mesh(d))
    }

    @Test fun newellNormalOfACounterClockwiseSquareIsUp() {
        val sq = listOf(Vec3(0.0, 0.0, 0.0), Vec3(1.0, 0.0, 0.0), Vec3(1.0, 1.0, 0.0), Vec3(0.0, 1.0, 0.0))
        assertEquals(1.0, Polygons.normal(sq).z, 1e-12)
        assertEquals(-1.0, Polygons.normal(sq.reversed()).z, 1e-12)
    }

    @Test fun outwardTurnsInwardFacesAround() {
        val cube = Primitives.cube()
        val flipped = Mesh(cube.vertices, cube.faces.map { it.reversedArray() })
        assertOutward(Primitives.outward(flipped))
    }
}
