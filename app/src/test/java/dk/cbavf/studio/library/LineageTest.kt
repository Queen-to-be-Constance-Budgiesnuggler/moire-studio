package dk.cbavf.studio.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class LineageTest {
    private fun a(id: String, at: Long, vararg parents: String, tags: List<String> = emptyList()) =
        AssetRecord(id = id, file = "$id.png", createdAt = at, parents = parents.toList(), operation = "t", device = "d", widthPx = 1, heightPx = 1, tags = tags)

    // a <- b <- c, and d <- e (e also made from a)
    private val lineage = Lineage(
        listOf(a("a", 1), a("b", 3, "a"), a("c", 5, "b", tags = listOf("moire")), a("d", 2, tags = listOf("grid", "moire")), a("e", 4, "d", "a")),
    )

    @Test fun newestFirstIsSortedByTime() {
        assertEquals(listOf("c", "e", "b", "d", "a"), lineage.newestFirst().map { it.id })
    }

    @Test fun newestFirstIsWorkedOutOnce() {
        assertSame(lineage.newestFirst(), lineage.newestFirst())
    }

    @Test fun childrenKeepTheLibraryOrder() {
        assertEquals(listOf("b", "e"), lineage.children("a").map { it.id })
        assertEquals(emptyList<String>(), lineage.children("c").map { it.id })
    }

    @Test fun aChildNamingAParentTwiceIsListedOnce() {
        val l = Lineage(listOf(a("a", 1), a("b", 2, "a", "a")))
        assertEquals(listOf("b"), l.children("a").map { it.id })
    }

    @Test fun childrenOfAnUnknownIdAreNone() {
        assertEquals(emptyList<String>(), lineage.children("zzz").map { it.id })
    }

    @Test fun tagsAreDistinctAndSorted() {
        assertEquals(listOf("grid", "moire"), lineage.allTags())
    }

    @Test fun generationFollowsTheFirstParent() {
        assertEquals(0, lineage.generation("a"))
        assertEquals(2, lineage.generation("c"))
        assertEquals(1, lineage.generation("e"))
        // asked again, the remembered answer is the same
        assertEquals(2, lineage.generation("c"))
    }

    @Test fun aCycleStopsTheGeneration() {
        val l = Lineage(listOf(a("x", 1, "y"), a("y", 2, "x")))
        assertEquals(1, l.generation("x"))
    }
}
