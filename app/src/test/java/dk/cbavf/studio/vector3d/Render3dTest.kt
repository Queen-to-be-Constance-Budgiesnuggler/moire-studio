package dk.cbavf.studio.vector3d

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Render3dTest {
    private val front = OrbitCamera(yawDeg = 0.0, pitchDeg = 0.0, distance = 5.0)
    private val corner = OrbitCamera(yawDeg = 35.0, pitchDeg = 25.0, distance = 5.0)
    private val plain = Grating(on = false)
    private fun cube(vararg mods: (SceneObject) -> SceneObject) =
        Scene(listOf(mods.fold(SceneObject(Shape.Cube, grating = plain)) { o, m -> m(o) }))

    @Test fun aCubeSeenSquareOnShowsOneFace() {
        assertEquals(1, Render3d.frame(cube(), front, 800, 600).faces.size)
    }

    @Test fun aCubeSeenFromACornerShowsThreeFaces() {
        assertEquals(3, Render3d.frame(cube(), corner, 800, 600).faces.size)
    }

    @Test fun aSeeThroughCubeShowsAllSixFaces() {
        assertEquals(6, Render3d.frame(cube({ it.copy(opacity = 0.5) }), corner, 800, 600).faces.size)
    }

    @Test fun aSphereShowsAboutHalfItsFaces() {
        val n = Render3d.frame(Scene(listOf(SceneObject(Shape.Sphere, detail = 2, grating = plain))), corner, 800, 600).faces.size
        assertTrue("$n faces", n in 130..190)
    }

    @Test fun aPlaneIsSeenFromBelowToo() {
        val below = OrbitCamera(pitchDeg = -40.0)
        assertEquals(1, Render3d.frame(Scene(listOf(SceneObject(Shape.Plane, grating = plain))), below, 800, 600).faces.size)
    }

    @Test fun facesComeFarthestFirst() {
        val f = Render3d.frame(cube({ it.copy(opacity = 0.5) }), corner, 800, 600).faces
        for (i in 1 until f.size) assertTrue(f[i - 1].depth >= f[i].depth)
    }

    @Test fun aNearerShapeIsDrawnAfterAFartherOne() {
        val scene = Scene(listOf(
            SceneObject(Shape.Cube, Vec3(0.0, 0.0, 1.5), color = 0xFF00FF00.toInt(), grating = plain),
            SceneObject(Shape.Cube, Vec3(0.0, 0.0, -1.5), color = 0xFFFF0000.toInt(), grating = plain),
        ))
        val f = Render3d.frame(scene, front.copy(distance = 8.0), 800, 600).faces
        assertEquals(0, f.last().fill and 0xFF0000)
    }

    @Test fun movingAShapeMovesItOnTheScreen() {
        val a = Render3d.frame(cube(), front, 800, 600).faces[0].outline
        val b = Render3d.frame(cube({ it.copy(position = Vec3.X) }), front, 800, 600).faces[0].outline
        assertTrue(b[0] > a[0])
    }

    @Test fun aFaceTowardsTheLightIsFullyLit() {
        val scene = cube().copy(light = Light(azimuthDeg = 0.0, elevationDeg = 0.0, ambient = 0.2))
        assertEquals(0xFFE0E0E0.toInt(), Render3d.frame(scene, front, 800, 600).faces[0].fill)
    }

    @Test fun aFaceAwayFromTheLightGetsOnlyTheAmbient() {
        val scene = cube().copy(light = Light(azimuthDeg = 180.0, elevationDeg = 0.0, ambient = 0.5))
        assertEquals(Colors.shaded(0xFFE0E0E0.toInt(), 0.5), Render3d.frame(scene, front, 800, 600).faces[0].fill)
    }

    @Test fun shadeRunsFromAmbientToOne() {
        val l = Light(azimuthDeg = 0.0, elevationDeg = 90.0, ambient = 0.3)
        assertEquals(1.0, l.shade(Vec3.Y), 1e-9)
        assertEquals(0.3, l.shade(-Vec3.Y), 1e-9)
        assertEquals(0.3, l.shade(Vec3.X), 1e-9)
    }

    @Test fun flatShadingGivesEveryCubeFaceItsOwnLevel() {
        val fills = Render3d.frame(cube(), corner, 800, 600).faces.map { it.fill }.toSet()
        assertEquals(3, fills.size)
    }

    @Test fun opacitySetsTheFillsAlpha() {
        val f = Render3d.frame(cube({ it.copy(opacity = 0.5) }), front, 800, 600).faces[0]
        assertEquals(128, f.fill ushr 24)
    }

    @Test fun shadedKeepsBlackBlackAndScalesEachChannel() {
        assertEquals(0xFF000000.toInt(), Colors.shaded(0xFF000000.toInt(), 1.0))
        assertEquals(0xFF402010.toInt(), Colors.shaded(0xFF804020.toInt(), 0.5))
    }

    @Test fun gratingLinesAreDrawnOnTheFaces() {
        val f = Render3d.frame(cube({ it.copy(grating = Grating(spacing = 0.1)) }), front, 800, 600).faces[0]
        assertEquals(9 * 4, f.lines.size)
    }

    @Test fun gratingLinesThinWithDistance() {
        val g = { o: SceneObject -> o.copy(grating = Grating(spacing = 0.1, width = 0.02)) }
        val near = Render3d.frame(cube(g), front, 800, 600).faces[0].lineWidth
        val far = Render3d.frame(cube(g), front.copy(distance = 10.0), 800, 600).faces[0].lineWidth
        assertTrue(far < near)
    }

    @Test fun gratingLinesStayInsideTheFaceOnTheScreen() {
        val f = Render3d.frame(cube({ it.copy(grating = Grating(spacing = 0.07, angleDeg = 33.0)) }), front, 800, 600).faces[0]
        val xs = (0 until 4).map { f.outline[2 * it] }
        val ys = (0 until 4).map { f.outline[2 * it + 1] }
        for (i in 0 until f.lines.size / 2) {
            assertTrue(f.lines[2 * i] in xs.min() - 0.01f..xs.max() + 0.01f)
            assertTrue(f.lines[2 * i + 1] in ys.min() - 0.01f..ys.max() + 0.01f)
        }
    }

    @Test fun anEmptySceneIsJustTheBackground() {
        val f = Render3d.frame(Scene(emptyList(), background = 0xFF123456.toInt()), front, 10, 10)
        assertTrue(f.faces.isEmpty())
        assertEquals(0xFF123456.toInt(), f.background)
    }
}
