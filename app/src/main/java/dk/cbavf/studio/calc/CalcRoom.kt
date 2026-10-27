package dk.cbavf.studio.calc

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import dk.cbavf.studio.routing.Router
import dk.cbavf.studio.routing.jackLabel
import dk.cbavf.studio.routing.model.CALC_RESULT
import dk.cbavf.studio.routing.model.JackType
import dk.cbavf.studio.routing.model.Rack
import dk.cbavf.studio.ui.ScrollRow

/** The calculator room's state: its book (history and variables) and the line being typed. Kept while the studio is open. */
class CalcState {
    var book by mutableStateOf(CalcBook())
    var field by mutableStateOf(TextFieldValue(""))

    /** Puts [text] into the line at the cursor (or over the selection). */
    fun insert(text: String) {
        val f = field
        val s = f.selection
        val next = f.text.substring(0, s.min) + text + f.text.substring(s.max)
        field = TextFieldValue(next, TextRange(s.min + text.length))
    }
}

/** A knob somewhere in the studio a result can be sent to (the same knobs as on the modules' backs). */
class CalcTarget(val label: String, val range: ClosedFloatingPointRange<Float>, val whole: Boolean, val set: (Float) -> Unit)

/** Typing helpers: what the chip shows, and what it puts in the line. */
private val KEYS = listOf(
    "ans" to "ans", "(" to "(", ")" to ")", "^" to "^", "π" to "pi", "√" to "sqrt(", "sin" to "sin(",
    "deg" to " deg", "in hex" to " in hex", "in bin" to " in bin", "in oct" to " in oct", "in dec" to " in dec",
    "in cm" to " in cm", "in inch" to " in inch", "in °F" to " in °F",
)

/**
 * The calculator: type a line (`2 * (3 + 4)`, `r = 2.5 cm`, `0xff in bin`, `100 °C in °F`), press Enter, and the result
 * joins the history. The latest result goes out on the rack's Result jack and can be sent to any knob in the studio.
 */
@Composable
fun CalcRoom(
    state: CalcState,
    router: Router,
    /** The knobs a result can be sent to, built when the menu opens. */
    targets: () -> List<CalcTarget>,
    onMessage: (String) -> Unit,
) {
    var error by remember { mutableStateOf<String?>(null) }
    val book = state.book
    val last = book.history.firstOrNull()

    fun submit() {
        if (state.field.text.isBlank()) return
        val (next, out) = book.submit(state.field.text)
        when (out) {
            is CalcOutcome.Ok -> {
                state.book = next
                state.field = TextFieldValue("")
                error = null
                router.publishLevel(CALC_RESULT, out.result.number.toFloat())
            }
            is CalcOutcome.Err -> error = out.message
        }
    }

    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // The result: the last line worked out, or what the line being typed would give.
        Surface(tonalElevation = 2.dp, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val preview = book.preview(state.field.text)
                when {
                    error != null -> Text(error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    preview is CalcOutcome.Ok -> Text("= " + preview.result.text, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                    else -> Text(if (last == null) "Type a sum and press Enter." else last.input, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                }
                Text(last?.text ?: "0", style = MaterialTheme.typography.headlineMedium, fontFamily = FontFamily.Monospace)
                last?.let { e ->
                    NumberBase.all(e.number)?.takeIf { e.number != 0.0 }?.let { bases ->
                        ScrollRow {
                            bases.forEach { (b, s) ->
                                Text("${b.label} $s", Modifier.clickable { state.insert(s) }, style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace)
                            }
                        }
                    }
                }
            }
        }
        OutlinedTextField(
            value = state.field,
            onValueChange = { state.field = it; error = null },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Sum") },
            placeholder = { Text("2 * (3 + 4), 5 km in mi, 0xff in bin") },
            textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }),
        )
        ScrollRow {
            FilledTonalButton(onClick = ::submit) { Text("=") }
            KEYS.forEach { (label, text) -> AssistChip(onClick = { state.insert(text) }, label = { Text(label) }) }
        }
        ScrollRow {
            SendToControl(last, targets, onMessage)
            SendToJack(router, last)
        }
        if (book.named.isNotEmpty()) {
            ScrollRow {
                Text("Variables", style = MaterialTheme.typography.labelMedium)
                book.named.forEach { (name, q) ->
                    AssistChip(onClick = { state.insert(name) }, label = { Text("$name = ${Calc.show(q, null).first}") })
                }
            }
        }
        HorizontalDivider()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("History", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
            TextButton(onClick = { state.book = book.clearHistory() }, enabled = book.history.isNotEmpty()) { Text("Clear") }
        }
        if (book.history.isEmpty()) {
            Text("Tap a line here to use it again.", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            items(book.history) { e ->
                Column(
                    Modifier.fillMaxWidth().clickable { state.field = TextFieldValue(e.input, TextRange(e.input.length)) }.padding(vertical = 6.dp),
                ) {
                    Text(e.input, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = FontFamily.Monospace)
                    Text(e.text, style = MaterialTheme.typography.bodyLarge, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}

/** Sends the last result to a knob of any module, kept inside its range. */
@Composable
private fun SendToControl(last: CalcEntry?, targets: () -> List<CalcTarget>, onMessage: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var list by remember { mutableStateOf(emptyList<CalcTarget>()) }
    Box {
        OutlinedButton(onClick = { list = targets(); open = true }, enabled = last != null) { Text("Send to a control…") }
        DropdownMenu(open, onDismissRequest = { open = false }, modifier = Modifier.heightIn(max = 420.dp)) {
            list.forEach { t ->
                DropdownMenuItem(
                    text = { Text("${t.label}  (${Calc.format(t.range.start.toDouble())} to ${Calc.format(t.range.endInclusive.toDouble())})") },
                    onClick = {
                        open = false
                        val e = last ?: return@DropdownMenuItem
                        val f = CalcSend.fit(e.number, t.range, t.whole)
                        t.set(f.value)
                        onMessage("${t.label} set to ${Calc.format(f.value.toDouble())}" + if (f.clamped) " (kept inside its range)" else "")
                    },
                )
            }
        }
    }
}

/** Patches the Result jack into a number input of another module, the way the Studio's back does. */
@Composable
private fun SendToJack(router: Router, last: CalcEntry?) {
    var open by remember { mutableStateOf(false) }
    val targets = router.doc.targetsOf(CALC_RESULT)
    Box {
        OutlinedButton(onClick = { open = true }) {
            Text(if (targets.isEmpty()) "Result jack: not patched" else "Result jack ⟶ " + targets.joinToString { jackLabel(it) })
        }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            Rack.modules.filter { it.id != Rack.moduleOf(CALC_RESULT) }.forEach { m ->
                m.inputs.filter { it.type.accepts(JackType.Value) }.forEach { inp ->
                    val on = router.doc.sourceOf(inp.id) == CALC_RESULT
                    DropdownMenuItem(
                        text = { Text((if (on) "✓ " else "   ") + jackLabel(inp.id)) },
                        onClick = {
                            if (on) router.disconnect(inp.id)
                            else {
                                router.connect(CALC_RESULT, inp.id)
                                last?.let { router.publishLevel(CALC_RESULT, it.number.toFloat()) }
                            }
                        },
                    )
                }
            }
        }
    }
}
