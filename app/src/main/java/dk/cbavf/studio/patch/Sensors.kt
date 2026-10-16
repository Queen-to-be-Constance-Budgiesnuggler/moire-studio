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
import dk.cbavf.studio.patch.model.SourceKind
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Latest raw value of every hardware source, from whichever sensors are switched on. Values are
 * written on sensor/audio threads and read on the UI thread; a stale read is harmless.
 */
class SensorHub(context: Context) : SensorEventListener {
    private val manager = context.getSystemService(SensorManager::class.java)
    private val values = FloatArray(SourceKind.entries.size)
    private val rotation = FloatArray(9)
    private val orientation = FloatArray(3)
    private var active = emptySet<Int>()
    private var mic: Thread? = null

    @Volatile
    private var micOn = false

    operator fun get(kind: SourceKind): Float = values[kind.ordinal]

    /** Switches hardware on for exactly the sources in [kinds] (others are released). */
    fun use(kinds: Set<SourceKind>, micAllowed: Boolean) {
        val types = kinds.mapNotNull { sensorType(it) }.toSet()
        if (types != active) {
            manager.unregisterListener(this)
            types.forEach { type -> manager.getDefaultSensor(type)?.let { manager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) } }
            active = types
        }
        val wantMic = SourceKind.Mic in kinds && micAllowed
        if (wantMic && mic == null) startMic() else if (!wantMic && mic != null) stopMic()
    }

    fun stop() = use(emptySet(), micAllowed = false)

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
        micOn = true
        mic = Thread {
            val rate = 16_000
            val size = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT).coerceAtLeast(1024)
            val record = runCatching {
                AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size)
            }.getOrNull() ?: return@Thread
            val buf = ShortArray(size / 2)
            record.startRecording()
            while (micOn) {
                val n = record.read(buf, 0, buf.size)
                if (n <= 0) continue
                var sum = 0.0
                for (i in 0 until n) sum += buf[i] * buf[i].toDouble()
                val rms = sqrt(sum / n) / Short.MAX_VALUE
                values[SourceKind.Mic.ordinal] = (20 * log10(rms.coerceAtLeast(1e-6))).toFloat().coerceIn(-60f, 0f)
            }
            record.stop()
            record.release()
        }.apply { isDaemon = true; start() }
    }

    private fun stopMic() {
        micOn = false
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
