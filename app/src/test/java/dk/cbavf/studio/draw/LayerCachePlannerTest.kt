package dk.cbavf.studio.draw

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LayerCachePlannerTest {
    // Layers are strings here; a name starting with "-" subtracts.
    private val planner = LayerCachePlanner<String> { it.startsWith("-") }
    private val screen = "1080x2000"

    private fun plan(vararg keys: String, context: Any = screen) = planner.plan(keys.toList(), context)

    /** Draws [keys] twice so they end up in the full picture. */
    private fun settle(vararg keys: String) {
        plan(*keys)
        plan(*keys)
    }

    @Test
    fun theFirstFrameIsDrawnLive() {
        assertEquals(CachePlan.Live, plan("a", "b"))
    }

    @Test
    fun layersThatStayTheSameAreBakedOnce() {
        plan("a", "b", "c")
        assertEquals(CachePlan.RebuildFull(0), plan("a", "b", "c"))
        assertEquals(CachePlan.Full, plan("a", "b", "c"))
        assertEquals(CachePlan.Full, plan("a", "b", "c"))
    }

    @Test
    fun aNewStrokeOnTopIsPaintedOntoTheFullPicture() {
        settle("a", "b", "c")
        assertEquals(CachePlan.ExtendFull(3), plan("a", "b", "c", "d"))
        assertEquals(CachePlan.Full, plan("a", "b", "c", "d"))
    }

    @Test
    fun editingALayerKeepsTheLayersBelowItInTheBase() {
        settle("a", "b", "c", "d")
        assertEquals(CachePlan.Base(0, 2), plan("a", "b", "c1", "d"))
        assertEquals(listOf("a", "b"), planner.baseKeys)
        assertEquals(CachePlan.Base(2, 2), plan("a", "b", "c2", "d"))
    }

    @Test
    fun whenTheEditStopsTheFullPictureIsBuiltFromTheBase() {
        settle("a", "b", "c", "d")
        plan("a", "b", "c1", "d")
        plan("a", "b", "c2", "d")
        assertEquals(CachePlan.RebuildFull(2), plan("a", "b", "c2", "d"))
        assertEquals(CachePlan.Full, plan("a", "b", "c2", "d"))
    }

    @Test
    fun editingBelowTheBasePaintsTheBaseAgain() {
        settle("a", "b", "c", "d")
        plan("a", "b", "c1", "d")
        assertEquals(CachePlan.Base(0, 1), plan("a", "b1", "c1", "d"))
        assertEquals(listOf("a"), planner.baseKeys)
    }

    @Test
    fun editingHigherUpAddsToTheBase() {
        settle("a", "b", "c", "d")
        plan("a", "b", "c1", "d")
        assertEquals(CachePlan.Base(2, 3), plan("a", "b", "c1", "d1"))
        assertEquals(listOf("a", "b", "c1"), planner.baseKeys)
    }

    @Test
    fun editingTheBottomLayerDrawsLive() {
        settle("a", "b")
        assertEquals(CachePlan.Live, plan("a1", "b"))
    }

    @Test
    fun removingTheTopLayerKeepsTheRestInTheBase() {
        settle("a", "b", "c")
        assertEquals(CachePlan.Base(0, 2), plan("a", "b"))
        assertEquals(CachePlan.RebuildFull(2), plan("a", "b"))
    }

    @Test
    fun aSubtractingLayerIsNeverPaintedOnTop() {
        settle("a", "b")
        assertEquals(CachePlan.Live, plan("a", "b", "-s"))
        assertEquals(CachePlan.RebuildFull(0), plan("a", "b", "-s"))
        assertEquals(CachePlan.Full, plan("a", "b", "-s"))
    }

    @Test
    fun aSubtractingLayerAboveAnEditDrawsLive() {
        settle("a", "b", "-s")
        assertEquals(CachePlan.Live, plan("a", "b1", "-s"))
    }

    @Test
    fun aSubtractingLayerBelowAnEditGoesIntoTheBase() {
        settle("a", "-s", "b", "c")
        assertEquals(CachePlan.Base(0, 3), plan("a", "-s", "b", "c1"))
        assertEquals(CachePlan.Base(3, 3), plan("a", "-s", "b", "c2"))
    }

    @Test
    fun aNewSizeOrNewImagesStartAgain() {
        settle("a", "b")
        assertEquals(CachePlan.Live, plan("a", "b", context = "1080x1900"))
        assertNull(planner.fullKeys)
        assertEquals(CachePlan.RebuildFull(0), plan("a", "b", context = "1080x1900"))
    }

    @Test
    fun resetForgetsBothPictures() {
        settle("a", "b", "c")
        plan("a", "b", "c1")
        planner.reset()
        assertNull(planner.fullKeys)
        assertNull(planner.baseKeys)
        assertEquals(CachePlan.Live, plan("a", "b", "c1"))
    }

    @Test
    fun prefixAndFirstDifference() {
        assertTrue(LayerCachePlanner.isPrefix(listOf("a"), listOf("a", "b")))
        assertTrue(LayerCachePlanner.isPrefix(emptyList(), listOf("a")))
        assertFalse(LayerCachePlanner.isPrefix(listOf("a", "b"), listOf("a")))
        assertFalse(LayerCachePlanner.isPrefix(listOf("x"), listOf("a", "b")))
        assertEquals(1, LayerCachePlanner.firstDifference(listOf("a", "b"), listOf("a", "c")))
        assertEquals(2, LayerCachePlanner.firstDifference(listOf("a", "b"), listOf("a", "b", "c")))
    }
}
