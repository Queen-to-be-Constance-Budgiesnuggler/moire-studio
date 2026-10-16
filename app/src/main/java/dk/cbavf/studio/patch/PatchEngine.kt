package dk.cbavf.studio.patch

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import dk.cbavf.studio.animate.AnimateState
import dk.cbavf.studio.draw.DrawState
import dk.cbavf.studio.loop.LoopEngine
import dk.cbavf.studio.patch.model.Param
import dk.cbavf.studio.patch.model.PatchDoc
import dk.cbavf.studio.patch.model.PatchMath
import dk.cbavf.studio.patch.model.Route
import dk.cbavf.studio.patch.model.SourceKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Runs the patch: every display frame, reads each route's source, maps and smooths it, and sets
 * the parameter on the Draw canvas. Recording appends the canvas to Animate as frames.
 */
class PatchEngine(
    private val sensors: SensorHub,
    private val draw: DrawState,
    private val animate: AnimateState,
    private val loop: LoopEngine,
    private val scope: CoroutineScope,
) {
    var doc by mutableStateOf(PatchDoc())
    var live by mutableStateOf(false)
        private set
    var recording by mutableStateOf(false)
        private set
    var recorded by mutableIntStateOf(0)
        private set
    var micAllowed by mutableStateOf(false)

    /** Touch pad position, 0..1. */
    var touchX by mutableFloatStateOf(0.5f)
    var touchY by mutableFloatStateOf(0.5f)

    /** Latest normalized source value and output per route, for the meters. */
    val meters = mutableStateMapOf<Int, Pair<Float, Float>>()

    private val smoothed = HashMap<Int, Float>()
    private var job: Job? = null
    private var nextId = 1

    fun load(d: PatchDoc) {
        doc = d
        nextId = (d.routes.maxOfOrNull { it.id } ?: 0) + 1
    }

    fun addRoute(source: SourceKind, layerId: Int, param: Param): Route {
        val r = Route(nextId++, source, layerId = layerId, param = param, min = param.defaultRange.start, max = param.defaultRange.endInclusive)
        doc = doc.copy(routes = doc.routes + r)
        return r
    }

    fun update(r: Route) {
        doc = doc.copy(routes = doc.routes.map { if (it.id == r.id) r else it })
        refreshHardware()
    }

    fun remove(id: Int) {
        doc = doc.copy(routes = doc.routes.filterNot { it.id == id })
        meters.remove(id)
        refreshHardware()
    }

    fun start() {
        if (live) return
        // One undo step in Draw takes back everything the patch did in this session.
        draw.checkpoint()
        live = true
        smoothed.clear()
        refreshHardware()
        job = scope.launch { run() }
    }

    fun stop() {
        live = false
        recording = false
        job?.cancel()
        sensors.stop()
    }

    fun toggleRecording() {
        if (!live) start()
        recording = !recording
        recorded = 0
    }

    /** Hardware runs only for the local sources some enabled route reads. */
    fun refreshHardware() {
        if (!live) return
        val local = doc.routes.filter { it.enabled && !it.remote }.map { it.source }.toSet()
        // A remote route on the other phone may need our sensors too: share them all while linked.
        val shared = if (loop.connected) SourceKind.entries.filter { !it.isLfo && !it.isTouch }.toSet() else emptySet()
        sensors.use(local + shared, micAllowed)
    }

    private suspend fun run() {
        var t0 = -1L
        var last = 0L
        var lastFrameAt = -1.0
        var lastShare = 0.0
        while (scope.isActive && live) {
            val now = withFrameNanos { it }
            if (t0 < 0) {
                t0 = now
                last = now
            }
            val t = (now - t0) / 1e9
            val dt = ((now - last) / 1e9).toFloat().coerceIn(0f, 0.25f)
            last = now

            val remote = loop.remoteSensors
            for (r in doc.routes) {
                if (!r.enabled) continue
                val raw = when {
                    r.source == SourceKind.Lfo1 -> PatchMath.lfo(doc.lfo1, t, seed = 1)
                    r.source == SourceKind.Lfo2 -> PatchMath.lfo(doc.lfo2, t, seed = 2)
                    r.source == SourceKind.TouchX -> touchX
                    r.source == SourceKind.TouchY -> touchY
                    r.remote -> remote[r.source] ?: continue
                    else -> sensors[r.source]
                }
                val n = if (r.source.isLfo || r.source.isTouch) raw else PatchMath.normalize(r.source, raw)
                val target = PatchMath.map(r, n)
                val v = PatchMath.smooth(smoothed[r.id] ?: target, target, r.smoothing, dt)
                smoothed[r.id] = v
                val i = draw.layers.indexOfFirst { it.id == r.layerId }
                if (i >= 0) draw.layers[i] = PatchMath.apply(draw.layers[i], r.param, v)
                meters[r.id] = n to v
            }

            if (recording) {
                val period = 1.0 / animate.fps.coerceAtLeast(1)
                if (lastFrameAt < 0 || t - lastFrameAt >= period) {
                    animate.record(draw.toDoc(), firstOfTake = recorded == 0)
                    recorded++
                    lastFrameAt = t
                }
            } else {
                lastFrameAt = -1.0
            }

            if (loop.connected && t - lastShare >= SHARE_PERIOD_SEC) {
                lastShare = t
                val values = SourceKind.entries.filter { !it.isLfo && !it.isTouch }.associateWith { sensors[it] }
                scope.launch { loop.sendSensors(values) }
            }
        }
    }

    companion object {
        private const val SHARE_PERIOD_SEC = 0.05
    }
}
