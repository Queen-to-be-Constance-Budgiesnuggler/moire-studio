package dk.cbavf.studio.patch

import dk.cbavf.studio.patch.model.Lfo
import dk.cbavf.studio.patch.model.LfoShape
import dk.cbavf.studio.patch.model.Param
import dk.cbavf.studio.patch.model.PatchDoc
import dk.cbavf.studio.patch.model.PatchMath
import dk.cbavf.studio.patch.model.PatchSignals
import dk.cbavf.studio.patch.model.Route
import dk.cbavf.studio.patch.model.SourceKind
import dk.cbavf.studio.routing.model.JackType
import dk.cbavf.studio.routing.model.PATCH_LFO1
import dk.cbavf.studio.routing.model.PATCH_LFO2
import dk.cbavf.studio.routing.model.PATCH_ROUTES
import dk.cbavf.studio.routing.model.PATCH_TOUCH_X
import dk.cbavf.studio.routing.model.PATCH_TOUCH_Y
import dk.cbavf.studio.routing.model.Rack
import dk.cbavf.studio.routing.model.RoutingDoc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PatchSignalsTest {
    private fun route(id: Int, min: Float = 0f, max: Float = 10f) =
        Route(id, SourceKind.Lfo1, layerId = 1, param = Param.Spacing, min = min, max = max)

    @Test
    fun thePatchBayIsARackUnitWithValueJacks() {
        val jacks = listOf(PATCH_LFO1, PATCH_LFO2, PATCH_TOUCH_X, PATCH_TOUCH_Y) + PATCH_ROUTES
        for (j in jacks) assertEquals(j, JackType.Value, Rack.output(j)?.type)
        assertEquals("patch", Rack.moduleOf(PATCH_LFO1))
    }

    @Test
    fun patchSignalsPatchIntoValueInputs() {
        val d = RoutingDoc().connect(PATCH_LFO1, "nodes.sig1").connect(PATCH_TOUCH_X, "oscout.a").connect(PATCH_ROUTES[0], "glitch.seed")
        assertEquals(3, d.cables.size)
        // Not into a picture input.
        assertEquals(d, d.connect(PATCH_LFO2, "compose.in1"))
    }

    @Test
    fun onlyPatchBayJacksAreListened() {
        val d = RoutingDoc().connect(PATCH_LFO2, "nodes.sig1").connect("sensors.roll", "nodes.sig2")
        assertEquals(setOf(PATCH_LFO2), PatchSignals.listened(d))
        assertTrue(PatchSignals.listened(RoutingDoc()).isEmpty())
    }

    @Test
    fun theFirstFourRoutesHaveJacksInListOrder() {
        val routes = listOf(route(7), route(3), route(9), route(1), route(4))
        assertEquals("patch.r1", PatchSignals.routeJack(routes, 7))
        assertEquals("patch.r4", PatchSignals.routeJack(routes, 1))
        assertNull(PatchSignals.routeJack(routes, 4))
        assertNull(PatchSignals.routeJack(routes, 42))
    }

    @Test
    fun aRouteSendsWhereItSitsInItsRange() {
        val r = route(1, min = 4f, max = 30f)
        assertEquals(0f, PatchSignals.routeLevel(r, 4f), 1e-6f)
        assertEquals(0.5f, PatchSignals.routeLevel(r, 17f), 1e-6f)
        assertEquals(1f, PatchSignals.routeLevel(r, 30f), 1e-6f)
        assertEquals(1f, PatchSignals.routeLevel(r, 99f), 1e-6f)
    }

    @Test
    fun aReversedRangeCountsDown() {
        val r = route(1, min = 10f, max = 0f)
        assertEquals(0f, PatchSignals.routeLevel(r, 10f), 1e-6f)
        assertEquals(0.75f, PatchSignals.routeLevel(r, 2.5f), 1e-6f)
    }

    @Test
    fun anEmptyRangeSendsZero() {
        assertEquals(0f, PatchSignals.routeLevel(route(1, min = 5f, max = 5f), 5f), 0f)
    }

    @Test
    fun freeLevelsAreSentOnlyForListenedJacks() {
        val levels = PatchSignals.freeLevels(PatchDoc(), 0.0, 0.2f, 0.8f, setOf(PATCH_TOUCH_Y))
        assertEquals(mapOf(PATCH_TOUCH_Y to 0.8f), levels)
    }

    @Test
    fun theLfoJacksFollowThePatchLfos() {
        val doc = PatchDoc(lfo1 = Lfo(LfoShape.Saw, 1f), lfo2 = Lfo(LfoShape.Random, 2f))
        val levels = PatchSignals.freeLevels(doc, 0.25, 0f, 0f, setOf(PATCH_LFO1, PATCH_LFO2))
        assertEquals(0.25f, levels.getValue(PATCH_LFO1), 1e-6f)
        // The same seed as the patch's own LFO 2 routes.
        assertEquals(PatchMath.lfo(doc.lfo2, 0.25, seed = 2), levels.getValue(PATCH_LFO2), 0f)
    }

    @Test
    fun touchLevelsStayBetweenZeroAndOne() {
        val levels = PatchSignals.freeLevels(PatchDoc(), 0.0, -0.5f, 1.5f, setOf(PATCH_TOUCH_X, PATCH_TOUCH_Y))
        assertEquals(0f, levels.getValue(PATCH_TOUCH_X), 0f)
        assertEquals(1f, levels.getValue(PATCH_TOUCH_Y), 0f)
    }
}
