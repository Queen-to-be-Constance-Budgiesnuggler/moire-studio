package dk.cbavf.studio.ui.history

import org.junit.Assert.*
import org.junit.Test

class HistoryTest {
    @Test fun startsWithOneEntry() {
        val h = History(0)
        assertEquals(listOf("Start"), h.labels())
        assertEquals(0, h.current)
        assertFalse(h.canUndo); assertFalse(h.canRedo)
        assertNull(h.undo()); assertNull(h.redo())
    }
    @Test fun pushUndoRedo() {
        val h = History(0)
        h.push("one", 1); h.push("two", 2)
        assertEquals(listOf("Start", "one", "two"), h.labels())
        assertEquals(2, h.current); assertEquals(2, h.index)
        assertEquals(1, h.undo()); assertEquals(0, h.undo()); assertNull(h.undo())
        assertTrue(h.canRedo)
        assertEquals(1, h.redo()); assertEquals(2, h.redo()); assertNull(h.redo())
    }
    @Test fun aNewActionDropsTheRedoBranch() {
        val h = History(0)
        h.push("a", 1); h.push("b", 2); h.push("c", 3)
        h.undo(); h.undo()
        h.push("x", 10)
        assertEquals(listOf("Start", "a", "x"), h.labels())
        assertEquals(10, h.current)
        assertFalse(h.canRedo)
    }
    @Test fun jumpTo() {
        val h = History("s")
        h.push("a", "A"); h.push("b", "B"); h.push("c", "C")
        assertEquals("A", h.jumpTo(1))
        assertEquals(1, h.index)
        assertTrue(h.canUndo); assertTrue(h.canRedo)
        assertEquals("C", h.jumpTo(3))
        assertNull(h.jumpTo(4)); assertNull(h.jumpTo(-1))
        assertEquals(3, h.index)
    }
    @Test fun limitDropsTheOldest() {
        val h = History(0, limit = 3)
        h.push("a", 1); h.push("b", 2); h.push("c", 3)
        assertEquals(listOf("a", "b", "c"), h.labels())
        assertEquals(2, h.index); assertEquals(3, h.current)
        assertEquals(2, h.undo()); assertEquals(1, h.undo()); assertNull(h.undo())
    }
    @Test fun entriesCarryTheClockTime() {
        var t = 1000L
        val h = History(0, clock = { t })
        t = 2000L; h.push("a", 1)
        assertEquals(listOf(1000L, 2000L), h.entries.map { it.timeMs })
    }
}
