package dk.cbavf.studio.slitscan

import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/** How the frame used by each slit is shifted along the sequence, on top of "one frame per slit". */
enum class OffsetMode { None, Ramp, Wave, Random }

/**
 * One time offset (in frames) per slit. [shift] is how strong it is: frames per slit for [OffsetMode.Ramp]
 * (so a slow ramp bends the picture through time), the amplitude in frames for [OffsetMode.Wave] and
 * [OffsetMode.Random]. The random offsets depend only on [seed], so the same settings give the same picture.
 */
fun slitOffsets(mode: OffsetMode, slits: Int, shift: Float, seed: Long = 1L): IntArray {
    if (slits <= 0) return IntArray(0)
    return when (mode) {
        OffsetMode.None -> IntArray(slits)
        OffsetMode.Ramp -> IntArray(slits) { (it * shift).roundToInt() }
        OffsetMode.Wave -> IntArray(slits) { (shift * sin(2 * PI * it / WAVE_SLITS)).roundToInt() }
        OffsetMode.Random -> {
            // A tiny linear congruential generator keeps this reproducible without java.util.Random's platform differences.
            var x = seed * 6364136223846793005L + 1442695040888963407L
            IntArray(slits) {
                x = x * 6364136223846793005L + 1442695040888963407L
                val unit = ((x ushr 33).toInt() and 0x7FFFFFFF) / 2147483647.0
                ((unit * 2 - 1) * shift).roundToInt()
            }
        }
    }
}

/** A Wave offset goes round once every this many slits. */
const val WAVE_SLITS = 32.0
