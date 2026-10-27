package dk.cbavf.studio.sensors

import kotlinx.serialization.Serializable
import kotlin.math.floor

@Serializable
data class SensorTake(
    val hz: Int,
    val kinds: List<String>,
    val frames: List<List<Float>>
) {
    /**
     * The duration of the sensor take in milliseconds.
     * Returns 0 when there are fewer than two frames.
     */
    val durationMs: Long = if (frames.size < 2) 0L else (frames.size - 1) * 1000L / hz
    
    /**
     * Returns the interpolated values at time [tMs].
     * If [loop] is false, times before 0 return the first frame,
     * and times at or past durationMs return the last frame.
     * If [loop] is true, [tMs] is wrapped into the range [0, durationMs).
     */
    fun valueAt(tMs: Long, loop: Boolean = false): List<Float> {
        if (frames.isEmpty()) return emptyList()
        if (frames.size == 1) return frames[0]
        val t = if (loop && durationMs > 0) Math.floorMod(tMs, durationMs) else tMs
        val pos = t * hz / 1000.0
        if (pos <= 0.0) return frames.first()
        val last = frames.size - 1
        if (pos >= last) return frames[last]
        val i = floor(pos).toInt()
        val f = (pos - i).toFloat()
        return frames[i].mapIndexed { k, v -> v * (1f - f) + frames[i + 1][k] * f }
    }
}

/**
 * A recorder for sensor data that keeps frames at specified intervals.
 */
class TakeRecorder(private val kinds: List<String>, private val hz: Int) {
    private val frames = mutableListOf<List<Float>>()
    private var nextFrameTimeMs = 0L
    
    /**
     * Adds a sensor reading at time [tMs].
     * Only keeps the frame if [tMs] is at or past the time the next frame is due.
     * The first call always keeps a frame (frame 0).
     * If [values.size] does not match [kinds.size], the call is ignored.
     */
    fun add(tMs: Long, values: List<Float>) {
        if (values.size != kinds.size) return
        
        if (frames.isEmpty()) {
            frames.add(values)
            nextFrameTimeMs = 1000L / hz
        } else if (tMs >= nextFrameTimeMs) {
            frames.add(values)
            nextFrameTimeMs += 1000L / hz
        }
    }
    
    /**
     * Finishes the recording and returns a [SensorTake].
     */
    fun finish(): SensorTake {
        return SensorTake(hz, kinds, frames)
    }
}
