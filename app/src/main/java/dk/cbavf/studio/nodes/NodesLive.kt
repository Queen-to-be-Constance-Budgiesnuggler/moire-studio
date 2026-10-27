package dk.cbavf.studio.nodes

import dk.cbavf.studio.debug.Debug
import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import dk.cbavf.studio.animate.AnimateState
import dk.cbavf.studio.foreground.Foreground
import dk.cbavf.studio.library.CanvasDoc
import dk.cbavf.studio.loop.LoopEngine
import dk.cbavf.studio.nodes.model.Evaluator
import dk.cbavf.studio.nodes.model.NodeValue
import dk.cbavf.studio.nodes.model.Prop
import dk.cbavf.studio.nodes.model.SENSOR_SOURCES
import dk.cbavf.studio.nodes.model.TimeEnv
import dk.cbavf.studio.patch.SensorHub
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Live mode for Node Lab, the Patch bay's successor: every display frame the graph is
 * evaluated at the current time with live sensors, the backdrop shows the result, and armed
 * Record nodes add frames to Animate.
 */
class NodesLive(
    private val nodes: NodesState,
    private val backend: AndroidImageBackend,
    private val cache: MutableMap<String, NodeValue>,
    private val sensors: SensorHub,
    private val loop: LoopEngine,
    private val animate: AnimateState,
    private val micAllowed: () -> Boolean,
    /** Size given to size-less canvases (generators) when they are recorded: the Draw canvas. */
    private val canvasSize: () -> Pair<Int, Int>,
    /** The long side of this phone's screen, for the Projector node. */
    private val screenPx: () -> Int = { 1920 },
) {
    var live by mutableStateOf(false)
        private set
    /** Record nodes that add frames while live. */
    var armed by mutableStateOf(setOf<Int>())
        private set
    var recorded by mutableIntStateOf(0)
        private set
    var fps by mutableFloatStateOf(0f)
        private set
    var seconds by mutableFloatStateOf(0f)
        private set
    var preview by mutableStateOf<ImageBitmap?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    /** Projector node shown full screen (its input is rendered at screen size instead of the preview). */
    var projecting by mutableStateOf<Int?>(null)
    var projection by mutableStateOf<ImageBitmap?>(null)
        private set
    /** Pictures sent to the other phone this session. */
    var sent by mutableIntStateOf(0)
        private set

    private var job: Job? = null
    private var scope: CoroutineScope? = null

    fun start(scope: CoroutineScope) {
        if (live) return
        this.scope = scope
        live = true
        error = null
        job = scope.launch { run() }
    }

    fun stop() {
        // Not running: leave the sensors alone (the Patch bay may be using them).
        if (!live) return
        live = false
        armed = emptySet()
        job?.cancel()
        job = null
        sensors.stop("nodes")
    }

    /** Arms or disarms a Record node; arming starts a new take (one Undo in Animate removes it). */
    fun toggleArm(id: Int, scope: CoroutineScope) {
        if (id in armed) {
            armed = armed - id
        } else {
            armed = armed + id
            recorded = 0
            start(scope)
        }
    }

    private suspend fun run() {
        val timeState = HashMap<String, Float>()
        val timeObjects = HashMap<String, Any>()
        val lastRecord = HashMap<Int, Double>()
        var t0 = -1L
        var last = 0L
        var lastShare = -1.0
        var counted = 0
        var countFrom = 0.0
        val lastSend = HashMap<Int, Double>()
        var sending = false
        while (live) {
            // Off screen Live waits (the hub has let the sensors go); it goes on where it was when the studio is back.
            Foreground.gate.awaitStarted()
            val now = withFrameNanos { it }
            if (t0 < 0) {
                t0 = now
                last = now
            }
            val t = (now - t0) / 1e9
            val dt = ((now - last) / 1e9).toFloat().coerceIn(0f, 0.5f)
            last = now
            val graph = nodes.top
            useSensors()

            val remote = loop.remoteSensors
            val down = nodes.touching
            val env = TimeEnv(
                t, dt, sensor = { kind, other -> if (other) remote[kind] else sensors[kind] }, state = timeState, objects = timeObjects,
                touching = { it in down },
            )
            // Full screen: the Projector's input at screen size replaces the backdrop preview.
            val projector = projecting?.takeIf { graph.node(it) != null }
            val target = projector ?: nodes.previewTarget
            val px = if (projector != null) screenPx() else LIVE_PX
            val fps = animate.fps.coerceAtLeast(1)
            val armedNow = armed
            val linked = loop.connected && !sending
            val result = try {
                withContext(Dispatchers.Default) {
                    if (cache.size > CACHE_LIMIT) cache.clear()
                    val ev = Evaluator(graph, backend, px, cache, env = env)
                    val image: Bitmap? = target?.let { backend.previewOf(ev.value(it), px) }
                    val takes = graph.nodes.filter { it.type == "record" && !it.muted && it.id in armedNow }.mapNotNull { n ->
                        val fire = if (graph.linkInto(n.id, 1) != null) ((ev.input(n, 1) as? NodeValue.Num)?.v ?: 0f) >= 0.5f
                        else t - (lastRecord[n.id] ?: -1.0) >= 1.0 / fps
                        if (fire) (ev.input(n, 0) as? NodeValue.Cnv)?.doc?.let { n.id to it } else null
                    }
                    // Send to Other Phone: one picture at a time, no faster than its Max fps, at its Size.
                    val outgoing = if (!linked) null else graph.nodes.filter { it.type == "tophone" && !it.muted }.firstNotNullOfOrNull { n ->
                        val maxFps = ((ev.input(n, 1) as? NodeValue.Num)?.v ?: 4f).coerceIn(0.2f, 30f)
                        if (t - (lastSend[n.id] ?: -1.0) < 1.0 / maxFps) return@firstNotNullOfOrNull null
                        val size = ((ev.input(n, 2) as? NodeValue.Num)?.v ?: 1080f).toInt().coerceIn(128, 4096)
                        val png = (n.props["Format"] as? Prop.Choice)?.v == "PNG"
                        val big = if (size == px) ev else Evaluator(graph, backend, size, cache, env = env)
                        backend.previewOf(big.value(n.id), size)?.let { Triple(n.id, it, png) }
                    }
                    Triple(image, takes, outgoing)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Debug.w("Studio", "live graph failed", e)
                error = if (e is OutOfMemoryError) "Not enough memory: Live stopped" else "Live stopped: ${e.message ?: e.javaClass.simpleName}"
                stop()
                return
            }
            if (projector != null) projection = result.first?.asImageBitmap() else preview = result.first?.asImageBitmap()
            result.third?.let { (id, bmp, png) ->
                lastSend[id] = t
                sending = true
                scope?.launch {
                    if (loop.sendFrame(bmp, png)) sent++
                    sending = false
                }
            }
            for ((id, doc) in result.second) {
                lastRecord[id] = t
                animate.record(sized(doc), firstOfTake = recorded == 0)
                recorded++
            }

            // The other phone's Sensor nodes may read ours.
            if (loop.connected && t - lastShare >= SHARE_PERIOD_SEC) {
                lastShare = t
                val values = SENSOR_SOURCES.associateWith { sensors[it] }
                scope?.launch { loop.sendSensors(values) }
            }
            counted++
            if (t - countFrom >= 1.0) {
                this.fps = (counted / (t - countFrom)).toFloat()
                counted = 0
                countFrom = t
            }
            seconds = t.toFloat()
        }
    }

    private fun sized(doc: CanvasDoc): CanvasDoc {
        if (doc.widthPx > 0 && doc.heightPx > 0) return doc
        val (w, h) = canvasSize()
        return doc.copy(widthPx = w.takeIf { it > 0 } ?: Evaluator.DEFAULT_W, heightPx = h.takeIf { it > 0 } ?: Evaluator.DEFAULT_H)
    }

    /** Hardware runs only for sources this graph's Sensor nodes read (all of them while linked, to share). */
    private fun useSensors() {
        val g = nodes.top
        val local = g.nodes.filter { it.type == "sensor" && !it.muted && (it.props["Phone"] as? Prop.Choice)?.v != "Other" }
            .map { n -> SENSOR_SOURCES.firstOrNull { it.label == (n.props["Source"] as? Prop.Choice)?.v } ?: SENSOR_SOURCES.first() }
            .toSet()
        sensors.use(if (loop.connected) SENSOR_SOURCES.toSet() else local, micAllowed(), owner = "nodes")
    }

    companion object {
        /** Live previews are smaller than still ones so the graph keeps up with the display. */
        const val LIVE_PX = 720
        private const val CACHE_LIMIT = 24
        private const val SHARE_PERIOD_SEC = 0.05
    }
}
