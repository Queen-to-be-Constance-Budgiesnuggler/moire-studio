package dk.cbavf.studio.compose

import dk.cbavf.studio.compose.model.ComposeDoc
import dk.cbavf.studio.compose.model.LayerFilter
import dk.cbavf.studio.compose.model.LayerMask
import dk.cbavf.studio.compose.model.MaskDab
import dk.cbavf.studio.compose.model.PaintLayerSource
import dk.cbavf.studio.compose.model.SolidLayerSource
import dk.cbavf.studio.draw.model.Pt
import dk.cbavf.studio.draw.model.Transform
import dk.cbavf.studio.library.ProjectStore
import dk.cbavf.studio.library.Recipe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposeToolsTest {
    private fun state(w: Int = 1000, h: Int = 800) = ComposeState().apply {
        edit.load(ComposeDoc(w, h))
    }

    @Test
    fun localCoordinatesUndoTheTransform() {
        val l = ComposeDoc.fitted(1, "x", SolidLayerSource(0, 200, 100), 200, 100, 1000, 800, 1f).copy(
            transform = Transform(tx = 500f, ty = 400f, rotationDeg = 90f, sx = 2f, sy = 2f),
        )
        // The source's top-left corner, through scale 2 and a quarter turn.
        val corner = l.corners()[0]
        val back = l.toLocal(corner)
        assertEquals(0f, back.x, 1e-3f)
        assertEquals(0f, back.y, 1e-3f)
    }

    @Test
    fun brushStrokesGoToAPaintLayerMadeOnDemand() {
        val s = state()
        s.color = 0xFFFF0000
        s.paintWidth = 10f
        s.beginStroke(Pt(100f, 100f), erase = false, "g1")
        for (x in 101..200) s.extendStroke(Pt(x.toFloat(), 100f), "g1")
        val src = s.selected!!.source as PaintLayerSource
        assertEquals(1, src.strokes.size)
        val st = src.strokes[0]
        assertTrue(st.points.size in 30..60) // a point every couple of pixels, not every one
        assertEquals(0xFFFF0000, st.argb)
        s.edit.undo() // the whole stroke is one step
        assertTrue((s.selected?.source as? PaintLayerSource)?.strokes.isNullOrEmpty())
    }

    @Test
    fun cropKeepsLayersAndMasksInPlace() {
        val s = state()
        s.addSolid(0xFF00FF00)
        s.update { it.copy(mask = LayerMask(dabs = listOf(MaskDab(300f, 300f, 10f, reveal = false)))) }
        s.selectRect(Pt(200f, 100f), Pt(700f, 600f))
        s.cropToSelection()
        val d = s.doc
        assertEquals(500, d.widthPx)
        assertEquals(500, d.heightPx)
        assertEquals(300f, d.layers[0].transform.tx, 1e-3f) // was 500, moved by −200
        assertEquals(100f, d.layers[0].mask!!.dabs[0].x, 1e-3f)
        assertNull(s.selection)
    }

    @Test
    fun selectionsMaskAndFill() {
        val s = state()
        s.addSolid(0xFF0000FF)
        s.selectRect(Pt(0f, 0f), Pt(100f, 100f))
        s.maskFromSelection(keep = true, feather = 4f)
        val m = s.selected!!.mask!!
        assertTrue(m.invert) // keep only the selection: start hidden, reveal the shape
        assertTrue(m.shapes.single().reveal)
        s.fillSelection(erase = false)
        val paint = s.selected!!.source as PaintLayerSource
        assertTrue(paint.strokes.single().fill)
    }

    @Test
    fun filtersStackAndRoundTrip() {
        val s = state()
        s.addSolid(0xFF808080)
        s.addFilter(LayerFilter.Blur(4f))
        s.addFilter(LayerFilter.Posterize(3))
        s.setFilter(0, "k", LayerFilter.Blur(12f))
        s.removeFilter(1)
        assertEquals(listOf<LayerFilter>(LayerFilter.Blur(12f)), s.selected!!.filters)
        s.flip(horizontal = true)
        assertTrue(s.selected!!.transform.sx < 0)
        val json = ProjectStore.json.encodeToString(Recipe.serializer(), Recipe(compose = s.doc))
        assertEquals(s.doc, ProjectStore.json.decodeFromString(Recipe.serializer(), json).compose)
    }

    @Test
    fun boxBlurSpreadsWithoutChangingTheTotal() {
        val w = 21
        val h = 21
        val px = IntArray(w * h) { 0xFF000000.toInt() }
        px[10 * w + 10] = 0xFFFFFFFF.toInt()
        boxBlur3(px, w, h, 4)
        val total = px.sumOf { it and 0xFF }
        assertTrue("energy roughly kept: $total", total in 200..320)
        assertTrue((px[10 * w + 10] and 0xFF) < 255)
        assertTrue((px[10 * w + 12] and 0xFF) > 0)
    }
}
