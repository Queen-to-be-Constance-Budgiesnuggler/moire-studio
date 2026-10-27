package dk.cbavf.studio.nodes.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Node Lab: the studio's pipelines as a node graph (docs/ARCHITECTURE.md §12).
 * Socket types are colour-coded as in Blender's node editors.
 */
enum class SocketType(val argb: Long) {
    Image(0xFFC7C729),   // yellow
    Canvas(0xFFE89A3C),  // orange
    Frames(0xFF4FB86A),  // green
    Value(0xFFA1A1A1),   // grey
    Signal(0xFF9B6BE8),  // violet
    Trigger(0xFFF2F2F2), // white
    /** On or off for a while (1 or 0): a Trigger is an instant, a Gate is held. */
    Gate(0xFF4FC3D9),    // cyan
    /** A reroute with nothing flowing in yet: takes on the type of what is linked into it. */
    Any(0xFF6E6E6E),
    ;

    /**
     * May a link carry [from] into this input? A canvas renders to an image on the way; signals
     * and triggers are numbers (a trigger is 1 on the frame it fires, else 0).
     */
    fun accepts(from: SocketType): Boolean =
        from == this || this == Any || from == Any ||
            (this == Image && from == Canvas) || (this == Value && (from == Signal || from == Trigger || from == Gate))
}

/** A node property that is not a socket (a choice, an asset, text). */
@Serializable
sealed interface Prop {
    @Serializable @SerialName("num")
    data class Num(val v: Float) : Prop

    @Serializable @SerialName("choice")
    data class Choice(val v: String) : Prop

    @Serializable @SerialName("text")
    data class Text(val v: String) : Prop
}

@Serializable
data class Node(
    val id: Int,
    val type: String,
    /** Position on the graph canvas (canvas units ≈ dp at zoom 1). */
    val x: Float,
    val y: Float,
    /** Values of unlinked Value inputs, and properties, by name. */
    val props: Map<String, Prop> = emptyMap(),
    /** Muted nodes pass their first matching input straight through (Blender's M). */
    val muted: Boolean = false,
    val collapsed: Boolean = false,
    val label: String? = null,
)

/** From an output socket (by index) to an input socket (by index). */
@Serializable
data class Link(val fromNode: Int, val fromSocket: Int, val toNode: Int, val toSocket: Int)

/** A labelled box around nodes (Blender's Frame), for organising big graphs. */
@Serializable
data class Frame(val id: Int, val label: String, val x: Float, val y: Float, val w: Float, val h: Float, val argb: Long = FRAME_COLORS[0]) {
    fun contains(n: Node, w: Float, h: Float) = n.x >= x && n.y >= y && n.x + w <= x + this.w && n.y + h <= y + this.h
}

/** Frame tints to cycle through (translucent, so the backdrop shows). */
val FRAME_COLORS = listOf(0x40707070L, 0x40A05050L, 0x4050A050L, 0x405070B0L, 0x40A0A040L, 0x40A050A0L)

@Serializable
data class Graph(
    val nodes: List<Node> = emptyList(),
    val links: List<Link> = emptyList(),
    val frames: List<Frame> = emptyList(),
    /** Node group definitions (top graph only). */
    val groups: List<NodeGroup> = emptyList(),
    /** When this graph is a group's body: the group's input and output sockets. */
    val inputs: List<GroupSocket> = emptyList(),
    val outputs: List<GroupSocket> = emptyList(),
) {
    val nextId: Int get() = maxOf(nodes.maxOfOrNull { it.id } ?: 0, frames.maxOfOrNull { it.id } ?: 0) + 1

    fun node(id: Int): Node? = nodes.firstOrNull { it.id == id }

    /** The link feeding an input, if any (an input takes one link). */
    fun linkInto(node: Int, socket: Int): Link? = links.firstOrNull { it.toNode == node && it.toSocket == socket }

    /** What an output carries; a reroute carries whatever flows into it. */
    fun outputType(id: Int, socket: Int, catalog: Catalog = this.catalog(), depth: Int = 0): SocketType? {
        val n = node(id) ?: return null
        if (n.type == REROUTE) {
            val l = linkInto(id, 0) ?: return SocketType.Any
            return if (depth > 64) SocketType.Any else outputType(l.fromNode, l.fromSocket, catalog, depth + 1)
        }
        return catalog[n.type]?.outputs?.getOrNull(socket)?.type
    }

    /**
     * Adds a link if the types fit, replacing whatever fed that input, and refusing links that
     * would make a cycle. Returns the graph unchanged otherwise.
     */
    fun connect(link: Link, catalog: Catalog = this.catalog()): Graph {
        val from = node(link.fromNode) ?: return this
        val to = node(link.toNode) ?: return this
        // Linking from Group Input's + (or into Group Output's +) adds a socket to the group.
        if (from.type == GROUP_IN && link.fromSocket == inputs.size && link.toNode != link.fromNode) {
            val d = catalog[to.type]?.inputs?.getOrNull(link.toSocket) ?: return this
            if (d.type == SocketType.Any) return this
            val name = generateSequence(d.name) { it + "+" }.first { n -> inputs.none { it.name == n } }
            val g = copy(inputs = inputs + GroupSocket.of(d).copy(name = name))
            return g.connect(link, GroupCatalog(catalog.groups, g.inputs, g.outputs))
        }
        if (to.type == GROUP_OUT && link.toSocket == outputs.size && link.toNode != link.fromNode) {
            val type = outputType(link.fromNode, link.fromSocket, catalog)?.takeIf { it != SocketType.Any } ?: return this
            val base = catalog[from.type]?.outputs?.getOrNull(link.fromSocket)?.name ?: "Output"
            val name = generateSequence(base) { it + "+" }.first { n -> outputs.none { it.name == n } }
            val g = copy(outputs = outputs + GroupSocket(name, type))
            return g.connect(link, GroupCatalog(catalog.groups, g.inputs, g.outputs))
        }
        val out = outputType(from.id, link.fromSocket, catalog) ?: return this
        val inp = catalog[to.type]?.inputs?.getOrNull(link.toSocket) ?: return this
        if (!inp.type.accepts(out) || link.fromNode == link.toNode) return this
        if (dependsOn(link.fromNode, link.toNode)) return this
        return copy(links = links.filterNot { it.toNode == link.toNode && it.toSocket == link.toSocket } + link)
    }

    /** True if [node] (transitively) takes input from [upstream]. */
    fun dependsOn(node: Int, upstream: Int): Boolean {
        val seen = HashSet<Int>()
        val stack = ArrayDeque(listOf(node))
        while (stack.isNotEmpty()) {
            val n = stack.removeLast()
            if (n == upstream) return true
            if (!seen.add(n)) continue
            links.filter { it.toNode == n }.forEach { stack.addLast(it.fromNode) }
        }
        return false
    }

    fun remove(ids: Set<Int>): Graph = copy(
        nodes = nodes.filterNot { it.id in ids },
        links = links.filterNot { it.fromNode in ids || it.toNode in ids },
    )

    /** Copies of [ids] offset by ([dx], [dy]), with the links between them (Blender's Shift+D). */
    fun duplicate(ids: Set<Int>, dx: Float, dy: Float): Pair<Graph, Set<Int>> {
        var next = nextId
        val map = nodes.filter { it.id in ids }.associate { it.id to next++ }
        val copies = nodes.filter { it.id in ids }.map { it.copy(id = map.getValue(it.id), x = it.x + dx, y = it.y + dy) }
        val inner = links.filter { it.fromNode in ids && it.toNode in ids }
            .map { it.copy(fromNode = map.getValue(it.fromNode), toNode = map.getValue(it.toNode)) }
        return copy(nodes = nodes + copies, links = links + inner) to map.values.toSet()
    }

    /**
     * Node Wrangler's lazy connect: the first output of [from] that some free (or any) input of
     * [to] accepts, preferring matching types.
     */
    fun lazyConnect(from: Int, to: Int, catalog: Catalog = this.catalog()): Graph {
        val a = node(from)?.let { catalog[it.type] } ?: return this
        val b = node(to)?.let { catalog[it.type] } ?: return this
        val pairs = a.outputs.indices.flatMap { o -> b.inputs.indices.map { i -> o to i } }
            .filter { (o, i) -> b.inputs[i].type.accepts(outputType(from, o, catalog) ?: return@filter false) }
            .sortedWith(compareBy({ (o, i) -> if (outputType(from, o, catalog) == b.inputs[i].type) 0 else 1 }, { (_, i) -> if (linkInto(to, i) == null) 0 else 1 }))
        val (o, i) = pairs.firstOrNull() ?: return this
        return connect(Link(from, o, to, i), catalog)
    }

    /** Node Wrangler's lazy mix: a new Mix node at ([x], [y]) taking [a] as A and [b] as B. */
    fun lazyMix(a: Int, b: Int, x: Float, y: Float, catalog: Catalog = this.catalog()): Pair<Graph, Int?> {
        fun imageOut(id: Int): Int? = catalog[node(id)?.type ?: ""]?.outputs?.indices?.firstOrNull { o ->
            outputType(id, o, catalog) in setOf(SocketType.Image, SocketType.Canvas, SocketType.Any)
        }
        if (a == b) return this to null
        val oa = imageOut(a) ?: return this to null
        val ob = imageOut(b) ?: return this to null
        val mix = Node(nextId, "mix", x, y, mapOf("Blend" to Prop.Choice("Normal")))
        val g = copy(nodes = nodes + mix).connect(Link(a, oa, mix.id, 0), catalog).connect(Link(b, ob, mix.id, 1), catalog)
        return g to mix.id
    }

    /**
     * Node Wrangler's swap links: exchanges what feeds the first two inputs of the same type that
     * have a link between them (Mix A ↔ B, Layer Canvases A ↔ B).
     */
    fun swapInputs(id: Int, catalog: Catalog = this.catalog()): Graph {
        val def = catalog[node(id)?.type ?: return this] ?: return this
        for (i in def.inputs.indices) for (j in i + 1 until def.inputs.size) {
            if (def.inputs[i].type != def.inputs[j].type) continue
            val li = linkInto(id, i)
            val lj = linkInto(id, j)
            if (li == null && lj == null) continue
            val rest = links.filterNot { it == li || it == lj }
            return copy(links = rest + listOfNotNull(li?.copy(toSocket = j), lj?.copy(toSocket = i)))
        }
        return this
    }

    /** Can [id] take a swap (see [swapInputs])? */
    fun canSwap(id: Int, catalog: Catalog = this.catalog()): Boolean = swapInputs(id, catalog) != this

    /** Puts a reroute dot on [link] at ([x], [y]): from → reroute → to. */
    fun insertReroute(link: Link, x: Float, y: Float): Pair<Graph, Int> {
        val r = Node(nextId, REROUTE, x, y)
        return copy(nodes = nodes + r, links = links - link + Link(link.fromNode, link.fromSocket, r.id, 0) + Link(r.id, 0, link.toNode, link.toSocket)) to r.id
    }

    /**
     * Drops an unlinked node onto [link] (Blender's auto-insert): from → node's best input, node's
     * best output → to. Unchanged if the node does not fit.
     */
    fun insertOnLink(id: Int, link: Link, catalog: Catalog = this.catalog()): Graph {
        if (links.any { it.fromNode == id || it.toNode == id } || link.fromNode == id || link.toNode == id) return this
        val def = catalog[node(id)?.type ?: return this] ?: return this
        val fromType = outputType(link.fromNode, link.fromSocket, catalog) ?: return this
        val toDef = catalog[node(link.toNode)?.type ?: return this]?.inputs?.getOrNull(link.toSocket) ?: return this
        val i = def.inputs.indices.sortedBy { if (def.inputs[it].type == fromType) 0 else 1 }.firstOrNull { def.inputs[it].type.accepts(fromType) } ?: return this
        val o = def.outputs.indices.sortedBy { if (outputType(id, it, catalog) == toDef.type) 0 else 1 }
            .firstOrNull { toDef.type.accepts(outputType(id, it, catalog) ?: return@firstOrNull false) } ?: return this
        return copy(links = links - link).connect(Link(link.fromNode, link.fromSocket, id, i), catalog).connect(Link(id, o, link.toNode, link.toSocket), catalog)
    }

    /**
     * Node Wrangler's align: lines the nodes up in a row (if they spread wider than tall) or a
     * column, in their current order, [gap] apart. [size] gives a node's width and height.
     */
    fun align(ids: Set<Int>, gap: Float, size: (Node) -> Pair<Float, Float>): Graph {
        val sel = nodes.filter { it.id in ids }
        if (sel.size < 2) return this
        val row = sel.maxOf { it.x } - sel.minOf { it.x } >= sel.maxOf { it.y } - sel.minOf { it.y }
        val moved = HashMap<Int, Node>()
        if (row) {
            val y = sel.minOf { it.y }
            var x = sel.minOf { it.x }
            for (n in sel.sortedBy { it.x }) { moved[n.id] = n.copy(x = x, y = y); x += size(n).first + gap }
        } else {
            val x = sel.minOf { it.x }
            var y = sel.minOf { it.y }
            for (n in sel.sortedBy { it.y }) { moved[n.id] = n.copy(x = x, y = y); y += size(n).second + gap }
        }
        return copy(nodes = nodes.map { moved[it.id] ?: it })
    }

    companion object {
        const val REROUTE = "reroute"
    }
}

/** A saved graph with a name; a [group] preset holds one Group node, to add into a graph. */
@Serializable
data class GraphPreset(val name: String, val graph: Graph, val group: Boolean = false)
