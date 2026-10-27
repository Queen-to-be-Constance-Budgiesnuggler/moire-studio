package dk.cbavf.studio

import dk.cbavf.studio.draw.DrawState
import dk.cbavf.studio.draw.Tool
import dk.cbavf.studio.draw.model.Pt
import dk.cbavf.studio.ui.Undoable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RedoTest {
    @Test
    fun undoableRedoesAndANewEditDropsRedo() {
        val u = Undoable(0)
        u.edit { 1 }
        u.edit { 2 }
        u.undo()
        assertEquals(1, u.doc)
        assertTrue(u.canRedo)
        u.redo()
        assertEquals(2, u.doc)
        assertFalse(u.canRedo)
        u.undo()
        u.undo()
        assertEquals(0, u.doc)
        u.redo()
        assertEquals(1, u.doc)
        // A new edit after undoing: what was undone cannot come back.
        u.edit { 5 }
        assertFalse(u.canRedo)
        u.undo()
        assertEquals(1, u.doc)
    }

    @Test
    fun drawRedoBringsBackAStroke() {
        val s = DrawState()
        val before = s.layers.size
        s.tool = Tool.Pen
        s.draftStart(Pt(0f, 0f))
        for (i in 1..10) s.draftMove(Pt(i * 10f, 0f))
        s.penUp()
        val drawn = s.layers.toList()
        s.undo()
        assertEquals(before, s.layers.size)
        assertTrue(s.canRedo)
        s.redo()
        assertEquals(drawn, s.layers.toList())
        assertFalse(s.canRedo)
    }
}
