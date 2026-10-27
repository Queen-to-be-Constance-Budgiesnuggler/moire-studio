package dk.cbavf.studio.compose

import dk.cbavf.studio.compose.model.ComposeDoc
import dk.cbavf.studio.draw.model.Pt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposeGroupsTest {
    /** Four fills, ids 1–4, bottom first. */
    private fun state() = ComposeState().apply {
        edit.load(ComposeDoc(1000, 800))
        repeat(4) { addSolid(0xFF000000 + it) }
    }

    private val ComposeState.order get() = doc.layers.map { it.id }

    @Test
    fun groupingPutsPickedLayersUnderANewGroupWhereTheTopOneWas() {
        val s = state()
        s.selectOnly(2)
        s.togglePicked(3)
        assertEquals(setOf(2, 3), s.pickedIds)
        s.groupPicked()
        val g = s.selected!!
        assertTrue(g.isGroup)
        assertEquals(listOf(1, 2, 3, g.id, 4), s.order)
        assertEquals(listOf(2, 3), s.doc.children(g.id).map { it.id })
        assertEquals(listOf(1, g.id, 4), s.doc.children(null).map { it.id })
        // Undo takes the whole grouping back in one step.
        s.edit.undo()
        assertEquals(listOf(1, 2, 3, 4), s.order)
        assertTrue(s.doc.layers.none { it.group != null })
    }

    @Test
    fun ungroupAndLeaveGroupKeepTheOrder() {
        val s = state()
        s.selectOnly(2); s.togglePicked(3); s.groupPicked()
        val gid = s.selectedId!!
        s.selectOnly(3)
        s.leaveGroup()
        assertNull(s.doc.layers.first { it.id == 3 }.group)
        // 3 was the top member, so it goes just above the group: still above 2.
        assertEquals(listOf(1, 2, gid, 3, 4), s.order)
        s.ungroup(gid)
        assertTrue(s.doc.layers.none { it.isGroup })
        assertEquals(listOf(1, 2, 3, 4), s.order)
    }

    @Test
    fun reorderingMovesAmongSiblingsOnly() {
        val s = state()
        s.moveBy(1, 2)
        assertEquals(listOf(2, 3, 1, 4), s.order)
        s.moveBy(1, -5)
        assertEquals(listOf(1, 2, 3, 4), s.order)
        s.selectOnly(2); s.togglePicked(3); s.groupPicked()
        val gid = s.selectedId!!
        // Inside the group, 2 can only swap with 3; the group itself moves with its members.
        s.moveBy(2, 5)
        assertEquals(listOf(3, 2), s.doc.children(gid).map { it.id })
        s.moveBy(gid, 1)
        assertEquals(listOf(1, 4, gid), s.doc.children(null).map { it.id })
        assertEquals(listOf(1, 4, 3, 2, gid), s.order)
    }

    @Test
    fun duplicatingAndDeletingAGroupTakesItsMembers() {
        val s = state()
        s.selectOnly(2); s.togglePicked(3); s.groupPicked()
        s.duplicate()
        val groups = s.doc.layers.filter { it.isGroup }
        assertEquals(2, groups.size)
        assertEquals(2, s.doc.members(groups[1].id).size)
        s.selectOnly(groups[1].id)
        s.delete()
        assertEquals(5, s.doc.layers.size)
    }

    @Test
    fun movingSeveralLayersMovesThemAllTogether() {
        val s = state()
        s.selectOnly(1); s.togglePicked(2)
        val before = s.doc.layers.associate { it.id to it.transform.tx }
        s.transformSelected(Pt(10f, 0f), 1f, 0f, "g")
        assertEquals(before.getValue(1) + 10f, s.doc.layers.first { it.id == 1 }.transform.tx, 1e-3f)
        assertEquals(before.getValue(2) + 10f, s.doc.layers.first { it.id == 2 }.transform.tx, 1e-3f)
        assertEquals(before.getValue(3), s.doc.layers.first { it.id == 3 }.transform.tx, 1e-3f)
    }

    @Test
    fun copyAndPasteMakeAClippedLayer() {
        val s = state()
        s.selectOnly(4)
        s.selectRect(Pt(0f, 0f), Pt(100f, 100f))
        s.copySelection(0f)
        assertNotNull(s.clipboard)
        s.paste()
        val pasted = s.selected!!
        assertEquals(5, s.doc.layers.size)
        assertEquals(4, s.doc.layers.indexOfFirst { it.id == pasted.id })
        assertEquals(4, pasted.mask!!.clip!!.points.size)
        assertEquals(ComposeTool.Move, s.tool)
    }

    @Test
    fun normalizedKeepsMembersUnderTheirGroupAndRescuesOrphans() {
        val s = state()
        s.selectOnly(1); s.togglePicked(4); s.groupPicked()
        val gid = s.selectedId!!
        // Members follow the group: 1 and 4 now sit just below it.
        assertEquals(listOf(2, 3, 1, 4, gid), s.order)
        val broken = s.doc.copy(layers = s.doc.layers.filterNot { it.id == gid }).normalized()
        assertTrue(broken.layers.none { it.group != null })
    }
}
