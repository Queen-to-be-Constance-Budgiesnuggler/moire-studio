package dk.cbavf.studio.math

import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.round

object Sliders {
    /**
     * Calculates a fine step size based on the range [min, max].
     * Returns 10^(floor(log10(span)) - 2) where span = max - min.
     * If span is not finite or <= 0, returns 1.0.
     * If integer is true, never returns less than 1.0.
     */
    fun fineStep(min: Double, max: Double, integer: Boolean = false): Double {
        val span = max - min
        if (!span.isFinite() || span <= 0) return 1.0
        
        val step = 10.0.pow(floor(log10(span)) - 2)
        return if (integer && step < 1.0) 1.0 else step
    }

    /**
     * Snaps a value [v] to the nearest grid point defined by [min], [max], and [step].
     * The grid starts at [min] with spacing [step].
     * Clamps result to [min..max] and rounds to 10 decimal places.
     */
    fun snapToGrid(v: Double, min: Double, max: Double, step: Double): Double {
        val snapped = min + floor((v - min) / step + 0.5) * step
        val clamped = snapped.coerceIn(min, max)
        return round(clamped * 1e10) / 1e10
    }

    /**
     * Moves [v] by one [step] in the direction specified by [dir] (+1 or -1).
     * Uses snapToGrid to ensure proper grid alignment.
     */
    fun nextStep(v: Double, min: Double, max: Double, step: Double, dir: Int): Double {
        return snapToGrid(v + dir * step, min, max, step)
    }

    /**
     * Maps a fraction [t] in [0..1] to a value in [min, max] using linear interpolation.
     */
    fun fromFraction(t: Double, min: Double, max: Double): Double {
        val clampedT = t.coerceIn(0.0, 1.0)
        return min + clampedT * (max - min)
    }

    /**
     * Maps a value [v] in [min, max] to a fraction in [0..1] using linear interpolation.
     * Returns 0.0 if max == min.
     */
    fun toFraction(v: Double, min: Double, max: Double): Double {
        if (max == min) return 0.0
        val t = (v - min) / (max - min)
        return t.coerceIn(0.0, 1.0)
    }

    /**
     * Maps a fraction [t] in [0..1] to a value in [min, max] using logarithmic interpolation.
     * Throws IllegalArgumentException if min <= 0 or max <= 0.
     */
    fun fromFractionLog(t: Double, min: Double, max: Double): Double {
        if (min <= 0 || max <= 0) throw IllegalArgumentException("Bounds must be positive")
        val clampedT = t.coerceIn(0.0, 1.0)
        return min * (max / min).pow(clampedT)
    }

    /**
     * Maps a value [v] in [min, max] to a fraction in [0..1] using logarithmic interpolation.
     * Returns 0.0 if max == min or v <= 0.
     * Throws IllegalArgumentException if min <= 0 or max <= 0.
     */
    fun toFractionLog(v: Double, min: Double, max: Double): Double {
        if (min <= 0 || max <= 0) throw IllegalArgumentException("Bounds must be positive")
        if (max == min || v <= 0) return 0.0
        val t = ln(v / min) / ln(max / min)
        return t.coerceIn(0.0, 1.0)
    }
}
