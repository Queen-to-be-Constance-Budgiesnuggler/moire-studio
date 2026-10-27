package dk.cbavf.studio.sonify

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin

/**
 * Turning a picture's row of brightness values into sound, by additive synthesis: the row is cut into [bins] bands, each band is a
 * sine whose pitch rises from [lowHz] to [highHz] across the row (on a logarithmic scale, like musical notes) and whose loudness is
 * the band's mean brightness. A bright patch on the left is a low tone, on the right a high one; a flat grey row is a chord.
 */
object Sonify {
    const val SAMPLE_RATE = 22050

    /** The pitch of band [i] of [bins]: [lowHz] at the first, [highHz] at the last, even steps of an octave fraction between. */
    fun bandHz(i: Int, bins: Int, lowHz: Double = 110.0, highHz: Double = 3520.0): Double {
        if (bins <= 1) return lowHz
        return lowHz * exp(ln(highHz / lowHz) * i / (bins - 1))
    }

    /** The mean of each of [bins] equal parts of [row] (a row shorter than [bins] repeats its values). */
    fun bandLevels(row: FloatArray, bins: Int): FloatArray {
        if (row.isEmpty()) return FloatArray(bins)
        return FloatArray(bins) { b ->
            val from = (b * row.size.toLong() / bins).toInt()
            val to = maxOf(from + 1, ((b + 1) * row.size.toLong() / bins).toInt()).coerceAtMost(row.size)
            var sum = 0f
            for (i in from until to) sum += row[i]
            sum / (to - from)
        }
    }

    /**
     * The sound of [row] (values 0..1) lasting [seconds], as 16-bit mono samples scaled so the loudest point sits at 80 % of full
     * scale. A black row is silence. Short fades at both ends stop clicks.
     */
    fun render(row: FloatArray, bins: Int = 48, seconds: Double = 1.5, lowHz: Double = 110.0, highHz: Double = 3520.0): ShortArray {
        val n = (seconds * SAMPLE_RATE).toInt().coerceAtLeast(1)
        val levels = bandLevels(row, bins)
        val raw = DoubleArray(n)
        var peak = 0.0
        for (b in 0 until bins) {
            val a = levels[b].coerceIn(0f, 1f).toDouble()
            if (a <= 0.0) continue
            val w = 2 * PI * bandHz(b, bins, lowHz, highHz) / SAMPLE_RATE
            for (i in 0 until n) raw[i] += a * sin(w * i)
        }
        for (i in 0 until n) peak = maxOf(peak, abs(raw[i]))
        val fade = (0.02 * SAMPLE_RATE).toInt().coerceAtMost(n / 2).coerceAtLeast(1)
        return ShortArray(n) { i ->
            val gain = if (peak > 0.0) 0.8 / peak else 0.0
            val edge = minOf(1.0, minOf(i, n - 1 - i).toDouble() / fade)
            (raw[i] * gain * edge * Short.MAX_VALUE).toInt().coerceIn(-32768, 32767).toShort()
        }
    }

    /** Plays [samples] once (mono, [SAMPLE_RATE] Hz) on its own thread and frees the track afterwards. */
    fun play(samples: ShortArray) {
        Thread {
            runCatching {
                val track = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(SAMPLE_RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(samples.size * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()
                track.write(samples, 0, samples.size)
                track.play()
                Thread.sleep(samples.size * 1000L / SAMPLE_RATE + 300)
                track.release()
            }
        }.start()
    }
}
