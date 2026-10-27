package dk.cbavf.studio.math

/**
 * Represents an ADSR envelope shape with attack, decay, sustain, and release times.
 */
data class AdsrShape(
    val attackMs: Double,
    val decayMs: Double,
    val sustain: Double,
    val releaseMs: Double
)

/**
 * Represents a handle point in the envelope editor.
 */
data class Handle(
    val x: Double,
    val y: Double
)

/**
 * Provides functionality for calculating envelope points and dragging handles.
 */
object EnvelopePoints {
    /**
     * The hold width as a fraction of the usable width.
     */
    const val HOLD = 0.15
    
    /**
     * The usable width as a fraction of the total axis width.
     */
    const val USABLE = 0.85

    /**
     * Calculates the handle positions for an ADSR shape.
     *
     * @param s The ADSR shape to calculate handles for.
     * @param axisMs The total time in milliseconds that spans the drawing area.
     * @return A list of four handles representing the envelope points.
     */
    fun handles(s: AdsrShape, axisMs: Double): List<Handle> {
        val attackX = s.attackMs / axisMs
        val decayX = (s.attackMs + s.decayMs) / axisMs
        val sustainEndX = decayX + HOLD
        val releaseEndX = sustainEndX + s.releaseMs / axisMs
        
        return listOf(
            Handle(attackX, 1.0),
            Handle(decayX, s.sustain),
            Handle(sustainEndX, s.sustain),
            Handle(releaseEndX, 0.0)
        )
    }

    /**
     * Returns a new ADSR shape after dragging a handle.
     *
     * @param s The original ADSR shape.
     * @param index The index of the handle being dragged (0-3).
     * @param x The new x coordinate of the handle.
     * @param y The new y coordinate of the handle.
     * @param axisMs The total time in milliseconds that spans the drawing area.
     * @return A new ADSR shape with updated values based on the dragged handle.
     */
    fun drag(s: AdsrShape, index: Int, x: Double, y: Double, axisMs: Double): AdsrShape {
        return when (index) {
            0 -> {
                // Move attack time
                val newAttack = x * axisMs
                val maxAttack = USABLE * axisMs - s.decayMs - s.releaseMs
                val attackMs = newAttack.coerceIn(0.0, maxOf(0.0, maxAttack))
                s.copy(attackMs = attackMs)
            }
            1 -> {
                // Move decay time and sustain level
                val newDecay = x * axisMs - s.attackMs
                val maxDecay = USABLE * axisMs - s.attackMs - s.releaseMs
                val decayMs = newDecay.coerceIn(0.0, maxOf(0.0, maxDecay))
                val sustain = y.coerceIn(0.0, 1.0)
                s.copy(decayMs = decayMs, sustain = sustain)
            }
            2 -> {
                // Move sustain level only
                val sustain = y.coerceIn(0.0, 1.0)
                s.copy(sustain = sustain)
            }
            3 -> {
                // Move release time
                val newRelease = (x - HOLD) * axisMs - s.attackMs - s.decayMs
                val maxRelease = USABLE * axisMs - s.attackMs - s.decayMs
                val releaseMs = newRelease.coerceIn(0.0, maxOf(0.0, maxRelease))
                s.copy(releaseMs = releaseMs)
            }
            else -> s
        }
    }
}
