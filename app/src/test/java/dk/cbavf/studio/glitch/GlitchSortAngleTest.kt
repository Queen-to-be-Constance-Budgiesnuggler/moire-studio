package dk.cbavf.studio.glitch

import dk.cbavf.studio.glitch.model.GlitchDoc
import dk.cbavf.studio.glitch.model.LastLoaded
import dk.cbavf.studio.glitch.model.SortField
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GlitchSortAngleTest {
    @Test
    fun turningTheAngleWhileOffTurnsOnTheAngleSort() {
        val d = GlitchDoc().withSortAngle(45f)
        assertEquals(SortField.Angle, d.sortField)
        assertEquals(45f, d.sortAngle)
    }

    @Test
    fun turningTheAngleKeepsTheEdgeSort() {
        val d = GlitchDoc(sortField = SortField.Edges).withSortAngle(90f)
        assertEquals(SortField.Edges, d.sortField)
    }

    @Test
    fun theAngleIsWholeDegreesWithinOneTurn() {
        assertEquals(12f, GlitchDoc().withSortAngle(12.7f).sortAngle)
        assertEquals(360f, GlitchDoc().withSortAngle(400f).sortAngle)
        assertEquals(0f, GlitchDoc().withSortAngle(-5f).sortAngle)
    }
}

class LastLoadedTest {
    @Test
    fun theSameKeyLoadsOnce() {
        var loads = 0
        val cache = LastLoaded<String, String> { loads++; "v$it" }
        assertEquals("va", cache["a"])
        assertEquals("va", cache["a"])
        assertEquals(1, loads)
    }

    @Test
    fun aNewKeyLoadsAgain() {
        var loads = 0
        val cache = LastLoaded<String, String> { loads++; "v$it" }
        cache["a"]
        assertEquals("vb", cache["b"])
        assertEquals(2, loads)
    }

    @Test
    fun aNullKeyGivesNullWithoutLoading() {
        var loads = 0
        val cache = LastLoaded<String, String> { loads++; "v$it" }
        assertNull(cache[null])
        assertEquals(0, loads)
    }

    @Test
    fun aMissingValueIsNotLookedForAgain() {
        var loads = 0
        val cache = LastLoaded<String, String> { loads++; null }
        assertNull(cache["gone"])
        assertNull(cache["gone"])
        assertEquals(1, loads)
    }
}
