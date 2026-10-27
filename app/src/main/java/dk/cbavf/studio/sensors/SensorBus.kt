package dk.cbavf.studio.sensors

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import dk.cbavf.studio.foreground.Foreground
import dk.cbavf.studio.foreground.RunGate
import dk.cbavf.studio.patch.SensorHub
import dk.cbavf.studio.patch.model.PatchMath
import dk.cbavf.studio.patch.model.SourceKind
import dk.cbavf.studio.routing.Router
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * The phone's sensors as rack outputs: when [on], the sensors are switched on and each frame their values (scaled 0..1) go out on the
 * "Sensors" unit's jacks, ready to patch into anything that takes a value. "Seed" is a whole number mixed from all the readings,
 * refreshed twice a second, for the labs that take a seed (a corruption seeded by the world). While the studio is off screen the
 * jacks are not updated, a recording is ended and kept, and the hub lets the sensors go.
 */
class SensorBus(
    private val hub: SensorHub,
    private val micAllowed: () -> Boolean,
    /** Called with a finished recording: it is kept with the project. */
    private val saveTake: (SensorTake) -> Unit = {},
) {
    var on by mutableStateOf(false)

    /** True while the readings are being recorded. */
    var recording by mutableStateOf(false)

    /** True while the jacks carry the recorded take (looped) instead of the live sensors. */
    var replaying by mutableStateOf(false)

    /** The take in hand: the last one recorded or loaded from the project. */
    var take by mutableStateOf<SensorTake?>(null)

    private val wanted = listOf(
        SourceKind.Azimuth, SourceKind.Pitch, SourceKind.Roll, SourceKind.AccelX, SourceKind.AccelY, SourceKind.AccelZ,
        SourceKind.Light, SourceKind.Mic,
    )

    suspend fun run(router: Router, gate: RunGate = Foreground.gate) {
        var was = false
        var seedAt = 0L
        var recorder: TakeRecorder? = null
        var recordStart = 0L
        var replayStart = 0L
        var wasReplaying = false
        while (true) {
            val live = on && gate.isStarted
            if (live != was) {
                if (live) hub.use(wanted.toSet(), micAllowed(), owner = "bus") else hub.stop("bus")
                was = live
            }
            // Leaving the screen ends a recording, so the take is kept and no gap is recorded into it.
            if (!gate.isStarted && recording) recording = false
            if (!live) {
                val r = recorder
                if (r != null) {
                    recorder = null
                    val done = r.finish()
                    if (done.frames.size >= 2) {
                        take = done
                        saveTake(done)
                    }
                }
                gate.sleep(250)
                continue
            }
            val now = withFrameMillis { it }
            // Recording starts and stops with the flag; a finished take is saved.
            if (recording && recorder == null) {
                recorder = TakeRecorder(wanted.map { it.name }, hz = 20)
                recordStart = now
            } else if (!recording && recorder != null) {
                val done = recorder.finish()
                recorder = null
                if (done.frames.size >= 2) {
                    take = done
                    saveTake(done)
                }
            }
            if (replaying && !wasReplaying) replayStart = now
            wasReplaying = replaying
            val playing = if (replaying) take else null
            val replayed = playing?.valueAt(now - replayStart, loop = true)
            // What every jack reads: the take's values while replaying, the live sensors otherwise.
            fun raw(kind: SourceKind): Float = replayed?.getOrNull(wanted.indexOf(kind)) ?: hub[kind]
            recorder?.add(now - recordStart, wanted.map { hub[it] })
            fun out(jack: String, kind: SourceKind) = router.publishLevel(jack, PatchMath.normalize(kind, raw(kind)).coerceIn(0f, 1f))
            out("sensors.roll", SourceKind.Roll)
            out("sensors.pitch", SourceKind.Pitch)
            out("sensors.heading", SourceKind.Azimuth)
            out("sensors.light", SourceKind.Light)
            out("sensors.mic", SourceKind.Mic)
            // Shake: how far the acceleration is from plain gravity, 0 at rest and 1 at about a g of shaking.
            val ax = raw(SourceKind.AccelX)
            val ay = raw(SourceKind.AccelY)
            val az = raw(SourceKind.AccelZ)
            router.publishLevel("sensors.shake", (abs(sqrt(ax * ax + ay * ay + az * az) - 9.81f) / 9.81f).coerceIn(0f, 1f))
            if (now - seedAt >= 500) {
                seedAt = now
                val mixed = SeedHash.fromFloats(wanted.map { raw(it) }.toFloatArray())
                router.publishLevel("sensors.seed", (mixed ushr 40).toFloat())
            }
        }
    }
}
