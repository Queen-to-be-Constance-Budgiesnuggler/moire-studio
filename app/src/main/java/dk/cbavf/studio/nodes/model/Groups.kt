package dk.cbavf.studio.nodes.model

import kotlinx.serialization.Serializable

/**
 * Node groups (Blender's Ctrl+G): a graph used as one node. Its body is an ordinary [Graph] with a
 * Group Input node (the group's inputs, as outputs) and a Group Output node; the body's
 * [Graph.inputs] and [Graph.outputs] are the group's sockets. Definitions live on the top graph and
 * every Group node of that type shares one, so editing inside a group changes all its uses.
 */
@Serializable
data class NodeGroup(val id: String, val name: String, val graph: Graph)

/** One socket of a group's interface. Value sockets carry their field's default and range. */
@Serializable
data class GroupSocket(
    val name: String,
    val type: SocketType,
    val default: Float = 0f,
    val min: Float = 0f,
    val max: Float = 1f,
    val whole: Boolean = false,
) {
    val def: SocketDef get() = SocketDef(name, type, default, min, max, whole)

    companion object {
        fun of(d: SocketDef) = GroupSocket(d.name, d.type, d.default, d.min, d.max, d.whole)
    }
}

const val GROUP_IN = "groupin"
const val GROUP_OUT = "groupout"
const val GROUP_PREFIX = "group:"

/** A free socket on Group Input / Output: linking to it adds a socket to the group. */
private val NEW_SOCKET = SocketDef("+", SocketType.Any)

/** The standard nodes plus this project's groups, and (inside a group) its Group Input / Output. */
class GroupCatalog(
    override val groups: List<NodeGroup>,
    private val inputs: List<GroupSocket> = emptyList(),
    private val outputs: List<GroupSocket> = emptyList(),
) : Catalog(emptyList()) {
    override fun get(type: String): NodeDef? = when {
        type == GROUP_IN -> NodeDef(GROUP_IN, "Group Input", NodeCategory.Group, outputs = inputs.map { it.def } + NEW_SOCKET,
            help = "The group's inputs. Link from + to add one.")
        type == GROUP_OUT -> NodeDef(GROUP_OUT, "Group Output", NodeCategory.Group, inputs = outputs.map { it.def } + NEW_SOCKET, sink = true,
            help = "The group's outputs. Link into + to add one.")
        type.startsWith(GROUP_PREFIX) -> groups.firstOrNull { GROUP_PREFIX + it.id == type }?.let(::groupDef)
        else -> Catalog[type]
    }

    override val all: List<NodeDef> get() = Catalog.all + groups.map(::groupDef)

    private fun groupDef(g: NodeGroup) = NodeDef(
        GROUP_PREFIX + g.id, g.name, NodeCategory.Group,
        inputs = g.graph.inputs.map { it.def }, outputs = g.graph.outputs.map { it.def },
        help = "Node group (${g.graph.nodes.size - 2} nodes inside).",
    )
}

/** The catalog for this graph's nodes, given the project's [groups] (kept on the top graph). */
fun Graph.catalogWith(groups: List<NodeGroup>): Catalog = GroupCatalog(groups, inputs, outputs)

/** The catalog of a top-level graph. */
fun Graph.catalog(): Catalog = catalogWith(groups)

/** The graph being edited at [path] (group ids, outermost first); the top graph for an empty path. */
fun Graph.at(path: List<String>): Graph =
    path.lastOrNull()?.let { id -> groups.firstOrNull { it.id == id }?.graph } ?: this

/** Applies [f] to the graph at [path]. */
fun Graph.updateAt(path: List<String>, f: (Graph) -> Graph): Graph {
    val id = path.lastOrNull() ?: return f(this)
    return copy(groups = groups.map { if (it.id == id) it.copy(graph = f(it.graph)) else it })
}

/** Is group [id] used by any node, here or inside any group? */
fun Graph.groupUsed(id: String): Boolean =
    (nodes + groups.flatMap { it.graph.nodes }).any { it.type == GROUP_PREFIX + id }

/** Groups whose bodies (transitively) contain group [id]: adding them inside [id] would loop. */
fun Graph.groupsContaining(id: String): Set<String> {
    val result = HashSet<String>()
    fun uses(g: NodeGroup, seen: Set<String>): Boolean =
        g.id == id || g.graph.nodes.any { n ->
            val inner = n.type.removePrefix(GROUP_PREFIX)
            n.type.startsWith(GROUP_PREFIX) && inner !in seen && groups.firstOrNull { it.id == inner }?.let { uses(it, seen + g.id) } == true
        }
    groups.filter { uses(it, emptySet()) }.forEach { result += it.id }
    return result
}

/**
 * Ctrl+G: turns nodes [ids] of the graph at [path] into a new group and a Group node in their
 * place. Links crossing the selection become the group's inputs and outputs (one per outside
 * socket). Returns the new top graph and the Group node's id, or null if nothing to group.
 */
fun Graph.groupNodes(path: List<String>, ids: Set<Int>, nodeWidth: Float = 210f): Pair<Graph, Int>? {
    val current = at(path)
    val cat = current.catalogWith(groups)
    val sel = current.nodes.filter { it.id in ids && it.type != GROUP_IN && it.type != GROUP_OUT }
    if (sel.isEmpty()) return null
    val selIds = sel.map { it.id }.toSet()
    val minX = sel.minOf { it.x }
    val minY = sel.minOf { it.y }
    val maxX = sel.maxOf { it.x }
    val maxId = sel.maxOf { it.id }
    val gin = Node(maxId + 1, GROUP_IN, 0f, 40f)
    val gout = Node(maxId + 2, GROUP_OUT, maxX - minX + 260f + nodeWidth + 80f, 40f)
    val gid = "g" + ((groups.mapNotNull { it.id.removePrefix("g").toIntOrNull() }.maxOrNull() ?: 0) + 1)
    val group = Node(current.nextId, GROUP_PREFIX + gid, minX, minY)

    fun unique(name: String, taken: List<GroupSocket>): String {
        if (taken.none { it.name == name }) return name
        var k = 2
        while (taken.any { it.name == "$name $k" }) k++
        return "$name $k"
    }

    val inbound = current.links.filter { it.fromNode !in selIds && it.toNode in selIds }
    val inSources = inbound.map { it.fromNode to it.fromSocket }.distinct()
    val inputs = mutableListOf<GroupSocket>()
    for (src in inSources) {
        val first = inbound.first { (it.fromNode to it.fromSocket) == src }
        val d = cat[current.node(first.toNode)!!.type]?.inputs?.getOrNull(first.toSocket) ?: SocketDef("Input", SocketType.Any)
        inputs += GroupSocket.of(d).copy(name = unique(d.name, inputs))
    }
    val outbound = current.links.filter { it.fromNode in selIds && it.toNode !in selIds }
    val outSources = outbound.map { it.fromNode to it.fromSocket }.distinct()
    val outputs = mutableListOf<GroupSocket>()
    for ((node, socket) in outSources) {
        val d = cat[current.node(node)!!.type]?.outputs?.getOrNull(socket)
        val type = current.outputType(node, socket, cat) ?: SocketType.Any
        outputs += GroupSocket(unique(d?.name ?: "Output", outputs), type)
    }

    val body = Graph(
        nodes = listOf(gin) + sel.map { it.copy(x = it.x - minX + 260f, y = it.y - minY + 40f) } + gout,
        links = current.links.filter { it.fromNode in selIds && it.toNode in selIds } +
            inbound.map { Link(gin.id, inSources.indexOf(it.fromNode to it.fromSocket), it.toNode, it.toSocket) } +
            outSources.mapIndexed { k, (node, socket) -> Link(node, socket, gout.id, k) },
        inputs = inputs,
        outputs = outputs,
    )
    val outside = current.copy(
        nodes = current.nodes.filterNot { it.id in selIds } + group,
        links = current.links.filterNot { it.fromNode in selIds || it.toNode in selIds } +
            inSources.mapIndexed { k, (node, socket) -> Link(node, socket, group.id, k) } +
            outbound.map { Link(group.id, outSources.indexOf(it.fromNode to it.fromSocket), it.toNode, it.toSocket) },
    )
    // Replace the nodes first (at the top level that replaces the whole graph), then add the definition.
    val replaced = updateAt(path) { outside }
    return replaced.copy(groups = replaced.groups + NodeGroup(gid, "Group ${gid.removePrefix("g")}", body)) to group.id
}

/**
 * Ungroup (Blender's Ctrl+Alt+G): puts the body of Group node [id] (in the graph at [path]) back in
 * its place, rewiring through its sockets; field values set on the Group node go to the inner
 * fields they fed. The definition is dropped once nothing uses it. Returns the new nodes' ids.
 */
fun Graph.ungroup(path: List<String>, id: Int): Pair<Graph, Set<Int>> {
    val current = at(path)
    val n = current.node(id) ?: return this to emptySet()
    val gid = n.type.removePrefix(GROUP_PREFIX)
    val group = groups.firstOrNull { it.id == gid && n.type.startsWith(GROUP_PREFIX) } ?: return this to emptySet()
    val body = group.graph
    val bodyCat = body.catalogWith(groups)
    val gin = body.nodes.firstOrNull { it.type == GROUP_IN }?.id
    val gout = body.nodes.firstOrNull { it.type == GROUP_OUT }?.id
    val inner = body.nodes.filter { it.type != GROUP_IN && it.type != GROUP_OUT }
    if (inner.isEmpty()) return this to emptySet()
    var next = current.nextId
    val map = inner.associate { it.id to next++ }
    val dx = n.x - inner.minOf { it.x }
    val dy = n.y - inner.minOf { it.y }

    // Fields set on the Group node reach the inner inputs its Group Input fed.
    val fed = HashMap<Int, Map<String, Prop>>()
    body.links.filter { it.fromNode == gin }.forEach { l ->
        val socket = body.inputs.getOrNull(l.fromSocket) ?: return@forEach
        val v = n.props[socket.name] as? Prop.Num ?: return@forEach
        val name = bodyCat[body.node(l.toNode)!!.type]?.inputs?.getOrNull(l.toSocket)?.name ?: return@forEach
        fed[l.toNode] = (fed[l.toNode] ?: emptyMap()) + (name to v)
    }
    val copies = inner.map { it.copy(id = map.getValue(it.id), x = it.x + dx, y = it.y + dy, props = it.props + (fed[it.id] ?: emptyMap())) }

    val links = ArrayList(current.links.filterNot { it.fromNode == id || it.toNode == id })
    for (l in body.links) {
        val fromOutside = if (l.fromNode == gin) current.linkInto(id, l.fromSocket) else null
        val sources = when {
            l.fromNode == gin -> listOfNotNull(fromOutside?.let { it.fromNode to it.fromSocket })
            else -> listOf(map.getValue(l.fromNode) to l.fromSocket)
        }
        val targets = when {
            l.toNode == gout -> current.links.filter { it.fromNode == id && it.fromSocket == l.toSocket }.map { it.toNode to it.toSocket }
            else -> listOf(map.getValue(l.toNode) to l.toSocket)
        }
        for ((fn, fs) in sources) for ((tn, ts) in targets) links += Link(fn, fs, tn, ts)
    }
    val replaced = updateAt(path) { current.copy(nodes = current.nodes.filterNot { it.id == id } + copies, links = links) }
    val cleaned = if (replaced.groupUsed(gid)) replaced else replaced.copy(groups = replaced.groups.filterNot { it.id == gid })
    return cleaned to map.values.toSet()
}
