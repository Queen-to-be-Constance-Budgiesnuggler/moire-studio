package dk.cbavf.studio.nodes

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dk.cbavf.studio.library.Lineage
import dk.cbavf.studio.library.ProjectStore
import dk.cbavf.studio.nodes.model.Catalog
import dk.cbavf.studio.nodes.model.GROUP_OUT
import dk.cbavf.studio.nodes.model.GROUP_PREFIX
import dk.cbavf.studio.nodes.model.Graph
import dk.cbavf.studio.nodes.model.GraphPreset
import dk.cbavf.studio.nodes.model.Link
import dk.cbavf.studio.nodes.model.Node
import dk.cbavf.studio.nodes.model.NodeCategory
import dk.cbavf.studio.nodes.model.NodeDef
import dk.cbavf.studio.nodes.model.Prop
import dk.cbavf.studio.nodes.model.SocketDef
import dk.cbavf.studio.nodes.model.SocketType
import dk.cbavf.studio.ui.LibraryPicker
import dk.cbavf.studio.ui.NumberDialog
import dk.cbavf.studio.ui.ScrollRow
import dk.cbavf.studio.ui.fmt
import kotlin.math.abs
import kotlin.math.roundToInt

// Node layout in canvas units (= dp at zoom 1). Rows have fixed heights so sockets line up.
private const val NODE_W = 210f
private const val HEADER = 30f
private const val ROW = 30f
private const val SOCKET_HIT = 18f
/** A reroute is a dot this wide; it is easier to grab than it looks. */
private const val DOT = 16f
private const val DOT_HIT = 14f

/** Sinks with a button row: Save / Send run once, Record arms. */
private val RUNNABLE = setOf("save", "tocompose", "record", "tophone", "projector")

/** Rows of a node, top to bottom: outputs, properties, inputs, and a Run row for runnable sinks. */
private fun rows(def: NodeDef) = def.outputs.size + def.props.size + def.inputs.size + if (def.type in RUNNABLE) 1 else 0

private fun nodeHeight(n: Node, def: NodeDef) = when {
    n.type == Graph.REROUTE -> DOT
    n.collapsed -> HEADER
    else -> HEADER + ROW * rows(def)
}

private fun nodeW(n: Node) = if (n.type == Graph.REROUTE) DOT else NODE_W

/** A node's width and height in canvas units (for Align). */
fun nodeSize(n: Node, cat: Catalog = Catalog): Pair<Float, Float> = nodeW(n) to (cat[n.type]?.let { nodeHeight(n, it) } ?: HEADER)

/** Is canvas point [p] on node [n]? Reroute dots get a margin so a finger can catch them. */
private fun hits(n: Node, p: Offset, cat: Catalog): Boolean {
    val def = cat[n.type] ?: return false
    val m = if (n.type == Graph.REROUTE) DOT_HIT else 0f
    return p.x in (n.x - m)..(n.x + nodeW(n) + m) && p.y in (n.y - m)..(n.y + nodeHeight(n, def) + m)
}

/** Centre of a socket in canvas units. Collapsed nodes gather their sockets on the header; a reroute's are its dot. */
private fun socketPos(n: Node, def: NodeDef, output: Boolean, index: Int): Offset {
    if (n.type == Graph.REROUTE) return Offset(n.x + DOT / 2, n.y + DOT / 2)
    val x = if (output) n.x + NODE_W else n.x
    if (n.collapsed) return Offset(x, n.y + HEADER / 2)
    val row = if (output) index else def.outputs.size + def.props.size + index
    return Offset(x, n.y + HEADER + ROW * row + ROW / 2)
}

/** Link curve control points, as Blender draws them (horizontal tangents at both ends). */
private fun bezier(a: Offset, b: Offset): List<Offset> {
    val dx = maxOf(40f, abs(b.x - a.x) / 2)
    return listOf(a, Offset(a.x + dx, a.y), Offset(b.x - dx, b.y), b)
}

private fun bezierPoints(a: Offset, b: Offset, n: Int = 24): List<Offset> {
    val (p0, p1, p2, p3) = bezier(a, b)
    return (0..n).map { i ->
        val t = i / n.toFloat()
        val u = 1 - t
        p0 * (u * u * u) + p1 * (3 * u * u * t) + p2 * (3 * u * t * t) + p3 * (t * t * t)
    }
}

private fun segmentsCross(a: Offset, b: Offset, c: Offset, d: Offset): Boolean {
    fun cross(o: Offset, p: Offset, q: Offset) = (p.x - o.x) * (q.y - o.y) - (p.y - o.y) * (q.x - o.x)
    val d1 = cross(c, d, a)
    val d2 = cross(c, d, b)
    val d3 = cross(a, b, c)
    val d4 = cross(a, b, d)
    return (d1 > 0) != (d2 > 0) && (d3 > 0) != (d4 > 0)
}

/** Distance from [p] to the segment [a]–[b]. */
private fun segmentDistance(p: Offset, a: Offset, b: Offset): Float {
    val ab = b - a
    val len2 = ab.x * ab.x + ab.y * ab.y
    val t = if (len2 == 0f) 0f else (((p.x - a.x) * ab.x + (p.y - a.y) * ab.y) / len2).coerceIn(0f, 1f)
    return (p - (a + ab * t)).getDistance()
}

/** A link's curve as points, in canvas units. */
private fun linkCurve(g: Graph, l: Link, cat: Catalog): List<Offset>? {
    val a = g.node(l.fromNode) ?: return null
    val b = g.node(l.toNode) ?: return null
    return bezierPoints(socketPos(a, cat[a.type] ?: return null, true, l.fromSocket), socketPos(b, cat[b.type] ?: return null, false, l.toSocket))
}

/** A frame being moved by its label bar (with the nodes inside) or resized by its corner. */
private data class FrameGrab(val id: Int, val resize: Boolean, val inside: Set<Int>)

/** A link being dragged out of a socket, in canvas units. */
private data class DragLink(val fromNode: Int, val fromSocket: Int, val from: Offset, val to: Offset)

@Composable
fun NodesRoom(
    state: NodesState,
    store: ProjectStore,
    lineage: Lineage,
    preview: ImageBitmap?,
    previewStatus: String,
    running: Boolean,
    onRun: (Int) -> Unit,
    onSavePreset: (String) -> Unit,
    onDeletePreset: (GraphPreset) -> Unit,
    runner: NodesLive? = null,
    onLive: () -> Unit = {},
    onArm: (Int) -> Unit = {},
) {
    val graph = state.graph
    val cat = state.catalog
    var pickingFor by remember { mutableStateOf<Int?>(null) }
    var addAt by remember { mutableStateOf<Offset?>(null) }
    var presetsOpen by remember { mutableStateOf(false) }
    var dragLink by remember { mutableStateOf<DragLink?>(null) }
    var lazyLine by remember { mutableStateOf<Pair<Offset, Offset>?>(null) }
    var knife by remember { mutableStateOf<List<Offset>?>(null) }
    var box by remember { mutableStateOf<Pair<Offset, Offset>?>(null) }

    pickingFor?.let { id ->
        LibraryPicker(store, lineage, single = true) { ids ->
            pickingFor = null
            ids.firstOrNull()?.let { state.setProp(id, "asset", Prop.Text(it)) }
        }
        return
    }
    addAt?.let { at -> AddMenu(state, onDismiss = { addAt = null }) { def -> state.add(def.type, at.x, at.y); addAt = null } }
    if (presetsOpen) PresetsDialog(state, onDismiss = { presetsOpen = false }, onSave = onSavePreset, onDelete = onDeletePreset)

    val density = LocalDensity.current.density
    val live by rememberUpdatedState(Triple(state.zoom, Offset(state.panX, state.panY), graph))

    Column(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .clipToBounds()
                .background(Color(0xFF1D1D1D))
                .pointerInput(state.tool, density) {
                    gestures(
                        state, density, { live },
                        onDragLink = { dragLink = it },
                        onLazy = { lazyLine = it },
                        onKnife = { knife = it },
                        onBox = { box = it },
                        onAddMenu = { addAt = it },
                    )
                }
        ) {
            // Backdrop: what the previewed node outputs (Blender Compositor's backdrop).
            if (state.backdrop && preview != null) {
                Image(preview, null, Modifier.fillMaxSize().graphicsLayer { alpha = 0.55f }, contentScale = ContentScale.Fit)
            }
            // Grid, in screen space so it is always there.
            Canvas(Modifier.fillMaxSize()) {
                val step = 40f * state.zoom * density
                if (step > 8f) {
                    var x = state.panX % step
                    while (x < size.width) { drawLine(Color(0x22FFFFFF), Offset(x, 0f), Offset(x, size.height)); x += step }
                    var y = state.panY % step
                    while (y < size.height) { drawLine(Color(0x22FFFFFF), Offset(0f, y), Offset(size.width, y)); y += step }
                }
            }
            Box(
                Modifier.fillMaxSize().graphicsLayer {
                    scaleX = state.zoom
                    scaleY = state.zoom
                    translationX = state.panX
                    translationY = state.panY
                    transformOrigin = TransformOrigin(0f, 0f)
                }
            ) {
                // Frames under everything, then links, nodes and socket dots.
                graph.frames.forEach { f -> FrameBox(f, selected = state.selectedFrame == f.id) }
                Canvas(Modifier.fillMaxSize()) {
                    val d = density
                    for (l in graph.links) {
                        val a = graph.node(l.fromNode) ?: continue
                        val b = graph.node(l.toNode) ?: continue
                        val ad = cat[a.type] ?: continue
                        val bd = cat[b.type] ?: continue
                        val p = socketPos(a, ad, true, l.fromSocket) * d
                        val q = socketPos(b, bd, false, l.toSocket) * d
                        val (p0, p1, p2, p3) = bezier(p, q)
                        val path = Path().apply { moveTo(p0.x, p0.y); cubicTo(p1.x, p1.y, p2.x, p2.y, p3.x, p3.y) }
                        val color = Color(graph.outputType(l.fromNode, l.fromSocket, cat)?.argb ?: 0xFFAAAAAA)
                        drawPath(path, if (a.muted || b.muted) color.copy(alpha = 0.35f) else color, style = Stroke(width = 3f * d))
                    }
                    dragLink?.let { dl ->
                        val (p0, p1, p2, p3) = bezier(dl.from * d, dl.to * d)
                        drawPath(Path().apply { moveTo(p0.x, p0.y); cubicTo(p1.x, p1.y, p2.x, p2.y, p3.x, p3.y) }, Color.White, style = Stroke(width = 2f * d))
                    }
                    lazyLine?.let { (a, b) -> drawLine(Color(0xFF7FD3FF), a * d, b * d, strokeWidth = 2f * d) }
                }
                graph.nodes.forEach { n ->
                    val def = cat[n.type] ?: return@forEach
                    if (n.type == Graph.REROUTE) return@forEach // drawn as a socket dot
                    NodeBox(
                        state, n, def, graph,
                        selected = n.id in state.selected,
                        previewing = state.previewTarget == n.id,
                        running = running,
                        armed = runner?.armed?.contains(n.id) == true,
                        onPick = { pickingFor = n.id },
                        onRun = { if (n.type == "record") onArm(n.id) else onRun(n.id) },
                    )
                }
                // Socket dots on top of the node edges.
                Canvas(Modifier.fillMaxSize()) {
                    val d = density
                    graph.nodes.forEach { n ->
                        val def = cat[n.type] ?: return@forEach
                        if (n.type == Graph.REROUTE) {
                            val c = socketPos(n, def, true, 0) * d
                            if (n.id in state.selected) drawCircle(Color(0xFFFFA733), 10f * d, c)
                            drawSocket(c, graph.outputType(n.id, 0, cat) ?: SocketType.Any, d)
                            return@forEach
                        }
                        def.outputs.forEachIndexed { i, s -> drawSocket(socketPos(n, def, true, i) * d, s.type, d) }
                        def.inputs.forEachIndexed { i, s -> if (!n.collapsed || graph.linkInto(n.id, i) != null) drawSocket(socketPos(n, def, false, i) * d, s.type, d) }
                    }
                }
            }
            // Knife / reroute line and the selection box, in screen space.
            knife?.let { pts ->
                val c = if (state.tool == NodeTool.Reroute) Color(0xFF7FD3FF) else Color(0xFFFF6B6B)
                Canvas(Modifier.fillMaxSize()) {
                    for (i in 1 until pts.size) drawLine(c, pts[i - 1], pts[i], strokeWidth = 3f)
                }
            }
            box?.let { (a, b) ->
                Canvas(Modifier.fillMaxSize()) {
                    val tl = Offset(minOf(a.x, b.x), minOf(a.y, b.y))
                    val sz = androidx.compose.ui.geometry.Size(abs(a.x - b.x), abs(a.y - b.y))
                    drawRect(Color(0x33FFA733), tl, sz)
                    drawRect(Color(0xFFFFA733), tl, sz, style = Stroke(width = 2f))
                }
            }
            Surface(Modifier.align(Alignment.TopStart).padding(8.dp), color = Color(0xCC2B2B2B), shape = MaterialTheme.shapes.small) {
                Text(
                    (state.openGroup?.let { "In group “${it.name}” · " } ?: "") + "${graph.nodes.size} nodes · ${graph.links.size} links · ${(state.zoom * 100).roundToInt()}%" + (if (previewStatus.isNotEmpty()) " · $previewStatus" else ""),
                    Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            if (graph.nodes.isEmpty()) {
                Text(
                    "Empty graph. Long-press to add a node, or open Presets.",
                    Modifier.align(Alignment.Center).padding(32.dp),
                    color = Color.LightGray,
                    textAlign = TextAlign.Center,
                )
            }
        }
        Toolbar(state, runner?.live == true, onLive, onAdd = { addAt = viewCentre(state, density) }, onPresets = { presetsOpen = true })
    }
}

private operator fun Offset.times(k: Float) = Offset(x * k, y * k)

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSocket(c: Offset, type: SocketType, d: Float) {
    drawCircle(Color(0xFF111111), 7f * d, c)
    drawCircle(Color(type.argb), 5f * d, c)
}

private fun viewCentre(state: NodesState, density: Float) = Offset((-state.panX + 300f) / (state.zoom * density), (-state.panY + 300f) / (state.zoom * density))

/**
 * Canvas gestures (Blender's node editor, for touch): drag a socket to link (from a linked input
 * it picks the link up); drag a node to move it (with the other selected nodes); tap selects;
 * drag empty space pans, two fingers zoom; long-press empty space opens Add; double-tap frames
 * everything. Lazy connect drags from node to node; Cut drags a knife across links.
 */
private suspend fun PointerInputScope.gestures(
    state: NodesState,
    density: Float,
    live: () -> Triple<Float, Offset, Graph>,
    onDragLink: (DragLink?) -> Unit,
    onLazy: (Pair<Offset, Offset>?) -> Unit,
    onKnife: (List<Offset>?) -> Unit,
    onBox: (Pair<Offset, Offset>?) -> Unit,
    onAddMenu: (Offset) -> Unit,
) {
    var gesture = 0
    var lastTapAt = 0L
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = true)
        gesture++
        val key = "g$gesture"
        val (zoom0, pan0, graph) = live()
        val cat = state.catalog
        fun unit(o: Offset, z: Float = live().first, p: Offset = live().second) = Offset((o.x - p.x) / (z * density), (o.y - p.y) / (z * density))
        val start = unit(down.position, zoom0, pan0)

        // What is under the finger: a socket, a node, or nothing.
        var socketHit: Triple<Node, Boolean, Int>? = null
        for (n in graph.nodes.asReversed()) {
            // A reroute dot is dragged to move it; links leave it with Lazy connect.
            if (n.type == Graph.REROUTE) continue
            val def = cat[n.type] ?: continue
            def.outputs.indices.firstOrNull { (socketPos(n, def, true, it) - start).getDistance() < SOCKET_HIT }?.let { socketHit = Triple(n, true, it) }
            def.inputs.indices.firstOrNull { (socketPos(n, def, false, it) - start).getDistance() < SOCKET_HIT }?.let { socketHit = Triple(n, false, it) }
            if (socketHit != null) break
        }
        val nodeHit = graph.nodes.lastOrNull { hits(it, start, cat) }
        // A frame is grabbed by its label bar (moves with its nodes) or its corner (resizes).
        val frameHit = if (socketHit != null || nodeHit != null) null else {
            graph.frames.lastOrNull { f -> start.x in f.x..(f.x + f.w) && start.y in f.y..(f.y + NodesState.FRAME_HEADER) }
                ?.let { f -> FrameGrab(f.id, false, graph.nodes.filter { n -> nodeSize(n, cat).let { (w, h) -> f.contains(n, w, h) } }.map { it.id }.toSet()) }
                ?: graph.frames.lastOrNull { f -> (Offset(f.x + f.w, f.y + f.h) - start).getDistance() < 28f }?.let { FrameGrab(it.id, true, emptySet()) }
        }

        // Link drag (every tool but Box allows it from a socket).
        var drag: DragLink? = null
        if (state.tool != NodeTool.Box) socketHit?.let { (n, output, i) ->
            val def = cat[n.type]!!
            drag = if (output) {
                DragLink(n.id, i, socketPos(n, def, true, i), start)
            } else {
                state.detach(n.id, i, key)?.let { l ->
                    val from = graph.node(l.fromNode)!!
                    DragLink(l.fromNode, l.fromSocket, socketPos(from, cat[from.type]!!, true, l.fromSocket), start)
                }
            }
            onDragLink(drag)
        }

        var moved = false
        val knifePts = mutableListOf(down.position)
        var lastPos = down.position
        var longPressed = false
        val downTime = down.uptimeMillis
        do {
            val event = awaitPointerEvent()
            val fingers = event.changes.count { it.pressed }
            val pos = event.changes.firstOrNull()?.position ?: down.position
            lastPos = pos
            if ((pos - down.position).getDistance() > viewConfiguration.touchSlop) moved = true
            val here = unit(pos)
            when {
                fingers >= 2 -> {
                    // Pinch zoom about the fingers, plus pan.
                    val c = event.calculateCentroid()
                    val z = event.calculateZoom()
                    val p = event.calculatePan()
                    val newZoom = (state.zoom * z).coerceIn(0.2f, 3f)
                    val k = newZoom / state.zoom
                    state.panX = c.x - (c.x - state.panX) * k + p.x
                    state.panY = c.y - (c.y - state.panY) * k + p.y
                    state.zoom = newZoom
                    drag = null
                    onDragLink(null)
                }
                drag != null -> {
                    drag = drag!!.copy(to = here)
                    onDragLink(drag)
                }
                state.tool == NodeTool.Box -> onBox(down.position to pos)
                (state.tool == NodeTool.Link || state.tool == NodeTool.Mix) && nodeHit != null ->
                    onLazy(Offset(nodeHit.x + nodeW(nodeHit) / 2, nodeHit.y + minOf(HEADER, nodeW(nodeHit)) / 2) to here)
                (state.tool == NodeTool.Cut || state.tool == NodeTool.Reroute) && nodeHit == null -> {
                    knifePts += pos
                    onKnife(knifePts.toList())
                }
                frameHit != null && moved && state.tool == NodeTool.Select -> {
                    val p = event.calculatePan()
                    val dx = p.x / (state.zoom * density)
                    val dy = p.y / (state.zoom * density)
                    state.selectedFrame = frameHit.id
                    if (frameHit.resize) state.resizeFrame(frameHit.id, dx, dy, key) else state.moveFrame(frameHit.id, frameHit.inside, dx, dy, key)
                }
                nodeHit != null && moved -> {
                    // Move the touched node, and the rest of the selection with it.
                    val ids = if (nodeHit.id in state.selected) state.selected else setOf(nodeHit.id).also { state.selected = it }
                    val p = event.calculatePan()
                    state.move(ids, p.x / (state.zoom * density), p.y / (state.zoom * density), key)
                }
                nodeHit == null && moved -> {
                    val p = event.calculatePan()
                    state.panX += p.x
                    state.panY += p.y
                }
                !moved && nodeHit == null && frameHit == null && event.changes.first().uptimeMillis - downTime > 550 && !longPressed -> {
                    longPressed = true
                    onAddMenu(here)
                }
            }
            event.changes.forEach { if (it.positionChanged()) it.consume() }
        } while (event.changes.any { it.pressed })

        // Release.
        val end = unit(lastPos)
        drag?.let { dl ->
            // Drop on an input socket (or anywhere on a node: its best matching input).
            val g = state.graph
            val target = g.nodes.asReversed().firstNotNullOfOrNull { n ->
                val def = cat[n.type] ?: return@firstNotNullOfOrNull null
                def.inputs.indices.firstOrNull { (socketPos(n, def, false, it) - dl.to).getDistance() < SOCKET_HIT * 1.5f }?.let { n.id to it }
            }
            if (target != null) {
                state.connect(Link(dl.fromNode, dl.fromSocket, target.first, target.second))
            } else {
                g.nodes.lastOrNull { n -> hits(n, dl.to, cat) && n.id != dl.fromNode }
                    ?.let { n -> state.lazyConnect(dl.fromNode, n.id) }
            }
        }
        onDragLink(null)
        if ((state.tool == NodeTool.Link || state.tool == NodeTool.Mix) && nodeHit != null) {
            onLazy(null)
            val to = state.graph.nodes.lastOrNull { n -> hits(n, end, cat) && n.id != nodeHit.id }
            if (to != null) {
                if (state.tool == NodeTool.Link) state.lazyConnect(nodeHit.id, to.id)
                else state.lazyMix(nodeHit.id, to.id, maxOf(nodeHit.x + nodeW(nodeHit), to.x + nodeW(to)) + 60f, (nodeHit.y + to.y) / 2)
            }
        }
        if (state.tool == NodeTool.Box && moved) {
            onBox(null)
            val a = start
            val b = end
            val r = androidx.compose.ui.geometry.Rect(minOf(a.x, b.x), minOf(a.y, b.y), maxOf(a.x, b.x), maxOf(a.y, b.y))
            state.selected = state.graph.nodes.filter { n ->
                val (w, h) = nodeSize(n, cat)
                r.overlaps(androidx.compose.ui.geometry.Rect(n.x, n.y, n.x + w, n.y + h))
            }.map { it.id }.toSet()
        }
        if ((state.tool == NodeTool.Cut || state.tool == NodeTool.Reroute) && knifePts.size > 1) {
            onKnife(null)
            // Every link the swipe crosses, and where (the crossing curve segment's middle).
            val g = state.graph
            val cutPts = knifePts.map { unit(it) }
            val crossings = g.links.mapNotNull { l ->
                val curve = linkCurve(g, l, cat) ?: return@mapNotNull null
                for (i in 1 until cutPts.size) for (j in 1 until curve.size) {
                    if (segmentsCross(cutPts[i - 1], cutPts[i], curve[j - 1], curve[j])) return@mapNotNull l to (curve[j - 1] + curve[j]) * 0.5f
                }
                null
            }
            if (state.tool == NodeTool.Cut) state.cut(crossings.map { it.first }.toSet())
            else state.insertReroutes(crossings.map { (l, p) -> l to ((p.x - DOT / 2) to (p.y - DOT / 2)) })
        }
        // Blender's auto-insert: a lone node dropped onto a link goes in between.
        if (state.tool == NodeTool.Select && moved && drag == null && nodeHit != null && state.selected == setOf(nodeHit.id)) {
            val g = state.graph
            val n = g.node(nodeHit.id)
            if (n != null && g.links.none { it.fromNode == n.id || it.toNode == n.id }) {
                val (w, h) = nodeSize(n, cat)
                val c = Offset(n.x + w / 2, n.y + minOf(h, HEADER) / 2)
                g.links.firstOrNull { l ->
                    val curve = linkCurve(g, l, cat) ?: return@firstOrNull false
                    (1 until curve.size).any { segmentDistance(c, curve[it - 1], curve[it]) < 24f }
                }?.let { state.insertOnLink(n.id, it) }
            }
        }
        if (!moved && drag == null && !longPressed) {
            if (nodeHit != null) {
                state.selected = setOf(nodeHit.id)
                state.selectedFrame = null
            } else if (frameHit != null) {
                state.selectedFrame = frameHit.id
                state.selected = emptySet()
            } else {
                state.selectedFrame = null
                // Double tap on empty space frames everything.
                if (down.uptimeMillis - lastTapAt < 350) frameAll(state, size.width.toFloat(), size.height.toFloat(), density)
                lastTapAt = down.uptimeMillis
                state.selected = emptySet()
            }
        }
    }
}

/** Fits every node into the view (Blender's Home key). */
fun frameAll(state: NodesState, w: Float, h: Float, density: Float) {
    val g = state.graph
    val cat = state.catalog
    if (g.nodes.isEmpty()) return
    val minX = g.nodes.minOf { it.x }
    val minY = g.nodes.minOf { it.y }
    val maxX = g.nodes.maxOf { it.x + nodeW(it) }
    val maxY = g.nodes.maxOf { n -> n.y + (cat[n.type]?.let { nodeHeight(n, it) } ?: HEADER) }
    val z = minOf(w / ((maxX - minX + 80) * density), h / ((maxY - minY + 80) * density)).coerceIn(0.2f, 1.5f)
    state.zoom = z
    state.panX = -(minX - 40) * z * density
    state.panY = -(minY - 40) * z * density
}

@Composable
private fun NodeBox(
    state: NodesState,
    n: Node,
    def: NodeDef,
    graph: Graph,
    selected: Boolean,
    previewing: Boolean,
    running: Boolean,
    armed: Boolean,
    onPick: () -> Unit,
    onRun: () -> Unit,
) {
    Column(
        Modifier
            .offset(n.x.dp, n.y.dp)
            .width(NODE_W.dp)
            .background(Color(0xFF303030), RoundedCornerShape(6.dp))
            .border(if (selected) 2.dp else 1.dp, if (selected) Color(0xFFFFA733) else Color(0xFF151515), RoundedCornerShape(6.dp))
            .graphicsLayer { alpha = if (n.muted) 0.55f else 1f }
    ) {
        // Header: collapse, title, preview eye.
        Row(
            Modifier.fillMaxWidth().height(HEADER.dp).background(Color(def.category.argb), RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp)).padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(if (n.collapsed) "▸" else "▾", Modifier.clickable { state.toggle(setOf(n.id)) { it.copy(collapsed = !it.collapsed) } }.padding(end = 6.dp), color = Color.White)
            Text((n.label ?: def.label) + if (n.muted) " (muted)" else "", Modifier.weight(1f), color = Color.White, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            // Tab into a group.
            if (n.type.startsWith(GROUP_PREFIX)) {
                Text("⇥", Modifier.clickable { state.enterGroup(n.id) }.padding(start = 6.dp), color = Color.White)
            }
            // The backdrop shows the top graph, so previews are picked there.
            if (state.path.isEmpty() && NodesState.previewable(n.type, state.catalog)) {
                Text(if (previewing) "◉" else "◎", Modifier.clickable { state.previewId = n.id }.padding(start = 6.dp), color = Color.White)
            }
        }
        if (n.collapsed) return@Column
        def.outputs.forEach { s ->
            Row(Modifier.fillMaxWidth().height(ROW.dp).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(s.name, Modifier.weight(1f), color = Color(0xFFDDDDDD), fontSize = 12.sp, textAlign = TextAlign.End)
            }
        }
        def.props.forEach { p ->
            Box(Modifier.fillMaxWidth().height(ROW.dp).padding(horizontal = 8.dp, vertical = 3.dp), contentAlignment = Alignment.CenterStart) {
                if (p.options.isEmpty()) {
                    // The asset picker of a Library Image node.
                    val id = (n.props[p.name] as? Prop.Text)?.v
                    Pill(if (id.isNullOrBlank()) "Pick image…" else "Image ${id.takeLast(6)}", onClick = onPick)
                } else {
                    ChoicePill(p.name, (n.props[p.name] as? Prop.Choice)?.v ?: p.default, p.options) { v -> state.setProp(n.id, p.name, Prop.Choice(v)) }
                }
            }
        }
        def.inputs.forEachIndexed { i, s ->
            Box(Modifier.fillMaxWidth().height(ROW.dp).padding(horizontal = 10.dp, vertical = 3.dp), contentAlignment = Alignment.CenterStart) {
                if (s.type == SocketType.Value && graph.linkInto(n.id, i) == null && n.type != GROUP_OUT) {
                    NumField(state, n, s)
                } else {
                    Text(s.name, color = Color(0xFFDDDDDD), fontSize = 12.sp)
                }
            }
        }
        if (def.type in RUNNABLE) {
            Box(Modifier.fillMaxWidth().height(ROW.dp).padding(horizontal = 8.dp, vertical = 3.dp)) {
                when {
                    state.path.isNotEmpty() -> Text("Runs from the top graph", color = Color(0xFFAAAAAA), fontSize = 12.sp)
                    def.type == "record" -> Pill(if (armed) "■ Stop recording" else "● Arm (starts Live)", onClick = onRun)
                    def.type == "projector" -> Pill("▶ Full screen", onClick = onRun)
                    def.type == "tophone" -> Pill(if (running) "Sending…" else "▶ Send once (Live streams)", onClick = { if (!running) onRun() })
                    else -> Pill(if (running) "Running…" else "▶ Run", onClick = { if (!running) onRun() })
                }
            }
        }
    }
}

@Composable
private fun Pill(text: String, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxSize().background(Color(0xFF545454), RoundedCornerShape(4.dp)).clickable(onClick = onClick).padding(horizontal = 8.dp),
        contentAlignment = Alignment.CenterStart,
    ) { Text(text, color = Color.White, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
}

@Composable
private fun ChoicePill(name: String, value: String, options: List<String>, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Pill("$name: $value ▾") { open = true }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            options.forEach { o -> DropdownMenuItem(text = { Text(o) }, onClick = { onPick(o); open = false }) }
        }
    }
}

/** A Value input's field: tap for an exact number, drag sideways to scrub (as in Blender). */
@Composable
private fun NumField(state: NodesState, n: Node, s: SocketDef) {
    val value = (n.props[s.name] as? Prop.Num)?.v ?: s.default
    var editing by remember { mutableStateOf(false) }
    var gesture by remember { mutableStateOf(0) }
    val shown = if (s.whole) "${value.roundToInt()}" else fmt(value)
    Row(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF545454), RoundedCornerShape(4.dp))
            .pointerInput(n.id, s.name) {
                detectHorizontalDragGestures(onDragStart = { gesture++ }) { change, dx ->
                    change.consume()
                    val cur = (state.graph.node(n.id)?.props?.get(s.name) as? Prop.Num)?.v ?: s.default
                    var v = (cur + dx / 300f * (s.max - s.min)).coerceIn(s.min, s.max)
                    if (s.whole) v = v.roundToInt().toFloat()
                    state.setProp(n.id, s.name, Prop.Num(v), "scrub${n.id}.${s.name}.$gesture")
                }
            }
            .clickable { editing = true }
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(s.name, Modifier.weight(1f), color = Color(0xFFDDDDDD), fontSize = 12.sp, maxLines = 1)
        Text(shown, color = Color.White, fontSize = 12.sp)
    }
    if (editing) {
        NumberDialog(s.name, value, s.min..s.max, s.whole, onChange = { v -> state.setProp(n.id, s.name, Prop.Num(v), "field${n.id}.${s.name}") }, onDismiss = { editing = false })
    }
}

@Composable
private fun Toolbar(state: NodesState, live: Boolean, onLive: () -> Unit, onAdd: () -> Unit, onPresets: () -> Unit) {
    val cat = state.catalog
    var renaming by remember { mutableStateOf<Triple<String, String, (String) -> Unit>?>(null) }
    renaming?.let { (title, initial, apply) -> RenameDialog(title, initial, onDismiss = { renaming = null }) { apply(it); renaming = null } }
    Surface(tonalElevation = 2.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
            ScrollRow {
                // Live runs the clock, LFOs and sensors every frame (the Patch bay's play button).
                FilterChip(selected = live, onClick = onLive, label = { Text(if (live) "■ Live" else "▶ Live") })
                TextButton(onClick = onAdd) { Text("+ Add") }
                TextButton(onClick = state::undo, enabled = state.edit.canUndo) { Text("Undo") }
                NodeTool.entries.forEach { t -> FilterChip(selected = state.tool == t, onClick = { state.tool = t }, label = { Text(t.label) }) }
                FilterChip(selected = state.backdrop, onClick = { state.backdrop = !state.backdrop }, label = { Text("Backdrop") })
                TextButton(onClick = onPresets) { Text("Presets") }
            }
            // The open group, and the selected frame.
            val group = state.openGroup
            val frame = state.selectedFrame?.let { id -> state.graph.frames.firstOrNull { it.id == id } }
            if (group != null || frame != null) {
                ScrollRow {
                    if (group != null) {
                        Text("Group “${group.name}”", style = MaterialTheme.typography.labelMedium)
                        TextButton(onClick = state::exitGroup) { Text("Exit group") }
                        TextButton(onClick = { renaming = Triple("Group name", group.name) { v -> state.renameGroup(v) } }) { Text("Rename group") }
                    }
                    if (frame != null) {
                        Text("Frame “${frame.label}”", style = MaterialTheme.typography.labelMedium)
                        TextButton(onClick = { renaming = Triple("Frame label", frame.label) { v -> state.updateFrame(frame.id) { it.copy(label = v.ifBlank { "Frame" }) } } }) { Text("Rename") }
                        TextButton(onClick = { state.nextFrameColor(frame.id) }) { Text("Colour") }
                        TextButton(onClick = { state.deleteFrame(frame.id) }) { Text("Remove frame") }
                    }
                }
            }
            val sel = state.selected
            ScrollRow {
                Text(if (sel.isEmpty()) "Nothing selected" else "${sel.size} selected", style = MaterialTheme.typography.labelMedium)
                TextButton(onClick = state::duplicateSelected, enabled = sel.isNotEmpty()) { Text("Duplicate") }
                TextButton(onClick = { state.toggle(sel) { it.copy(muted = !it.muted) } }, enabled = sel.isNotEmpty()) { Text("Mute") }
                TextButton(onClick = { state.toggle(sel) { it.copy(collapsed = !it.collapsed) } }, enabled = sel.isNotEmpty()) { Text("Collapse") }
                TextButton(onClick = state::deleteSelected, enabled = sel.isNotEmpty()) { Text("Delete") }
                // Node Wrangler: align in a row or column; swap A/B links.
                TextButton(onClick = { state.alignSelected(40f) { nodeSize(it, cat) } }, enabled = sel.size >= 2) { Text("Align") }
                if (sel.any { state.graph.canSwap(it, cat) }) TextButton(onClick = state::swapSelected) { Text("Swap A↔B") }
                // Blender's Ctrl+J (frame) and Ctrl+G (group); Tab / Ctrl+Alt+G for group nodes.
                TextButton(onClick = { state.frameSelected { nodeSize(it, cat) } }, enabled = sel.isNotEmpty()) { Text("Frame") }
                TextButton(onClick = { state.groupSelected(NODE_W) }, enabled = sel.isNotEmpty()) { Text("Group") }
                val groupSel = sel.filter { state.graph.node(it)?.type?.startsWith(GROUP_PREFIX) == true }
                if (groupSel.size == 1) TextButton(onClick = { state.enterGroup(groupSel.first()) }) { Text("Enter group") }
                if (groupSel.isNotEmpty()) TextButton(onClick = state::ungroupSelected) { Text("Ungroup") }
                if (sel.size == 1) {
                    val n = state.graph.node(sel.first())
                    if (n != null) TextButton(onClick = { renaming = Triple("Node label", n.label ?: cat[n.type]?.label.orEmpty()) { v -> state.rename(n.id, v) } }) { Text("Rename") }
                }
                TextButton(onClick = { state.selected = state.graph.nodes.map { it.id }.toSet() }) { Text("Select all") }
                // Node Wrangler: change the blend of every selected Mix at once.
                if (sel.any { id -> state.graph.node(id)?.type == "mix" }) {
                    var open by remember { mutableStateOf(false) }
                    Box {
                        TextButton(onClick = { open = true }) { Text("Blend for all ▾") }
                        DropdownMenu(open, onDismissRequest = { open = false }) {
                            dk.cbavf.studio.nodes.model.BLEND_NAMES.forEach { b -> DropdownMenuItem(text = { Text(b) }, onClick = { state.setForSelected("Blend", b); open = false }) }
                        }
                    }
                }
            }
        }
    }
}

/** Searchable list of every node, by category (Blender's Shift+A). */
@Composable
private fun AddMenu(state: NodesState, onDismiss: () -> Unit, onAdd: (NodeDef) -> Unit) {
    var query by remember { mutableStateOf("") }
    val matches = state.catalog.all.filter { state.addable(it.type) }.filter { query.isBlank() || it.label.contains(query, ignoreCase = true) || it.category.label.contains(query, ignoreCase = true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add node") },
        text = {
            Column {
                OutlinedTextField(query, { query = it }, singleLine = true, label = { Text("Search") })
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    NodeCategory.entries.forEach { cat ->
                        val inCat = matches.filter { it.category == cat }
                        if (inCat.isEmpty()) return@forEach
                        item { Text(cat.label, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.labelLarge, color = Color(cat.argb)) }
                        items(inCat) { d ->
                            Column(Modifier.fillMaxWidth().clickable { onAdd(d) }.padding(vertical = 6.dp, horizontal = 4.dp)) {
                                Text(d.label)
                                if (d.help.isNotEmpty()) Text(d.help, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun PresetsDialog(state: NodesState, onDismiss: () -> Unit, onSave: (String) -> Unit, onDelete: (GraphPreset) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Presets") },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Save current graph as") }, modifier = Modifier.weight(1f))
                    TextButton(onClick = { onSave(name.trim()); name = "" }, enabled = name.isNotBlank() && state.graph.nodes.isNotEmpty()) { Text("Save") }
                }
                LazyColumn(Modifier.heightIn(max = 380.dp)) {
                    if (state.presets.isNotEmpty()) item { Text("Yours", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.labelLarge) }
                    items(state.presets) { p ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(p.name, Modifier.weight(1f).clickable { state.load(p.graph); onDismiss() }.padding(vertical = 8.dp))
                            TextButton(onClick = { onDelete(p) }) { Text("Delete") }
                        }
                    }
                    item { Text("Built in", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.labelLarge) }
                    items(NodesState.builtInPresets()) { p ->
                        Text(p.name, Modifier.fillMaxWidth().clickable { state.load(p.graph); onDismiss() }.padding(vertical = 8.dp))
                    }
                    item {
                        TextButton(onClick = { state.load(Graph()); onDismiss() }) { Text("New empty graph") }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/** A frame: a tinted, labelled box behind its nodes, with a resize corner. */
@Composable
private fun FrameBox(f: dk.cbavf.studio.nodes.model.Frame, selected: Boolean) {
    Box(
        Modifier
            .offset(f.x.dp, f.y.dp)
            .size(f.w.dp, f.h.dp)
            .background(Color(f.argb), RoundedCornerShape(8.dp))
            .border(if (selected) 2.dp else 1.dp, if (selected) Color(0xFFFFA733) else Color(0x55FFFFFF), RoundedCornerShape(8.dp))
    ) {
        Text(
            f.label,
            Modifier.fillMaxWidth().height(NodesState.FRAME_HEADER.dp).padding(horizontal = 10.dp, vertical = 5.dp),
            color = Color(0xDDFFFFFF), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Text("◢", Modifier.align(Alignment.BottomEnd).padding(4.dp), color = Color(0x99FFFFFF), fontSize = 14.sp)
    }
}

@Composable
private fun RenameDialog(title: String, initial: String, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(text, { text = it }, singleLine = true) },
        confirmButton = { TextButton(onClick = { onDone(text.trim()) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
