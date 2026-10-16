package dk.cbavf.studio.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/** Control widgets shared by the rooms. */

@Composable
fun ScrollRow(content: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

/**
 * A labelled slider whose value can also be typed exactly: tap the value to enter a number or
 * nudge it by a fine step. Every slider in the app is one of these.
 */
@Composable
fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    unit: String,
    steps: Int = 0,
    format: (Float) -> String = ::fmt,
    /** Typed text → slider value (default: the number itself; a comma works as a decimal point). */
    parse: (String) -> Float? = ::parseNumber,
    /** The value as text to edit (default: a number with as many decimals as the step needs). */
    editText: ((Float) -> String)? = null,
    /** Nudge size in slider units (default: from the range and format). */
    step: Float? = null,
    keyboard: KeyboardType = KeyboardType.Decimal,
    onChange: (Float) -> Unit,
) {
    var editing by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(84.dp), style = MaterialTheme.typography.labelMedium)
        Slider(
            value = value.coerceIn(range),
            onValueChange = onChange,
            valueRange = range,
            steps = steps,
            modifier = Modifier.weight(1f),
        )
        // Tap the number to type it.
        Text(
            "${format(value)}$unit",
            Modifier.width(64.dp).clickable { editing = true }.padding(vertical = 8.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            textDecoration = TextDecoration.Underline,
        )
    }
    if (editing) {
        val nudge = step ?: defaultStep(range, steps, format)
        val whole = !format(1.5f).contains('.')
        val decimals = if (whole && nudge >= 1f) 0 else maxOf(2, kotlin.math.ceil(-log10(nudge.toDouble())).toInt())
        ExactValueDialog(
            label, value, range, unit, format, parse,
            editText ?: { v -> String.format(Locale.US, "%.${decimals}f", v) },
            nudge, keyboard,
            onChange = onChange,
            onDismiss = { editing = false },
        )
    }
}

@Composable
private fun ExactValueDialog(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    unit: String,
    format: (Float) -> String,
    parse: (String) -> Float?,
    editText: (Float) -> String,
    step: Float,
    keyboard: KeyboardType,
    onChange: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(editText(value)) }
    var current by remember { mutableStateOf(value) }
    val parsed = parse(text)
    fun nudge(by: Float) {
        current = (current + by).coerceIn(range)
        text = editText(current)
        onChange(current)
    }
    fun apply() {
        val v = parse(text) ?: return
        onChange(v.coerceIn(range))
        onDismiss()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(label) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    suffix = { if (unit.isNotBlank()) Text(unit.trim()) },
                    isError = parsed == null,
                    supportingText = {
                        Text(
                            if (parsed == null) "Not a number" else "Range ${format(range.start)} – ${format(range.endInclusive)}" +
                                if (parsed < range.start || parsed > range.endInclusive) " (will be clamped)" else "",
                        )
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = keyboard, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { apply() }),
                )
                // Fine nudges: applied at once, so you can watch the result.
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilledTonalButton(onClick = { nudge(-step * 10) }) { Text("−−") }
                    FilledTonalButton(onClick = { nudge(-step) }) { Text("−") }
                    FilledTonalButton(onClick = { nudge(step) }) { Text("+") }
                    FilledTonalButton(onClick = { nudge(step * 10) }) { Text("++") }
                }
                Text("Step ${editText(step)} (−− / ++ ten steps)", style = MaterialTheme.typography.labelSmall)
            }
        },
        confirmButton = { TextButton(onClick = ::apply, enabled = parsed != null) { Text("Set") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/** The exact-value dialog on its own, for number fields that are not sliders (node inputs). */
@Composable
fun NumberDialog(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    whole: Boolean,
    onChange: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    val format: (Float) -> String = if (whole) { v -> "${v.toInt()}" } else ::fmt
    val nudge = defaultStep(range, 0, format)
    val decimals = if (whole) 0 else maxOf(2, kotlin.math.ceil(-log10(nudge.toDouble())).toInt())
    ExactValueDialog(
        label, value, range, "", format, ::parseNumber,
        { v -> String.format(Locale.US, "%.${decimals}f", v) },
        nudge, KeyboardType.Decimal, onChange, onDismiss,
    )
}

/** A nudge that moves a visible digit: whole numbers by 1, others by a hundredth of the range's magnitude. */
private fun defaultStep(range: ClosedFloatingPointRange<Float>, steps: Int, format: (Float) -> String): Float {
    val span = range.endInclusive - range.start
    if (steps > 0) return span / (steps + 1)
    if (!format(1.5f).contains('.')) return 1f
    return 10f.pow(floor(log10(span.coerceAtLeast(1e-6f))) - 2).coerceAtLeast(0.001f)
}

fun parseNumber(text: String): Float? = text.trim().replace(',', '.').removePrefix("+").toFloatOrNull()

fun fmt(x: Number): String = String.format(Locale.US, "%.2f", x.toDouble())
