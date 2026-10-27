package dk.cbavf.studio.draw

import dk.cbavf.studio.draw.groups.visibleLayers
import dk.cbavf.studio.draw.model.Grating
import dk.cbavf.studio.draw.model.RadialBurst
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DrawGroupsTest {
    /** A canvas with four gratings, ids 1 to 4 (the default two plus two more). */
    private fun four(): DrawState = DrawState().apply { add(Grating()); add(Grating()) }

    private fun DrawState.pick(vararg ids: Int) {
        selectSingle(ids.first())
        ids.drop(1).forEach { toggleSingle(it) }
    }

    @Test fun groupingMakesOneGroupAndUngroupingRemovesIt() {
        val s = four()
        s.pick(1, 2)
        s.groupSelection()
        assertEquals(1, s.groups.size)
        assertEquals(setOf(s.groups[0].id), s.layers.filter { it.id in setOf(1, 2) }.map { it.group }.toSet())
        assertTrue(s.layers.filter { it.id in setOf(3, 4) }.all { it.group == null })
        s.pick(1, 2)
        s.ungroupSelection()
        assertTrue(s.groups.isEmpty())
        assertTrue(s.layers.all { it.group == null })
    }

    @Test fun layersPickedInsideAGroupBecomeASubgroup() {
        val s = four()
        s.pick(1, 2, 3, 4)
        s.groupSelection()
        val outer = s.groups.single().id
        s.pick(3, 4)
        s.groupSelection()
        assertEquals(2, s.groups.size)
        val inner = s.groups.first { it.parent == outer }
        assertEquals(setOf(inner.id), s.layers.filter { it.id in setOf(3, 4) }.map { it.group }.toSet())
        assertEquals(setOf(outer), s.layers.filter { it.id in setOf(1, 2) }.map { it.group }.toSet())
        // the tabs: the outer group's row holds layers 1 and 2 and the subgroup
        val row = s.tabItems(outer)
        assertEquals(listOf(1, 2), row.mapNotNull { it.layer?.id })
        assertEquals(listOf(inner.id), row.mapNotNull { it.group?.id })
        assertEquals(1, s.tabItems(null).size)
    }

    @Test fun groupingTwoWholeGroupsNestsThem() {
        val s = four()
        s.pick(1, 2); s.groupSelection()
        s.pick(3, 4); s.groupSelection()
        val (a, b) = s.groups.map { it.id }
        s.selectGroup(a)
        s.toggleSingle(3)
        s.toggleSingle(4)
        s.groupSelection()
        assertEquals(3, s.groups.size)
        val top = s.groups.first { it.parent == null }
        assertEquals(setOf(a, b), s.groups.filter { it.parent == top.id }.map { it.id }.toSet())
    }

    @Test fun hiddenGroupsHideTheirLayersEverywhere() {
        val s = four()
        s.pick(1, 2); s.groupSelection()
        val g = s.groups.single().id
        assertEquals(4, s.visibleLayers().size)
        s.toggleGroupVisible(g)
        assertEquals(listOf(3, 4), s.visibleLayers().map { it.id })
        assertFalse(s.isShown(s.layers.first { it.id == 1 }))
        // the canvas other rooms see has them left out
        assertEquals(listOf(3, 4), s.toDoc().visibleLayers().map { it.id })
        s.toggleGroupVisible(g)
        assertEquals(4, s.visibleLayers().size)
    }

    @Test fun hideAllAndShowAllReachLayersHiddenOneByOne() {
        val s = four()
        s.pick(1, 2); s.groupSelection()
        s.setGroupedVisible(false)
        assertEquals(listOf(3, 4), s.visibleLayers().map { it.id })
        s.setGroupedVisible(true)
        assertEquals(4, s.visibleLayers().size)
        assertTrue(s.layers.all { it.visible })
    }

    @Test fun foldingIsNotAnUndoStep() {
        val s = four()
        s.pick(1, 2); s.groupSelection()
        val before = s.timeline.size
        s.toggleGroupCollapsed(s.groups.single().id)
        assertTrue(s.groups.single().collapsed)
        assertEquals(before, s.timeline.size)
    }

    @Test fun duplicateIsInPlaceAndCopiesTheGroup() {
        val s = four()
        s.pick(1, 2); s.groupSelection()
        val g = s.groups.single().id
        s.selectGroup(g)
        s.duplicate()
        assertEquals(6, s.layers.size)
        assertEquals(2, s.groups.size)
        val copy = s.groups.first { it.id != g }
        val copied = s.layers.filter { it.group == copy.id }
        assertEquals(2, copied.size)
        // exactly where the originals are: same content, no offset
        assertEquals(s.layers.first { it.id == 1 }.content, copied[0].content)
        // directly above the topmost original in the stack
        assertEquals(listOf(1, 2), s.layers.take(2).map { it.id })
        assertEquals(copied.map { it.id }, s.layers.subList(2, 4).map { it.id })
        assertEquals(copied.map { it.id }.toSet(), s.selection)
    }

    @Test fun pasteKeepsItsOffset() {
        val s = DrawState()
        s.add(RadialBurst())
        val burst = s.layers.last()
        s.select(burst.id)
        s.copy()
        s.paste()
        val pasted = s.layers.last().content as RadialBurst
        assertEquals((burst.content as RadialBurst).centerXPx + 32f, pasted.centerXPx, 0.001f)
    }

    @Test fun historyListsLabelledStepsAndJumps() {
        val s = four()
        s.pick(1, 2); s.groupSelection()
        s.selectGroup(s.groups.single().id)
        s.duplicate()
        assertEquals(listOf("Start", "Add layer", "Add layer", "Group", "Duplicate"), s.timeline)
        assertEquals(4, s.position)
        s.jumpTo(2)
        assertEquals(2, s.position)
        assertEquals(4, s.layers.size)
        assertTrue(s.groups.isEmpty())
        // undone steps stay in the list until something new is done
        assertEquals(5, s.timeline.size)
        s.jumpTo(4)
        assertEquals(6, s.layers.size)
        s.jumpTo(2)
        s.add(Grating())
        assertEquals(listOf("Start", "Add layer", "Add layer", "Add layer"), s.timeline)
    }

    @Test fun oldProjectsWithGroupNumbersGetGroupEntries() {
        val s = DrawState()
        val doc = s.toDoc().copy(layers = s.layers.map { it.copy(group = 7) })
        s.open(doc, null)
        assertEquals(listOf(7), s.groups.map { it.id })
        assertEquals("Group 7", s.groups[0].name)
    }
}
