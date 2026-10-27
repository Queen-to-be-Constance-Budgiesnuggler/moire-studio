package dk.cbavf.studio.math

import androidx.compose.runtime.mutableStateMapOf

/**
 * One tunable number behind the studio's maths, with what it means. [key] is `group.name`; the value
 * the studio uses is [MathParams.get], which is [default] until you change it in Studio → Config.
 */
class MathParam(
    val key: String,
    val default: Double,
    val min: Double,
    val max: Double,
    val unit: String,
    val description: String,
) {
    val group: String get() = key.substringBefore('.')
}

/**
 * The constants of the studio's maths in one place, readable and changeable without touching code.
 * Overrides are kept in Compose state (so a change is live) and saved in `user.profile` as `mathParams`.
 * Every parameter listed here is read by the code that uses it; a few formulas still keep their numbers inside
 * the functions (see docs/PENDING.md).
 */
object MathParams {
    val all: List<MathParam> = listOf(
        MathParam("slider.holdMs", 400.0, 150.0, 1500.0, "ms", "How long the red half of a slider thumb is held before it starts repeating fine steps."),
        MathParam("slider.repeatMs", 120.0, 30.0, 600.0, "ms", "Time between repeated fine steps while the red half is held."),
        MathParam("slider.accelMs", 1000.0, 300.0, 4000.0, "ms", "While held, the fine step grows ten times every this long."),
        MathParam("slider.dragPxPerStep", 8.0, 2.0, 40.0, "dp", "Sideways drag on the red half that makes one fine step."),
        MathParam("slider.logStepPercent", 1.0, 0.1, 10.0, "%", "A fine step on a logarithmic slider (frequency, zoom) changes the value by this percentage."),
        MathParam("sensor.slowAlpha", 0.08, 0.005, 0.5, "", "How fast a Sensor node's slow average follows the sensor; its High-pass output is the sensor minus that average."),
        MathParam("envelope.curveAmount", 4.0, 0.5, 12.0, "", "How strongly the Exponential and Logarithmic envelope curves bend (envelope nodes, control envelopes, the editor)."),
        MathParam("gate.duty", 0.5, 0.05, 0.95, "", "The share of each period a clock's gate is on: the Studio clock gate and clock-driven control envelopes."),
        MathParam("layout.panelDefaultDp", 420.0, 280.0, 560.0, "dp", "Width of the controls panel beside the picture in landscape before you drag it."),
        MathParam("layout.panelMaxDp", 560.0, 360.0, 800.0, "dp", "The widest the landscape controls panel may be dragged."),
        MathParam("ab.holdMs", 500.0, 200.0, 1500.0, "ms", "How long a finger is held still on a picture before the A/B view starts."),
        MathParam("ab.holdSlopDp", 8.0, 2.0, 30.0, "dp", "How far a finger may move during that hold before it counts as a drag."),
        MathParam("fringe.minPeriodPx", 4.0, 1.0, 20.0, "px", "Predicted moiré fringes closer together than this are not drawn over the canvas."),
    )

    private val overrides = mutableStateMapOf<String, Double>()

    fun param(key: String): MathParam? = all.firstOrNull { it.key == key }

    /** The value in use: the override if there is one, otherwise the default. */
    fun get(key: String): Double = overrides[key] ?: param(key)?.default ?: error("Unknown math parameter $key")

    /** Sets a value, kept within the parameter's range; setting the default removes the override. */
    fun set(key: String, value: Double) {
        val p = param(key) ?: return
        val v = value.coerceIn(p.min, p.max)
        if (v == p.default) overrides.remove(key) else overrides[key] = v
    }

    fun reset(key: String) { overrides.remove(key) }

    fun resetAll() = overrides.clear()

    fun isChanged(key: String): Boolean = overrides.containsKey(key)

    /** Only what differs from the defaults, as saved in the profile. */
    fun snapshot(): Map<String, Double> = overrides.toMap()

    /** Replaces the overrides with saved ones; unknown keys are ignored and values clamped. */
    fun load(saved: Map<String, Double>) {
        overrides.clear()
        for ((k, v) in saved) set(k, v)
    }
}
