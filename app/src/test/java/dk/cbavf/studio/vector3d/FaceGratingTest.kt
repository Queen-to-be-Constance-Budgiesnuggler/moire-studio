package dk.cbavf.studio.vector3d

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class FaceGratingTest {
    /** The cube's front face: a unit square at z = 0.5 facing +z. */
    private val square = listOf(Vec3(-0.5, -0.5, 0.5), Vec3(0.5, -0.5, 0.5), Vec3(0.5, 0.5, 0.5), Vec3(-0.5, 0.5, 0.5))
    private val n = Vec3.Z

    @Test fun linesOnAWallRunLevelAtAngleZero() {
        for ((a, b) in FaceGrating.segments(square, n, Grating(spacing = 0.1))) {
            assertEquals(a.y, b.y, 1e-12)
            assertEquals(1.0, abs(a.x - b.x), 1e-12)
        }
    }

    @Test fun linesOnTheEdgesAreLeftOut() {
        assertEquals(9, FaceGrating.segments(square, n, Grating(spacing = 0.1)).size)
    }

    @Test fun phaseSlidesTheLinesAcross() {
        val ys = FaceGrating.segments(square, n, Grating(spacing = 0.1, phase = 0.5)).map { it.first.y }.sorted()
        assertEquals(10, ys.size)
        assertEquals(-0.45, ys.first(), 1e-9)
    }

    @Test fun linesAreOneSpacingApart() {
        val ys = FaceGrating.segments(square, n, Grating(spacing = 0.125)).map { it.first.y }.sorted()
        for (i in 1 until ys.size) assertEquals(0.125, ys[i] - ys[i - 1], 1e-9)
    }

    @Test fun angleNinetyTurnsTheLinesUpright() {
        for ((a, b) in FaceGrating.segments(square, n, Grating(spacing = 0.1, angleDeg = 90.0))) assertEquals(a.x, b.x, 1e-9)
    }

    @Test fun slantedLinesAreParallelAndEndOnTheEdges() {
        val segs = FaceGrating.segments(square, n, Grating(spacing = 0.05, angleDeg = 30.0))
        assertTrue(segs.size > 10)
        val dir = (segs[0].second - segs[0].first).normalized()
        for ((a, b) in segs) {
            val d = (b - a).normalized()
            assertEquals(1.0, abs(d dot dir), 1e-9)
            for (p in listOf(a, b)) {
                assertEquals(0.5, p.z, 1e-12)
                assertTrue("not on an edge: $p", abs(abs(p.x) - 0.5) < 1e-9 || abs(abs(p.y) - 0.5) < 1e-9)
            }
        }
    }

    @Test fun linesWorkOnTriangles() {
        val tri = listOf(Vec3(0.0, 0.0, 0.0), Vec3(1.0, 0.0, 0.0), Vec3(0.0, 1.0, 0.0))
        val segs = FaceGrating.segments(tri, Vec3.Z, Grating(spacing = 0.25))
        assertEquals(3, segs.size)
        // At height y the triangle is 1 − y wide.
        for ((a, b) in segs) assertEquals(1 - a.y, abs(a.x - b.x), 1e-9)
    }

    @Test fun floorsGetLinesToo() {
        val floor = Primitives.plane().polygon(0)
        assertEquals(9, FaceGrating.segments(floor, Vec3.Y, Grating(spacing = 0.1)).size)
    }

    @Test fun aGratingSwitchedOffDrawsNothing() {
        assertTrue(FaceGrating.segments(square, n, Grating(on = false)).isEmpty())
        assertTrue(FaceGrating.segments(square, n, Grating(spacing = 0.0)).isEmpty())
    }

    @Test fun veryFineSpacingIsCapped() {
        assertTrue(FaceGrating.segments(square, n, Grating(spacing = 1e-6)).size <= FaceGrating.MAX_LINES)
    }

    @Test fun aLineThatOnlyTouchesACornerIsNoCut() {
        val levels = square.map { it.y }
        assertNull(FaceGrating.cut(square, levels, 0.5))
        assertNull(FaceGrating.cut(square, levels, 2.0))
    }
}
