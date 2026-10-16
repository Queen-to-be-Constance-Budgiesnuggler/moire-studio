package dk.cbavf.studio.nodes.model

import dk.cbavf.studio.draw.model.EllipseHatch
import dk.cbavf.studio.draw.model.Grating
import dk.cbavf.studio.draw.model.Layer
import dk.cbavf.studio.draw.model.Profile
import dk.cbavf.studio.draw.model.RadialBurst
import dk.cbavf.studio.library.CanvasDoc
import dk.cbavf.studio.draw.model.Blend
import dk.cbavf.studio.patch.model.Lfo
import dk.cbavf.studio.patch.model.LfoShape
import dk.cbavf.studio.patch.model.PatchMath
import dk.cbavf.studio.patch.model.SourceKind
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin

/** What flows along a link. Images are opaque handles owned by the [ImageBackend]. */
sealed interface NodeValue {
    data class Num(val v: Float) : NodeValue
    data class Img(val handle: Any, val width: Int, val height: Int) : NodeValue
    data class Cnv(val doc: CanvasDoc) : NodeValue
    data class Frm(val items: List<FrameRef>) : NodeValue
}

/** One frame of a Frames value: a canvas recipe (Animate) or a Library image (Sequence). */
sealed interface FrameRef {
    data class Canvas(val doc: CanvasDoc) : FrameRef
    data class Asset(val id: String) : FrameRef
}

/**
 * The moment a graph is evaluated at, and the live inputs behind time nodes. Without Live the
 * graph is shown at time 0 with no sensors. [state] outlives one run (sensor smoothing, sample
 * and hold), keyed by node id.
 */
class TimeEnv(
    val t: Double = 0.0,
    val dt: Float = 0f,
    val sensor: (SourceKind, remote: Boolean) -> Float? = { _, _ -> null },
    /** Keyed by node path ("12", or "4/12" for node 12 inside Group node 4). */
    val state: MutableMap<String, Float> = HashMap(),
)

/** How a group's body reaches the Group node outside it: its inputs' values and identities. */
class GroupOuter(val input: (Int) -> NodeValue?, val key: (Int) -> String)

/** The image work, supplied by the platform (Android bitmaps on the phone, fakes in tests). */
interface ImageBackend {
    fun libraryImage(assetId: String, maxPx: Int): NodeValue.Img?
    fun drawCanvas(): CanvasDoc?
    /** Frames of a room: "animate" or "sequence". */
    fun frames(source: String): List<FrameRef> = emptyList()
    /** The latest picture from the linked phone, at most [maxPx] on its long side. */
    fun remoteImage(maxPx: Int): NodeValue.Img? = null
    /** How many pictures have come from the linked phone (changes with each one). */
    fun remoteCount(): Int = 0
    fun render(doc: CanvasDoc, width: Int, height: Int, paper: Boolean, maxPx: Int): NodeValue.Img
    /** Image → image operations by node type, with the node's resolved numbers and properties. */
    fun process(type: String, inputs: List<NodeValue?>, nums: List<Float>, props: Map<String, String>, maxPx: Int): NodeValue.Img?
}

/**
 * Pulls values through the graph: only what is asked for is computed, once per run, and image
 * results are kept across runs in [cache] by a key of the node, its settings and its inputs, so
 * moving one slider only recomputes the nodes downstream of it.
 */
class Evaluator(
    private val graph: Graph,
    private val backend: ImageBackend,
    private val maxPx: Int,
    private val cache: MutableMap<String, NodeValue> = HashMap(),
    catalog: Catalog? = null,
    private val checkCancel: () -> Unit = {},
    private val env: TimeEnv = TimeEnv(),
    /** The project's node groups (the top graph's); a group body is evaluated with them too. */
    private val groups: List<NodeGroup> = graph.groups,
    /** Set when [graph] is a group's body. */
    private val outer: GroupOuter? = null,
    private val prefix: String = "",
    private val depth: Int = 0,
) {
    private val catalog: Catalog = catalog ?: graph.catalogWith(groups)
    private val memo = HashMap<Pair<Int, Int>, NodeValue?>()
    private val keys = HashMap<Int, String>()
    private val inner = HashMap<Int, Evaluator?>()

    /** Output [socket] of node [id]; null when unconnected or unavailable. */
    fun value(id: Int, socket: Int = 0): NodeValue? = memo.getOrPut(id to socket) { compute(id, socket) }

    /** What an input of a node receives: its link's value, or its own field. */
    fun input(node: Node, index: Int): NodeValue? {
        val def = catalog[node.type]?.inputs?.getOrNull(index) ?: return null
        val link = graph.linkInto(node.id, index)
        if (link != null) {
            val v = value(link.fromNode, link.fromSocket) ?: return null
            // A canvas feeding an image input is rendered at its own size.
            if (def.type == SocketType.Image && v is NodeValue.Cnv) {
                val w = v.doc.widthPx.takeIf { it > 0 } ?: DEFAULT_W
                val h = v.doc.heightPx.takeIf { it > 0 } ?: DEFAULT_H
                return backend.render(v.doc.copy(widthPx = w, heightPx = h), w, h, paper = false, maxPx)
            }
            return v
        }
        return if (def.type == SocketType.Value) NodeValue.Num(field(node, def)) else null
    }

    private fun field(node: Node, def: SocketDef): Float = (node.props[def.name] as? Prop.Num)?.v ?: def.default

    private fun num(node: Node, i: Int): Float = (input(node, i) as? NodeValue.Num)?.v ?: catalog[node.type]!!.inputs[i].default

    private fun prop(node: Node, name: String): String {
        val def = catalog[node.type]?.props?.firstOrNull { it.name == name }
        return when (val p = node.props[name]) {
            is Prop.Choice -> p.v
            is Prop.Text -> p.v
            else -> def?.default ?: ""
        }
    }

    /** Identity of what an input receives (for keys across a group's edge). */
    private fun inputKey(n: Node, i: Int): String =
        graph.linkInto(n.id, i)?.let { "${key(it.fromNode)}:${it.fromSocket}" }
            ?: "f${(n.props[catalog[n.type]?.inputs?.getOrNull(i)?.name ?: ""] as? Prop.Num)?.v}"

    /** The evaluator of Group node [n]'s body, fed by this graph. */
    private fun body(n: Node): Evaluator? = inner.getOrPut(n.id) {
        val g = groups.firstOrNull { GROUP_PREFIX + it.id == n.type } ?: return@getOrPut null
        if (depth >= MAX_DEPTH) return@getOrPut null
        Evaluator(
            g.graph, backend, maxPx, cache, null, checkCancel, env, groups,
            GroupOuter({ i -> input(n, i) }, { i -> inputKey(n, i) }), "$prefix${n.id}/", depth + 1,
        )
    }

    /**
     * Identity of a node's output: type, settings and (recursively) inputs. A node with only number
     * outputs is identified by its values, so an image fed by a moving LFO is recomputed only when
     * the number actually changes (and comes from the cache when it repeats).
     */
    fun key(id: Int): String = keys.getOrPut(id) {
        val n = graph.node(id) ?: return@getOrPut "missing$id"
        val def = catalog[n.type]
        if (def != null && def.outputs.isNotEmpty() && def.outputs.all { it.type in NUMBER_TYPES }) {
            return@getOrPut "=" + def.outputs.indices.joinToString(",") { (value(id, it) as? NodeValue.Num)?.v?.toString() ?: "_" }
        }
        if (n.type == GROUP_IN) return@getOrPut "in[" + graph.inputs.indices.joinToString(",") { outer?.key?.invoke(it) ?: "_" } + "]"
        val ins = catalog[n.type]?.inputs?.indices?.joinToString(",") { i ->
            graph.linkInto(id, i)?.let { "${key(it.fromNode)}:${it.fromSocket}" } ?: "_"
        }
        "${n.type}|${n.muted}|${n.props.toSortedMap()}|[$ins]|$maxPx" + when {
            n.type == "draw" -> "|${backend.drawCanvas().hashCode()}"
            n.type == "animate" || n.type == "sequence" -> "|${backend.frames(n.type).hashCode()}"
            n.type == "fromphone" -> "|${backend.remoteCount()}"
            // Editing inside a group changes every use of it.
            n.type.startsWith(GROUP_PREFIX) -> "|${groups.firstOrNull { GROUP_PREFIX + it.id == n.type }?.graph.hashCode()}"
            else -> ""
        }
    }

    private fun compute(id: Int, socket: Int): NodeValue? {
        checkCancel()
        val n = graph.node(id) ?: return null
        val def = catalog[n.type] ?: return null
        if (n.muted) {
            // Pass the first input of the output's type straight through.
            val outType = def.outputs.getOrNull(socket)?.type ?: return null
            val i = def.inputs.indexOfFirst { it.type == outType }
            return if (i >= 0) input(n, i) else null
        }
        val cacheable = def.outputs.getOrNull(socket)?.type == SocketType.Image
        // Only images are cached; a number node's key is its value, so it must not ask for it here.
        val cacheKey = if (cacheable) key(id) + "#$socket" else ""
        if (cacheable) cache[cacheKey]?.let { return it }
        if (n.type.startsWith(GROUP_PREFIX) || n.type == GROUP_IN) {
            val out = if (n.type == GROUP_IN) outer?.input?.invoke(socket)
            else body(n)?.let { b -> b.groupOutput(socket) }
            if (cacheable && out != null) cache[cacheKey] = out
            return out
        }
        val out = when (n.type) {
            "value" -> NodeValue.Num(num(n, 0))
            "math" -> NodeValue.Num(math(prop(n, "Operation"), num(n, 0), num(n, 1)))
            "maprange" -> {
                val (v, a, b, c, d) = (0..4).map { num(n, it) }
                val t = if (b == a) 0f else (v - a) / (b - a)
                NodeValue.Num(c + (d - c) * (if (prop(n, "Clamp") == "On") t.coerceIn(0f, 1f) else t))
            }
            "image" -> prop(n, "asset").takeIf { it.isNotBlank() }?.let { backend.libraryImage(it, maxPx) }
            "draw" -> backend.drawCanvas()?.let { NodeValue.Cnv(it) }
            "grating" -> NodeValue.Cnv(generator(Grating(num(n, 0), num(n, 1), num(n, 2), num(n, 3), if (prop(n, "Profile") == "Sine") Profile.Sine else Profile.Lines)))
            "burst" -> NodeValue.Cnv(generator(RadialBurst(num(n, 0).toInt(), num(n, 2), num(n, 1))))
            "ellipses" -> NodeValue.Cnv(generator(EllipseHatch(spacingPx = num(n, 0), aspect = num(n, 1), rotationDeg = num(n, 2), duty = num(n, 3))))
            "render" -> (input(n, 0) as? NodeValue.Cnv)?.let { c ->
                val w = num(n, 1).toInt().coerceIn(1, 8192)
                val h = num(n, 2).toInt().coerceIn(1, 8192)
                backend.render(c.doc.copy(widthPx = w, heightPx = h), w, h, prop(n, "Paper") == "White", maxPx)
            }
            "viewer", "save", "tocompose", "record", "tophone", "projector", Graph.REROUTE -> input(n, 0)
            "fromphone" -> if (socket == 1) NodeValue.Num(backend.remoteCount().toFloat()) else backend.remoteImage(maxPx)
            GROUP_OUT -> input(n, socket)
            "stack" -> {
                val a = (input(n, 0) as? NodeValue.Cnv)?.doc
                val b = (input(n, 1) as? NodeValue.Cnv)?.doc
                if (a == null || b == null) a?.let(NodeValue::Cnv) ?: b?.let(NodeValue::Cnv)
                else {
                    val blend = runCatching { Blend.valueOf(prop(n, "Blend")) }.getOrDefault(Blend.Normal)
                    var next = (a.layers.maxOfOrNull { it.id } ?: 0) + 1
                    val top = b.layers.map { it.copy(id = next++, blend = blend) }
                    val sized = if (a.widthPx > 0) a else a.copy(widthPx = b.widthPx, heightPx = b.heightPx)
                    NodeValue.Cnv(sized.copy(layers = a.layers + top))
                }
            }
            "drive" -> (input(n, 0) as? NodeValue.Cnv)?.let { c ->
                val param = DRIVE_PARAMS.firstOrNull { it.label == prop(n, "Parameter") } ?: DRIVE_PARAMS.first()
                val i = num(n, 1).toInt() - 1
                NodeValue.Cnv(c.doc.copy(layers = c.doc.layers.mapIndexed { j, l -> if (j == i) PatchMath.apply(l, param, num(n, 2)) else l }))
            }
            "clock" -> {
                val rate = num(n, 0).toDouble()
                val steps = num(n, 1).toLong()
                val ticks = floor(env.t * rate).toLong()
                when (socket) {
                    0 -> NodeValue.Num(env.t.toFloat())
                    1 -> NodeValue.Num((if (steps > 0) ticks.mod(steps) else ticks).toFloat())
                    2 -> NodeValue.Num((env.t * rate - ticks).toFloat())
                    else -> NodeValue.Num(if (env.dt > 0f && rate > 0 && ticks != floor((env.t - env.dt) * rate).toLong()) 1f else 0f)
                }
            }
            "lfo" -> {
                val rate = num(n, 0)
                val shape = runCatching { LfoShape.valueOf(prop(n, "Shape")) }.getOrDefault(LfoShape.Sine)
                val x = PatchMath.lfo(Lfo(shape, rate), env.t + if (rate > 0f) num(n, 3) / rate.toDouble() else 0.0, seed = id)
                NodeValue.Num(num(n, 1) + (num(n, 2) - num(n, 1)) * x)
            }
            "sensor" -> {
                val kind = SENSOR_SOURCES.firstOrNull { it.label == prop(n, "Source") } ?: SENSOR_SOURCES.first()
                val raw = env.sensor(kind, prop(n, "Phone") == "Other")
                if (socket == 1) NodeValue.Num(raw ?: kind.range.start)
                else {
                    val target = raw?.let { PatchMath.normalize(kind, it) } ?: 0f
                    val v = PatchMath.smooth(env.state["$prefix$id"] ?: target, target, num(n, 2), env.dt)
                    env.state["$prefix$id"] = v
                    NodeValue.Num(num(n, 0) + (num(n, 1) - num(n, 0)) * v)
                }
            }
            "hold" -> {
                val fired = ((input(n, 1) as? NodeValue.Num)?.v ?: 0f) >= 0.5f
                val k = "$prefix$id"
                if (fired || k !in env.state) env.state[k] = num(n, 0)
                NodeValue.Num(env.state.getValue(k))
            }
            "animate", "sequence" -> {
                val items = backend.frames(n.type)
                if (socket == 0) NodeValue.Frm(items) else NodeValue.Num(items.size.toFloat())
            }
            "frameat" -> {
                val items = (input(n, 0) as? NodeValue.Frm)?.items.orEmpty()
                if (socket == 1) NodeValue.Num(items.size.toFloat())
                else frameIndex(num(n, 1).toInt(), items.size, prop(n, "Wrap"))?.let { i ->
                    when (val f = items[i]) {
                        is FrameRef.Asset -> backend.libraryImage(f.id, maxPx)
                        is FrameRef.Canvas -> {
                            val w = f.doc.widthPx.takeIf { it > 0 } ?: DEFAULT_W
                            val h = f.doc.heightPx.takeIf { it > 0 } ?: DEFAULT_H
                            backend.render(f.doc.copy(widthPx = w, heightPx = h), w, h, paper = true, maxPx)
                        }
                    }
                }
            }
            else -> {
                // Image processors: the image first, then their numbers.
                val ins = def.inputs.indices.map { input(n, it) }
                if (ins.firstOrNull() == null) return null
                val nums = def.inputs.indices.map { i -> (ins[i] as? NodeValue.Num)?.v ?: def.inputs[i].default }
                val props = def.props.associate { it.name to prop(n, it.name) }
                backend.process(n.type, ins, nums, props, maxPx)
            }
        }
        if (cacheable && out != null) cache[cacheKey] = out
        return out
    }

    /** What this group body's Group Output receives at [socket]. */
    private fun groupOutput(socket: Int): NodeValue? {
        val out = graph.nodes.firstOrNull { it.type == GROUP_OUT } ?: return null
        return input(out, socket)
    }

    companion object {
        /** Groups inside groups, this deep at most (a loop of groups stops here). */
        private const val MAX_DEPTH = 8
        const val DEFAULT_W = 1080
        const val DEFAULT_H = 1920
        private val NUMBER_TYPES = setOf(SocketType.Value, SocketType.Signal, SocketType.Trigger)

        /** Index into [count] frames: wrapped round (Loop), bounced (PingPong) or held at the ends. */
        fun frameIndex(i: Int, count: Int, wrap: String): Int? = when {
            count <= 0 -> null
            wrap == "Clamp" -> i.coerceIn(0, count - 1)
            wrap == "PingPong" && count > 1 -> {
                val p = i.mod(2 * count - 2)
                if (p < count) p else 2 * count - 2 - p
            }
            else -> i.mod(count)
        }

        fun math(op: String, a: Float, b: Float): Float = when (op) {
            "Add" -> a + b
            "Subtract" -> a - b
            "Multiply" -> a * b
            "Divide" -> if (b == 0f) 0f else a / b
            "Power" -> a.toDouble().pow(b.toDouble()).toFloat()
            "Minimum" -> minOf(a, b)
            "Maximum" -> maxOf(a, b)
            "Sine" -> sin(a * PI.toFloat() / 180) * (if (b == 0f) 1f else b)
            "Modulo" -> if (b == 0f) 0f else a.mod(b)
            else -> a
        }

        /** A generator as a size-less canvas; it fills whatever it is rendered into. */
        private fun generator(content: dk.cbavf.studio.draw.model.Content) = CanvasDoc(0, 0, listOf(Layer(1, content)))
    }
}

