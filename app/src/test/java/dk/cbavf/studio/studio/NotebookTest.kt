package dk.cbavf.studio.studio

import dk.cbavf.studio.compose.model.ComposeDoc
import dk.cbavf.studio.develop.model.DevelopDoc
import dk.cbavf.studio.glitch.model.GlitchDoc
import dk.cbavf.studio.library.AssetRecord
import dk.cbavf.studio.library.CanvasDoc
import dk.cbavf.studio.library.Recipe
import dk.cbavf.studio.sequence.model.SequenceDoc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotebookTest {
    private fun rec(
        id: String,
        t: Long = 1,
        op: String = "draw.render",
        parents: List<String> = emptyList(),
        w: Int = 1080,
        h: Int = 1800,
        recipeFile: String? = "recipes/$id.json",
        rerunOf: String? = null,
        note: String? = null,
    ) = AssetRecord(id, "$id.png", t, parents, op, device = "x", widthPx = w, heightPx = h, recipeFile = recipeFile, rerunOf = rerunOf, note = note)

    private val canvas = CanvasDoc(1080, 1800, emptyList())

    @Test
    fun aNoteIsTrimmed() {
        assertEquals("hello", Notebook.cleanNote("  hello \n"))
    }

    @Test
    fun aBlankNoteIsNoNote() {
        assertNull(Notebook.cleanNote("   \n "))
        assertNull(Notebook.cleanNote(null))
    }

    @Test
    fun aLongNoteIsCapped() {
        assertEquals(Notebook.NOTE_MAX, Notebook.cleanNote("x".repeat(Notebook.NOTE_MAX + 50))!!.length)
    }

    @Test
    fun noteChangedIgnoresSpaceAtTheEnds() {
        val a = rec("a", note = "moiré at 3°")
        assertFalse(Notebook.noteChanged(a, " moiré at 3° "))
        assertTrue(Notebook.noteChanged(a, "moiré at 4°"))
        assertTrue(Notebook.noteChanged(rec("b"), "new"))
        assertFalse(Notebook.noteChanged(rec("b"), "  "))
    }

    @Test
    fun theFourRenderKindsCanBeRerun() {
        listOf("draw.render", "compose.render", "develop.render", "glitch.render").forEach { op ->
            assertTrue(op, Notebook.canRerun(rec("a", op = op)))
        }
    }

    @Test
    fun otherKindsAndEntriesWithoutARecipeCannotBeRerun() {
        assertFalse(Notebook.canRerun(rec("a", op = "sequence.gif")))
        assertFalse(Notebook.canRerun(rec("a", op = "camera.capture")))
        assertFalse(Notebook.canRerun(rec("a", op = "draw.render", recipeFile = null)))
    }

    @Test
    fun aDrawRerunKeepsTheCanvasTheParentsAndTheSavedScale() {
        val a = rec("a", parents = listOf("p1", "p2"), w = 2160, h = 3600)
        val plan = Notebook.plan(a, Recipe(canvas = canvas))!!
        assertEquals(RerunKind.Draw, plan.kind)
        assertEquals(canvas, plan.recipe.canvas)
        assertEquals(listOf("p1", "p2"), plan.parents)
        assertEquals(2f, plan.scale, 0f)
    }

    @Test
    fun aRerunPointsAtItsOwnFirstRun() {
        assertEquals("a", Notebook.plan(rec("a"), Recipe(canvas = canvas))!!.origin)
    }

    @Test
    fun aRerunOfARerunStillPointsAtTheFirstRun() {
        val b = rec("b", t = 2, rerunOf = "a")
        assertEquals("a", Notebook.plan(b, Recipe(canvas = canvas))!!.origin)
    }

    @Test
    fun aRecipeWithoutWhatTheKindNeedsGivesNoPlan() {
        assertNull(Notebook.plan(rec("a", op = "draw.render"), Recipe(sequence = SequenceDoc())))
        assertNull(Notebook.plan(rec("a", op = "draw.render"), null))
        assertNull(Notebook.plan(rec("a", op = "draw.render"), Recipe(canvas = CanvasDoc(0, 0, emptyList()))))
        assertNull(Notebook.plan(rec("a", op = "sequence.gif"), Recipe(sequence = SequenceDoc())))
    }

    @Test
    fun developAndGlitchNeedTheirSourceImage() {
        assertNull(Notebook.plan(rec("a", op = "develop.render"), Recipe(develop = DevelopDoc())))
        assertNull(Notebook.plan(rec("a", op = "glitch.render"), Recipe(glitch = GlitchDoc())))
        assertEquals(RerunKind.Develop, Notebook.plan(rec("a", op = "develop.render"), Recipe(develop = DevelopDoc(sourceAssetId = "s")))!!.kind)
    }

    @Test
    fun aGlitchRerunIsMadeAtTheFirstRunsSize() {
        val plan = Notebook.plan(rec("a", op = "glitch.render", w = 3072, h = 2048), Recipe(glitch = GlitchDoc(sourceAssetId = "s")))!!
        assertEquals(3072, plan.maxPx)
    }

    @Test
    fun aComposeRerunKeepsOnlyTheComposeRecipe() {
        val doc = ComposeDoc(widthPx = 800, heightPx = 600)
        val plan = Notebook.plan(rec("a", op = "compose.render"), Recipe(compose = doc, canvas = canvas))!!
        assertEquals(Recipe(compose = doc), plan.recipe)
    }

    @Test
    fun drawScaleIsOneWhenASizeIsUnknown() {
        assertEquals(1f, Notebook.drawScale(0, 1080), 0f)
        assertEquals(1f, Notebook.drawScale(1080, 0), 0f)
    }

    @Test
    fun runsOfListsTheFirstRunAndItsRerunsOldestFirst() {
        val a = rec("a", t = 1)
        val c = rec("c", t = 3, rerunOf = "a")
        val b = rec("b", t = 2, rerunOf = "a")
        val other = rec("x", t = 4)
        val all = listOf(other, c, b, a)
        assertEquals(listOf("a", "b", "c"), Notebook.runsOf(all, c).map { it.id })
        assertEquals(listOf("a", "b", "c"), Notebook.runsOf(all, a).map { it.id })
        assertEquals(listOf("x"), Notebook.runsOf(all, other).map { it.id })
    }

    @Test
    fun runLineNumbersARerunAndNamesTheFirstRun() {
        val a = rec("a", t = 1)
        val b = rec("b", t = 2, rerunOf = "a")
        assertEquals("Run 2 · first run T1", Notebook.runLine(listOf(b, a), b) { "T$it" })
    }

    @Test
    fun runLineOnAFirstRunCountsItsReruns() {
        val a = rec("a", t = 1)
        val all = listOf(rec("c", t = 3, rerunOf = "a"), rec("b", t = 2, rerunOf = "a"), a)
        assertEquals("First run · 2 reruns", Notebook.runLine(all, a) { "T$it" })
        assertEquals("First run · 1 rerun", Notebook.runLine(all.drop(1), a) { "T$it" })
    }

    @Test
    fun runLineIsEmptyForAnEntryNeverRerun() {
        val a = rec("a")
        assertNull(Notebook.runLine(listOf(a), a) { "T$it" })
    }

    @Test
    fun runLineSaysWhenTheFirstRunIsGone() {
        val b = rec("b", t = 2, rerunOf = "a")
        assertEquals("Rerun · the first run is gone", Notebook.runLine(listOf(b), b) { "T$it" })
    }
}
