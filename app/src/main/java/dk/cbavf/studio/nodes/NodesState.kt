package dk.cbavf.studio.nodes

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dk.cbavf.studio.nodes.model.Catalog
import dk.cbavf.studio.nodes.model.FRAME_COLORS
import dk.cbavf.studio.nodes.model.Frame
import dk.cbavf.studio.nodes.model.GROUP_IN
import dk.cbavf.studio.nodes.model.GROUP_OUT
import dk.cbavf.studio.nodes.model.GROUP_PREFIX
import dk.cbavf.studio.nodes.model.Graph
import dk.cbavf.studio.nodes.model.GraphPreset
import dk.cbavf.studio.nodes.model.Link
import dk.cbavf.studio.nodes.model.Node
import dk.cbavf.studio.nodes.model.NodeGroup
import dk.cbavf.studio.nodes.model.Prop
import dk.cbavf.studio.nodes.model.SocketType
import dk.cbavf.studio.nodes.model.at
import dk.cbavf.studio.nodes.model.catalogWith
import dk.cbavf.studio.nodes.model.groupNodes
import dk.cbavf.studio.nodes.model.groupPreset
import dk.cbavf.studio.nodes.model.groupsContaining
import dk.cbavf.studio.nodes.model.insertGroupPreset
import dk.cbavf.studio.nodes.model.moveGroupSocket
import dk.cbavf.studio.nodes.model.removeGroupSocket
import dk.cbavf.studio.nodes.model.ungroup
import dk.cbavf.studio.nodes.model.updateAt
import dk.cbavf.studio.ui.Undoable

/** One-finger tools on the graph canvas (Blender: select/move, box select, knife; Node Wrangler lazy connect/mix). */
enum class NodeTool(val label: String) {
    Select("Select"),
    Box("Box select"),
    Link("Lazy connect"),
    Mix("Lazy mix"),
    Cut("Cut links"),
    Reroute("Add reroutes"),
}

/**
 * Node Lab editor state: the project graph (undoable), the group being edited, selection,
 * view and presets. [top] is the whole project; [graph] is what the canvas shows (the top graph,
 * or the body of the group opened with Tab).
 */
class NodesState {
    val edit = Undoable(Graph())
    val top: Graph get() = edit.doc

    /** Groups opened, outermost first (Blender's Tab into a group). */
    var path by mutableStateOf(listOf<String>())
        private set
    val graph: Graph get() = top.at(path)
    val catalog: Catalog get() = graph.catalogWith(top.groups)
    val openGroup: NodeGroup? get() = path.lastOrNull()?.let { id -> top.groups.firstOrNull { it.id == id } }

    var selected by mutableStateOf(setOf<Int>())
    var selectedFrame by mutableStateOf<Int?>(null)
    /** Node shown on the backdrop (Node Wrangler's preview, top level); null = the first Viewer. */
    var previewId by mutableStateOf<Int?>(null)
    var tool by mutableStateOf(NodeTool.Select)
    var backdrop by mutableStateOf(true)
    var zoom by mutableFloatStateOf(1f)
    var panX by mutableFloatStateOf(40f)
    var panY by mutableFloatStateOf(40f)
    var presets by mutableStateOf<List<GraphPreset>>(emptyList())
    /** Touch Pad nodes (top graph) with a finger on their pad now: their Down gate. Not saved. */
    var touching by mutableStateOf(setOf<Int>())

    /** What the backdrop shows (always the top graph): the chosen node, or what the first Viewer receives. */
    val previewTarget: Int?
        get() = previewId?.takeIf { top.node(it) != null } ?: top.nodes.firstOrNull { it.type == "viewer" }?.id

    /** An edit of the graph on the canvas (inside the open group, if any). */
    fun change(key: Any? = null, f: (Graph) -> Graph) = edit.edit(key) { it.updateAt(path, f) }

    fun add(type: String, x: Float, y: Float, props: Map<String, Prop> = emptyMap()): Node? {
        val def = catalog[type] ?: return null
        // A group cannot go inside itself.
        if (type.startsWith(GROUP_PREFIX) && path.isNotEmpty() && type.removePrefix(GROUP_PREFIX) in path.flatMap { top.groupsContaining(it) }) return null
        val defaults = def.props.filter { it.options.isNotEmpty() }.associate { it.name to (Prop.Choice(it.default) as Prop) }
        val node = Node(graph.nextId, type, x, y, defaults + props)
        change { it.copy(nodes = it.nodes + node) }
        selected = setOf(node.id)
        return node
    }

    /** Node types that may be added here (no group inside itself, no second Group Input/Output). */
    fun addable(type: String): Boolean = when {
        type == GROUP_IN || type == GROUP_OUT -> false
        type.startsWith(GROUP_PREFIX) -> path.isEmpty() || type.removePrefix(GROUP_PREFIX) !in path.flatMap { top.groupsContaining(it) }
        else -> true
    }

    fun move(ids: Set<Int>, dx: Float, dy: Float, key: Any) = change(key) { g ->
        g.copy(nodes = g.nodes.map { if (it.id in ids) it.copy(x = it.x + dx, y = it.y + dy) else it })
    }

    fun setProp(id: Int, name: String, value: Prop, key: Any? = null) = change(key) { g ->
        g.copy(nodes = g.nodes.map { if (it.id == id) it.copy(props = it.props + (name to value)) else it })
    }

    /** Sets several properties of one node as a single change (one undo step per [key]). */
    fun setProps(id: Int, values: Map<String, Prop>, key: Any? = null) = change(key) { g ->
        g.copy(nodes = g.nodes.map { if (it.id == id) it.copy(props = it.props + values) else it })
    }

    fun connect(link: Link) = change { it.connect(link, catalog) }

    /** Picks up the link into an input (Blender: drag it off the socket); returns it. */
    fun detach(node: Int, socket: Int, key: Any): Link? {
        val l = graph.linkInto(node, socket) ?: return null
        change(key) { g -> g.copy(links = g.links - l) }
        return l
    }

    fun lazyConnect(from: Int, to: Int) = change { it.lazyConnect(from, to, catalog) }

    fun cut(links: Set<Link>) {
        if (links.isEmpty()) return
        change { g -> g.copy(links = g.links - links) }
    }

    /** Node Wrangler: drag from [a] to [b] adds a Mix of the two at ([x], [y]). */
    fun lazyMix(a: Int, b: Int, x: Float, y: Float) {
        var id: Int? = null
        change { g -> g.lazyMix(a, b, x, y, catalog).also { id = it.second }.first }
        id?.let { selected = setOf(it) }
    }

    /** Swaps the A/B links of every selected node that has a pair. */
    fun swapSelected() = change { g -> selected.fold(g) { acc, id -> acc.swapInputs(id, catalog) } }

    /** Reroute dots where a swipe crossed links (one per link, at the crossing). */
    fun insertReroutes(at: List<Pair<Link, Pair<Float, Float>>>) {
        if (at.isEmpty()) return
        change { g -> at.fold(g) { acc, (l, p) -> acc.insertReroute(l, p.first, p.second).first } }
    }

    fun insertOnLink(id: Int, link: Link) = change { it.insertOnLink(id, link, catalog) }

    fun alignSelected(gap: Float, size: (Node) -> Pair<Float, Float>) = change { it.align(selected, gap, size) }

    fun deleteSelected() {
        // Group Input / Output stay: they are the group's edges.
        val ids = selected.filter { id -> graph.node(id)?.type.let { it != GROUP_IN && it != GROUP_OUT } }.toSet()
        if (ids.isEmpty()) return
        change { it.remove(ids) }
        selected = emptySet()
    }

    fun duplicateSelected() {
        val ids0 = selected.filter { id -> graph.node(id)?.type.let { it != GROUP_IN && it != GROUP_OUT } }.toSet()
        if (ids0.isEmpty()) return
        var ids = emptySet<Int>()
        change { g -> g.duplicate(ids0, 40f, 40f).also { ids = it.second }.first }
        selected = ids
    }

    fun toggle(ids: Set<Int>, f: (Node) -> Node) = change { g -> g.copy(nodes = g.nodes.map { if (it.id in ids) f(it) else it }) }

    fun rename(id: Int, label: String?) = toggle(setOf(id)) { it.copy(label = label?.takeIf { l -> l.isNotBlank() }) }

    // --- Groups ---------------------------------------------------------------------------------

    /** Ctrl+G: the selection becomes a group, with a Group node in its place. */
    fun groupSelected(nodeWidth: Float) {
        var id: Int? = null
        edit.edit { t -> t.groupNodes(path, selected, nodeWidth)?.also { id = it.second }?.first ?: t }
        id?.let { selected = setOf(it) }
    }

    /** Ctrl+Alt+G: every selected Group node is replaced by its contents. */
    fun ungroupSelected() {
        val groupsSel = selected.filter { graph.node(it)?.type?.startsWith(GROUP_PREFIX) == true }
        if (groupsSel.isEmpty()) return
        var ids = emptySet<Int>()
        edit.edit { t -> groupsSel.fold(t) { acc, id -> acc.ungroup(path, id).also { ids = ids + it.second }.first } }
        selected = ids
    }

    /** Tab: open the selected Group node's body. */
    fun enterGroup(id: Int) {
        val type = graph.node(id)?.type ?: return
        if (!type.startsWith(GROUP_PREFIX)) return
        val gid = type.removePrefix(GROUP_PREFIX)
        if (top.groups.none { it.id == gid }) return
        path = path + gid
        selected = emptySet()
        selectedFrame = null
    }

    fun exitGroup() {
        if (path.isEmpty()) return
        path = path.dropLast(1)
        selected = emptySet()
        selectedFrame = null
    }

    fun renameGroup(name: String) {
        val gid = path.lastOrNull() ?: return
        if (name.isBlank()) return
        edit.edit { t -> t.copy(groups = t.groups.map { if (it.id == gid) it.copy(name = name.trim()) else it }) }
    }

    /** Removes socket [index] of the open group's inputs (or outputs), with its links on every use. */
    fun removeGroupSocket(output: Boolean, index: Int) {
        val gid = path.lastOrNull() ?: return
        edit.edit { it.removeGroupSocket(gid, output, index) }
    }

    /** Moves socket [from] of the open group's inputs (or outputs) to place [to]; links follow. */
    fun moveGroupSocket(output: Boolean, from: Int, to: Int) {
        val gid = path.lastOrNull() ?: return
        edit.edit { it.moveGroupSocket(gid, output, from, to) }
    }

    /** Saved group presets (the other presets are whole graphs). */
    val groupPresets: List<GraphPreset> get() = presets.filter { it.group }

    /** Saves the open group as group preset [name], replacing a group preset of that name. */
    fun saveGroupPreset(name: String): Boolean {
        val gid = path.lastOrNull() ?: return false
        val p = top.groupPreset(gid, name.trim().ifBlank { return false }) ?: return false
        presets = presets.filterNot { it.group && it.name == p.name } + p
        return true
    }

    /** Adds group preset [p] here as a new Group node at ([x], [y]), with its own copy of the group. */
    fun addGroupPreset(p: GraphPreset, x: Float, y: Float) {
        var id: Int? = null
        edit.edit { t -> t.insertGroupPreset(path, p, x, y)?.also { id = it.second }?.first ?: t }
        id?.let { selected = setOf(it) }
    }

    // --- Frames ---------------------------------------------------------------------------------

    /** Ctrl+J: a labelled frame around the selected nodes. */
    fun frameSelected(size: (Node) -> Pair<Float, Float>) {
        val sel = graph.nodes.filter { it.id in selected }
        if (sel.isEmpty()) return
        val minX = sel.minOf { it.x } - FRAME_PAD
        val minY = sel.minOf { it.y } - FRAME_PAD - FRAME_HEADER
        val maxX = sel.maxOf { it.x + size(it).first } + FRAME_PAD
        val maxY = sel.maxOf { it.y + size(it).second } + FRAME_PAD
        val f = Frame(graph.nextId, "Frame", minX, minY, maxX - minX, maxY - minY)
        change { it.copy(frames = it.frames + f) }
        selectedFrame = f.id
        selected = emptySet()
    }

    /** Moves a frame and the nodes [inside] it. */
    fun moveFrame(id: Int, inside: Set<Int>, dx: Float, dy: Float, key: Any) = change(key) { g ->
        g.copy(
            frames = g.frames.map { if (it.id == id) it.copy(x = it.x + dx, y = it.y + dy) else it },
            nodes = g.nodes.map { if (it.id in inside) it.copy(x = it.x + dx, y = it.y + dy) else it },
        )
    }

    fun resizeFrame(id: Int, dw: Float, dh: Float, key: Any) = change(key) { g ->
        g.copy(frames = g.frames.map { if (it.id == id) it.copy(w = (it.w + dw).coerceAtLeast(120f), h = (it.h + dh).coerceAtLeast(80f)) else it })
    }

    fun updateFrame(id: Int, f: (Frame) -> Frame) = change { g -> g.copy(frames = g.frames.map { if (it.id == id) f(it) else it }) }

    fun nextFrameColor(id: Int) = updateFrame(id) { it.copy(argb = FRAME_COLORS[(FRAME_COLORS.indexOf(it.argb) + 1).mod(FRAME_COLORS.size)]) }

    /** Removes the frame only; its nodes stay. */
    fun deleteFrame(id: Int) {
        change { g -> g.copy(frames = g.frames.filterNot { it.id == id }) }
        selectedFrame = null
    }

    /** Replaces the graph as one undoable step (Undo brings the previous graph back). */
    fun replace(g: Graph) {
        edit.edit { g }
        reset()
    }

    fun load(g: Graph) {
        edit.open(g)
        reset()
    }

    private fun reset() {
        path = emptyList()
        selected = emptySet()
        selectedFrame = null
        previewId = null
    }

    /** After Undo / Redo: leave a group whose definition is gone. */
    fun undo() {
        edit.undo()
        tidy()
    }

    fun redo() {
        edit.redo()
        tidy()
    }

    private fun tidy() {
        if (path.any { id -> top.groups.none { it.id == id } }) path = emptyList()
        selected = selected.filter { graph.node(it) != null }.toSet()
        if (selectedFrame != null && graph.frames.none { it.id == selectedFrame }) selectedFrame = null
    }

    /** Node Wrangler: batch-change a choice on every selected node that has it. */
    fun setForSelected(name: String, value: String) = change { g ->
        g.copy(nodes = g.nodes.map { n -> if (n.id in selected && catalog[n.type]?.props?.any { it.name == name } == true) n.copy(props = n.props + (name to Prop.Choice(value))) else n })
    }

    companion object {
        const val FRAME_PAD = 20f
        const val FRAME_HEADER = 30f

        /** Ready-made pipelines, also the examples of what a graph can do. */
        fun builtInPresets(): List<GraphPreset> = listOf(
            GraphPreset("Grating over photo", Graph(
                nodes = listOf(
                    Node(1, "image", 0f, 0f),
                    Node(2, "grating", 0f, 260f, mapOf("Profile" to Prop.Choice("Lines"))),
                    Node(3, "mix", 260f, 60f, mapOf("Blend" to Prop.Choice("Multiply"))),
                    Node(4, "develop", 520f, 60f),
                    Node(5, "viewer", 780f, 60f),
                ),
                links = listOf(Link(1, 0, 3, 0), Link(2, 0, 3, 1), Link(3, 0, 4, 0), Link(4, 0, 5, 0)),
            )),
            GraphPreset("Moiré pair (difference)", Graph(
                nodes = listOf(
                    Node(1, "grating", 0f, 0f),
                    Node(2, "grating", 0f, 240f, mapOf("Spacing" to Prop.Num(10.6f), "Angle" to Prop.Num(5f))),
                    Node(3, "render", 260f, 0f, mapOf("Paper" to Prop.Choice("White"))),
                    Node(4, "render", 260f, 240f, mapOf("Paper" to Prop.Choice("White"))),
                    Node(5, "mix", 520f, 100f, mapOf("Blend" to Prop.Choice("Difference"))),
                    Node(6, "viewer", 780f, 100f),
                ),
                links = listOf(Link(1, 0, 3, 0), Link(2, 0, 4, 0), Link(3, 0, 5, 0), Link(4, 0, 5, 1), Link(5, 0, 6, 0)),
            )),
            GraphPreset("FFT texture", Graph(
                nodes = listOf(
                    Node(1, "image", 0f, 0f),
                    Node(2, "fft", 260f, 0f, mapOf("Filter" to Prop.Choice("BandPass"))),
                    Node(3, "posterize", 520f, 0f),
                    Node(4, "viewer", 780f, 0f),
                ),
                links = listOf(Link(1, 0, 2, 0), Link(2, 0, 3, 0), Link(3, 0, 4, 0)),
            )),
            GraphPreset("Glitch screen blend", Graph(
                nodes = listOf(
                    Node(1, "image", 0f, 60f),
                    Node(2, "glitch", 260f, 0f, mapOf("Bytes/px" to Prop.Choice("3"))),
                    Node(3, "mix", 520f, 60f, mapOf("Blend" to Prop.Choice("Screen"), "Factor" to Prop.Num(0.7f))),
                    Node(4, "viewer", 780f, 60f),
                ),
                links = listOf(Link(1, 0, 2, 0), Link(1, 0, 3, 0), Link(2, 0, 3, 1), Link(3, 0, 4, 0)),
            )),
            // Live presets (press ▶ Live): the Patch bay's routes as graphs.
            GraphPreset("Live: LFO sweeps a moiré (record it)", Graph(
                nodes = listOf(
                    Node(1, "grating", 0f, 0f),
                    Node(3, "lfo", 0f, 220f, mapOf("Rate Hz" to Prop.Num(0.1f), "Min" to Prop.Num(-6f), "Max" to Prop.Num(6f))),
                    Node(2, "grating", 260f, 220f),
                    Node(4, "stack", 520f, 60f, mapOf("Blend" to Prop.Choice("Multiply"))),
                    Node(5, "render", 780f, 0f, mapOf("Paper" to Prop.Choice("White"))),
                    Node(6, "viewer", 1040f, 0f),
                    Node(7, "record", 780f, 260f),
                ),
                links = listOf(Link(3, 0, 2, 1), Link(1, 0, 4, 0), Link(2, 0, 4, 1), Link(4, 0, 5, 0), Link(5, 0, 6, 0), Link(4, 0, 7, 0)),
            )),
            GraphPreset("Live: tilt the phone to turn a grating", Graph(
                nodes = listOf(
                    Node(1, "grating", 260f, 0f),
                    Node(3, "sensor", 0f, 240f, mapOf("Source" to Prop.Choice("Rotation: roll"), "Min" to Prop.Num(-10f), "Max" to Prop.Num(10f))),
                    Node(2, "grating", 260f, 240f),
                    Node(4, "stack", 520f, 100f, mapOf("Blend" to Prop.Choice("Multiply"))),
                    Node(5, "render", 780f, 100f, mapOf("Paper" to Prop.Choice("White"))),
                    Node(6, "viewer", 1040f, 100f),
                ),
                links = listOf(Link(3, 0, 2, 1), Link(1, 0, 4, 0), Link(2, 0, 4, 1), Link(4, 0, 5, 0), Link(5, 0, 6, 0)),
            )),
            GraphPreset("Live: LFO drives Draw layer 1", Graph(
                nodes = listOf(
                    Node(1, "draw", 0f, 0f),
                    Node(3, "lfo", 0f, 160f, mapOf("Min" to Prop.Num(-20f), "Max" to Prop.Num(20f))),
                    Node(2, "drive", 260f, 0f, mapOf("Parameter" to Prop.Choice("Angle"))),
                    Node(4, "render", 520f, 0f, mapOf("Paper" to Prop.Choice("White"))),
                    Node(5, "viewer", 780f, 0f),
                ),
                links = listOf(Link(1, 0, 2, 0), Link(3, 0, 2, 2), Link(2, 0, 4, 0), Link(4, 0, 5, 0)),
            )),
            // Two phones (link them in Loop first).
            GraphPreset("Two phones: stream a live moiré to the other phone", Graph(
                nodes = listOf(
                    Node(1, "grating", 0f, 0f),
                    Node(3, "lfo", 0f, 220f, mapOf("Rate Hz" to Prop.Num(0.1f), "Min" to Prop.Num(-6f), "Max" to Prop.Num(6f))),
                    Node(2, "grating", 260f, 220f),
                    Node(4, "stack", 520f, 60f, mapOf("Blend" to Prop.Choice("Multiply"))),
                    Node(5, "render", 780f, 0f, mapOf("Paper" to Prop.Choice("White"))),
                    Node(6, "viewer", 1040f, 0f),
                    Node(7, "tophone", 1040f, 160f, mapOf("Format" to Prop.Choice("PNG"))),
                ),
                links = listOf(Link(3, 0, 2, 1), Link(1, 0, 4, 0), Link(2, 0, 4, 1), Link(4, 0, 5, 0), Link(5, 0, 6, 0), Link(5, 0, 7, 0)),
            )),
            GraphPreset("Two phones: project what the other phone sends", Graph(
                nodes = listOf(
                    Node(1, "fromphone", 0f, 0f),
                    Node(2, "develop", 260f, 0f),
                    Node(3, "viewer", 520f, 0f),
                    Node(4, "projector", 520f, 120f),
                ),
                links = listOf(Link(1, 0, 2, 0), Link(2, 0, 3, 0), Link(2, 0, 4, 0)),
            )),
            GraphPreset("Live: play Animate frames", Graph(
                nodes = listOf(
                    Node(1, "animate", 0f, 0f),
                    Node(3, "clock", 0f, 160f, mapOf("Rate" to Prop.Num(12f))),
                    Node(2, "frameat", 260f, 0f),
                    Node(4, "viewer", 520f, 0f),
                ),
                links = listOf(Link(1, 0, 2, 0), Link(3, 1, 2, 1), Link(2, 0, 4, 0)),
            )),
        )

        /** Types whose first output is an image or canvas, so they can be previewed. */
        fun previewable(type: String, catalog: Catalog = Catalog): Boolean = catalog[type]?.let { d ->
            d.outputs.firstOrNull()?.type in setOf(SocketType.Image, SocketType.Canvas) || d.type == "viewer"
        } == true
    }
}
