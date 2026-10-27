package dk.cbavf.studio.draw.groups

import org.junit.Assert.*
import org.junit.Test

class GroupLogicTest {
    private val groups = listOf(GNode(1, null), GNode(2, 1), GNode(3, 2), GNode(4, null))

    @Test fun visibilityWalksTheAncestors() {
        assertTrue(GroupLogic.effectiveVisible(LNode(10, 3), groups))
        val hidden = listOf(GNode(1, null, visible = false), GNode(2, 1), GNode(3, 2))
        assertFalse(GroupLogic.effectiveVisible(LNode(10, 3), hidden))
        assertFalse(GroupLogic.effectiveVisible(LNode(10, null, visible = false), groups))
        assertTrue(GroupLogic.effectiveVisible(LNode(10, null), groups))
        assertTrue(GroupLogic.effectiveVisible(LNode(10, 99), groups))
    }
    @Test fun cycleDoesNotHang() {
        val cyc = listOf(GNode(1, 2), GNode(2, 1))
        assertFalse(GroupLogic.effectiveVisible(LNode(10, 1), cyc))
        GroupLogic.descendants(1, cyc)
    }
    @Test fun descendantsAndLayers() {
        assertEquals(setOf(2, 3), GroupLogic.descendants(1, groups))
        assertEquals(emptySet<Int>(), GroupLogic.descendants(4, groups))
        val layers = listOf(LNode(10, 1), LNode(11, 3), LNode(12, 4), LNode(13, null), LNode(14, 2))
        assertEquals(listOf(10, 11, 14), GroupLogic.layersIn(1, layers, groups).map { it.id })
        assertEquals(listOf(12), GroupLogic.layersIn(4, layers, groups).map { it.id })
    }
    @Test fun duplicateWholeGroupTreeCopiesTheGroups() {
        val layers = listOf(LNode(10, 1), LNode(11, 3), LNode(12, 4), LNode(14, 2))
        val r = GroupLogic.duplicate(layers, groups, setOf(10, 11, 14), 100, 50)
        assertEquals(mapOf(10 to 100, 11 to 101, 14 to 102), r.layerIdMap)
        assertEquals(mapOf(1 to 50, 2 to 51, 3 to 52), r.groupIdMap)
        assertEquals(listOf(GNode(50, null), GNode(51, 50), GNode(52, 51)), r.newGroups)
        assertEquals(listOf(LNode(100, 50), LNode(101, 52), LNode(102, 51)), r.newLayers)
    }
    @Test fun partialSelectionKeepsTheOriginalGroup() {
        val layers = listOf(LNode(10, 1), LNode(11, 3), LNode(14, 2))
        val r = GroupLogic.duplicate(layers, groups, setOf(11), 100, 50)
        assertEquals(mapOf(3 to 50), r.groupIdMap)
        assertEquals(listOf(GNode(50, 2)), r.newGroups)
        assertEquals(listOf(LNode(100, 50)), r.newLayers)
        val r2 = GroupLogic.duplicate(layers, groups, setOf(10), 100, 50)
        assertTrue(r2.groupIdMap.isEmpty())
        assertEquals(listOf(LNode(100, 1)), r2.newLayers)
    }
    @Test fun flagsAreKept() {
        val g = listOf(GNode(1, null, visible = false))
        val l = listOf(LNode(10, 1, visible = false), LNode(11, null, visible = true))
        val r = GroupLogic.duplicate(l, g, setOf(10, 11), 20, 5)
        assertEquals(listOf(GNode(5, null, false)), r.newGroups)
        assertEquals(listOf(LNode(20, 5, false), LNode(21, null, true)), r.newLayers)
    }
    @Test fun aGroupWithOneSelectedLayerIsCopied() {
        val r = GroupLogic.duplicate(listOf(LNode(12, 4)), groups, setOf(12), 100, 50)
        assertEquals(mapOf(4 to 50), r.groupIdMap)
        assertFalse(r.groupIdMap.containsKey(1))
        assertEquals(listOf(LNode(100, 50)), r.newLayers)
    }
}
