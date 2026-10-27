package dk.cbavf.studio.math

import java.util.Random

enum class Edge { None, Rising, Falling }

/**
 * Detects edges in a boolean gate signal.
 * Returns Rising when the gate transitions from false to true,
 * Falling when it transitions from true to false,
 * and None otherwise.
 */
class EdgeDetector {
    private var previous = false
    
    /**
     * Updates the edge detector with the current gate value.
     * @param gate The current boolean value
     * @return The detected edge type
     */
    fun update(gate: Boolean): Edge {
        val result = if (gate && !previous) Edge.Rising
            else if (!gate && previous) Edge.Falling
            else Edge.None
        previous = gate
        return result
    }
    
    /**
     * Resets the edge detector to start with a false previous value.
     */
    fun reset() {
        previous = false
    }
}

/**
 * Provides basic boolean logic operations.
 */
object GateLogic {
    /**
     * Performs logical AND operation on two boolean values.
     * @param a First operand
     * @param b Second operand
     * @return True if both operands are true, false otherwise
     */
    fun and(a: Boolean, b: Boolean): Boolean = a && b
    
    /**
     * Performs logical OR operation on two boolean values.
     * @param a First operand
     * @param b Second operand
     * @return True if at least one operand is true, false otherwise
     */
    fun or(a: Boolean, b: Boolean): Boolean = a || b
    
    /**
     * Performs logical XOR operation on two boolean values.
     * @param a First operand
     * @param b Second operand
     * @return True if exactly one operand is true, false otherwise
     */
    fun xor(a: Boolean, b: Boolean): Boolean = a != b
    
    /**
     * Performs logical NOT operation on a boolean value.
     * @param a Operand
     * @return True if the operand is false, false otherwise
     */
    fun not(a: Boolean): Boolean = !a
}

/**
 * Converts a trigger signal into a gate signal with a specified duration.
 * @param lengthSec The duration in seconds that the gate stays high after a trigger
 */
class TriggerToGate(private val lengthSec: Double) {
    private var lastTrigger = -lengthSec
    
    /**
     * Updates the trigger-to-gate converter with the current time and trigger signal.
     * @param nowSec Current time in seconds
     * @param trigger The trigger signal (true for one update)
     * @return The resulting gate signal
     */
    fun update(nowSec: Double, trigger: Boolean): Boolean {
        if (trigger) {
            lastTrigger = nowSec
        }
        return nowSec < lastTrigger + lengthSec && lengthSec > 0
    }
}

/**
 * Delays a gate signal by a specified amount of time.
 * @param delaySec The delay in seconds
 */
class GateDelay(private val delaySec: Double) {
    private val history = mutableListOf<Pair<Double, Boolean>>()
    
    /**
     * Updates the gate delay with the current time and gate value.
     * @param nowSec Current time in seconds
     * @param gate The current gate value
     * @return The delayed gate value
     */
    fun update(nowSec: Double, gate: Boolean): Boolean {
        // Add current pair to history
        history.add(nowSec to gate)
        
        // Keep the latest entry at or before the cutoff (it is the answer) and everything after it; drop what is older.
        val cutoffTime = nowSec - delaySec
        while (history.size >= 2 && history[1].first <= cutoffTime) {
            history.removeAt(0)
        }

        // The first entry is the latest one at or before the cutoff, if there is one.
        if (history[0].first <= cutoffTime) {
            return history[0].second
        }

        // No valid entry found, return false
        return false
    }
}

/**
 * Divides a gate signal by a factor n, outputting true only on every nth rising edge.
 * @param n The division factor (must be positive)
 */
class GateDivide(private val n: Int) {
    private val detector = EdgeDetector()
    private var count = 0
    
    /**
     * Updates the gate divider with the current gate value.
     * @param gate The current gate value
     * @return The divided gate value
     */
    fun update(gate: Boolean): Boolean {
        val edge = detector.update(gate)
        if (edge == Edge.Rising) {
            count++
        }
        val effectiveN = if (n < 1) 1 else n
        return gate && count % effectiveN == 0
    }
}

/**
 * Applies probability to a gate signal, passing the gate with a given probability.
 * @param p The probability of passing (0.0 to 1.0)
 * @param seed The random seed for reproducible results
 */
class GateProbability(private val p: Double, private val seed: Long) {
    private val random = Random(seed)
    private val detector = EdgeDetector()
    private var passing = false
    
    /**
     * Updates the probability gate with the current gate value.
     * @param gate The current gate value
     * @return The probabilistic gate value
     */
    fun update(gate: Boolean): Boolean {
        val edge = detector.update(gate)
        if (edge == Edge.Rising) {
            passing = random.nextDouble() < p
        }
        return gate && passing
    }
}

/**
 * Samples and holds an input value on rising edges of a trigger signal.
 */
class SampleAndHold {
    private var sample = 0.0
    private val detector = EdgeDetector()
    
    /**
     * Updates the sample-and-hold with the current trigger and input values.
     * @param trigger The trigger signal (true for one update)
     * @param input The input value to sample
     * @return The held value
     */
    fun update(trigger: Boolean, input: Double): Double {
        val edge = detector.update(trigger)
        if (edge == Edge.Rising) {
            sample = input
        }
        return sample
    }
}
