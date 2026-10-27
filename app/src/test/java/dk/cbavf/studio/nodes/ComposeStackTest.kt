package dk.cbavf.studio.nodes

import dk.cbavf.studio.compose.model.ComposeDoc
import dk.cbavf.studio.compose.model.ComposeLayer
import dk.cbavf.studio.compose.model.GroupLayerSource
import dk.cbavf.studio.compose.model.RouteLayerSource
import dk.cbavf.studio.compose.model.SolidLayerSource
import dk.cbavf.studio.draw.model.Transform
import dk.cbavf.studio.library.CanvasDoc
import dk.cbavf.studio.nodes.model.Catalog
import dk.cbavf.studio.nodes.model.ComposeStack
import dk.cbavf.studio.nodes.model.Evaluator
import dk.cbavf.studio.nodes.model.Graph
import dk.cbavf.studio.nodes.model.ImageBackend
import dk.cbavf.studio.nodes.model.Link
import dk.cbavf.studio.nodes.model.Node
import dk.cbavf.studio.nodes.model.NodeValue
import dk.cbavf.studio.nodes.model.Prop
import dk.cbavf.studio.nodes.model.SocketType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposeStackTest {
    private val solid = ComposeLayer(1, "Fill", SolidLayerSource(0xFFFF0000, 100, 100), Transform(500f, 500f))
    private fun live(id: Int, input: String, w: Int = 200, h: Int = 100, sx: Float = 1f) =
        ComposeLayer(id, "Live", RouteLayerSource(input, w, h), Transform(300f, 400f, 30f, sx, sx))

    private fun linked(tag: String, w: Int = 400, h: Int = 400) = ComposeStack.Linked(tag, w, h)

    /** Images are strings; the composite lists each layer's input (or "solid"), "-" for a hidden one. */
    private class FakeBackend(var doc: ComposeDoc?) : ImageBackend {
        var version = 0
        var drawn: Map<String, NodeValue.Img> = emptyMap()
        override fun libraryImage(assetId: String, maxPx: Int) = NodeValue.Img("img($assetId)", 640, 480)
        override fun drawCanvas(): CanvasDoc? = null
        override fun render(doc: CanvasDoc, width: Int, height: Int, paper: Boolean, maxPx: Int) = NodeValue.Img("render", width, height)
        override fun process(type: String, inputs: List<NodeValue?>, nums: List<Float>, props: Map<String, String>, maxPx: Int): NodeValue.Img? = null
        override fun composeDoc() = doc
        override fun composeVersion() = version
        override fun composite(doc: ComposeDoc, images: Map<String, NodeValue.Img>, maxPx: Int): NodeValue.Img {
            drawn = images
            val names = doc.layers.joinToString(",") { l ->
                if (!l.visible) "-" else when (val s = l.source) {
                    is RouteLayerSource -> images[s.input]?.handle?.toString() ?: s.input
                    else -> "solid"
                }
            }
            return NodeValue.Img("stack($names)", doc.widthPx, doc.heightPx)
        }
    }

    private fun node(id: Int = 1, hide: Boolean = false) =
        Node(id, "composestack", 0f, 0f, if (hide) mapOf("Unlinked" to Prop.Choice("Hide")) else emptyMap())

    private fun asset(id: Int, name: String) = Node(id, "image", 0f, 0f, mapOf("asset" to Prop.Text(name)))

    // --- The plan -------------------------------------------------------------------------------

    @Test fun aLinkedInputFeedsItsLiveLayer() {
        val doc = ComposeDoc(layers = listOf(solid, live(2, "compose.in2")))
        val out = ComposeStack.plan(doc, listOf(null, linked("a")), hideUnlinked = false)!!
        assertEquals(ComposeStack.NODE_INPUT + "a", (out.layers[1].source as RouteLayerSource).input)
        assertEquals(2, out.layers.size)
    }

    @Test fun theFedLayerKeepsItsPlaceAndWidth() {
        val l = live(2, "compose.in1", w = 200, h = 100, sx = 2f)
        val out = ComposeStack.retarget(l, linked("a", w = 800, h = 800))
        assertEquals(l.transform.tx, out.transform.tx, 0f)
        assertEquals(l.transform.ty, out.transform.ty, 0f)
        assertEquals(l.transform.rotationDeg, out.transform.rotationDeg, 0f)
        // 200 px at 2× was 400 wide; 800 px at 0.5× is still 400.
        assertEquals(400f, out.sourceWidth * out.transform.sx, 0.001f)
        assertEquals(0.5f, out.transform.sy, 0.001f)
    }

    @Test fun anUnlinkedLiveLayerKeepsTheRackByDefault() {
        val doc = ComposeDoc(layers = listOf(live(1, "compose.in1")))
        assertEquals(doc, ComposeStack.plan(doc, listOf(null, null, null, null), hideUnlinked = false))
    }

    @Test fun hideHidesUnlinkedLiveLayersOnly() {
        val doc = ComposeDoc(layers = listOf(solid, live(2, "compose.in1"), live(3, "compose.in2")))
        val out = ComposeStack.plan(doc, listOf(linked("a")), hideUnlinked = true)!!
        assertEquals(listOf(true, true, false), out.layers.map { it.visible })
    }

    @Test fun aLinkedInputWithNoLiveLayerGoesOnTopFitted() {
        val doc = ComposeDoc(widthPx = 1000, heightPx = 1000, layers = listOf(solid))
        val out = ComposeStack.plan(doc, listOf(null, null, linked("c", 2000, 500)), hideUnlinked = false)!!
        val top = out.layers.last()
        assertEquals(ComposeStack.NODE_INPUT + "c", (top.source as RouteLayerSource).input)
        assertEquals(2, top.id)
        assertEquals("Node · Layer 3", top.name)
        // Fitted inside the document: 2000 wide into 1000.
        assertEquals(0.5f, top.transform.sx, 0.001f)
        assertEquals(500f, top.transform.tx, 0f)
    }

    @Test fun anEmptyDocumentTakesTheFirstLinkedSize() {
        val out = ComposeStack.plan(ComposeDoc(), listOf(null, linked("b", 640, 480)), hideUnlinked = false)!!
        assertEquals(640, out.widthPx)
        assertEquals(480, out.heightPx)
    }

    @Test fun nothingShownGivesNoPlan() {
        assertNull(ComposeStack.plan(ComposeDoc(), emptyList(), hideUnlinked = false))
        val onlyLive = ComposeDoc(layers = listOf(live(1, "compose.in1")))
        assertNull(ComposeStack.plan(onlyLive, emptyList(), hideUnlinked = true))
        val group = ComposeDoc(layers = listOf(ComposeLayer(1, "Group", GroupLayerSource(10, 10), Transform())))
        assertNull(ComposeStack.plan(group, emptyList(), hideUnlinked = false))
    }

    @Test fun aLiveLayerInsideAGroupIsFedToo() {
        val g = ComposeLayer(5, "Group", GroupLayerSource(100, 100), Transform())
        val doc = ComposeDoc(layers = listOf(live(1, "compose.in4").copy(group = 5), g))
        val out = ComposeStack.plan(doc, listOf(null, null, null, linked("d")), hideUnlinked = false)!!
        assertEquals(ComposeStack.NODE_INPUT + "d", (out.layers[0].source as RouteLayerSource).input)
        assertEquals(5, out.layers[0].group)
    }

    @Test fun tagsAreStableAndTellInputsApart() {
        assertEquals(ComposeStack.tag("image|a1"), ComposeStack.tag("image|a1"))
        assertNotEquals(ComposeStack.tag("image|a1"), ComposeStack.tag("image|a2"))
    }

    // --- The node -------------------------------------------------------------------------------

    @Test fun theNodeHasFourImageInputsAndAnImageOutput() {
        val def = Catalog["composestack"]!!
        assertEquals(listOf("Layer 1", "Layer 2", "Layer 3", "Layer 4"), def.inputs.map { it.name })
        assertTrue(def.inputs.all { it.type == SocketType.Image })
        assertEquals(listOf(SocketType.Image), def.outputs.map { it.type })
        assertEquals(listOf("Rack", "Hide"), def.props.single().options)
    }

    @Test fun withNothingLinkedItShowsTheRoomStack() {
        val b = FakeBackend(ComposeDoc(widthPx = 800, heightPx = 600, layers = listOf(solid, live(2, "compose.in1"))))
        val v = Evaluator(Graph(listOf(node()), emptyList()), b, 1024).value(1) as NodeValue.Img
        assertEquals("stack(solid,compose.in1)", v.handle)
        assertEquals(800, v.width)
        assertEquals(600, v.height)
    }

    @Test fun aLinkedImageReplacesTheRackOnItsLayer() {
        val b = FakeBackend(ComposeDoc(layers = listOf(solid, live(2, "compose.in1"), live(3, "compose.in2"))))
        val g = Graph(listOf(node(), asset(2, "a1")), listOf(Link(2, 0, 1, 1)))
        val v = Evaluator(g, b, 1024).value(1) as NodeValue.Img
        assertEquals("stack(solid,compose.in1,img(a1))", v.handle)
        assertEquals(1, b.drawn.size)
    }

    @Test fun hideOnTheNodeHidesUnlinkedLiveLayers() {
        val b = FakeBackend(ComposeDoc(layers = listOf(solid, live(2, "compose.in1"))))
        val v = Evaluator(Graph(listOf(node(hide = true)), emptyList()), b, 1024).value(1) as NodeValue.Img
        assertEquals("stack(solid,-)", v.handle)
    }

    @Test fun noComposeRoomGivesNothing() {
        assertNull(Evaluator(Graph(listOf(node()), emptyList()), FakeBackend(null), 1024).value(1))
    }

    @Test fun editingTheRoomOrItsRackInputsChangesTheKey() {
        val b = FakeBackend(ComposeDoc(layers = listOf(solid)))
        val g = Graph(listOf(node()), emptyList())
        val before = Evaluator(g, b, 1024).key(1)
        b.version = 1
        val rack = Evaluator(g, b, 1024).key(1)
        b.doc = ComposeDoc(layers = listOf(solid.copy(opacity = 0.5f)))
        val edited = Evaluator(g, b, 1024).key(1)
        assertNotEquals(before, rack)
        assertNotEquals(rack, edited)
    }

    @Test fun theSameRoomAndInputsComeFromTheCache() {
        val b = FakeBackend(ComposeDoc(layers = listOf(solid)))
        val g = Graph(listOf(node(), asset(2, "a1")), listOf(Link(2, 0, 1, 0)))
        val cache = HashMap<String, NodeValue>()
        val first = Evaluator(g, b, 1024, cache).value(1)
        b.drawn = emptyMap()
        val second = Evaluator(g, b, 1024, cache).value(1)
        assertEquals(first, second)
        // Not drawn again.
        assertTrue(b.drawn.isEmpty())
    }
}
