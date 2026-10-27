package dk.cbavf.studio.forge

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import dk.cbavf.studio.nodes.model.Graph
import dk.cbavf.studio.nodes.model.Node
import dk.cbavf.studio.nodes.model.Prop
import dk.cbavf.studio.nodes.model.SocketType
import dk.cbavf.studio.nodes.model.catalog
import dk.cbavf.studio.plugins.PluginBehaviour
import dk.cbavf.studio.ui.LabeledSlider
import dk.cbavf.studio.ui.ScrollRow

/**
 * The behaviour behind a plugin's controls, as a list instead of a canvas: steps (Node Lab's number nodes), each input fed by
 * a control, another step or its own number; then the outputs, each fed the same way. It is still a node graph underneath.
 */
@Composable
fun BehaviourEditor(graph: Graph, onChange: (Graph) -> Unit) {
    val cat = graph.catalog()
    Text("Behaviour", style = MaterialTheme.typography.titleMedium)
    Text(
        "Steps turn the controls into outputs. Each step is a Node Lab node. The outputs show in the Plugins room, " +
            "and cables in the UI Editor can read them.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (graph.inputs.isEmpty()) Text("Add a slider, toggle or button above to have something to work with.", style = MaterialTheme.typography.labelMedium)

    PluginBehaviour.steps(graph).forEach { n ->
        val def = cat[n.type] ?: return@forEach
        Surface(Modifier.fillMaxWidth(), tonalElevation = 1.dp, shape = RoundedCornerShape(8.dp)) {
            Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(PluginBehaviour.stepName(graph, n), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                    TextButton(onClick = { onChange(PluginBehaviour.removeStep(graph, n.id)) }) { Text("Remove") }
                }
                if (def.help.isNotBlank()) Text(def.help, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                def.props.filter { it.options.isNotEmpty() }.forEach { p ->
                    val current = (n.props[p.name] as? Prop.Choice)?.v ?: p.default
                    ScrollRow {
                        Text(p.name, Modifier.width(72.dp), style = MaterialTheme.typography.labelMedium)
                        p.options.forEach { o ->
                            FilterChip(selected = o == current, onClick = { onChange(PluginBehaviour.setProp(graph, n.id, p.name, Prop.Choice(o))) }, label = { Text(o) })
                        }
                    }
                }
                def.inputs.forEachIndexed { i, s -> InputRow(graph, n, i, s.name, s.type, s.min..s.max, s.default, onChange) }
            }
        }
    }
    StepMenu { type -> onChange(PluginBehaviour.addStep(graph, type).first) }

    Text("Outputs", style = MaterialTheme.typography.titleSmall)
    val out = graph.node(PluginBehaviour.OUT)
    graph.outputs.forEachIndexed { i, s ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                if (out != null) InputRow(graph, out, i, s.name, SocketType.Value, s.min..s.max, s.min, onChange)
            }
            TextButton(onClick = { onChange(PluginBehaviour.removeOutput(graph, i)) }) { Text("Remove") }
        }
    }
    if (graph.outputs.isEmpty()) Text("No outputs yet.", style = MaterialTheme.typography.labelMedium)
    var newName by remember { mutableStateOf("") }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(newName, { newName = it }, label = { Text("Output name") }, singleLine = true, modifier = Modifier.weight(1f))
        TextButton(
            onClick = { onChange(PluginBehaviour.addOutput(graph, newName)); newName = "" },
            enabled = newName.isNotBlank(),
        ) { Text("Add output") }
    }
}

/** One input of a step (or one output): where its value comes from, and its own number when nothing feeds it. */
@Composable
private fun InputRow(
    graph: Graph, n: Node, socket: Int, name: String, type: SocketType,
    range: ClosedFloatingPointRange<Float>, default: Float, onChange: (Graph) -> Unit,
) {
    val src = PluginBehaviour.sourceOf(graph, n.id, socket)
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) {
            Text("$name ⟵ " + (src?.label ?: if (type == SocketType.Value) "its own number" else "nothing") + " ▾", style = MaterialTheme.typography.labelMedium)
        }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(if (type == SocketType.Value) "Its own number" else "Nothing") },
                onClick = { onChange(PluginBehaviour.unlink(graph, n.id, socket)); open = false },
            )
            PluginBehaviour.sources(graph, n.id, socket).forEach { s ->
                DropdownMenuItem(text = { Text(s.label) }, onClick = { onChange(PluginBehaviour.link(graph, s, n.id, socket)); open = false })
            }
        }
    }
    if (src == null && type == SocketType.Value) {
        val v = (n.props[name] as? Prop.Num)?.v ?: default
        LabeledSlider(name, v, range, "") { onChange(PluginBehaviour.setProp(graph, n.id, name, Prop.Num(it))) }
    }
}

/** "Add step": every step type, by name. */
@Composable
private fun StepMenu(onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) { Text("Add step ▾") }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            PluginBehaviour.stepDefs().forEach { d ->
                DropdownMenuItem(text = { Text(d.label) }, onClick = { onPick(d.type); open = false })
            }
        }
    }
}
