package dk.cbavf.studio.nodes

import dk.cbavf.studio.library.CanvasDoc
import dk.cbavf.studio.nodes.model.Evaluator
import dk.cbavf.studio.nodes.model.Graph
import dk.cbavf.studio.nodes.model.ImageBackend
import dk.cbavf.studio.nodes.model.Link
import dk.cbavf.studio.nodes.model.Node
import dk.cbavf.studio.nodes.model.NodeValue
import dk.cbavf.studio.nodes.model.Prop
import dk.cbavf.studio.nodes.model.SocketType
import dk.cbavf.studio.nodes.model.TimeEnv
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GateNodesTest {
    private class NoImages : ImageBackend {
        override fun libraryImage(assetId: String, maxPx: Int): NodeValue.Img? = null
        override fun moduleLevel(jack: String): Float = if (jack == "nodes.sig2") 0.25f else 1f
        override fun drawCanvas(): CanvasDoc? = null
        override fun render(doc: CanvasDoc, width: Int, height: Int, paper: Boolean, maxPx: Int) = NodeValue.Img("", width, height)
        override fun process(type: String, inputs: List<NodeValue?>, nums: List<Float>, props: Map<String, String>, maxPx: Int) = NodeValue.Img("", 1, 1)
    }

    private val state = HashMap<String, Float>()
    private val objects = HashMap<String, Any>()

    private fun switch(id: Int, on: Boolean) = Node(id, "gateswitch", 0f, 0f, mapOf("State" to Prop.Choice(if (on) "On" else "Off")))

    /** One frame of a live graph at time [t]; [state] and [objects] carry over, as they do in Live. */
    private fun level(g: Graph, id: Int, t: Double): Float =
        (Evaluator(g, NoImages(), 1024, env = TimeEnv(t, 0.016f, state = state, objects = objects)).value(id, 0) as NodeValue.Num).v

    @Test fun theSwitchIsOneOrZero() {
        assertEquals(0f, level(Graph(listOf(switch(1, false)), emptyList()), 1, 0.0), 0f)
        assertEquals(1f, level(Graph(listOf(switch(1, true)), emptyList()), 1, 0.0), 0f)
    }

    @Test fun aGateLinksIntoANumberInputButATriggerDoesNotLinkIntoAGate() {
        assertTrue(SocketType.Value.accepts(SocketType.Gate))
        assertTrue(SocketType.Gate.accepts(SocketType.Gate))
        assertTrue(!SocketType.Gate.accepts(SocketType.Trigger))
    }

    @Test fun envelopeRisesSettlesAndReleases() {
        fun graph(on: Boolean) = Graph(
            listOf(
                switch(1, on),
                Node(2, "envelope", 0f, 0f, mapOf("Attack ms" to Prop.Num(100f), "Decay ms" to Prop.Num(200f), "Sustain" to Prop.Num(0.6f), "Release ms" to Prop.Num(400f))),
            ),
            listOf(Link(1, 0, 2, 0)),
        )
        assertEquals(0f, level(graph(false), 2, 0.0), 1e-4f)          // nothing has opened yet
        assertEquals(0f, level(graph(true), 2, 1.0), 1e-4f)           // gate opens at t = 1.0: attack starts at 0
        assertEquals(0.5f, level(graph(true), 2, 1.05), 1e-3f)        // halfway through a 100 ms linear attack
        assertEquals(1f, level(graph(true), 2, 1.1), 1e-3f)           // top of the attack
        assertEquals(0.6f, level(graph(true), 2, 1.5), 1e-3f)         // settled at sustain
        assertEquals(0.6f, level(graph(false), 2, 2.0), 1e-3f)        // gate closes: release starts from sustain
        assertEquals(0.3f, level(graph(false), 2, 2.2), 1e-3f)        // halfway through a 400 ms release
        assertEquals(0f, level(graph(false), 2, 2.4), 1e-3f)
    }

    @Test fun gateToTriggerFiresOnTheRisingEdgeOnly() {
        fun graph(on: Boolean) = Graph(listOf(switch(1, on), Node(2, "gate2trig", 0f, 0f)), listOf(Link(1, 0, 2, 0)))
        assertEquals(0f, level(graph(false), 2, 0.0), 0f)
        assertEquals(1f, level(graph(true), 2, 0.1), 0f)
        assertEquals(0f, level(graph(true), 2, 0.2), 0f)
        assertEquals(0f, level(graph(false), 2, 0.3), 0f)
    }

    @Test fun gateLogicCombinesTwoGates() {
        fun logic(op: String, a: Boolean, b: Boolean) = Graph(
            listOf(switch(1, a), switch(2, b), Node(3, "gatelogic", 0f, 0f, mapOf("Operation" to Prop.Choice(op)))),
            listOf(Link(1, 0, 3, 0), Link(2, 0, 3, 1)),
        )
        assertEquals(0f, level(logic("AND", true, false), 3, 0.0), 0f)
        assertEquals(1f, level(logic("OR", true, false), 3, 0.0), 0f)
        assertEquals(1f, level(logic("XOR", true, false), 3, 0.0), 0f)
        assertEquals(0f, level(logic("XOR", true, true), 3, 0.0), 0f)
        assertEquals(1f, level(logic("NOT A", false, false), 3, 0.0), 0f)
    }

    @Test fun triggerToGateHoldsForItsLength() {
        // a Gate Switch stands in for the trigger source: a rising edge into Gate to Trigger fires the Trigger to Gate
        fun graph(on: Boolean) = Graph(
            listOf(switch(1, on), Node(2, "gate2trig", 0f, 0f), Node(3, "trig2gate", 0f, 0f, mapOf("Length s" to Prop.Num(0.5f)))),
            listOf(Link(1, 0, 2, 0), Link(2, 0, 3, 0)),
        )
        assertEquals(0f, level(graph(false), 3, 0.0), 0f)
        assertEquals(1f, level(graph(true), 3, 1.0), 0f)
        assertEquals(1f, level(graph(true), 3, 1.4), 0f)
        assertEquals(0f, level(graph(true), 3, 1.5), 0f)
    }

    @Test fun moduleSignalReadsTheRackNumber() {
        fun sig(jack: String) = Graph(listOf(Node(1, "modulesignal", 0f, 0f, mapOf("Jack" to Prop.Choice(jack)))), emptyList())
        assertEquals(1f, level(sig("Signal 1"), 1, 0.0), 0f)
        assertEquals(0.25f, level(sig("Signal 2"), 1, 0.0), 0f)
    }
}
