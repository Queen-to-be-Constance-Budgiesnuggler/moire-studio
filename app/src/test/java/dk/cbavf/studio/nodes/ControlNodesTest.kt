package dk.cbavf.studio.nodes

import dk.cbavf.studio.library.CanvasDoc
import dk.cbavf.studio.nodes.model.Evaluator
import dk.cbavf.studio.nodes.model.Graph
import dk.cbavf.studio.nodes.model.ImageBackend
import dk.cbavf.studio.nodes.model.Link
import dk.cbavf.studio.nodes.model.Node
import dk.cbavf.studio.nodes.model.NodeValue
import dk.cbavf.studio.nodes.model.Prop
import dk.cbavf.studio.nodes.model.patchToGraph
import dk.cbavf.studio.patch.model.Param
import dk.cbavf.studio.patch.model.PatchDoc
import dk.cbavf.studio.patch.model.Route
import dk.cbavf.studio.patch.model.SourceKind
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.exp

/** The Touch Pad, Smooth and Switch nodes, evaluated as Live does. */
class ControlNodesTest {
    private class NoImages : ImageBackend {
        override fun libraryImage(assetId: String, maxPx: Int): NodeValue.Img? = null
        override fun drawCanvas(): CanvasDoc? = null
        override fun render(doc: CanvasDoc, width: Int, height: Int, paper: Boolean, maxPx: Int) = NodeValue.Img("", width, height)
        override fun process(type: String, inputs: List<NodeValue?>, nums: List<Float>, props: Map<String, String>, maxPx: Int) = NodeValue.Img("", 1, 1)
    }

    private val state = HashMap<String, Float>()
    private val objects = HashMap<String, Any>()

    /** Output [socket] of node [id] at time [t]; memory carries over between calls, as in Live. */
    private fun value(g: Graph, id: Int, t: Double = 0.0, socket: Int = 0, touching: Set<Int> = emptySet()): Float =
        (Evaluator(g, NoImages(), 1024, env = dk.cbavf.studio.nodes.model.TimeEnv(t, 0.016f, state = state, objects = objects, touching = { it in touching }))
            .value(id, socket) as NodeValue.Num).v

    private fun pad(x: Float, y: Float) =
        Node(1, "touchpad", 0f, 0f, mapOf("X" to Prop.Num(x), "Y" to Prop.Num(y), "Min" to Prop.Num(0f), "Max" to Prop.Num(100f)))

    @Test fun touchPadMapsXAndYToMinMax() {
        val g = Graph(listOf(pad(0.25f, 0.5f)))
        assertEquals(25f, value(g, 1, socket = 0), 1e-4f)
        assertEquals(50f, value(g, 1, socket = 1), 1e-4f)
    }

    @Test fun anUntouchedPadSitsInTheMiddle() {
        val g = Graph(listOf(Node(1, "touchpad", 0f, 0f)))
        assertEquals(0.5f, value(g, 1, socket = 0), 1e-5f)
    }

    @Test fun downIsOnOnlyWhileAFingerIsOnThePad() {
        val g = Graph(listOf(pad(0.5f, 0.5f)))
        assertEquals(0f, value(g, 1, socket = 2), 0f)
        assertEquals(1f, value(g, 1, socket = 2, touching = setOf(1)), 0f)
    }

    @Test fun smoothFollowsItsInputSlowly() {
        fun graph(v: Float) = Graph(
            listOf(pad(v, 0f), Node(2, "smooth", 0f, 0f, mapOf("Rise s" to Prop.Num(1f), "Fall s" to Prop.Num(1f)))),
            listOf(Link(1, 0, 2, 0)),
        )
        assertEquals(0f, value(graph(0f), 2, 0.0), 1e-4f)
        assertEquals(100f * (1f - exp(-1f)), value(graph(1f), 2, 1.0), 1e-3f)
    }

    @Test fun smoothWithoutLiveShowsItsInput() {
        val g = Graph(listOf(Node(1, "smooth", 0f, 0f, mapOf("Value" to Prop.Num(4f)))))
        assertEquals(4f, (Evaluator(g, NoImages(), 1024).value(1) as NodeValue.Num).v, 0f)
    }

    @Test fun aMutedSmoothPassesItsValueThrough() {
        val g = Graph(listOf(Node(1, "smooth", 0f, 0f, mapOf("Value" to Prop.Num(4f)), muted = true)))
        assertEquals(4f, value(g, 1), 0f)
    }

    @Test fun switchPicksBWhenAGateIsOn() {
        fun graph(on: Boolean) = Graph(
            listOf(
                Node(1, "gateswitch", 0f, 0f, mapOf("State" to Prop.Choice(if (on) "On" else "Off"))),
                Node(2, "switch", 0f, 0f, mapOf("A" to Prop.Num(3f), "B" to Prop.Num(8f))),
            ),
            listOf(Link(1, 0, 2, 2)),
        )
        assertEquals(3f, value(graph(false), 2), 0f)
        assertEquals(8f, value(graph(true), 2), 0f)
    }

    @Test fun switchFadesByAValue() {
        val g = Graph(listOf(Node(1, "switch", 0f, 0f, mapOf("A" to Prop.Num(0f), "B" to Prop.Num(10f), "Select" to Prop.Num(0.3f), "Mode" to Prop.Choice("Fade")))))
        assertEquals(3f, value(g, 1), 1e-5f)
    }

    @Test fun aPatchTouchRouteBecomesATouchPadThroughSmooth() {
        val doc = PatchDoc(listOf(Route(1, SourceKind.TouchY, layerId = 7, param = Param.Angle, min = -10f, max = 10f, smoothing = 0.5f)))
        val g = patchToGraph(doc, listOf(7), 1080, 2000)
        assertEquals(listOf("draw", "touchpad", "smooth", "drive", "render", "viewer"), g.nodes.map { it.type })
        val padId = g.nodes.first { it.type == "touchpad" }.id
        val smooth = g.nodes.first { it.type == "smooth" }
        assertEquals(Prop.Num(0.5f), smooth.props["Rise s"])           // 2 s x 0.5 squared
        assertEquals(Link(padId, 1, smooth.id, 0), g.links.first { it.toNode == smooth.id })  // the pad's Y
        // Every link is valid.
        assertEquals(g.links.toSet(), g.links.fold(g.copy(links = emptyList())) { acc, l -> acc.connect(l) }.links.toSet())
    }

    @Test fun aTouchRouteWithoutSmoothingFeedsTheDriveStraight() {
        val doc = PatchDoc(listOf(Route(1, SourceKind.TouchX, layerId = 7, param = Param.Angle, min = 0f, max = 1f, smoothing = 0f)))
        val g = patchToGraph(doc, listOf(7), 1080, 2000)
        val padId = g.nodes.first { it.type == "touchpad" }.id
        val drive = g.nodes.first { it.type == "drive" }
        assertEquals(Link(padId, 0, drive.id, 2), g.links.first { it.toNode == drive.id && it.toSocket == 2 })
    }
}
