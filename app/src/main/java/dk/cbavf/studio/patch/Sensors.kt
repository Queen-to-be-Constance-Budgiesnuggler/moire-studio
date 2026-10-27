package dk.cbavf.studio.patch

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import dk.cbavf.studio.foreground.RunGate
import dk.cbavf.studio.patch.model.SensorWant
import dk.cbavf.studio.patch.model.SensorWants
import dk.cbavf.studio.patch.model.SourceKind
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Latest raw value of every hardware source, from whichever sensors are switched on. Values are
 * written on sensor/audio threads and read on the UI thread; a stale read is harmless.
 * While the studio is off screen ([onStop]) every sensor and the microphone are let go; [onStart] switches back on what the
 * owners still want.
 */
class SensorHub(context: Context) : SensorEventListener, RunGate.Hook {
    private val manager = context.getSystemService(SensorManager::class.java)
    private val values = FloatArray(SourceKind.entries.size)
    private val rotation = FloatArray(9)
    private val orientation = FloatArray(3)
    private var active = emptySet<Int>()
    /** The microphone thread; an old thread sees it is no longer this one and ends, so a quick stop and start never runs two. */
    @Volatile
    private var mic: Thread? = null

    operator fun get(kind: SourceKind): Float = values[kind.ordinal]

    private val wants = HashMap<String, SensorWant>()

    /** True while the studio is off screen: nothing runs, but what each owner wants is kept for [onStart]. */
    private var paused = false

    /**
     * Says what [owner] needs: the hub switches on the sources every owner wants together and releases the rest. A second owner no
     * longer switches off what the first one is using.
     */
    fun use(kinds: Set<SourceKind>, micAllowed: Boolean, owner: String) {
        if (kinds.isEmpty() && !micAllowed) wants.remove(owner) else wants[owner] = SensorWant(kinds, micAllowed)
        apply()
    }

    /** Switches hardware on for exactly what the owners want together (others are released; all of it while paused). */
    private fun apply() {
        val merged = SensorWants.merge(wants.values, paused)
        val types = merged.kinds.mapNotNull { sensorType(it) }.toSet()
        if (types != active) {
            manager.unregisterListener(this)
            types.forEach { type -> manager.getDefaultSensor(type)?.let { manager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) } }
            active = types
        }
        val wantMic = SensorWants.micOn(merged)
        if (wantMic && mic == null) startMic() else if (!wantMic && mic != null) stopMic()
    }

    /** [owner] needs nothing any more; what other owners use stays on. */
    fun stop(owner: String) = use(emptySet(), micAllowed = false, owner = owner)

    /** The studio left the screen: every sensor and the microphone are let go. */
    override fun onStop() {
        paused = true
        apply()
    }

    /** The studio is back: what the owners still want is switched on again. */
    override fun onStart() {
        paused = false
        apply()
    }

    /** The studio is closing: nothing is wanted any more. */
    fun release() {
        wants.clear()
        apply()
    }

    /** Which sources this phone actually has. */
    fun available(kind: SourceKind): Boolean {
        val type = sensorType(kind) ?: return true
        return manager.getDefaultSensor(type) != null
    }

    override fun onSensorChanged(e: SensorEvent) {
        when (e.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> {
                SensorManager.getRotationMatrixFromVector(rotation, e.values)
                SensorManager.getOrientation(rotation, orientation)
                values[SourceKind.Azimuth.ordinal] = Math.toDegrees(orientation[0].toDouble()).toFloat()
                values[SourceKind.Pitch.ordinal] = Math.toDegrees(orientation[1].toDouble()).toFloat()
                values[SourceKind.Roll.ordinal] = Math.toDegrees(orientation[2].toDouble()).toFloat()
            }
            Sensor.TYPE_ACCELEROMETER -> {
                values[SourceKind.AccelX.ordinal] = e.values[0]
                values[SourceKind.AccelY.ordinal] = e.values[1]
                values[SourceKind.AccelZ.ordinal] = e.values[2]
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                val (x, y, z) = Triple(e.values[0], e.values[1], e.values[2])
                values[SourceKind.Magnet.ordinal] = sqrt(x * x + y * y + z * z)
            }
            Sensor.TYPE_LIGHT -> values[SourceKind.Light.ordinal] = e.values[0]
            // Most proximity sensors are binary: anything under the maximum range counts as near.
            Sensor.TYPE_PROXIMITY -> values[SourceKind.Proximity.ordinal] = if (e.values[0] < e.sensor.maximumRange) 1f else 0f
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    /** Microphone level as RMS in dBFS (−60..0), from a small mono buffer; nothing is recorded or kept. */
    @SuppressLint("MissingPermission")
    private fun startMic() {
        mic = Thread {
            val rate = 16_000
            val size = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT).coerceAtLeast(1024)
            val record = runCatching {
                AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size)
            }.getOrNull() ?: return@Thread
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                return@Thread
            }
            val buf = ShortArray(size / 2)
            record.startRecording()
            while (mic === Thread.currentThread()) {
                val n = record.read(buf, 0, buf.size)
                // An error (the microphone was taken away) ends the thread instead of spinning on it.
                if (n < 0) break
                if (n == 0) continue
                var sum = 0.0
                for (i in 0 until n) sum += buf[i] * buf[i].toDouble()
                val rms = sqrt(sum / n) / Short.MAX_VALUE
                values[SourceKind.Mic.ordinal] = (20 * log10(rms.coerceAtLeast(1e-6))).toFloat().coerceIn(-60f, 0f)
            }
            record.stop()
            record.release()
        }.apply { isDaemon = true }
        mic?.start()
    }

    private fun stopMic() {
        mic = null
    }

    private fun sensorType(kind: SourceKind): Int? = when (kind) {
        SourceKind.Azimuth, SourceKind.Pitch, SourceKind.Roll -> Sensor.TYPE_ROTATION_VECTOR
        SourceKind.AccelX, SourceKind.AccelY, SourceKind.AccelZ -> Sensor.TYPE_ACCELEROMETER
        SourceKind.Magnet -> Sensor.TYPE_MAGNETIC_FIELD
        SourceKind.Light -> Sensor.TYPE_LIGHT
        SourceKind.Proximity -> Sensor.TYPE_PROXIMITY
        else -> null
    }
}
