package dk.cbavf.studio.modulation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.staticCompositionLocalOf
import dk.cbavf.studio.math.Curve
import dk.cbavf.studio.math.Envelope
import kotlinx.serialization.Serializable

/**
 * An envelope attached to one control. While its gate is on, the control moves from the value you set by
 * `depth` times the control's range times the envelope level (0..1); when the gate closes it falls back.
 * The gate comes from a Gate button on the control (hold it), or, when [repeatHz] is above zero, from a clock that
 * holds the gate for the first half of each period, or, when [sensor] is set, from that sensor crossing [threshold].
 */
@Serializable
data class ControlModulation(
    val attackMs: Float = 50f,
    val decayMs: Float = 200f,
    val sustain: Float = 0.6f,
    val releaseMs: Float = 400f,
    val curve: String = "Linear",
    /** −1..1: how much of the control's range the full envelope moves it (negative moves it down). */
    val depth: Float = 0.5f,
    /** 0 = a Gate button on the control; above 0 = a clock at this many times a second. */
    val repeatHz: Float = 0f,
    /** A sensor from [SensorTriggers.names] whose level opens the gate, or empty for none. A clock wins over it. */
    val sensor: String = "",
    /** 0..1: the sensor level the gate opens at (see [ThresholdGate]). */
    val threshold: Float = 0.5f,
    /** 0..1: the width of the band around [threshold] in which the gate keeps its state. */
    val band: Float = 0.1f,
    /** False: the gate opens when the sensor rises above the level. True: when it falls below. */
    val falling: Boolean = false,
) {
    /** True when a clock opens the gate. */
    val byClock: Boolean get() = repeatHz > 0f

    /** True when a sensor opens the gate. */
    val bySensor: Boolean get() = !byClock && sensor.isNotEmpty()

    /** True when the Gate button next to the control opens the gate. */
    val byButton: Boolean get() = !byClock && !bySensor
}

/** The maths of a modulated control, as plain functions of time. */
object ModulationMath {
    private fun curveOf(m: ControlModulation) = runCatching { Curve.valueOf(m.curve) }.getOrDefault(Curve.Linear)

    /**
     * The envelope level 0..1 [sinceOnMs] milliseconds after the gate opened. [sinceOffMs] is how long ago the gate closed,
     * or null while it is still open.
     */
    fun level(m: ControlModulation, sinceOnMs: Double, sinceOffMs: Double?): Double {
        val gateOffMs = sinceOffMs?.let { (sinceOnMs - it).coerceAtLeast(0.0) }
        return Envelope.adsr(
            sinceOnMs, m.attackMs.toDouble(), m.decayMs.toDouble(), m.sustain.toDouble(), m.releaseMs.toDouble(),
            curveOf(m), amount = dk.cbavf.studio.math.MathParams.get("envelope.curveAmount"), gateOffMs = gateOffMs,
        )
    }

    /**
     * The level of a clock-driven envelope at time [tSec]: the gate opens at the start of each period (1 / repeatHz seconds)
     * and stays open for its first half. A rate of zero or less gives 0.
     */
    fun repeatLevel(m: ControlModulation, tSec: Double): Double {
        if (m.repeatHz <= 0f) return 0.0
        val periodMs = 1000.0 / m.repeatHz
        val phaseMs = (tSec * 1000.0).mod(periodMs)
        val openMs = periodMs * dk.cbavf.studio.math.MathParams.get("gate.duty")
        return level(m, phaseMs, if (phaseMs > openMs) phaseMs - openMs else null)
    }

    /** The control's value for a base value and an envelope level: base + depth × range × level, kept inside the range. */
    fun apply(base: Float, range: ClosedFloatingPointRange<Float>, m: ControlModulation, level: Double): Float {
        val span = range.endInclusive - range.start
        return (base + m.depth * span * level.toFloat()).coerceIn(range.start, range.endInclusive)
    }
}

/** The modulations of this phone, keyed `room.label` (for example `Draw.Spacing`), kept with the workspace. */
class ModulationHub(initial: Map<String, ControlModulation>, private val save: (Map<String, ControlModulation>) -> Unit) {
    private val map = mutableStateMapOf<String, ControlModulation>().apply { putAll(initial) }

    operator fun get(key: String): ControlModulation? = map[key]

    fun set(key: String, m: ControlModulation?) {
        if (m == null) map.remove(key) else map[key] = m
        save(map.toMap())
    }
}

val LocalModulation = staticCompositionLocalOf<ModulationHub?> { null }

/**
 * The sensor levels a sensor-triggered envelope reads. [read] gives a sensor's level 0..1 by its name in
 * [SensorTriggers.names], or null while the sensors are off. [switchOn] turns them on.
 */
class SensorLevels(val read: (String) -> Float?, val switchOn: () -> Unit)

val LocalSensorLevels = staticCompositionLocalOf<SensorLevels?> { null }

/** The room the controls below belong to, so a control's key is `room.label`. */
val LocalControlScope = compositionLocalOf { "" }

/** The key of a control, or null when it has no scope (then it cannot be modulated). */
@Composable
fun controlKey(label: String, scope: String = LocalControlScope.current): String? = if (scope.isEmpty()) null else "$scope.$label"
