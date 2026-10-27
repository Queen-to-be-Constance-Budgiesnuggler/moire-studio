package dk.cbavf.studio.physics

import dk.cbavf.studio.draw.model.Pt
import dk.cbavf.studio.physics.model.BodyKind
import dk.cbavf.studio.physics.model.BodyShape
import dk.cbavf.studio.physics.model.BodySpec
import dk.cbavf.studio.physics.model.ContactPair
import dk.cbavf.studio.physics.model.Gravity
import dk.cbavf.studio.physics.model.WorldSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The dyn4j world itself (it is plain Java, so it runs in unit tests). */
class PhysicsWorldTest {
    private val ball = BodySpec(1, BodyShape.Circle(20f), Pt(500f, 100f), 0f, BodyKind.Moving)

    @Test fun aBallFallsDownTheScreen() {
        val w = PhysicsWorld(listOf(ball), WorldSettings(walls = false), 1000, 1000)
        repeat(10) { w.step(Gravity.down) }
        assertTrue(w.poses().getValue(1).y > 110f)
        assertEquals(500f, w.poses().getValue(1).x, 0.01f)
    }

    @Test fun tiltPullsSideways() {
        val w = PhysicsWorld(listOf(ball), WorldSettings(walls = false), 1000, 1000)
        repeat(10) { w.step(Pt(9.81f, 0f)) }
        assertTrue(w.poses().getValue(1).x > 510f)
    }

    @Test fun aFixedBodyStaysPut() {
        val w = PhysicsWorld(listOf(ball.copy(kind = BodyKind.Fixed)), WorldSettings(walls = false), 1000, 1000)
        repeat(10) { w.step(Gravity.down) }
        assertEquals(100f, w.poses().getValue(1).y, 0.001f)
    }

    @Test fun anOffBodyIsLeftOut() {
        val w = PhysicsWorld(listOf(ball.copy(kind = BodyKind.Off)), WorldSettings(), 1000, 1000)
        assertTrue(w.poses().isEmpty())
    }

    @Test fun theFloorStopsTheBallAndItsHitIsATrigger() {
        val w = PhysicsWorld(listOf(ball), WorldSettings(bounce = 0f), 1000, 1000)
        val hits = (0 until 120).flatMap { w.step(Gravity.down) }
        val y = w.poses().getValue(1).y
        assertEquals(980f, y, 3f)
        assertEquals(1, hits.count { it.pair == ContactPair.of(-1, 1) })
        assertTrue(hits.first().strength > 0f)
    }

    @Test fun aBallRestingOnTheFloorIsOneHitNotOnePerStep() {
        val resting = ball.copy(centre = Pt(500f, 980f))
        val w = PhysicsWorld(listOf(resting), WorldSettings(bounce = 0f), 1000, 1000)
        val hits = (0 until 60).flatMap { w.step(Gravity.down) }
        assertEquals(1, hits.size)
        assertEquals(ContactPair.of(-1, 1), hits.single().pair)
    }

    @Test fun aBallLandingOnAFixedLineHitsIt() {
        val floor = BodySpec(2, BodyShape.Chain(listOf(Pt(-400f, 0f), Pt(400f, 0f))), Pt(500f, 400f), 0f, BodyKind.Fixed)
        val w = PhysicsWorld(listOf(ball, floor), WorldSettings(walls = false, bounce = 0f), 1000, 1000)
        val hits = (0 until 90).flatMap { w.step(Gravity.down) }
        assertTrue(hits.any { it.pair == ContactPair.of(1, 2) })
        assertEquals(380f, w.poses().getValue(1).y, 3f)
    }

    @Test fun aTurnedBoxStartsTurned() {
        val box = BodySpec(3, BodyShape.Box(40f, 20f), Pt(100f, 100f), 30f, BodyKind.Fixed)
        val w = PhysicsWorld(listOf(box), WorldSettings(walls = false), 1000, 1000)
        assertEquals(30f, w.poses().getValue(3).angleDeg, 0.01f)
        assertEquals(100f, w.poses().getValue(3).x, 0.01f)
    }

    @Test fun aPolygonBodyFalls() {
        val tri = BodySpec(4, BodyShape.Polygon(listOf(Pt(-20f, -10f), Pt(20f, -10f), Pt(0f, 20f))), Pt(300f, 100f), 0f, BodyKind.Moving)
        val w = PhysicsWorld(listOf(tri), WorldSettings(walls = false), 1000, 1000)
        repeat(10) { w.step(Gravity.down) }
        assertTrue(w.poses().getValue(4).y > 110f)
    }
}
