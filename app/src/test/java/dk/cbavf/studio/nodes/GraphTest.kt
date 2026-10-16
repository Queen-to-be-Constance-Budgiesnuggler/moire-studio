package dk.cbavf.studio.nodes

import dk.cbavf.studio.library.CanvasDoc
import dk.cbavf.studio.library.ProjectStore
import dk.cbavf.studio.draw.model.Grating
import dk.cbavf.studio.draw.model.Layer
import dk.cbavf.studio.nodes.model.Evaluator
import dk.cbavf.studio.nodes.model.FrameRef
import dk.cbavf.studio.nodes.model.TimeEnv
import dk.cbavf.studio.nodes.model.catalogWith
import dk.cbavf.studio.nodes.model.groupNodes
import dk.cbavf.studio.nodes.model.groupUsed
import dk.cbavf.studio.nodes.model.groupsContaining
import dk.cbavf.studio.nodes.model.ungroup
import dk.cbavf.studio.nodes.model.updateAt
import dk.cbavf.studio.patch.model.SourceKind
import dk.cbavf.studio.nodes.model.Graph
import dk.cbavf.studio.nodes.model.GraphPreset
import dk.cbavf.studio.nodes.model.ImageBackend
import dk.cbavf.studio.nodes.model.Link
import dk.cbavf.studio.nodes.model.Node
import dk.cbavf.studio.nodes.model.NodeValue
import dk.cbavf.studio.nodes.model.Prop
import dk.cbavf.studio.nodes.model.SocketType
import kotlinx.serialization.builtins.ListSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class GraphTest {
    /** Images are strings describing how they were made, so tests can read the pipeline back. */
    private class FakeBackend : ImageBackend {
        var processed = 0
        override fun libraryImage(assetId: String, maxPx: Int) = NodeValue.Img("img($assetId)", 100, 50)
        override fun drawCanvas(): CanvasDoc? = CanvasDoc(1080, 1920, emptyList())
        var animFrames: List<FrameRef> = emptyList()
        var remote: NodeValue.Img? = null
        var remoteN = 0
        override fun remoteImage(maxPx: Int) = remote
        override fun remoteCount() = remoteN
        override fun frames(source: String) = if (source == "animate") animFrames else emptyList()
        override fun render(doc: CanvasDoc, width: Int, height: Int, paper: Boolean, maxPx: Int) =
            NodeValue.Img("render(${doc.layers.size},$width×$height)", width, height)
        override fun process(type: String, inputs: List<NodeValue?>, nums: List<Float>, props: Map<String, String>, maxPx: Int): NodeValue.Img {
            processed++
            val a = inputs[0] as NodeValue.Img
            val extra = (inputs.getOrNull(1) as? NodeValue.Img)?.let { ",${it.handle}" } ?: ""
            val n = nums.drop(1).joinToString("") { ",$it" }
            return NodeValue.Img("$type(${a.handle}$extra$n${props.values.joinToString("") { ",$it" }})", a.width, a.height)
        }
    }

    private fun graph(vararg nodes: Node, links: List<Link> = emptyList()) = Graph(nodes.toList(), links)

    @Test
    fun linksCheckTypesAndReplaceAndRefuseCycles() {
        var g = graph(Node(1, "image", 0f, 0f), Node(2, "blur", 0f, 0f), Node(3, "blur", 0f, 0f), Node(4, "value", 0f, 0f))
        g = g.connect(Link(1, 0, 2, 0))
        assertEquals(1, g.links.size)
        // Value into an Image input: refused.
        assertEquals(g, g.connect(Link(4, 0, 2, 0)))
        // A second link into the same input replaces the first.
        g = g.connect(Link(3, 0, 2, 0))
        assertEquals(listOf(Link(3, 0, 2, 0)), g.links)
        // 2 feeds 3 would close a loop 3 → 2 → 3.
        assertEquals(g, g.connect(Link(2, 0, 3, 0)))
        assertTrue(SocketType.Image.accepts(SocketType.Canvas))
    }

    @Test
    fun evaluationFollowsLinksAndUsesFieldsForUnlinkedValues() {
        val g = graph(
            Node(1, "image", 0f, 0f, mapOf("asset" to Prop.Text("a1"))),
            Node(2, "blur", 0f, 0f, mapOf("Radius" to Prop.Num(5f))),
            Node(3, "value", 0f, 0f, mapOf("Value" to Prop.Num(3f))),
            Node(4, "math", 0f, 0f, mapOf("Operation" to Prop.Choice("Multiply"), "B" to Prop.Num(4f))),
            Node(5, "sharpen", 0f, 0f),
            links = listOf(Link(1, 0, 2, 0), Link(2, 0, 5, 0), Link(3, 0, 4, 0), Link(4, 0, 5, 1)),
        )
        val v = Evaluator(g, FakeBackend(), 1024).value(5) as NodeValue.Img
        // Sharpen amount comes from Math: 3 × 4 = 12; its radius is the default 2.
        assertEquals("sharpen(blur(img(a1),5.0),12.0,2.0)", v.handle)
    }

    @Test
    fun canvasesRenderOnTheWayIntoImageInputs() {
        val g = graph(Node(1, "grating", 0f, 0f), Node(2, "invert", 0f, 0f), links = listOf(Link(1, 0, 2, 0)))
        val v = Evaluator(g, FakeBackend(), 1024).value(2) as NodeValue.Img
        assertEquals("invert(render(1,1080×1920))", v.handle)
    }

    @Test
    fun mutedNodesPassThrough() {
        val g = graph(
            Node(1, "image", 0f, 0f, mapOf("asset" to Prop.Text("a"))),
            Node(2, "blur", 0f, 0f, muted = true),
            links = listOf(Link(1, 0, 2, 0)),
        )
        assertEquals("img(a)", (Evaluator(g, FakeBackend(), 1024).value(2) as NodeValue.Img).handle)
    }

    @Test
    fun mixTakesBothImagesAndItsBlendMode() {
        val g = graph(
            Node(1, "image", 0f, 0f, mapOf("asset" to Prop.Text("a"))),
            Node(2, "image", 0f, 0f, mapOf("asset" to Prop.Text("b"))),
            Node(3, "mix", 0f, 0f, mapOf("Blend" to Prop.Choice("Multiply"), "Factor" to Prop.Num(0.5f))),
            links = listOf(Link(1, 0, 3, 0), Link(2, 0, 3, 1)),
        )
        assertEquals("mix(img(a),img(b),0.0,0.5,Multiply)", (Evaluator(g, FakeBackend(), 1024).value(3) as NodeValue.Img).handle)
    }

    @Test
    fun cacheSkipsUnchangedNodesAcrossRuns() {
        val backend = FakeBackend()
        val cache = HashMap<String, NodeValue>()
        val base = graph(
            Node(1, "image", 0f, 0f, mapOf("asset" to Prop.Text("a"))),
            Node(2, "blur", 0f, 0f),
            Node(3, "invert", 0f, 0f),
            links = listOf(Link(1, 0, 2, 0), Link(2, 0, 3, 0)),
        )
        val first = Evaluator(base, backend, 1024, cache).value(3)
        assertEquals(2, backend.processed)
        // Same graph again: nothing recomputed.
        assertSame(first, Evaluator(base, backend, 1024, cache).value(3))
        assertEquals(2, backend.processed)
        // Change only the last node: the blur upstream comes from the cache.
        val changed = base.copy(nodes = base.nodes.map { if (it.id == 3) it.copy(muted = false, props = mapOf("x" to Prop.Num(1f))) else it })
        Evaluator(changed, backend, 1024, cache).value(3)
        assertEquals(3, backend.processed)
    }

    @Test
    fun mathAndMapRange() {
        assertEquals(5f, Evaluator.math("Add", 2f, 3f), 0f)
        assertEquals(0f, Evaluator.math("Divide", 2f, 0f), 0f)
        assertEquals(1f, Evaluator.math("Sine", 90f, 0f), 1e-6f)
        val g = graph(Node(1, "maprange", 0f, 0f, mapOf("Value" to Prop.Num(5f), "From max" to Prop.Num(10f), "To max" to Prop.Num(100f))))
        assertEquals(50f, (Evaluator(g, FakeBackend(), 1024).value(1) as NodeValue.Num).v, 1e-4f)
    }

    @Test
    fun duplicateAndLazyConnectAndRemove() {
        var g = graph(Node(1, "image", 0f, 0f), Node(2, "blur", 100f, 0f), links = listOf())
        g = g.lazyConnect(1, 2)
        assertEquals(listOf(Link(1, 0, 2, 0)), g.links)
        val (g2, ids) = g.duplicate(setOf(1, 2), 50f, 50f)
        assertEquals(4, g2.nodes.size)
        assertEquals(2, g2.links.size) // the inner link was copied too
        assertTrue(ids.all { id -> g2.node(id)!!.x >= 50f })
        val g3 = g2.remove(ids)
        assertEquals(g, g3)
        assertNull(g3.node(ids.first()))
    }

    @Test
    fun presetsRoundTrip() {
        val p = GraphPreset("Photo grating", graph(Node(1, "grating", 10f, 20f, mapOf("Profile" to Prop.Choice("Sine"))), Node(2, "viewer", 300f, 20f), links = listOf(Link(1, 0, 2, 0))))
        val json = ProjectStore.json.encodeToString(ListSerializer(GraphPreset.serializer()), listOf(p))
        assertEquals(listOf(p), ProjectStore.json.decodeFromString(ListSerializer(GraphPreset.serializer()), json))
    }

    private fun num(g: Graph, id: Int, socket: Int = 0, env: TimeEnv = TimeEnv(), backend: FakeBackend = FakeBackend()) =
        (Evaluator(g, backend, 1024, env = env).value(id, socket) as NodeValue.Num).v

    @Test
    fun clockCountsStepsAndTicksOnce() {
        val g = graph(Node(1, "clock", 0f, 0f, mapOf("Rate" to Prop.Num(2f), "Steps" to Prop.Num(3f))))
        // t = 2.6 s at 2/s: tick 5, wrapped to step 2, phase 0.2.
        assertEquals(2.6f, num(g, 1, 0, TimeEnv(2.6, 0.016f)), 1e-4f)
        assertEquals(2f, num(g, 1, 1, TimeEnv(2.6, 0.016f)), 0f)
        assertEquals(0.2f, num(g, 1, 2, TimeEnv(2.6, 0.016f)), 1e-4f)
        // Tick fires on the frame that crosses 2.5 s, not the next.
        assertEquals(1f, num(g, 1, 3, TimeEnv(2.51, 0.02f)), 0f)
        assertEquals(0f, num(g, 1, 3, TimeEnv(2.53, 0.02f)), 0f)
        // Without Live (dt = 0) nothing fires.
        assertEquals(0f, num(g, 1, 3, TimeEnv(2.5, 0f)), 0f)
    }

    @Test
    fun lfoStaysBetweenMinAndMax() {
        val g = graph(Node(1, "lfo", 0f, 0f, mapOf("Rate Hz" to Prop.Num(1f), "Min" to Prop.Num(-5f), "Max" to Prop.Num(5f), "Shape" to Prop.Choice("Saw"))))
        assertEquals(-5f, num(g, 1, env = TimeEnv(0.0)), 1e-4f)
        assertEquals(0f, num(g, 1, env = TimeEnv(0.5)), 1e-4f)
        for (k in 0..40) assertTrue(num(g, 1, env = TimeEnv(k * 0.137)) in -5f..5f)
    }

    @Test
    fun sensorMapsSmoothsAndReadsTheOtherPhone() {
        val g = graph(Node(1, "sensor", 0f, 0f, mapOf("Source" to Prop.Choice(SourceKind.Roll.label), "Min" to Prop.Num(0f), "Max" to Prop.Num(10f), "Smoothing" to Prop.Num(0f))))
        // Roll −180..180: 90° is three quarters of the way.
        assertEquals(7.5f, num(g, 1, env = TimeEnv(sensor = { k, remote -> if (k == SourceKind.Roll && !remote) 90f else null })), 1e-4f)
        assertEquals(90f, num(g, 1, 1, TimeEnv(sensor = { _, _ -> 90f })), 0f)
        val other = g.copy(nodes = g.nodes.map { it.copy(props = it.props + ("Phone" to Prop.Choice("Other"))) })
        assertEquals(5f, num(other, 1, env = TimeEnv(sensor = { _, remote -> if (remote) 0f else 90f })), 1e-4f)
        // Smoothing: a jump is followed gradually across frames.
        val smooth = g.copy(nodes = g.nodes.map { it.copy(props = it.props + ("Smoothing" to Prop.Num(0.5f))) })
        val state = HashMap<String, Float>()
        assertEquals(5f, num(smooth, 1, env = TimeEnv(0.0, 0.016f, { _, _ -> 0f }, state)), 1e-4f)
        val next = num(smooth, 1, env = TimeEnv(0.016, 0.016f, { _, _ -> 180f }, state))
        assertTrue(next > 5f && next < 10f)
    }

    @Test
    fun sampleAndHoldKeepsTheValueBetweenTicks() {
        val g = graph(
            Node(1, "clock", 0f, 0f, mapOf("Rate" to Prop.Num(1f))),
            Node(2, "hold", 0f, 0f),
            links = listOf(Link(1, 0, 2, 0), Link(1, 3, 2, 1)),
        )
        val state = HashMap<String, Float>()
        assertEquals(0.5f, num(g, 2, env = TimeEnv(0.5, 0.1f, state = state)), 1e-4f)
        assertEquals(0.5f, num(g, 2, env = TimeEnv(0.9, 0.1f, state = state)), 1e-4f)
        assertEquals(1.05f, num(g, 2, env = TimeEnv(1.05, 0.1f, state = state)), 1e-4f)
    }

    @Test
    fun frameAtPlaysFramesWithWrapModes() {
        assertEquals(1, Evaluator.frameIndex(4, 3, "Loop"))
        assertEquals(2, Evaluator.frameIndex(9, 3, "Clamp"))
        assertEquals(listOf(0, 1, 2, 1, 0, 1), (0..5).map { Evaluator.frameIndex(it, 3, "PingPong") })
        assertNull(Evaluator.frameIndex(0, 0, "Loop"))
        val backend = FakeBackend().apply { animFrames = listOf(FrameRef.Canvas(CanvasDoc(10, 20, emptyList())), FrameRef.Asset("p")) }
        val g = graph(
            Node(1, "animate", 0f, 0f),
            Node(2, "clock", 0f, 0f, mapOf("Rate" to Prop.Num(1f))),
            Node(3, "frameat", 0f, 0f),
            links = listOf(Link(1, 0, 3, 0), Link(2, 1, 3, 1)),
        )
        assertEquals("render(0,10×20)", (Evaluator(g, backend, 1024, env = TimeEnv(0.5)).value(3) as NodeValue.Img).handle)
        assertEquals("img(p)", (Evaluator(g, backend, 1024, env = TimeEnv(1.5)).value(3) as NodeValue.Img).handle)
        assertEquals(2f, num(g, 3, 1, backend = backend), 0f)
    }

    @Test
    fun stackAndDriveWorkOnVectorCanvases() {
        val g = graph(
            Node(1, "grating", 0f, 0f),
            Node(2, "grating", 0f, 0f),
            Node(3, "stack", 0f, 0f, mapOf("Blend" to Prop.Choice("Multiply"))),
            Node(4, "value", 0f, 0f, mapOf("Value" to Prop.Num(30f))),
            Node(5, "drive", 0f, 0f, mapOf("Parameter" to Prop.Choice("Angle"), "Layer" to Prop.Num(2f))),
            links = listOf(Link(1, 0, 3, 0), Link(2, 0, 3, 1), Link(3, 0, 5, 0), Link(4, 0, 5, 2)),
        )
        val doc = (Evaluator(g, FakeBackend(), 1024).value(5) as NodeValue.Cnv).doc
        assertEquals(2, doc.layers.size)
        assertEquals(listOf(1, 2), doc.layers.map(Layer::id))
        assertEquals(dk.cbavf.studio.draw.model.Blend.Multiply, doc.layers[1].blend)
        assertEquals(0f, (doc.layers[0].content as Grating).angleDeg, 0f)
        assertEquals(30f, (doc.layers[1].content as Grating).angleDeg, 0f)
    }

    @Test
    fun imagesFedByMovingNumbersAreCachedByValue() {
        val backend = FakeBackend()
        val cache = HashMap<String, NodeValue>()
        val g = graph(
            Node(1, "image", 0f, 0f, mapOf("asset" to Prop.Text("a"))),
            Node(2, "lfo", 0f, 0f, mapOf("Rate Hz" to Prop.Num(1f), "Shape" to Prop.Choice("Square"), "Max" to Prop.Num(10f))),
            Node(3, "blur", 0f, 0f),
            links = listOf(Link(1, 0, 3, 0), Link(2, 0, 3, 1)),
        )
        // A square wave takes two values: only two blurs, however many frames run.
        for (k in 0 until 20) Evaluator(g, backend, 1024, cache, env = TimeEnv(k * 0.1, 0.1f)).value(3)
        assertEquals(2, backend.processed)
    }

    @Test
    fun triggersAndSignalsFeedValueInputs() {
        assertTrue(SocketType.Value.accepts(SocketType.Trigger))
        assertTrue(SocketType.Value.accepts(SocketType.Signal))
        assertTrue(!SocketType.Trigger.accepts(SocketType.Value))
        assertTrue(!SocketType.Frames.accepts(SocketType.Image))
    }

    @Test
    fun everyBuiltInPresetLinksAreValid() {
        for (p in NodesState.builtInPresets()) {
            val rebuilt = p.graph.links.fold(p.graph.copy(links = emptyList())) { g, l -> g.connect(l) }
            assertEquals(p.name, p.graph.links.toSet(), rebuilt.links.toSet())
            p.graph.nodes.forEach { n -> assertTrue("${p.name}: ${n.type}", dk.cbavf.studio.nodes.model.Catalog[n.type] != null) }
        }
    }

    @Test
    fun reroutesCarryWhatFlowsInAndAdoptItsType() {
        var g = graph(Node(1, "image", 0f, 0f, mapOf("asset" to Prop.Text("a"))), Node(2, "reroute", 0f, 0f), Node(3, "blur", 0f, 0f), Node(4, "value", 0f, 0f), Node(5, "reroute", 0f, 0f))
        assertEquals(SocketType.Any, g.outputType(2, 0))
        g = g.connect(Link(1, 0, 2, 0)).connect(Link(2, 0, 3, 0))
        assertEquals(SocketType.Image, g.outputType(2, 0))
        assertEquals("blur(img(a),8.0)", (Evaluator(g, FakeBackend(), 1024).value(3) as NodeValue.Img).handle)
        // A reroute carrying a number cannot feed an image input.
        val v = g.connect(Link(4, 0, 5, 0))
        assertEquals(v, v.connect(Link(5, 0, 3, 0)))
        // Insert a reroute on a link: from → dot → to, same result.
        val (r, id) = g.insertReroute(Link(2, 0, 3, 0), 50f, 50f)
        assertEquals(setOf(Link(1, 0, 2, 0), Link(2, 0, id, 0), Link(id, 0, 3, 0)), r.links.toSet())
        assertEquals("blur(img(a),8.0)", (Evaluator(r, FakeBackend(), 1024).value(3) as NodeValue.Img).handle)
    }

    @Test
    fun lazyMixAndSwap() {
        val g = graph(Node(1, "image", 0f, 0f, mapOf("asset" to Prop.Text("a"))), Node(2, "grating", 0f, 0f), Node(3, "value", 0f, 0f))
        val (m, id) = g.lazyMix(1, 2, 300f, 0f)
        assertEquals(setOf(Link(1, 0, id!!, 0), Link(2, 0, id, 1)), m.links.toSet())
        assertEquals("mix(img(a),render(1,1080×1920),0.0,1.0,Normal)", (Evaluator(m, FakeBackend(), 1024).value(id) as NodeValue.Img).handle)
        // A number is no picture to mix.
        assertNull(g.lazyMix(1, 3, 0f, 0f).second)
        val swapped = m.swapInputs(id)
        assertEquals(setOf(Link(1, 0, id, 1), Link(2, 0, id, 0)), swapped.links.toSet())
        assertTrue(m.canSwap(id))
        assertTrue(!m.canSwap(1))
    }

    @Test
    fun droppingANodeOnALinkInsertsIt() {
        val g = graph(Node(1, "image", 0f, 0f), Node(2, "viewer", 0f, 0f), Node(3, "invert", 0f, 0f), links = listOf(Link(1, 0, 2, 0)))
        assertEquals(setOf(Link(1, 0, 3, 0), Link(3, 0, 2, 0)), g.insertOnLink(3, Link(1, 0, 2, 0)).links.toSet())
        // A Value node does not fit between two images.
        val v = g.copy(nodes = g.nodes + Node(4, "value", 0f, 0f))
        assertEquals(v, v.insertOnLink(4, Link(1, 0, 2, 0)))
    }

    @Test
    fun alignMakesARowOrAColumn() {
        val g = graph(Node(1, "blur", 0f, 0f), Node(2, "blur", 500f, 37f), Node(3, "blur", 220f, -20f))
        val row = g.align(setOf(1, 2, 3), 40f) { 210f to 90f }
        assertEquals(listOf(0f to -20f, 500f to -20f, 250f to -20f), row.nodes.map { it.x to it.y })
        val col = graph(Node(1, "blur", 10f, 0f), Node(2, "blur", 0f, 400f)).align(setOf(1, 2), 40f) { 210f to 90f }
        assertEquals(listOf(0f to 0f, 0f to 130f), col.nodes.map { it.x to it.y })
    }

    @Test
    fun patchRoutesBecomeAGraph() {
        val doc = dk.cbavf.studio.patch.model.PatchDoc(
            routes = listOf(
                dk.cbavf.studio.patch.model.Route(1, SourceKind.Roll, layerId = 7, param = dk.cbavf.studio.patch.model.Param.Angle, min = -10f, max = 10f, smoothing = 0.5f),
                dk.cbavf.studio.patch.model.Route(2, SourceKind.Lfo2, layerId = 9, param = dk.cbavf.studio.patch.model.Param.Spacing, min = 4f, max = 30f, enabled = false),
                dk.cbavf.studio.patch.model.Route(3, SourceKind.Light, layerId = 99, param = dk.cbavf.studio.patch.model.Param.Angle, min = 0f, max = 1f),
            ),
        )
        val g = dk.cbavf.studio.nodes.model.patchToGraph(doc, listOf(9, 7), 1080, 2000)
        assertEquals(listOf("draw", "sensor", "drive", "lfo", "drive", "render", "viewer"), g.nodes.map { it.type })
        // Every link is valid, and the chain runs draw → drive → drive → render → viewer.
        assertEquals(g.links.toSet(), g.links.fold(g.copy(links = emptyList())) { acc, l -> acc.connect(l) }.links.toSet())
        val drives = g.nodes.filter { it.type == "drive" }
        assertEquals(listOf(2f, 1f), drives.map { (it.props["Layer"] as Prop.Num).v })
        assertEquals(listOf(false, true), drives.map { it.muted })
        val sensor = g.nodes.first { it.type == "sensor" }
        assertEquals(Prop.Choice(SourceKind.Roll.label), sensor.props["Source"])
        assertEquals(Prop.Num(0.5f), sensor.props["Smoothing"])
        val lfo = g.nodes.first { it.type == "lfo" }
        assertEquals(Prop.Choice("Triangle"), lfo.props["Shape"])
    }

    private fun img(g: Graph, id: Int) = (Evaluator(g, FakeBackend(), 1024).value(id) as NodeValue.Img).handle

    private val chain = graph(
        Node(1, "image", 0f, 0f, mapOf("asset" to Prop.Text("a"))),
        Node(2, "blur", 200f, 0f),
        Node(3, "invert", 400f, 0f),
        Node(4, "viewer", 600f, 0f),
        Node(5, "value", 0f, 200f, mapOf("Value" to Prop.Num(3f))),
        links = listOf(Link(1, 0, 2, 0), Link(2, 0, 3, 0), Link(3, 0, 4, 0), Link(5, 0, 2, 1)),
    )

    @Test
    fun groupingKeepsTheResultAndUngroupingRestoresIt() {
        val before = img(chain, 4)
        assertEquals("invert(blur(img(a),3.0))", before)
        val (g, gid) = chain.groupNodes(emptyList(), setOf(2, 3))!!
        assertEquals(1, g.groups.size)
        val body = g.groups.single().graph
        assertEquals(listOf("Image", "Radius"), body.inputs.map { it.name })
        assertEquals(listOf(SocketType.Image, SocketType.Image), listOf(body.inputs[0].type, body.outputs[0].type))
        assertEquals(listOf("image", "viewer", "value", "group:g1"), g.nodes.map { it.type })
        assertEquals(before, img(g, 4))
        // Every link outside is valid against the group's sockets.
        assertEquals(g.links.toSet(), g.links.fold(g.copy(links = emptyList())) { acc, l -> acc.connect(l) }.links.toSet())

        val (u, ids) = g.ungroup(emptyList(), gid)
        assertEquals(2, ids.size)
        assertTrue(u.groups.isEmpty())
        assertEquals(before, img(u, 4))
    }

    @Test
    fun groupFieldsFeedTheInsideAndSurviveUngroup() {
        val (g, gid) = chain.groupNodes(emptyList(), setOf(2, 3))!!
        // Unlink the Value and type a radius on the Group node instead.
        val fielded = g.copy(links = g.links.filterNot { it.fromNode == 5 }, nodes = g.nodes.map { if (it.id == gid) it.copy(props = mapOf("Radius" to Prop.Num(7f))) else it })
        assertEquals("invert(blur(img(a),7.0))", img(fielded, 4))
        val (u, ids) = fielded.ungroup(emptyList(), gid)
        val blur = u.nodes.first { it.id in ids && it.type == "blur" }
        assertEquals(Prop.Num(7f), blur.props["Radius"])
        assertEquals("invert(blur(img(a),7.0))", img(u, 4))
    }

    @Test
    fun editingInsideAGroupChangesEveryUseAndTheCache() {
        val (g, gid) = chain.groupNodes(emptyList(), setOf(2, 3))!!
        val cache = HashMap<String, NodeValue>()
        assertEquals("invert(blur(img(a),3.0))", (Evaluator(g, FakeBackend(), 1024, cache).value(4) as NodeValue.Img).handle)
        // Mute the invert inside the group: the result changes, not served stale from the cache.
        val edited = g.updateAt(listOf("g1")) { b -> b.copy(nodes = b.nodes.map { if (it.type == "invert") it.copy(muted = true) else it }) }
        assertEquals("blur(img(a),3.0)", (Evaluator(edited, FakeBackend(), 1024, cache).value(4) as NodeValue.Img).handle)
        assertTrue(edited.groupUsed("g1"))
        assertEquals(gid, edited.nodes.first { it.type == "group:g1" }.id)
    }

    @Test
    fun linkingToThePlusSocketAddsAGroupSocket() {
        val (g, _) = chain.groupNodes(emptyList(), setOf(2, 3))!!
        val body = g.groups.single().graph
        val gin = body.nodes.first { it.type == "groupin" }
        val blur = body.nodes.first { it.type == "blur" }
        val cat = body.catalogWith(g.groups)
        // Group Input's "+" is the socket after the real ones: link it to Blur's Radius... already
        // fed, so use a fresh Sharpen's Amount.
        val sharpen = Node(body.nextId, "sharpen", 300f, 300f)
        val b2 = body.copy(nodes = body.nodes + sharpen).connect(Link(gin.id, body.inputs.size, sharpen.id, 1), cat)
        assertEquals(3, b2.inputs.size)
        assertEquals("Amount", b2.inputs.last().name)
        assertTrue(b2.links.contains(Link(gin.id, 2, sharpen.id, 1)))
        // Into Group Output's "+": a new output of the source's type.
        val gout = body.nodes.first { it.type == "groupout" }
        val b3 = body.connect(Link(blur.id, 0, gout.id, body.outputs.size), cat)
        assertEquals(listOf(SocketType.Image, SocketType.Image), b3.outputs.map { it.type })
    }

    @Test
    fun timeStateIsKeptPerGroupUse() {
        val inner = graph(
            Node(1, "groupin", 0f, 0f), Node(2, "hold", 0f, 0f), Node(3, "groupout", 0f, 0f),
            links = listOf(Link(1, 0, 2, 0), Link(2, 0, 3, 0)),
        ).copy(inputs = listOf(dk.cbavf.studio.nodes.model.GroupSocket("Value", SocketType.Value)), outputs = listOf(dk.cbavf.studio.nodes.model.GroupSocket("Value", SocketType.Signal)))
        val top = Graph(
            nodes = listOf(Node(10, "group:h", 0f, 0f, mapOf("Value" to Prop.Num(1f))), Node(11, "group:h", 0f, 0f, mapOf("Value" to Prop.Num(2f)))),
            groups = listOf(dk.cbavf.studio.nodes.model.NodeGroup("h", "Hold", inner)),
        )
        val state = HashMap<String, Float>()
        val ev = Evaluator(top, FakeBackend(), 1024, env = TimeEnv(state = state))
        assertEquals(1f, (ev.value(10) as NodeValue.Num).v, 0f)
        assertEquals(2f, (ev.value(11) as NodeValue.Num).v, 0f)
        assertEquals(setOf("10/2", "11/2"), state.keys)
    }

    @Test
    fun aGroupCannotGoInsideItself() {
        val (g, _) = chain.groupNodes(emptyList(), setOf(2, 3))!!
        val (g2, _) = g.groupNodes(emptyList(), setOf(g.nodes.first { it.type == "group:g1" }.id))!!
        // g2 contains g1; inside g1, neither g1 nor g2 may be added.
        assertEquals(setOf("g1", "g2"), g2.groupsContaining("g1"))
        assertEquals(setOf("g2"), g2.groupsContaining("g2"))
        // Nested groups still evaluate to the same picture.
        assertEquals("invert(blur(img(a),3.0))", img(g2, 4))
    }

    @Test
    fun picturesFromTheOtherPhoneRefreshTheCache() {
        val backend = FakeBackend()
        val cache = HashMap<String, NodeValue>()
        val g = graph(Node(1, "fromphone", 0f, 0f), Node(2, "invert", 0f, 0f), Node(3, "tophone", 0f, 0f), Node(4, "projector", 0f, 0f),
            links = listOf(Link(1, 0, 2, 0), Link(2, 0, 3, 0), Link(2, 0, 4, 0)))
        // Nothing received yet: nothing to show.
        assertNull(Evaluator(g, backend, 1024, cache).value(4))
        backend.remote = NodeValue.Img("p1", 10, 10); backend.remoteN = 1
        assertEquals("invert(p1)", (Evaluator(g, backend, 1024, cache).value(3) as NodeValue.Img).handle)
        // A new picture changes the key, so the old result is not served from the cache.
        backend.remote = NodeValue.Img("p2", 10, 10); backend.remoteN = 2
        assertEquals("invert(p2)", (Evaluator(g, backend, 1024, cache).value(4) as NodeValue.Img).handle)
        assertEquals(2f, (Evaluator(g, backend, 1024, cache).value(1, 1) as NodeValue.Num).v, 0f)
    }
}
