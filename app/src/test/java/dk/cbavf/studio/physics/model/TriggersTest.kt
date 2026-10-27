package dk.cbavf.studio.physics.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TriggersTest {
    private val ab = ContactPair.of(1, 2)
    private val ac = ContactPair.of(1, 3)

    @Test fun aPairHasOneNameWhicheverWayRound() {
        assertEquals(ContactPair.of(2, 1), ContactPair.of(1, 2))
    }

    @Test fun aNewTouchIsAHit() {
        val e = ContactEdges()
        assertEquals(listOf(ab), e.step(setOf(ab)))
    }

    @Test fun stayingInTouchIsNoNewHit() {
        val e = ContactEdges()
        e.step(setOf(ab))
        assertTrue(e.step(setOf(ab)).isEmpty())
    }

    @Test fun aShortGapIsNoNewHit() {
        val e = ContactEdges(releaseSteps = 3)
        e.step(setOf(ab))
        e.step(emptySet())
        e.step(emptySet())
        assertTrue(e.step(setOf(ab)).isEmpty())
    }

    @Test fun touchingAgainAfterALongGapIsAHit() {
        val e = ContactEdges(releaseSteps = 3)
        e.step(setOf(ab))
        repeat(3) { e.step(emptySet()) }
        assertEquals(listOf(ab), e.step(setOf(ab)))
    }

    @Test fun eachNewPairIsItsOwnHit() {
        val e = ContactEdges()
        e.step(setOf(ab))
        assertEquals(listOf(ac), e.step(setOf(ab, ac)))
    }

    @Test fun resetForgetsWhoTouches() {
        val e = ContactEdges()
        e.step(setOf(ab))
        e.reset()
        assertEquals(listOf(ab), e.step(setOf(ab)))
    }

    @Test fun theTriggerIsOneOnlyOnAStepWithAHit() {
        val t = TriggerOut()
        assertEquals(1f, t.step(listOf(Hit(ab, 0.5f)), wallsCount = true), 0f)
        assertEquals(0f, t.step(emptyList(), wallsCount = true), 0f)
    }

    @Test fun hitsAreCountedAndTheHardestIsKept() {
        val t = TriggerOut()
        t.step(listOf(Hit(ab, 0.2f), Hit(ac, 0.7f)), wallsCount = true)
        assertEquals(2, t.count)
        assertEquals(0.7f, t.lastStrength, 0f)
    }

    @Test fun wallHitsCanBeLeftOut() {
        val t = TriggerOut()
        val wall = ContactPair.of(-1, 5)
        assertTrue(wall.touchesWall)
        assertEquals(0f, t.step(listOf(Hit(wall, 1f)), wallsCount = false), 0f)
        assertEquals(0, t.count)
    }

    @Test fun strengthIsSpeedUpToFull() {
        assertEquals(0.5f, TriggerOut.strength(TriggerOut.FULL_SPEED / 2), 0.0001f)
        assertEquals(1f, TriggerOut.strength(100f), 0f)
        assertEquals(0f, TriggerOut.strength(-1f), 0f)
    }
}
