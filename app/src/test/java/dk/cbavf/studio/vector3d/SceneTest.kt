package dk.cbavf.studio.vector3d

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class SceneTest {
    private val empty = Scene(emptyList())

    @Test fun addPutsTheShapeAtTheEnd() {
        val s = empty.add(Shape.Cube).add(Shape.Sphere)
        assertEquals(listOf(Shape.Cube, Shape.Sphere), s.objects.map { it.shape })
    }

    @Test fun newShapesTakeTheNextColour() {
        val s = empty.add(Shape.Cube).add(Shape.Cone)
        assertEquals(Scene.PALETTE[0], s.objects[0].color)
        assertEquals(Scene.PALETTE[1], s.objects[1].color)
    }

    @Test fun removeTakesOutOneShape() {
        val s = empty.add(Shape.Cube).add(Shape.Plane).add(Shape.Cone).remove(1)
        assertEquals(listOf(Shape.Cube, Shape.Cone), s.objects.map { it.shape })
    }

    @Test fun removeOutOfRangeChangesNothing() {
        val s = empty.add(Shape.Cube)
        assertSame(s, s.remove(5))
    }

    @Test fun updateChangesOnlyThatShape() {
        val s = empty.add(Shape.Cube).add(Shape.Cube).update(1) { it.copy(yawDeg = 45.0) }
        assertEquals(0.0, s.objects[0].yawDeg, 0.0)
        assertEquals(45.0, s.objects[1].yawDeg, 0.0)
    }

    @Test fun placeTiltsThenTurnsThenMoves() {
        val o = SceneObject(Shape.Cube, position = Vec3(0.0, 1.0, 0.0), yawDeg = 90.0, tiltDeg = 90.0)
        // (0, 0, 1) tilted 90° about x is (0, −1, 0); turning about y leaves it; then moved up by one.
        val p = o.place(Vec3.Z)
        assertEquals(0.0, p.x, 1e-9)
        assertEquals(0.0, p.y, 1e-9)
        assertEquals(0.0, p.z, 1e-9)
    }

    @Test fun turnNinetyAboutUpTakesXToMinusZ() {
        val d = Vec3.X.rotatedY(90.0)
        assertEquals(0.0, d.x, 1e-9)
        assertEquals(-1.0, d.z, 1e-9)
    }

    @Test fun detailIsHeldInRange() {
        assertEquals(Shape.Cylinder.mesh(Shape.MAX_DETAIL).faces.size, Shape.Cylinder.mesh(99).faces.size)
        assertEquals(Shape.Cone.mesh(0).faces.size, Shape.Cone.mesh(-4).faces.size)
    }
}
