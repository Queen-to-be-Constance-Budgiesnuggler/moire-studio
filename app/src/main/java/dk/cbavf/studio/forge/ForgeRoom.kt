package dk.cbavf.studio.forge

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dk.cbavf.studio.nodes.model.Graph
import dk.cbavf.studio.plugins.PluginBehaviour
import dk.cbavf.studio.plugins.PluginFormat
import dk.cbavf.studio.plugins.PluginKind
import dk.cbavf.studio.plugins.PluginManifest
import dk.cbavf.studio.plugins.PluginScopes
import dk.cbavf.studio.plugins.PluginStore
import dk.cbavf.studio.plugins.PluginWidget
import dk.cbavf.studio.studio.StudioPrefs
import dk.cbavf.studio.ui.ScrollRow
import dk.cbavf.studio.uieditor.ControlKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Module Forge: build a module's face (name, kind, controls), give it a behaviour (a node graph behind the controls), wrap it
 * as a plugin and switch it on. An enabled plugin shows up as a panel in the UI Editor, where its controls can be placed and
 * patched. Plugins are data, never code.
 */
@Composable
fun ForgeRoom(prefs: StudioPrefs, onChanged: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { PluginStore(context) }
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(PluginKind.Controller) }
    var scopeId by remember { mutableStateOf<String?>(null) }
    val widgets = remember { mutableStateListOf<PluginWidget>() }
    var behaviour by remember { mutableStateOf<Graph?>(null) }
    var newLabel by remember { mutableStateOf("") }
    var newKind by remember { mutableStateOf(ControlKind.Slider) }
    var revision by remember { mutableStateOf(0) }
    val installed by produceState(emptyList<PluginManifest>(), revision) { value = withContext(Dispatchers.IO) { store.installed() } }
    val author = prefs.name.ifBlank { prefs.handle }

    /** The behaviour follows the controls: a control added or removed is added to or removed from its Group Input. */
    fun resync() {
        behaviour = behaviour?.let { PluginBehaviour.sync(widgets.toList(), it) }
    }

    fun manifest() = PluginManifest(
        id = PluginFormat.idFor(name), name = name.trim(), author = author, description = description.trim(),
        kind = kind, widgets = widgets.toList(), behaviour = behaviour?.let { PluginBehaviour.sync(widgets.toList(), it) },
        scope = scopeId.takeIf { kind == PluginKind.Scope },
    )

    fun open(m: PluginManifest) {
        name = m.name; description = m.description; kind = m.kind; scopeId = m.scope
        widgets.clear(); widgets.addAll(m.widgets)
        behaviour = m.behaviour
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Module Forge", style = MaterialTheme.typography.titleLarge)
        Text(
            "Build a module's face, give it a behaviour, wrap it as a plugin, switch it on. It then has its own panel in the UI Editor " +
                "(and so its own jacks on the back). Studio → Plugins lists, enables and removes plugins.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (installed.isNotEmpty()) ScrollRow {
            Text("Open", Modifier.width(56.dp), style = MaterialTheme.typography.labelMedium)
            installed.forEach { m -> TextButton(onClick = { open(m) }) { Text(m.name) } }
        }
        OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(description, { description = it }, label = { Text("What it is for") }, modifier = Modifier.fillMaxWidth())
        ScrollRow {
            Text("Kind", Modifier.width(56.dp), style = MaterialTheme.typography.labelMedium)
            PluginKind.entries.forEach { k -> FilterChip(selected = kind == k, onClick = { kind = k }, label = { Text(k.name) }) }
        }
        if (kind == PluginKind.Scope) ScrollRow {
            Text("Shows", Modifier.width(56.dp), style = MaterialTheme.typography.labelMedium)
            FilterChip(selected = scopeId == null, onClick = { scopeId = null }, label = { Text("Nothing") })
            PluginScopes.ALL.forEach { (id, label) -> FilterChip(selected = scopeId == id, onClick = { scopeId = id }, label = { Text(label) }) }
        }
        HorizontalDivider()
        Text("Controls", style = MaterialTheme.typography.titleMedium)
        widgets.forEachIndexed { i, w ->
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("${w.kind.name.lowercase()}  ·  ${w.label}", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { widgets.removeAt(i); resync() }) { Text("Remove") }
            }
        }
        if (widgets.isEmpty()) Text("None yet.", style = MaterialTheme.typography.labelMedium)
        ScrollRow { ControlKind.entries.forEach { k -> FilterChip(selected = newKind == k, onClick = { newKind = k }, label = { Text(k.name) }) } }
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(newLabel, { newLabel = it }, label = { Text("Label") }, singleLine = true, modifier = Modifier.weight(1f))
            TextButton(
                onClick = { widgets += PluginWidget(newLabel.trim(), newKind); newLabel = ""; resync() },
                enabled = newLabel.isNotBlank() && widgets.none { it.label.equals(newLabel.trim(), true) },
            ) { Text("Add") }
        }
        HorizontalDivider()
        val graph = behaviour
        if (graph == null) {
            TextButton(onClick = { behaviour = PluginBehaviour.starter(widgets.toList()) }) { Text("Give it a behaviour") }
        } else {
            BehaviourEditor(graph) { behaviour = it }
            TextButton(onClick = { behaviour = null }) { Text("No behaviour") }
        }
        HorizontalDivider()
        val ready = name.isNotBlank()
        ScrollRow {
            Button(
                enabled = ready,
                onClick = {
                    val m = manifest()
                    store.save(m)
                    prefs.config.updateProfile { it.copy(enabledPlugins = (it.enabledPlugins + m.id).distinct()) }
                    revision++
                    onChanged()
                    Toast.makeText(context, "“${m.name}” saved and switched on: see the UI Editor", Toast.LENGTH_LONG).show()
                },
            ) { Text("Save and switch on") }
            TextButton(
                enabled = ready,
                onClick = {
                    val m = manifest()
                    scope.launch {
                        val uri = withContext(Dispatchers.IO) { runCatching { store.shareFile(m) }.getOrNull() }
                        if (uri == null || !PluginStore.openShareSheet(context, m, uri)) Toast.makeText(context, "Could not share it", Toast.LENGTH_SHORT).show()
                    }
                },
            ) { Text("Share…") }
            TextButton(
                enabled = ready,
                onClick = {
                    val uri = runCatching { store.export(manifest()) }.getOrNull()
                    Toast.makeText(context, if (uri != null) "Exported ${PluginFormat.idFor(name)}.${PluginFormat.EXTENSION} to Pictures/Studio" else "Export failed", Toast.LENGTH_LONG).show()
                },
            ) { Text("Export .${PluginFormat.EXTENSION}") }
            TextButton(onClick = { name = ""; description = ""; widgets.clear(); behaviour = null; scopeId = null; kind = PluginKind.Controller }) { Text("Clear") }
        }
    }
}
