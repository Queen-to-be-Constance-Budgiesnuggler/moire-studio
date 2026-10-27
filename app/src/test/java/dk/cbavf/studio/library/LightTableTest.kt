package dk.cbavf.studio.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LightTableTest {
    private fun rec(id: String, t: Long, rating: Int = 0, flag: Int = 0, label: ColorLabel? = null, op: String = "draw.render") =
        AssetRecord(id, "$id.png", t, emptyList(), op, device = "x", widthPx = 1, heightPx = 1, rating = rating, flag = flag, label = label)

    @Test
    fun marksFilterByStarsFlagAndLabel() {
        val a = rec("a", 1, rating = 3, flag = 1, label = ColorLabel.Red)
        val b = rec("b", 2, rating = 5, flag = -1)
        assertTrue(Marks(minRating = 3).admits(a))
        assertFalse(Marks(minRating = 4).admits(a))
        assertTrue(Marks(flag = 1).admits(a))
        assertFalse(Marks(flag = 1).admits(b))
        assertTrue(Marks(label = ColorLabel.Red).admits(a))
        assertFalse(Marks(label = ColorLabel.Red).admits(b))
        assertFalse(Marks().active)
    }

    @Test
    fun sortOrders() {
        val list = listOf(rec("a", 1, rating = 2, op = "glitch.render"), rec("b", 3, rating = 5, op = "camera.capture"), rec("c", 2, rating = 5, op = "draw.render"))
        assertEquals(listOf("b", "c", "a"), list.sortedFor(LibrarySort.Newest).map { it.id })
        assertEquals(listOf("a", "c", "b"), list.sortedFor(LibrarySort.Oldest).map { it.id })
        assertEquals(listOf("b", "c", "a"), list.sortedFor(LibrarySort.Rating).map { it.id })
        assertEquals(listOf("b", "c", "a"), list.sortedFor(LibrarySort.Kind).map { it.id })
    }
}
