package dk.cbavf.studio.routing

import dk.cbavf.studio.draw.model.Grating
import dk.cbavf.studio.draw.model.ImageLayer
import dk.cbavf.studio.draw.model.Layer
import dk.cbavf.studio.library.CanvasDoc
import dk.cbavf.studio.library.imageAssetIds
import dk.cbavf.studio.routing.model.DRAW_IMAGE_IN
import dk.cbavf.studio.routing.model.JackType
import dk.cbavf.studio.routing.model.LOOP_IN
import dk.cbavf.studio.routing.model.Rack
import dk.cbavf.studio.routing.model.RouteLayers
import dk.cbavf.studio.routing.model.RoutingDoc
import dk.cbavf.studio.routing.model.SEQUENCE_IN
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteLayersTest {
    private fun canvas(vararg ids: String) = CanvasDoc(
        1000, 2000,
        listOf(Layer(1, Grating(spacingPx = 10f))) + ids.mapIndexed { i, id -> Layer(i + 2, ImageLayer.fitted(id, 100, 50, 1000, 2000)) },
    )

    @Test
    fun aLiveLayerNamesItsRackInput() {
        assertEquals("route:draw.in", RouteLayers.idOf(DRAW_IMAGE_IN))
        assertEquals(DRAW_IMAGE_IN, RouteLayers.inputOf("route:draw.in"))
        assertNull(RouteLayers.inputOf("a1b2c3"))
        assertNull(RouteLayers.inputOf("route:"))
    }

    @Test
    fun liveLayersAreNotLibraryParents() {
        val doc = canvas("a1", RouteLayers.idOf(DRAW_IMAGE_IN))
        assertEquals(listOf("a1"), doc.imageAssetIds())
        assertEquals(listOf(DRAW_IMAGE_IN), RouteLayers.inputsIn(doc))
    }

    @Test
    fun aLayerIsAddedOnlyWhenPatchedAndMissing() {
        val without = canvas("a1").layers
        val with = canvas(RouteLayers.idOf(DRAW_IMAGE_IN)).layers
        assertTrue(RouteLayers.needsLayer(without, DRAW_IMAGE_IN, patched = true))
        assertFalse(RouteLayers.needsLayer(without, DRAW_IMAGE_IN, patched = false))
        assertFalse(RouteLayers.needsLayer(with, DRAW_IMAGE_IN, patched = true))
    }

    @Test
    fun theNewLayerIsFittedOnTheCanvas() {
        val l = RouteLayers.layer(DRAW_IMAGE_IN, 400, 200, 1000, 2000)
        assertEquals("route:draw.in", l.assetId)
        assertEquals(500f, l.transform.tx, 1e-4f)
        assertEquals(1000f, l.transform.ty, 1e-4f)
        assertEquals(2.25f, l.transform.sx, 1e-4f)
    }

    @Test
    fun savingPinsTheLiveLayerToItsSnapshot() {
        val doc = canvas("a1", RouteLayers.idOf(DRAW_IMAGE_IN))
        val pinned = RouteLayers.pinned(doc, mapOf(DRAW_IMAGE_IN to "snap1"))
        assertEquals(listOf("a1", "snap1"), pinned.imageAssetIds())
        assertTrue(RouteLayers.inputsIn(pinned).isEmpty())
        // The rest of the layer (place, size) stays as it was.
        assertEquals((doc.layers[2].content as ImageLayer).transform, (pinned.layers[2].content as ImageLayer).transform)
    }

    @Test
    fun aLiveLayerWithNothingPatchedIsLeftOutOfTheSave() {
        val doc = canvas("a1", RouteLayers.idOf(DRAW_IMAGE_IN))
        val pinned = RouteLayers.pinned(doc, emptyMap())
        assertEquals(2, pinned.layers.size)
        assertEquals(listOf("a1"), pinned.imageAssetIds())
    }

    @Test
    fun theNewInputsTakePictures() {
        for (input in listOf(DRAW_IMAGE_IN, SEQUENCE_IN, LOOP_IN)) assertEquals(input, JackType.Image, Rack.input(input)?.type)
        val d = RoutingDoc().connect("camera.last", DRAW_IMAGE_IN).connect("compose.image", SEQUENCE_IN).connect("draw.canvas", LOOP_IN)
        assertEquals(3, d.cables.size)
    }

    @Test
    fun drawCannotFeedItsOwnImageLayer() {
        assertEquals(RoutingDoc(), RoutingDoc().connect("draw.canvas", DRAW_IMAGE_IN))
        // Nor round a loop: Draw → Compose, then Compose → Draw's image layer.
        val d = RoutingDoc().connect("draw.canvas", "compose.in1")
        assertEquals(d, d.connect("compose.image", DRAW_IMAGE_IN))
    }
}
