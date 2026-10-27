package dk.cbavf.studio.math

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EnvelopePointsTest {
    private val axis = 4000.0
    private val s = AdsrShape(attackMs = 400.0, decayMs = 800.0, sustain = 0.5, releaseMs = 1000.0)

    @Test fun handlesSitWhereTheShapeSays() {
        val h = EnvelopePoints.handles(s, axis)
        assertEquals(4, h.size)
        assertEquals(0.1, h[0].x, 1e-9); assertEquals(1.0, h[0].y, 1e-9)
        assertEquals(0.3, h[1].x, 1e-9); assertEquals(0.5, h[1].y, 1e-9)
        assertEquals(0.45, h[2].x, 1e-9); assertEquals(0.5, h[2].y, 1e-9)
        assertEquals(0.7, h[3].x, 1e-9); assertEquals(0.0, h[3].y, 1e-9)
    }

    @Test fun draggingAHandleWhereItIsChangesNothing() {
        val h = EnvelopePoints.handles(s, axis)
        for (i in 0..3) {
            val d = EnvelopePoints.drag(s, i, h[i].x, h[i].y, axis)
            assertEquals(s.attackMs, d.attackMs, 1e-6)
            assertEquals(s.decayMs, d.decayMs, 1e-6)
            assertEquals(s.sustain, d.sustain, 1e-9)
            assertEquals(s.releaseMs, d.releaseMs, 1e-6)
        }
    }

    @Test fun theAttackHandleMovesOnlyTheAttack() {
        val d = EnvelopePoints.drag(s, 0, 0.2, 0.3, axis)
        assertEquals(800.0, d.attackMs, 1e-6)
        assertEquals(s.decayMs, d.decayMs, 1e-9)
        assertEquals(s.sustain, d.sustain, 1e-9)
    }

    @Test fun theDecayHandleMovesDecayAndSustain() {
        val d = EnvelopePoints.drag(s, 1, 0.4, 0.8, axis)
        assertEquals(s.attackMs, d.attackMs, 1e-9)
        assertEquals(1200.0, d.decayMs, 1e-6)   // 0.4 * 4000 - 400
        assertEquals(0.8, d.sustain, 1e-9)
    }

    @Test fun theSustainEndMovesOnlyTheLevel() {
        val d = EnvelopePoints.drag(s, 2, 0.9, 0.25, axis)
        assertEquals(0.25, d.sustain, 1e-9)
        assertEquals(s.attackMs, d.attackMs, 1e-9)
        assertEquals(s.decayMs, d.decayMs, 1e-9)
        assertEquals(s.releaseMs, d.releaseMs, 1e-9)
    }

    @Test fun theReleaseHandleMovesTheRelease() {
        val d = EnvelopePoints.drag(s, 3, 0.8, 0.0, axis)
        // (0.8 - 0.15) * 4000 - 400 - 800 = 1400
        assertEquals(1400.0, d.releaseMs, 1e-6)
    }

    @Test fun levelsStayBetweenZeroAndOne() {
        assertEquals(1.0, EnvelopePoints.drag(s, 1, 0.3, 5.0, axis).sustain, 1e-9)
        assertEquals(0.0, EnvelopePoints.drag(s, 2, 0.3, -2.0, axis).sustain, 1e-9)
    }

    @Test fun theTotalNeverPassesTheUsableWidth() {
        val limit = EnvelopePoints.USABLE * axis
        val a = EnvelopePoints.drag(s, 0, 1.0, 1.0, axis)
        assertTrue(a.attackMs + a.decayMs + a.releaseMs <= limit + 1e-6)
        val d = EnvelopePoints.drag(s, 1, 1.0, 0.5, axis)
        assertTrue(d.attackMs + d.decayMs + d.releaseMs <= limit + 1e-6)
        val r = EnvelopePoints.drag(s, 3, 1.0, 0.0, axis)
        assertTrue(r.attackMs + r.decayMs + r.releaseMs <= limit + 1e-6)
    }

    @Test fun durationsNeverGoNegative() {
        assertEquals(0.0, EnvelopePoints.drag(s, 0, -0.5, 0.0, axis).attackMs, 1e-9)
        assertEquals(0.0, EnvelopePoints.drag(s, 1, 0.0, 0.5, axis).decayMs, 1e-9)
        assertEquals(0.0, EnvelopePoints.drag(s, 3, 0.0, 0.0, axis).releaseMs, 1e-9)
    }

    @Test fun anUnknownHandleChangesNothing() {
        assertEquals(s, EnvelopePoints.drag(s, 7, 0.5, 0.5, axis))
    }

    @Test fun aShapeAlreadyTooLongDoesNotCrashAndCollapsesTheDraggedPart() {
        val long = AdsrShape(attackMs = 4000.0, decayMs = 4000.0, sustain = 0.5, releaseMs = 4000.0)
        assertEquals(0.0, EnvelopePoints.drag(long, 0, 0.5, 1.0, axis).attackMs, 1e-9)
        assertEquals(0.0, EnvelopePoints.drag(long, 1, 0.5, 0.5, axis).decayMs, 1e-9)
        assertEquals(0.0, EnvelopePoints.drag(long, 3, 0.9, 0.0, axis).releaseMs, 1e-9)
    }
}
