package dk.cbavf.studio.routing

import dk.cbavf.studio.routing.model.Cable
import dk.cbavf.studio.routing.model.RackPreset
import dk.cbavf.studio.routing.model.RackPresets
import dk.cbavf.studio.routing.model.RoutingDoc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RackPresetsTest {
    private val twoCables = RoutingDoc().connect("draw.canvas", "compose.in1").connect("sensors.roll", "nodes.sig1")

    @Test
    fun everyBuiltInPresetFitsTheRack() {
        for (p in RackPresets.BUILT_IN) assertEquals(p.name, emptyList<Cable>(), RackPresets.load(p).dropped)
    }

    @Test
    fun builtInNamesAreUnique() {
        assertEquals(RackPresets.BUILT_IN.size, RackPresets.BUILT_IN.map { it.name }.toSet().size)
    }

    @Test
    fun savingKeepsTheCables() {
        val saved = RackPresets.save(emptyList(), "  Mine ", twoCables, at = 5)
        assertEquals(listOf(RackPreset("Mine", twoCables.cables, 5)), saved)
    }

    @Test
    fun savingUnderTheSameNameReplacesIt() {
        val first = RackPresets.save(emptyList(), "A", twoCables, at = 1)
        val both = RackPresets.save(first, "B", RoutingDoc(), at = 2)
        val again = RackPresets.save(both, "A", RoutingDoc(), at = 3)
        assertEquals(listOf("B", "A"), again.map { it.name })
        assertTrue(again.last().cables.isEmpty())
    }

    @Test
    fun aBlankNameIsRefused() {
        assertTrue(RackPresets.save(emptyList(), "   ", twoCables, at = 1).isEmpty())
    }

    @Test
    fun deletingRemovesOnlyThatPreset() {
        val saved = RackPresets.save(RackPresets.save(emptyList(), "A", twoCables, 1), "B", twoCables, 2)
        assertEquals(listOf("B"), RackPresets.delete(saved, "A").map { it.name })
    }

    @Test
    fun loadingRebuildsThePatching() {
        val load = RackPresets.load(RackPreset("x", twoCables.cables))
        assertEquals(twoCables, load.doc)
        assertTrue(load.dropped.isEmpty())
    }

    @Test
    fun cablesThatNoLongerFitAreLeftOut() {
        val gone = Cable("nowhere.out", "compose.in2")
        val wrongType = Cable("sensors.roll", "compose.in3")
        val load = RackPresets.load(RackPreset("x", twoCables.cables + gone + wrongType))
        assertEquals(twoCables, load.doc)
        assertEquals(listOf(gone, wrongType), load.dropped)
    }

    @Test
    fun aSecondCableIntoOneInputIsDropped() {
        val load = RackPresets.load(RackPreset("x", listOf(Cable("draw.canvas", "compose.in1"), Cable("camera.last", "compose.in1"))))
        assertEquals("draw.canvas", load.doc.sourceOf("compose.in1"))
        assertEquals(1, load.dropped.size)
    }

    @Test
    fun aLoopInAPresetIsRefused() {
        val load = RackPresets.load(RackPreset("x", listOf(Cable("draw.canvas", "compose.in1"), Cable("compose.image", "draw.in"))))
        assertEquals(1, load.doc.cables.size)
        assertEquals(listOf(Cable("compose.image", "draw.in")), load.dropped)
    }

    @Test
    fun matchingIgnoresCableOrder() {
        val p = RackPreset("x", twoCables.cables.reversed())
        assertTrue(RackPresets.matches(p, twoCables))
        assertFalse(RackPresets.matches(p, RoutingDoc()))
    }

    @Test
    fun theEmptyPresetUnplugsEverything() {
        val empty = RackPresets.BUILT_IN.first { it.cables.isEmpty() }
        assertTrue(RackPresets.load(empty).doc.cables.isEmpty())
        assertTrue(RackPresets.isBuiltIn(empty))
        assertFalse(RackPresets.isBuiltIn(RackPreset(empty.name, twoCables.cables)))
    }
}
