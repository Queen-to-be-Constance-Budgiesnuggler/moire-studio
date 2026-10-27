package dk.cbavf.studio.draw

import dk.cbavf.studio.animate.model.Animation
import dk.cbavf.studio.animate.model.Step
import dk.cbavf.studio.draw.model.Grating
import dk.cbavf.studio.draw.model.ImageLayer
import dk.cbavf.studio.draw.model.Layer
import dk.cbavf.studio.draw.model.Pt
import dk.cbavf.studio.draw.model.Transform
import dk.cbavf.studio.library.CanvasDoc
import dk.cbavf.studio.library.ProjectStore
import dk.cbavf.studio.library.imageAssetIds
import dk.cbavf.studio.patch.model.Param
import dk.cbavf.studio.patch.model.PatchMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageLayerTest {
    private val photo = ImageLayer.fitted("a1", 4000, 3000, 1000, 2000, fraction = 1f)

    @Test
    fun fittedImagesAreCentredAndContained() {
        // 4000×3000 into 1000×2000: width limits, scale 0.25.
        assertEquals(0.25f, photo.transform.sx, 1e-6f)
        assertEquals(500f, photo.transform.tx, 0f)
        assertTrue(photo.contains(Pt(500f, 1000f)))
        assertTrue(photo.contains(Pt(10f, 700f)))
        assertFalse(photo.contains(Pt(500f, 300f))) // above the 375 px high image band
    }

    @Test
    fun rotatedImagesHitTestInTheirOwnFrame() {
        val img = ImageLayer("a", 200, 20, Transform(tx = 100f, ty = 100f, rotationDeg = 90f))
        assertTrue(img.contains(Pt(100f, 180f)))  // along the turned long side
        assertFalse(img.contains(Pt(180f, 100f)))
    }

    @Test
    fun imagesAreAddedSelectedAndTappable() {
        val s = DrawState()
        s.canvasWidth = 1000
        s.canvasHeight = 2000
        s.addImages(listOf(photo))
        val id = s.layers.last().id
        assertEquals(setOf(id), s.selection)
        assertEquals(listOf("a1"), s.imageAssetIds())
        s.select(s.layers.first().id)
        assertTrue(s.tapSelect(Pt(500f, 1000f)))
        assertEquals(id, s.primaryId)
        s.undo()
        assertTrue(s.imageAssetIds().isEmpty())
    }

    @Test
    fun imagesMoveWithStepsAndPatchRoutes() {
        val doc = CanvasDoc(1000, 2000, listOf(Layer(1, Grating()), Layer(2, photo)))
        val stepped = Animation.stepped(doc, 2, Step(dx = 10f, turnDeg = 5f)).layers[1].content as ImageLayer
        assertEquals(510f, stepped.transform.tx, 1e-3f)
        assertEquals(5f, stepped.transform.rotationDeg, 1e-3f)
        assertEquals(0.3f, (PatchMath.apply(Layer(2, photo), Param.Opacity, 0.3f).content as ImageLayer).opacity, 0f)
        assertTrue(Param.Opacity in Param.forContent(photo))
    }

    @Test
    fun libraryImagesBecomeAnimateFrames() {
        val a = dk.cbavf.studio.animate.AnimateState()
        val photos = listOf(
            dk.cbavf.studio.library.AssetRecord("p1", "assets/p1.jpg", 1, emptyList(), "camera.capture", null, "d", 4000, 3000),
            dk.cbavf.studio.library.AssetRecord("p2", "assets/p2.jpg", 2, emptyList(), "camera.capture", null, "d", 3000, 4000),
        )
        assertFalse(a.addImageFrames(photos, fallback = null)) // no canvas size yet
        assertTrue(a.addImageFrames(photos, fallback = 1080 to 2400))
        assertEquals(2, a.frames.size)
        assertEquals(listOf("p1"), a.frames[0].doc.imageAssetIds())
        assertEquals(1080, a.frames[1].doc.widthPx)
        a.undo()
        assertEquals(0, a.frames.size)
    }

    @Test
    fun imageLayersRoundTripAndNameTheirParents() {
        val doc = CanvasDoc(1000, 2000, listOf(Layer(1, photo), Layer(2, photo.copy(assetId = "b2")), Layer(3, photo)))
        assertEquals(listOf("a1", "b2"), doc.imageAssetIds())
        val json = ProjectStore.json.encodeToString(CanvasDoc.serializer(), doc)
        assertTrue(json.contains("\"type\":\"image\""))
        assertEquals(doc, ProjectStore.json.decodeFromString(CanvasDoc.serializer(), json))
    }
}
