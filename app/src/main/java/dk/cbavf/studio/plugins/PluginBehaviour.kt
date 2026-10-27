package dk.cbavf.studio.plugins

import dk.cbavf.studio.library.CanvasDoc
import dk.cbavf.studio.nodes.model.Catalog
import dk.cbavf.studio.nodes.model.Evaluator
import dk.cbavf.studio.nodes.model.GROUP_IN
import dk.cbavf.studio.nodes.model.GROUP_OUT
import dk.cbavf.studio.nodes.model.Graph
import dk.cbavf.studio.nodes.model.GroupOuter
import dk.cbavf.studio.nodes.model.GroupSocket
import dk.cbavf.studio.nodes.model.ImageBackend
import dk.cbavf.studio.nodes.model.Link
import dk.cbavf.studio.nodes.model.Node
import dk.cbavf.studio.nodes.model.NodeDef
import dk.cbavf.studio.nodes.model.NodeGroup
import dk.cbavf.studio.nodes.model.NodeValue
import dk.cbavf.studio.nodes.model.Prop
import dk.cbavf.studio.nodes.model.SocketType
import dk.cbavf.studio.nodes.model.TimeEnv
import dk.cbavf.studio.nodes.model.catalog
import dk.cbavf.studio.nodes.model.catalogWith
import dk.cbavf.studio.nodes.model.removeGroupSocket
import dk.cbavf.studio.uieditor.ControlKind

/**
 * A plugin's behaviour: what its controls do, as a Node Lab graph shaped like a group's body. Its Group Input (node [IN]) has
 * one socket per control (a slider is a Value over its range, a toggle a Gate, a button a Trigger); its Group Output (node
 * [OUT]) has the plugin's outputs, numbers that show in the Plugins room and on the module's back, where cables can read them.
 * In between are steps: Node Lab's number nodes only (no pictures, no sensors), so a plugin stays data and stays safe.
 */
object PluginBehaviour {
    const val IN = 1
    const val OUT = 2
    const val MAX_STEPS = 48

    /** The node types a step may be: numbers in, numbers out. */
    val STEP_TYPES = listOf(
        "value", "math", "maprange", "switch", "smooth", "clock", "lfo", "fmam", "hold",
        "trig2gate", "gate2trig", "gatelogic", "gatedelay", "gatedivide", "gateprob", "envelope",
    )

    fun stepDefs(): List<NodeDef> = STEP_TYPES.mapNotNull { Catalog[it] }

    /** The socket a control has on the Group Input; null for one that is not a number (an envelope). */
    fun controlSocket(w: PluginWidget): GroupSocket? = when (w.kind) {
        ControlKind.Slider -> GroupSocket(w.label, SocketType.Value, w.min, w.min, maxOf(w.max, w.min))
        ControlKind.Toggle -> GroupSocket(w.label, SocketType.Gate)
        ControlKind.Button -> GroupSocket(w.label, SocketType.Trigger)
        ControlKind.Envelope -> null
    }

    /** The controls that feed the graph, in Group Input order. */
    fun controls(widgets: List<PluginWidget>): List<PluginWidget> = widgets.filter { controlSocket(it) != null }

    /** The plugin's outputs (none without a behaviour). */
    fun outputs(m: PluginManifest): List<GroupSocket> = m.behaviour?.outputs.orEmpty()

    /** How an output is labelled next to the controls (on the module's back and in the UI Editor). */
    fun outputLabel(name: String) = "$name (out)"

    /** A new behaviour for [widgets]: the controls on the left, no steps, no outputs yet. */
    fun starter(widgets: List<PluginWidget>): Graph = sync(widgets, Graph())

    /**
     * [g] fitted to [widgets] and cleaned: the Group Input's sockets are the controls again (a link follows its control by
     * name; a removed control's links go), there is one Group Input ([IN]) and one Group Output ([OUT]), only step types are
     * kept ([MAX_STEPS] at most), outputs are numbers with names of their own, and links that do not fit are dropped.
     */
    fun sync(widgets: List<PluginWidget>, g: Graph): Graph {
        val inputs = controls(widgets).map { controlSocket(it)!! }
        val outputs = ArrayList<GroupSocket>()
        for (o in g.outputs) {
            val name = generateSequence(o.name.ifBlank { "Out" }) { "$it+" }.first { n -> outputs.none { it.name == n } }
            outputs += o.copy(name = name, type = SocketType.Value)
        }
        val oldIn = g.nodes.firstOrNull { it.type == GROUP_IN }
        val oldOut = g.nodes.firstOrNull { it.type == GROUP_OUT }
        val steps = g.nodes.filter { it.type in STEP_TYPES }.take(MAX_STEPS)

        // Group Input and Output take ids 1 and 2; a step keeps its id unless that is taken.
        val ids = HashMap<Int, Int>()
        oldIn?.let { ids[it.id] = IN }
        oldOut?.let { ids[it.id] = OUT }
        val used = hashSetOf(IN, OUT)
        var next = maxOf(3, (steps.maxOfOrNull { it.id } ?: 0) + 1)
        val placed = steps.map { s ->
            val id = if (s.id !in used && s.id !in ids) s.id else next++
            used += id
            ids[s.id] = id
            s.copy(id = id)
        }
        val gin = Node(IN, GROUP_IN, oldIn?.x ?: 0f, oldIn?.y ?: 40f)
        val gout = Node(OUT, GROUP_OUT, oldOut?.x ?: 560f, oldOut?.y ?: 40f, oldOut?.props.orEmpty())
        var out = Graph(nodes = listOf(gin) + placed + gout, inputs = inputs, outputs = outputs)

        for (l in g.links) {
            val from = ids[l.fromNode] ?: continue
            val to = ids[l.toNode] ?: continue
            val fromSocket = if (from == IN) {
                val name = g.inputs.getOrNull(l.fromSocket)?.name ?: continue
                inputs.indexOfFirst { it.name == name }.takeIf { it >= 0 } ?: continue
            } else l.fromSocket
            if (to == OUT && l.toSocket >= outputs.size) continue
            out = out.connect(Link(from, fromSocket, to, l.toSocket))
        }
        return out
    }

    /** The steps, in the order they were added. */
    fun steps(g: Graph): List<Node> = g.nodes.filter { it.type in STEP_TYPES }.sortedBy { it.id }

    /** Adds a step of [type] below the others; the graph and the new step's id (null if it may not be added). */
    fun addStep(g: Graph, type: String): Pair<Graph, Int?> {
        val count = steps(g).size
        if (type !in STEP_TYPES || count >= MAX_STEPS) return g to null
        val n = Node(maxOf(g.nextId, 3), type, 280f, 40f + 160f * count)
        return g.copy(nodes = g.nodes + n) to n.id
    }

    fun removeStep(g: Graph, id: Int): Graph = if (id == IN || id == OUT) g else g.remove(setOf(id))

    /** Adds an output called [name] (made unique), running from [min] to [max] for the cables. */
    fun addOutput(g: Graph, name: String, min: Float = 0f, max: Float = 1f): Graph {
        val base = name.trim().ifBlank { "Out" }
        val unique = generateSequence(base) { "$it+" }.first { n -> g.outputs.none { it.name == n } }
        return g.copy(outputs = g.outputs + GroupSocket(unique, SocketType.Value, 0f, min, maxOf(max, min + 1e-3f)))
    }

    /** Removes output [index] and the link into it. */
    fun removeOutput(g: Graph, index: Int): Graph {
        val wrapped = Graph(groups = listOf(NodeGroup(BODY, "", g)))
        return wrapped.removeGroupSocket(BODY, true, index).groups.first().graph
    }

    /** Somewhere a step's input (or an output) can take its value from. */
    data class Source(val node: Int, val socket: Int, val label: String)

    /** What can feed input [toSocket] of node [toNode]: the controls and the other steps' outputs that fit, without a loop. */
    fun sources(g: Graph, toNode: Int, toSocket: Int): List<Source> {
        val cat = g.catalog()
        val target = g.node(toNode) ?: return emptyList()
        val type = cat[target.type]?.inputs?.getOrNull(toSocket)?.type ?: return emptyList()
        val out = ArrayList<Source>()
        g.inputs.forEachIndexed { i, s -> if (type.accepts(s.type)) out += Source(IN, i, s.name) }
        for (n in steps(g)) {
            if (n.id == toNode || g.dependsOn(n.id, toNode)) continue
            val def = cat[n.type] ?: continue
            def.outputs.forEachIndexed { i, s ->
                if (type.accepts(s.type)) out += Source(n.id, i, stepName(g, n) + if (def.outputs.size > 1) " · ${s.name}" else "")
            }
        }
        return out
    }

    /** What feeds input [toSocket] of [toNode] now, or null if it uses its own number. */
    fun sourceOf(g: Graph, toNode: Int, toSocket: Int): Source? {
        val l = g.linkInto(toNode, toSocket) ?: return null
        return sources(g, toNode, toSocket).firstOrNull { it.node == l.fromNode && it.socket == l.fromSocket }
            ?: Source(l.fromNode, l.fromSocket, "?")
    }

    /** "LFO 2": a step's kind and its place among the steps. */
    fun stepName(g: Graph, n: Node): String = (Catalog[n.type]?.label ?: n.type) + " " + (steps(g).indexOfFirst { it.id == n.id } + 1)

    fun link(g: Graph, from: Source, toNode: Int, toSocket: Int): Graph = g.connect(Link(from.node, from.socket, toNode, toSocket))

    fun unlink(g: Graph, toNode: Int, toSocket: Int): Graph = g.copy(links = g.links.filterNot { it.toNode == toNode && it.toSocket == toSocket })

    /** Sets a property of node [id]: an unlinked input's own number, or a choice. */
    fun setProp(g: Graph, id: Int, name: String, p: Prop): Graph = g.copy(nodes = g.nodes.map { if (it.id == id) it.copy(props = it.props + (name to p)) else it })

    private const val BODY = "behaviour"
}

/**
 * Runs one plugin's behaviour from frame to frame, keeping what its steps remember (smoothing, gates, envelopes, held values).
 * Numbers only, so a step is cheap.
 */
class BehaviourRun {
    private val state = HashMap<String, Float>()
    private val objects = HashMap<String, Any>()

    /** The outputs at [t] seconds ([dt] since the last step), from the controls' [values] in Group Input order. */
    fun step(g: Graph, values: List<Float>, t: Double, dt: Float): List<Float> {
        val out = g.node(PluginBehaviour.OUT) ?: return g.outputs.map { 0f }
        val ev = Evaluator(
            graph = g, backend = NoImages, maxPx = 0, catalog = g.catalogWith(emptyList()),
            env = TimeEnv(t, dt, state = state, objects = objects), groups = emptyList(),
            outer = GroupOuter({ i -> NodeValue.Num(values.getOrElse(i) { 0f }) }, { i -> "c$i" }),
        )
        return g.outputs.indices.map { i ->
            val v = (ev.input(out, i) as? NodeValue.Num)?.v ?: 0f
            if (v.isFinite()) v else 0f
        }
    }

    /** A behaviour has no pictures: nothing here is ever asked for. */
    private object NoImages : ImageBackend {
        override fun libraryImage(assetId: String, maxPx: Int): NodeValue.Img? = null
        override fun drawCanvas(): CanvasDoc? = null
        override fun render(doc: CanvasDoc, width: Int, height: Int, paper: Boolean, maxPx: Int) = NodeValue.Img(Unit, 0, 0)
        override fun process(type: String, inputs: List<NodeValue?>, nums: List<Float>, props: Map<String, String>, maxPx: Int): NodeValue.Img? = null
    }
}
