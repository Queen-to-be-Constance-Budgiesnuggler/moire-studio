package dk.cbavf.studio.physics

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import dk.cbavf.studio.draw.model.Pt
import dk.cbavf.studio.foreground.Foreground
import dk.cbavf.studio.library.CanvasDoc
import dk.cbavf.studio.patch.SensorHub
import dk.cbavf.studio.patch.model.SourceKind
import dk.cbavf.studio.physics.model.BodyKind
import dk.cbavf.studio.physics.model.BodySpec
import dk.cbavf.studio.physics.model.Gravity
import dk.cbavf.studio.physics.model.GravitySource
import dk.cbavf.studio.physics.model.Hit
import dk.cbavf.studio.physics.model.Pose
import dk.cbavf.studio.physics.model.ShapeMapper
import dk.cbavf.studio.physics.model.TriggerOut
import dk.cbavf.studio.physics.model.WorldSettings
import dk.cbavf.studio.routing.Router
import dk.cbavf.studio.routing.model.PHYSICS_HIT
import dk.cbavf.studio.routing.model.PHYSICS_HITS
import dk.cbavf.studio.routing.model.PHYSICS_STRENGTH
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * The Physics room: a canvas taken from Draw, its shapes as bodies in a [PhysicsWorld], stepped while Run is on. The frame on
 * show, the hits and what is being recorded live here; the world itself is made again from the canvas on every reset.
 */
class PhysicsState {
    /** The canvas the bodies come from; null until "Take from Draw". */
    var source by mutableStateOf<CanvasDoc?>(null)
        private set

    /** The kind picked for each layer that is not the default. */
    var kinds by mutableStateOf(mapOf<Int, BodyKind>())
        private set

    /** How the world is made. Changing it starts again from the canvas. */
    var settings by mutableStateOf(WorldSettings())
        private set

    var gravitySource by mutableStateOf(GravitySource.Tilt)
    /** Gravity as a share of the Earth's or the tilt's; read every step, so it changes a running world. */
    var gravityScale by mutableFloatStateOf(1f)

    var running by mutableStateOf(false)
    var recording by mutableStateOf(false)
    var outlines by mutableStateOf(true)

    /** The canvas as the bodies are now. */
    var frame by mutableStateOf<CanvasDoc?>(null)
        private set
    var poses by mutableStateOf<Map<Int, Pose>>(emptyMap())
        private set
    /** The gravity used for the last step (m/s², y down the screen). */
    var gravityNow by mutableStateOf(Gravity.down)
        private set
    /** True when the last step used the tilt, false when it fell back to straight down. */
    var tiltInUse by mutableStateOf(false)
        private set
    var hits by mutableIntStateOf(0)
        private set
    var lastStrength by mutableFloatStateOf(0f)
        private set

    /** How long "Bake" simulates, in seconds. */
    var bakeSeconds by mutableFloatStateOf(3f)

    /** Frames kept while Record is on, oldest first; [recordedCount] follows it. */
    private val recorded = ArrayList<CanvasDoc>()
    var recordedCount by mutableIntStateOf(0)
        private set

    internal var world: PhysicsWorld? = null
        private set
    private val triggers = TriggerOut()

    val specs: List<BodySpec> get() = source?.let { ShapeMapper.specs(it, kinds) }.orEmpty()

    /** A new canvas: every body starts as its default kind, and the world starts again. */
    fun load(doc: CanvasDoc) {
        source = doc
        kinds = emptyMap()
        reset()
    }

    fun setKind(layerId: Int, kind: BodyKind) {
        kinds = kinds + (layerId to kind)
        reset()
    }

    fun updateSettings(s: WorldSettings) {
        settings = s.clamped()
        reset()
    }

    /** Back to the canvas as it was taken: bodies where they were drawn, no hits. Recorded frames stay. */
    fun reset() {
        world = null
        frame = source
        poses = emptyMap()
        triggers.reset()
        hits = 0
        lastStrength = 0f
    }

    /** The world for the next step, made from the canvas if there is none; null with no canvas. */
    internal fun worldOrNew(): PhysicsWorld? {
        world?.let { return it }
        val doc = source ?: return null
        return PhysicsWorld(specs, settings, doc.widthPx, doc.heightPx).also { world = it }
    }

    /** One step worked out by [computeStep], not yet shown: made by [world], which a reset may since have thrown away. */
    internal class StepResult(val world: PhysicsWorld, val frame: CanvasDoc, val poses: Map<Int, Pose>, val hits: List<Hit>, val gravity: Pt, val tilt: Boolean)

    /** Steps [w] one frame under [gravity] and draws [doc] as its bodies are then. Call off the main thread. */
    internal fun computeStep(w: PhysicsWorld, doc: CanvasDoc, gravity: Pt, tilt: Boolean): StepResult {
        val h = w.step(gravity)
        return StepResult(w, w.frame(doc), w.poses(), h, gravity, tilt)
    }

    /**
     * Shows a step's result and gives the trigger level for it, or null when a reset made a new world while the step was worked
     * out: that result belongs to the old world and is thrown away.
     */
    internal fun accept(r: StepResult): Float? {
        if (world !== r.world) return null
        return applyStep(r.frame, r.poses, r.hits, r.gravity, r.tilt)
    }

    /** True when Record has kept as many frames as it can. */
    val recordFull: Boolean get() = recordedCount >= MAX_RECORDED

    /** Takes one step's result; gives the trigger level for this step. */
    internal fun applyStep(newFrame: CanvasDoc, newPoses: Map<Int, Pose>, stepHits: List<Hit>, gravity: Pt, tilt: Boolean): Float {
        frame = newFrame
        poses = newPoses
        gravityNow = gravity
        tiltInUse = tilt
        val pulse = triggers.step(stepHits, settings.wallsTrigger)
        hits = triggers.count
        lastStrength = triggers.lastStrength
        if (recording && !recordFull) {
            recorded += newFrame
            recordedCount = recorded.size
        }
        // Full: Record goes off; the room says so.
        if (recordFull) recording = false
        return pulse
    }

    /** The recorded frames, handed over and cleared. */
    fun takeRecorded(): List<CanvasDoc> = recorded.toList().also { clearRecorded() }

    fun clearRecorded() {
        recorded.clear()
        recordedCount = 0
    }

    /** How many frames a bake makes with the current length and frame rate, the start frame included. */
    val bakeFrames: Int get() = bakeSteps(bakeSeconds, settings.fps) + 1

    /** True when the bake length at this frame rate asks for more frames than a bake keeps. */
    val bakeCut: Boolean get() = bakeSeconds * settings.fps > MAX_RECORDED - 1

    /**
     * [bakeSeconds] of the world from the start, one canvas per step (the start first), off the main thread; never more than
     * [MAX_RECORDED] frames in all. Gravity stays as the last tilt that was read, or straight down.
     */
    suspend fun bake(): List<CanvasDoc> {
        val doc = source ?: return emptyList()
        val specs = specs
        val s = settings
        val g = if (gravitySource == GravitySource.Tilt && tiltInUse) gravityNow else Gravity.down * gravityScale
        val n = bakeSteps(bakeSeconds, s.fps)
        return withContext(Dispatchers.Default) {
            val w = PhysicsWorld(specs, s, doc.widthPx, doc.heightPx)
            listOf(w.frame(doc)) + List(n) {
                w.step(g)
                w.frame(doc)
            }
        }
    }

    /**
     * Steps the world while Run is on. Off screen it waits ([Foreground.gate]), the Hit jack goes to 0, and the accelerometer is
     * let go with every other sensor; with Run off it waits too and asks for no sensor. Hits go out on the Physics unit's jacks.
     */
    suspend fun run(sensors: SensorHub, router: Router, rotation: () -> Int) {
        // Leaving the screen mid-step would leave a hit's 1 on the jack until the studio is back.
        val remove = Foreground.gate.add(object : dk.cbavf.studio.foreground.RunGate.Hook {
            override fun onStop() = router.publishLevel(PHYSICS_HIT, 0f)
            override fun onStart() = Unit
        })
        // The sensors asked for, so the hub is only told when that changes (it takes them back by itself after a stop).
        var tiltAsked = false
        fun ask(tilt: Boolean) {
            if (tilt == tiltAsked) return
            tiltAsked = tilt
            if (tilt) sensors.use(TILT_SENSORS, micAllowed = false, owner = SENSOR_OWNER) else sensors.stop(SENSOR_OWNER)
        }
        try {
            while (true) {
                if (!Foreground.gate.isStarted) router.publishLevel(PHYSICS_HIT, 0f)
                Foreground.gate.awaitStarted()
                if (!running) {
                    ask(false)
                    router.publishLevel(PHYSICS_HIT, 0f)
                    snapshotFlow { running }.first { it }
                    continue
                }
                val tilt = gravitySource == GravitySource.Tilt
                ask(tilt)
                val w = worldOrNew()
                val doc = source
                if (w == null || doc == null) {
                    running = false
                    continue
                }
                val reading = if (tilt) Gravity.fromAccel(sensors[SourceKind.AccelX], sensors[SourceKind.AccelY], sensors[SourceKind.AccelZ], rotation(), gravityScale) else null
                val g = Gravity.pick(gravitySource, reading, gravityScale)
                val result = withContext(Dispatchers.Default) { computeStep(w, doc, g, reading != null) }
                val pulse = accept(result) ?: continue
                router.publishLevel(PHYSICS_HIT, pulse)
                router.publishLevel(PHYSICS_STRENGTH, lastStrength)
                router.publishLevel(PHYSICS_HITS, hits.toFloat())
                Foreground.gate.sleep((1000.0 * settings.stepSeconds).toLong())
            }
        } finally {
            remove()
            sensors.stop(SENSOR_OWNER)
        }
    }

    companion object {
        /** Record and Bake keep at most this many frames (20 s at 30 frames a second). */
        const val MAX_RECORDED = 600
        private const val SENSOR_OWNER = "physics"
        private val TILT_SENSORS = setOf(SourceKind.AccelX, SourceKind.AccelY, SourceKind.AccelZ)

        /** Steps in a bake of [seconds] at [fps]: at least one, and with the start frame no more than [MAX_RECORDED] frames. */
        fun bakeSteps(seconds: Float, fps: Int): Int = (seconds * fps).toInt().coerceIn(1, MAX_RECORDED - 1)
    }
}
