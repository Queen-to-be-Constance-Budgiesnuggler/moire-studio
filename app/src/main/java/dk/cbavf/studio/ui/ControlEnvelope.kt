package dk.cbavf.studio.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import dk.cbavf.studio.math.AdsrShape
import dk.cbavf.studio.math.Curve
import dk.cbavf.studio.modulation.ControlModulation
import dk.cbavf.studio.modulation.LocalControlScope
import dk.cbavf.studio.modulation.LocalSensorLevels
import dk.cbavf.studio.modulation.ModulationMath
import dk.cbavf.studio.modulation.SensorTriggers
import dk.cbavf.studio.modulation.ThresholdGate
import dk.cbavf.studio.ui.layout.PanelDialog
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Runs an envelope on a control. Draws nothing: every frame it works out the control's value from the value you set
 * ([value] when you last moved it), the envelope level and the depth, and writes it with [onChange]. When the envelope is idle
 * the control goes back to the value you set. Moving the slider yourself sets that value.
 */
@Composable
fun ModulationDriver(
    mod: ControlModulation,
    held: Boolean,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
) {
    val modNow by rememberUpdatedState(mod)
    val heldNow by rememberUpdatedState(held)
    val rangeNow by rememberUpdatedState(range)
    val changeNow by rememberUpdatedState(onChange)
    val valueNow by rememberUpdatedState(value)
    val sensorsNow by rememberUpdatedState(LocalSensorLevels.current)
    var base by remember { mutableStateOf(value) }
    var lastWritten by remember { mutableStateOf<Float?>(null) }

    // The room hands our writes back a few frames late, so remember the last dozen: a value that is none of them was set by a
    // person and is the new base. (Comparing with only the last write made the base chase the modulation upwards.)
    val recent = remember { ArrayDeque<Float>() }
    LaunchedEffect(value) {
        val eps = (rangeNow.endInclusive - rangeNow.start) * 1e-4f
        if (recent.none { abs(it - value) <= eps }) base = value
    }

    LaunchedEffect(Unit) {
        var wasHeld = false
        var onAt = -1L
        var offAt = -1L
        val sensorGate = ThresholdGate()
        val t0 = withFrameMillis { it }
        while (true) {
            // Off screen the envelope does not run.
            dk.cbavf.studio.foreground.Foreground.gate.awaitStarted()
            val now = withFrameMillis { it }
            val m = modNow
            val level: Double
            if (m.byClock) {
                level = ModulationMath.repeatLevel(m, (now - t0) / 1000.0)
            } else {
                // The gate is the G button, or the sensor crossing its level (closed while the sensors are off).
                val gate = if (m.bySensor) {
                    val x = sensorsNow?.read(m.sensor)
                    if (x == null) sensorGate.off() else sensorGate.next(x, m.threshold, m.band, m.falling)
                    sensorGate.open
                } else {
                    sensorGate.off()
                    heldNow
                }
                if (gate && !wasHeld) { onAt = now; offAt = -1L }
                if (!gate && wasHeld) offAt = now
                wasHeld = gate
                level = if (onAt < 0) 0.0 else ModulationMath.level(m, (now - onAt).toDouble(), if (offAt < 0) null else (now - offAt).toDouble())
            }
            val r = rangeNow
            val eps = (r.endInclusive - r.start) * 1e-5f
            val target = ModulationMath.apply(base, r, m, level)
            val last = lastWritten ?: valueNow
            if (abs(target - last) > eps) {
                lastWritten = target
                recent.addLast(target)
                while (recent.size > 12) recent.removeFirst()
                changeNow(target)
            }
        }
    }
}

/** A small round button that is "on" while a finger is on it: the gate of a control's envelope. */
@Composable
fun GateButton(onHeld: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    var down by remember { mutableStateOf(false) }
    Box(
        modifier
            .size(34.dp)
            .background(if (down) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(17.dp))
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown()
                    down = true
                    onHeld(true)
                    waitForUpOrCancellation()
                    down = false
                    onHeld(false)
                }
            },
        contentAlignment = Alignment.Center,
    ) { Text("G", style = MaterialTheme.typography.labelMedium, color = if (down) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant) }
}

/** Which sensor opens the gate, at what level, with what band, on a rise or a fall; and what the sensor reads now. */
@Composable
private fun SensorTriggerControls(m: ControlModulation, update: (ControlModulation) -> Unit) {
    val sensors = LocalSensorLevels.current
    ScrollRow {
        SensorTriggers.names.forEach { s ->
            FilterChip(selected = m.sensor == s, onClick = { update(m.copy(sensor = s)) }, label = { Text(SensorTriggers.label(s)) })
        }
    }
    LabeledSlider("Level", m.threshold * 100f, 0f..100f, "%", format = { "${it.roundToInt()}" }) { update(m.copy(threshold = it / 100f)) }
    LabeledSlider("Band", m.band * 100f, 0f..50f, "%", format = { "${it.roundToInt()}" }) { update(m.copy(band = it / 100f)) }
    ScrollRow {
        FilterChip(selected = !m.falling, onClick = { update(m.copy(falling = false)) }, label = { Text("Rises above") })
        FilterChip(selected = m.falling, onClick = { update(m.copy(falling = true)) }, label = { Text("Falls below") })
    }
    val now = sensors?.read(m.sensor)
    if (sensors == null || now == null) {
        ScrollRow {
            Text("The sensors are off.", style = MaterialTheme.typography.labelSmall)
            if (sensors != null) TextButton(onClick = sensors.switchOn) { Text("Switch sensors on") }
        }
    } else {
        Text("${SensorTriggers.label(m.sensor)} now: ${(now * 100).roundToInt()} %", style = MaterialTheme.typography.labelSmall)
    }
}

/** The envelope of one control: shape, curve, depth and trigger. Changes apply at once; "Remove" takes the envelope off. */
@Composable
fun ControlEnvelopeDialog(label: String, initial: ControlModulation?, onChange: (ControlModulation?) -> Unit, onDismiss: () -> Unit) {
    var m by remember { mutableStateOf(initial ?: ControlModulation()) }
    fun update(next: ControlModulation) { m = next; onChange(next) }
    // Make the control modulated as soon as the box opens, so what you see is what you hear.
    LaunchedEffect(Unit) { if (initial == null) onChange(m) }
    PanelDialog(
        title = "Envelope on $label",
        onDismiss = onDismiss,
        confirm = {
            TextButton(onClick = { onChange(null); onDismiss() }) { Text("Remove") }
            TextButton(onClick = onDismiss) { Text("Done") }
        },
    ) {
        // The sliders in this box are not controls to modulate themselves.
        CompositionLocalProvider(LocalControlScope provides "") {
            val curve = runCatching { Curve.valueOf(m.curve) }.getOrDefault(Curve.Linear)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                EnvelopeEditor(
                    AdsrShape(m.attackMs.toDouble(), m.decayMs.toDouble(), m.sustain.toDouble(), m.releaseMs.toDouble()), curve,
                    onChange = { s ->
                        update(m.copy(
                            attackMs = s.attackMs.roundToInt().toFloat(), decayMs = s.decayMs.roundToInt().toFloat(),
                            sustain = (Math.round(s.sustain * 100) / 100.0).toFloat(), releaseMs = s.releaseMs.roundToInt().toFloat(),
                        ))
                    },
                )
                ScrollRow { Curve.entries.forEach { c -> FilterChip(selected = c == curve, onClick = { update(m.copy(curve = c.name)) }, label = { Text(c.name) }) } }
                LabeledSlider("Depth", m.depth * 100f, -100f..100f, "%", format = { "${it.roundToInt()}" }) { update(m.copy(depth = it / 100f)) }
                ScrollRow {
                    Text("Trigger", style = MaterialTheme.typography.labelMedium)
                    FilterChip(selected = m.byButton, onClick = { update(m.copy(repeatHz = 0f, sensor = "")) }, label = { Text("Gate button") })
                    FilterChip(selected = m.byClock, onClick = { update(m.copy(repeatHz = if (m.byClock) m.repeatHz else 1f, sensor = "")) }, label = { Text("Clock") })
                    FilterChip(
                        selected = m.bySensor,
                        onClick = { update(m.copy(repeatHz = 0f, sensor = m.sensor.ifEmpty { "light" })) },
                        label = { Text("Sensor") },
                    )
                }
                if (m.byClock) LabeledSlider("Rate", m.repeatHz, 0.1f..8f, " Hz") { update(m.copy(repeatHz = it)) }
                if (m.bySensor) SensorTriggerControls(m, ::update)
                Text(
                    when {
                        m.byClock -> "The envelope runs by itself, once per period."
                        m.bySensor -> if (m.falling) "The envelope starts when the sensor falls below the level." else "The envelope starts when the sensor rises above the level."
                        else -> "Hold the G button next to the control to open the gate."
                    },
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}
