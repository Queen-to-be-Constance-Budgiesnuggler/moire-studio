package dk.cbavf.studio.library

import org.junit.Assert.assertEquals
import org.junit.Test

class DeletePlanTest {
    private fun a(id: String, vararg parents: String) =
        AssetRecord(id = id, file = "$id.png", createdAt = 0, parents = parents.toList(), operation = "t", device = "d", widthPx = 1, heightPx = 1)

    // a <- b <- c, and d <- e (e also made from a)
    private val all = listOf(a("a"), a("b", "a"), a("c", "b"), a("d"), a("e", "d", "a"))

    @Test fun aLeafCanGo() {
        val p = DeletePlan.of(all, setOf("c"), withDescendants = false)
        assertEquals(listOf("c"), p.order.map { it.id })
        assertEquals(emptyList<String>(), p.blocked.map { it.id })
    }

    @Test fun anAssetWithChildrenIsBlockedUnlessItsChildrenGoToo() {
        val p = DeletePlan.of(all, setOf("b"), withDescendants = false)
        assertEquals(emptyList<String>(), p.order.map { it.id })
        assertEquals(listOf("b"), p.blocked.map { it.id })
    }

    @Test fun pickedChildrenGoBeforeTheirParents() {
        val p = DeletePlan.of(all, setOf("b", "c"), withDescendants = false)
        assertEquals(listOf("c", "b"), p.order.map { it.id })
        assertEquals(emptyList<String>(), p.blocked.map { it.id })
    }

    @Test fun aBranchTakesEverythingMadeFromIt() {
        val p = DeletePlan.of(all, setOf("a"), withDescendants = true)
        // a's descendants are b, c and e; d stays (e is made from d too, but d is not chosen)
        assertEquals(setOf("a", "b", "c", "e"), p.order.map { it.id }.toSet())
        // every child comes before its parent
        val pos = p.order.mapIndexed { i, r -> r.id to i }.toMap()
        assertEquals(true, pos.getValue("c") < pos.getValue("b"))
        assertEquals(true, pos.getValue("b") < pos.getValue("a"))
        assertEquals(true, pos.getValue("e") < pos.getValue("a"))
        assertEquals(emptyList<String>(), p.blocked.map { it.id })
    }

    @Test fun aSelectionAboveAnUnpickedChildStays() {
        // deleting a and b without c: c is not chosen and has parent b, so b and a are both blocked
        val p = DeletePlan.of(all, setOf("a", "b"), withDescendants = false)
        assertEquals(emptyList<String>(), p.order.map { it.id })
        assertEquals(setOf("a", "b"), p.blocked.map { it.id }.toSet())
    }

    @Test fun unknownIdsAreIgnored() {
        val p = DeletePlan.of(all, setOf("zzz"), withDescendants = true)
        assertEquals(emptyList<String>(), p.order.map { it.id })
        assertEquals(emptyList<String>(), p.blocked.map { it.id })
    }
}
