package dk.cbavf.studio.sensors

class LowPass(alpha: Double) {
    /** Returns the next filtered value */
    private var y: Double = Double.NaN
    private val alpha = alpha.coerceIn(0.0, 1.0)
    
    fun next(x: Double): Double {
        if (y.isNaN()) {
            y = x
            return x
        }
        y = y + alpha * (x - y)
        return y
    }
    
    /** Resets the filter state */
    fun reset() {
        y = Double.NaN
    }
}

class HighPass(alpha: Double) {
    /** Returns the next high-passed value */
    private val lowPass = LowPass(alpha)
    
    fun next(x: Double): Double {
        return x - lowPass.next(x)
    }
    
    /** Resets the filter state */
    fun reset() {
        lowPass.reset()
    }
}

class Derivative {
    /** Returns the next derivative value */
    private var previous: Double = Double.NaN
    private var dtSec: Double = 0.0
    
    fun next(x: Double, dtSec: Double): Double {
        if (previous.isNaN()) {
            previous = x
            return 0.0
        }
        if (dtSec <= 0.0) {
            previous = x
            return 0.0
        }
        val result = (x - previous) / dtSec
        previous = x
        return result
    }
    
    /** Resets the derivative state */
    fun reset() {
        previous = Double.NaN
        this.dtSec = 0.0
    }
}

class Hysteresis(val high: Double, val low: Double) {
    /** Returns the current on/off state after this sample */
    private var state: Boolean = false
    
    fun next(x: Double): Boolean {
        if (x >= high) {
            state = true
        } else if (x <= low) {
            state = false
        }
        return state
    }
    
    /** Returns true only on the sample where the state turns on */
    fun rising(x: Double): Boolean {
        val oldState = state
        next(x)
        return !oldState && state
    }
    
    /** Resets the hysteresis state */
    fun reset() {
        state = false
    }
}

object SeedHash {
    /** SplitMix64 hash function */
    fun splitMix(x: Long): Long {
        var z = x + 0x9E3779B97F4A7C15uL.toLong()
        z = (z xor (z ushr 30)) * 0xBF58476D1CE4E5B9uL.toLong()
        z = (z xor (z ushr 27)) * 0x94D049BB133111EBuL.toLong()
        return z xor (z ushr 31)
    }
    
    /** Generates a hash from an array of floats */
    fun fromFloats(values: FloatArray): Long {
        if (values.isEmpty()) return 0L
        var s: Long = 0L
        for (value in values) {
            s = splitMix(s xor value.toRawBits().toLong())
        }
        return s
    }
}
