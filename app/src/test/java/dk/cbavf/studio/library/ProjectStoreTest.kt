package dk.cbavf.studio.library

import dk.cbavf.studio.draw.model.Blend
import dk.cbavf.studio.draw.model.Grating
import dk.cbavf.studio.draw.model.Ink
import dk.cbavf.studio.draw.model.Layer
import dk.cbavf.studio.draw.model.Pen
import dk.cbavf.studio.draw.model.Pt
import dk.cbavf.studio.draw.model.RadialBurst
import dk.cbavf.studio.draw.model.Stroke
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ProjectStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val doc = CanvasDoc(
        widthPx = 1080,
        heightPx = 1800,
        layers = listOf(
            Layer(1, Grating(spacingPx = 9f, angleDeg = 3f)),
            Layer(2, RadialBurst(rays = 90), blend = Blend.Difference, ink = Ink.White),
            Layer(3, Stroke.fromScreen(listOf(Pt(0f, 0f), Pt(40f, 10f), Pt(80f, 0f)), Pen.Rails.stack)),
        ),
    )

    private fun ProjectStore.add(parents: List<String>, now: Long) =
        addAsset(byteArrayOf(1, 2, 3), parents, "draw.render", Recipe(canvas = doc), "test", 1080, 1800, now)

    @Test
    fun recipeRoundTripsThroughJson() {
        val store = ProjectStore(tmp.root)
        store.saveCanvas(Autosave(doc, sourceAssetId = "a1"))
        val loaded = ProjectStore(tmp.root).loadCanvas()!!
        assertEquals(doc, loaded.doc)
        assertEquals("a1", loaded.sourceAssetId)
    }

    @Test
    fun assetsBuildAGenerationTree() {
        val store = ProjectStore(tmp.root)
        val root = store.add(emptyList(), now = 1)
        val child = store.add(listOf(root.id), now = 2)
        val grandchild = store.add(listOf(child.id), now = 3)
        val sibling = store.add(listOf(root.id), now = 4)

        val lineage = ProjectStore(tmp.root).lineage()
        assertEquals(listOf(child, root), lineage.ancestors(grandchild.id))
        assertEquals(2, lineage.generation(grandchild.id))
        assertEquals(setOf(child.id, sibling.id), lineage.children(root.id).map { it.id }.toSet())
        assertEquals(listOf(sibling, grandchild, child, root), lineage.newestFirst())
        assertEquals(doc, store.recipe(lineage[grandchild.id]!!)!!.canvas)
        assertArrayEquals(byteArrayOf(1, 2, 3), store.fileOf(grandchild).readBytes())
    }

    @Test
    fun ancestorsStopOnCyclesAndMissingParents() {
        val a = AssetRecord("a", "a.png", 1, listOf("b"), "x", null, "d", 1, 1)
        val b = AssetRecord("b", "b.png", 2, listOf("a"), "x", null, "d", 1, 1)
        val orphan = AssetRecord("c", "c.png", 3, listOf("gone"), "x", null, "d", 1, 1)
        val lineage = Lineage(listOf(a, b, orphan))
        assertEquals(listOf(b), lineage.ancestors("a"))
        assertTrue(lineage.ancestors("c").isEmpty())
    }

    @Test
    fun corruptCanvasIsSetAsideNotThrown() {
        File(tmp.root, "canvas.json").writeText("{ not json")
        assertNull(ProjectStore(tmp.root).loadCanvas())
        assertTrue(tmp.root.listFiles()!!.any { it.name.startsWith("canvas.json.corrupt-") })
    }

    @Test
    fun recipesLiveOutsideTheIndex() {
        val store = ProjectStore(tmp.root)
        val r = store.add(emptyList(), now = 1)
        assertEquals("3 layers", r.summary)
        assertFalse(File(tmp.root, "lineage.json").readText().contains("grating"))
        assertTrue(File(tmp.root, r.recipeFile!!).readText().contains("grating"))
    }

    @Test
    fun inlineRecipesAreMigratedOnLoad() {
        val legacy = AssetRecord("old", "assets/old.png", 1, emptyList(), "draw.render", doc, "d", 1080, 1800)
        File(tmp.root, "lineage.json").writeText(ProjectStore.json.encodeToString(LineageFile.serializer(), LineageFile(listOf(legacy))))
        val store = ProjectStore(tmp.root)
        val migrated = store.lineage()["old"]!!
        assertNull(migrated.recipe)
        assertEquals(doc, store.recipe(migrated)!!.canvas)
        assertFalse(File(tmp.root, "lineage.json").readText().contains("grating"))
    }

    @Test
    fun aNotebookNoteIsSavedWithTheLineage() {
        val store = ProjectStore(tmp.root)
        val r = store.add(emptyList(), now = 1)
        store.setNote(r.id, "spacing 9 px beats best")
        assertEquals("spacing 9 px beats best", ProjectStore(tmp.root).lineage()[r.id]!!.note)
    }

    @Test
    fun aBlankNoteRemovesTheNote() {
        val store = ProjectStore(tmp.root)
        val r = store.add(emptyList(), now = 1)
        store.setNote(r.id, "first")
        store.setNote(r.id, "  ")
        assertNull(ProjectStore(tmp.root).lineage()[r.id]!!.note)
    }

    @Test
    fun aRerunRemembersItsFirstRunAndKeepsOtherFields() {
        val store = ProjectStore(tmp.root)
        val first = store.add(emptyList(), now = 1)
        val again = store.add(emptyList(), now = 2)
        store.updateMany(setOf(again.id)) { it.copy(exportedUri = "content://x") }
        store.setRerunOf(again.id, first.id)
        val loaded = ProjectStore(tmp.root).lineage()[again.id]!!
        assertEquals(first.id, loaded.rerunOf)
        assertEquals("content://x", loaded.exportedUri)
        assertNull(ProjectStore(tmp.root).lineage()[first.id]!!.rerunOf)
    }

    @Test
    fun barrierSharesItsImageRecipe() {
        val store = ProjectStore(tmp.root)
        val image = store.add(emptyList(), now = 1)
        val barrier = store.addAsset(byteArrayOf(9), listOf(image.id), "animate.barrier", null, "test", 1, 1, now = 2, recipeOf = image)
        assertEquals(image.recipeFile, barrier.recipeFile)
    }

    @Test
    fun updateReplacesRecord() {
        val store = ProjectStore(tmp.root)
        val r = store.add(emptyList(), now = 1)
        store.update(r.copy(exportedUri = "content://x"))
        assertEquals("content://x", store.lineage()[r.id]!!.exportedUri)
    }
}
