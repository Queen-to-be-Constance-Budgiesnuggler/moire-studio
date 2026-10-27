package dk.cbavf.studio.nodes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import dk.cbavf.studio.math.AdsrShape
import dk.cbavf.studio.math.Curve
import dk.cbavf.studio.nodes.model.Catalog
import dk.cbavf.studio.nodes.model.Node
import dk.cbavf.studio.nodes.model.Prop
import dk.cbavf.studio.ui.EnvelopeEditor
import dk.cbavf.studio.ui.LabeledSlider
import dk.cbavf.studio.ui.ScrollRow
import kotlin.math.roundToInt

/** The editor for an Envelope node: drag the shape, or type exact values; the curve is a choice of the node. */
@Composable
fun EnvelopeNodeDialog(state: NodesState, node: Node, cat: Catalog, onDismiss: () -> Unit) {
    val defs = cat["envelope"]?.inputs.orEmpty()
    fun value(name: String): Float {
        val i = defs.indexOfFirst { it.name == name }
        return (node.props[name] as? Prop.Num)?.v ?: defs.getOrNull(i)?.default ?: 0f
    }
    val shape = AdsrShape(value("Attack ms").toDouble(), value("Decay ms").toDouble(), value("Sustain").toDouble(), value("Release ms").toDouble())
    val curve = runCatching { Curve.valueOf((node.props["Curve"] as? Prop.Choice)?.v ?: "Linear") }.getOrDefault(Curve.Linear)

    fun set(name: String, v: Float) = state.setProp(node.id, name, Prop.Num(v), "env${node.id}.$name")
    fun setShape(s: AdsrShape) = state.setProps(
        node.id,
        mapOf(
            "Attack ms" to Prop.Num(s.attackMs.roundToInt().toFloat()),
            "Decay ms" to Prop.Num(s.decayMs.roundToInt().toFloat()),
            "Sustain" to Prop.Num((Math.round(s.sustain * 100) / 100.0).toFloat()),
            "Release ms" to Prop.Num(s.releaseMs.roundToInt().toFloat()),
        ),
        "env${node.id}.shape",
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Envelope") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                EnvelopeEditor(shape, curve, onChange = ::setShape)
                ScrollRow {
                    Curve.entries.forEach { c ->
                        FilterChip(selected = c == curve, onClick = { state.setProp(node.id, "Curve", Prop.Choice(c.name)) }, label = { Text(c.name) })
                    }
                }
                LabeledSlider("Attack", shape.attackMs.toFloat(), 0f..4000f, " ms", format = { "${it.roundToInt()}" }) { set("Attack ms", it.roundToInt().toFloat()) }
                LabeledSlider("Decay", shape.decayMs.toFloat(), 0f..4000f, " ms", format = { "${it.roundToInt()}" }) { set("Decay ms", it.roundToInt().toFloat()) }
                LabeledSlider("Sustain", shape.sustain.toFloat(), 0f..1f, "") { set("Sustain", it) }
                LabeledSlider("Release", shape.releaseMs.toFloat(), 0f..4000f, " ms", format = { "${it.roundToInt()}" }) { set("Release ms", it.roundToInt().toFloat()) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}
