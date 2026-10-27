package dk.cbavf.studio.develop

import org.junit.Assert.assertEquals
import org.junit.Test

class DevelopHistoryTest {
    @Test fun theHistoryListsEveryEditAndCanJump() {
        val s = DevelopState()
        s.edit { it.copy(exposureEv = 1f) }
        s.edit { it.copy(exposureEv = 2f) }
        s.edit { it.copy(exposureEv = 3f) }
        assertEquals(listOf("Start", "Edit 1", "Edit 2", "Edit 3"), s.timeline)
        assertEquals(3, s.position)
        s.jumpTo(1)
        assertEquals(1, s.position)
        assertEquals(1f, s.doc.exposureEv, 0f)
        // the undone edits stay in the list until a new edit
        assertEquals(4, s.timeline.size)
        s.jumpTo(3)
        assertEquals(3f, s.doc.exposureEv, 0f)
        s.jumpTo(0)
        assertEquals(0f, s.doc.exposureEv, 0f)
        s.edit { it.copy(exposureEv = 9f) }
        assertEquals(listOf("Start", "Edit 1"), s.timeline)
    }
}
