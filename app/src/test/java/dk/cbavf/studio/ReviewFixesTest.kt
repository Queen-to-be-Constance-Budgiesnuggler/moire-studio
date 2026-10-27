package dk.cbavf.studio

import dk.cbavf.studio.animate.AnimateState
import dk.cbavf.studio.animate.model.AnimationDoc
import dk.cbavf.studio.animate.model.Frame
import dk.cbavf.studio.draw.DrawState
import dk.cbavf.studio.draw.Tool
import dk.cbavf.studio.draw.model.EllipseHatch
import dk.cbavf.studio.draw.model.Grating
import dk.cbavf.studio.draw.model.Layer
import dk.cbavf.studio.draw.model.Pt
import dk.cbavf.studio.draw.model.Stroke
import dk.cbavf.studio.draw.model.label
import dk.cbavf.studio.draw.model.transformed
import dk.cbavf.studio.library.CanvasDoc
import dk.cbavf.studio.library.ProjectStore
import dk.cbavf.studio.library.Recipe
import dk.cbavf.studio.patch.model.Param
import dk.cbavf.studio.patch.model.PatchMath
import dk.cbavf.studio.ui.Undoable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Covers the fixes and additions from the review pass after step 8. */
class ReviewFixesTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val other = CanvasDoc(100, 100, listOf(Layer(7, EllipseHatch())))

    @Test
    fun openingInDrawIsUndoable() {
        val s = DrawState()
        val before = s.layers.toList()
        s.sourceAssetId = "mine"
        s.open(other, "theirs")
        assertEquals("theirs", s.sourceAssetId)
        assertTrue(s.canUndo)
        s.undo()
        assertEquals(before, s.layers.toList())
        assertEquals("mine", s.sourceAssetId)
    }

    @Test
    fun newCanvasIsUndoable() {
        val s = DrawState()
        s.add(EllipseHatch())
        val n = s.layers.size
        s.newCanvas()
        assertEquals(2, s.layers.size)
        s.undo()
        assertEquals(n, s.layers.size)
    }

    @Test
    fun layersReorder() {
        val s = DrawState()
        val (a, b) = s.layers.map { it.id }
        s.select(a)
        s.moveLayer(1)
        assertEquals(listOf(b, a), s.layers.map { it.id })
        s.moveLayer(1) // already on top: nothing
        assertEquals(listOf(b, a), s.layers.map { it.id })
    }

    @Test
    fun cornerHandleStretchesAlongTheStrokesOwnAxes() {
        val s = DrawState()
        s.tool = Tool.Pen
        s.draftStart(Pt(100f, 100f))
        for (i in 1..10) s.draftMove(Pt(100f + i * 10f, 100f + i * 5f))
        s.penUp()
        val stroke = s.layers.last()
        val bounds = DrawState.strokeBounds(stroke.content as Stroke)
        val corner = Pt(bounds.right, bounds.bottom)
        assertEquals(stroke.id, s.handleAt(corner))
        s.beginStretch(stroke.id, corner)
        val t = (stroke.content as Stroke).transform
        // Twice as far from the centre in x only.
        s.stretchTo(Pt(t.tx + (corner.x - t.tx) * 2, corner.y))
        s.endStretch()
        val after = (s.layers.last().content as Stroke).transform
        assertEquals(2f, after.sx, 1e-3f)
        assertEquals(1f, after.sy, 1e-3f)
        s.undo()
        assertEquals(1f, (s.layers.last().content as Stroke).transform.sx, 0f)
    }

    @Test
    fun ellipsesMoveTurnAndPatch() {
        val e = EllipseHatch(spacingPx = 10f)
        val moved = e.transformed(Pt(5f, -3f), 2f, 30f, Pt(0f, 0f)) as EllipseHatch
        assertEquals(5f, moved.centerXPx, 0f)
        assertEquals(20f, moved.spacingPx, 0f)
        assertEquals(30f, moved.rotationDeg, 0f)
        assertEquals(0.5f, (PatchMath.apply(Layer(1, e), Param.Aspect, 0.5f).content as EllipseHatch).aspect, 0f)
        assertEquals("Ellipses", e.label)
    }

    @Test
    fun opensInOtherRoomsAreUndoable() {
        val a = AnimateState()
        a.insert(other)
        a.fps = 24
        a.open(AnimationDoc(listOf(Frame(other), Frame(other)), fps = 6))
        assertEquals(6, a.fps)
        a.undo()
        assertEquals(1, a.frames.size)
        assertEquals(24, a.fps)

        val u = Undoable("draft")
        u.open("opened")
        u.undo()
        assertEquals("draft", u.doc)
    }

    @Test
    fun deleteRefusesParentsAndCleansUp() {
        val store = ProjectStore(tmp.root)
        val parent = store.addAsset(byteArrayOf(1), emptyList(), "draw.render", Recipe(canvas = other), "t", 1, 1)
        val child = store.addAsset(byteArrayOf(2), listOf(parent.id), "animate.barrier", null, "t", 1, 1, recipeOf = parent)
        assertFalse(store.delete(parent)) // has a child
        assertTrue(store.delete(child))
        // The shared recipe stays: the parent still uses it.
        assertTrue(File(tmp.root, parent.recipeFile!!).exists())
        assertTrue(store.delete(parent))
        assertFalse(File(tmp.root, parent.recipeFile!!).exists())
        assertFalse(store.fileOf(parent).exists())
        assertNull(store.lineage()[parent.id])
    }

    @Test
    fun tagsAreStoredSortedAndFound() {
        val store = ProjectStore(tmp.root)
        val r = store.addAsset(byteArrayOf(1), emptyList(), "draw.render", null, "t", 1, 1)
        store.setTags(r.id, listOf("print", "loop", "print"))
        val l = store.lineage()
        assertEquals(listOf("loop", "print"), l[r.id]!!.tags)
        assertEquals(listOf("loop", "print"), l.allTags())
    }

    @Test
    fun gratingsKeepTheirProfileThroughJson() {
        val doc = CanvasDoc(10, 10, listOf(Layer(1, Grating(profile = dk.cbavf.studio.draw.model.Profile.Sine)), Layer(2, EllipseHatch(aspect = 2f))))
        val json = ProjectStore.json.encodeToString(CanvasDoc.serializer(), doc)
        assertEquals(doc, ProjectStore.json.decodeFromString(CanvasDoc.serializer(), json))
    }
}
