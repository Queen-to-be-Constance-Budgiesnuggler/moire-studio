package dk.cbavf.studio.math

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

enum class Curve {
    Linear,
    Exponential,
    Logarithmic,
    SCurve
}

object Envelope {
    /**
     * Maps x (clamped to 0..1) to 0..1, with shape(0)=0 and shape(1)=1 for every curve.
     *
     * @param x input value in range [0, 1]
     * @param curve the shaping curve to use
     * @param amount controls the curve's steepness (default 4.0)
     * @return shaped output value in range [0, 1]
     */
    fun shape(x: Double, curve: Curve, amount: Double = 4.0): Double {
        val clampedX = max(0.0, min(1.0, x))
        
        return when (curve) {
            Curve.Linear -> clampedX
            Curve.Exponential -> {
                if (amount < 1e-6) {
                    clampedX
                } else {
                    (exp(amount * clampedX) - 1) / (exp(amount) - 1)
                }
            }
            Curve.Logarithmic -> {
                1.0 - shape(1.0 - clampedX, Curve.Exponential, amount)
            }
            Curve.SCurve -> {
                clampedX * clampedX * (3 - 2 * clampedX)
            }
        }
    }

    /**
     * Calculates the envelope level at time tMs.
     *
     * @param tMs time in milliseconds since gate on
     * @param attackMs attack time in milliseconds
     * @param decayMs decay time in milliseconds
     * @param sustain sustain level (0..1)
     * @param releaseMs release time in milliseconds
     * @param curve shaping curve for attack and decay segments
     * @param amount shaping amount for attack and decay segments
     * @param gateOffMs time when gate went off, or null if still held
     * @return envelope level (0..1)
     */
    fun adsr(
        tMs: Double,
        attackMs: Double,
        decayMs: Double,
        sustain: Double,
        releaseMs: Double,
        curve: Curve = Curve.Linear,
        amount: Double = 4.0,
        gateOffMs: Double? = null
    ): Double {
        if (tMs < 0) return 0.0
        
        val t = tMs
        val gateOff = gateOffMs
        
        // If we're in release phase
        if (gateOff != null && t > gateOff) {
            val r = t - gateOff
            if (releaseMs <= 0 || r >= releaseMs) return 0.0
            
            // Calculate the level at the moment the gate went off
            val l0 = heldLevel(gateOff, attackMs, decayMs, sustain, curve, amount)
            return l0 * (1.0 - shape(r / releaseMs, curve, amount))
        }
        
        // Return the held level at time t
        return heldLevel(t, attackMs, decayMs, sustain, curve, amount)
    }
    
    /**
     * Calculates the held level at time t (as if gate stays on).
     */
    private fun heldLevel(
        t: Double,
        attackMs: Double,
        decayMs: Double,
        sustain: Double,
        curve: Curve,
        amount: Double
    ): Double {
        // Handle zero-length attack
        if (attackMs <= 0) {
            // If there's no attack, we immediately go to decay phase or sustain
            if (decayMs <= 0) {
                return sustain
            }
            // Decay starts immediately after attack
            val decayTime = max(0.0, t - attackMs)
            if (decayTime < decayMs) {
                return 1.0 - (1.0 - sustain) * shape(decayTime / decayMs, curve, amount)
            } else {
                return sustain
            }
        }
        
        // Normal case with attack phase
        if (t < attackMs) {
            return shape(t / attackMs, curve, amount)
        }
        
        // Attack phase complete, now in decay or sustain
        val decayTime = t - attackMs
        if (decayMs <= 0) {
            // No decay, go straight to sustain
            return sustain
        }
        
        if (decayTime < decayMs) {
            return 1.0 - (1.0 - sustain) * shape(decayTime / decayMs, curve, amount)
        } else {
            return sustain
        }
    }
}
