package dk.cbavf.studio.compose

import dk.cbavf.studio.compose.model.AssetLayerSource
import dk.cbavf.studio.compose.model.Background
import dk.cbavf.studio.compose.model.CanvasLayerSource
import dk.cbavf.studio.compose.model.ComposeDoc
import dk.cbavf.studio.develop.model.DevelopDoc
import dk.cbavf.studio.draw.model.Blend
import dk.cbavf.studio.draw.model.Grating
import dk.cbavf.studio.draw.model.ImageLayer
import dk.cbavf.studio.draw.model.Layer
import dk.cbavf.studio.draw.model.Pt
import dk.cbavf.studio.draw.model.Transform
import dk.cbavf.studio.library.AssetRecord
import dk.cbavf.studio.library.CanvasDoc
import dk.cbavf.studio.library.ProjectStore
import dk.cbavf.studio.library.Recipe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposeTest {
    private fun photo(id: String, w: Int, h: Int) = AssetRecord(id, "assets/$id.jpg", 1, emptyList(), "camera.capture", null, "d", w, h)

    @Test
    fun firstImageSetsTheDocumentAndLaterOnesFit() {
        val s = ComposeState()
        s.addImages(listOf(photo("a", 4000, 3000), photo("b", 1000, 2000)))
        assertEquals(4000, s.doc.widthPx)
        assertEquals(3000, s.doc.heightPx)
        val (a, b) = s.doc.layers
        assertEquals(1f, a.transform.sx, 1e-6f)          // the first fills it 1:1
        assertEquals(1.5f, b.transform.sx, 1e-6f)        // the second fitted: 3000 / 2000
        assertEquals(b.id, s.selectedId)
        assertEquals(listOf("a", "b"), s.doc.assetIds())
    }

    @Test
    fun layersPickMoveAndUndo() {
        val s = ComposeState()
        s.addImages(listOf(photo("a", 400, 400)))
        s.addGenerator(Grating())
        val (img, grating) = s.doc.layers
        // The grating covers everything and is on top: a tap picks it.
        assertTrue(s.pick(Pt(200f, 200f)))
        assertEquals(grating.id, s.selectedId)
        s.move(-1)
        assertEquals(listOf(grating.id, img.id), s.doc.layers.map { it.id })
        s.selectedId = img.id
        s.transformSelected(Pt(10f, 0f), 2f, 90f, "g1")
        s.transformSelected(Pt(5f, 0f), 1f, 0f, "g1")
        val t = s.selected!!.transform
        assertEquals(215f, t.tx, 1e-3f)
        assertEquals(2f, t.sx, 1e-3f)
        assertEquals(90f, t.rotationDeg, 1e-3f)
        s.edit.undo() // one gesture = one step
        assertEquals(200f, s.selected!!.transform.tx, 1e-3f)
    }

    @Test
    fun maskStrokesSpaceTheirDabs() {
        val s = ComposeState()
        s.addImages(listOf(photo("a", 1000, 1000)))
        s.brushRadius = 30f
        for (x in 0..100) s.paintMask(Pt(100f + x, 500f), "stroke")
        val dabs = s.selected!!.mask!!.dabs
        assertTrue(dabs.size in 9..12) // every ~10 px (a third of the radius), not every pixel
        assertFalse(dabs.first().reveal)
    }

    @Test
    fun duplicateAndDeleteKeepSelectionSensible() {
        val s = ComposeState()
        s.addImages(listOf(photo("a", 100, 100)))
        s.duplicate()
        assertEquals(2, s.doc.layers.size)
        assertEquals("Image a copy", s.selected!!.name)
        s.delete()
        assertEquals(1, s.doc.layers.size)
        assertEquals(s.doc.layers.first().id, s.selectedId)
        s.delete()
        assertNull(s.selectedId)
    }

    @Test
    fun canvasLayersBringTheirImagesAsParentsAndRoundTrip() {
        val canvas = CanvasDoc(500, 500, listOf(Layer(1, ImageLayer("inner", 10, 10, Transform())), Layer(2, Grating())))
        val doc = ComposeDoc(
            800, 600, Background.Transparent,
            listOf(
                ComposeDoc.fitted(1, "photo", AssetLayerSource("p", 800, 600), 800, 600, 800, 600),
                ComposeDoc.fitted(2, "draw", CanvasLayerSource(canvas), 500, 500, 800, 600).copy(blend = Blend.Overlay, opacity = 0.4f, adjust = DevelopDoc(exposureEv = 1f)),
            ),
        )
        assertEquals(listOf("p", "inner"), doc.assetIds())
        val json = ProjectStore.json.encodeToString(Recipe.serializer(), Recipe(compose = doc))
        assertEquals(doc, ProjectStore.json.decodeFromString(Recipe.serializer(), json).compose)
    }
}
