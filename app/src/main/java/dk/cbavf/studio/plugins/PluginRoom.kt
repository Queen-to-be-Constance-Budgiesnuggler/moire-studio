package dk.cbavf.studio.plugins

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dk.cbavf.studio.foreground.Foreground
import dk.cbavf.studio.foreground.RunGate
import dk.cbavf.studio.math.AdsrShape
import dk.cbavf.studio.math.Curve
import dk.cbavf.studio.routing.ButtonSpec
import dk.cbavf.studio.routing.KnobSpec
import dk.cbavf.studio.routing.ModuleBackSpec
import dk.cbavf.studio.routing.Router
import dk.cbavf.studio.routing.SourcePicker
import dk.cbavf.studio.routing.model.PLUGINS_SCOPE_IN
import dk.cbavf.studio.routing.model.Rack
import dk.cbavf.studio.ui.EnvelopeEditor
import dk.cbavf.studio.ui.LabeledSlider
import dk.cbavf.studio.ui.ScrollRow
import dk.cbavf.studio.ui.fmt
import dk.cbavf.studio.uieditor.ControlKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * The live values of the switched-on plugins' controls, keyed `plugin-<id>.<label>`: a slider holds its number, a toggle 0 or 1, a
 * button 1 until the next behaviour step after it is pressed. Plugin controls are real controls: the UI Editor's cables can read
 * them and drive them. A plugin with a behaviour also has outputs, worked out by [runBehaviours] each frame.
 */
class PluginState {
    private val values = mutableStateMapOf<String, Float>()
    private val outputs = mutableStateMapOf<String, Float>()
    private val runs = HashMap<String, BehaviourRun>()
    private var start = -1L
    private var lastT = 0.0

    fun key(plugin: PluginManifest, w: PluginWidget) = "plugin-${plugin.id}.${w.label}"

    private fun outKey(plugin: PluginManifest, name: String) = "plugin-${plugin.id}>$name"

    fun value(plugin: PluginManifest, w: PluginWidget): Float = values[key(plugin, w)] ?: when (w.kind) {
        ControlKind.Slider -> w.min
        else -> 0f
    }

    fun set(plugin: PluginManifest, w: PluginWidget, v: Float) {
        values[key(plugin, w)] = v
    }

    /** Output [name] of [plugin]'s behaviour, as last worked out (0 before the first step). */
    fun output(plugin: PluginManifest, name: String): Float = outputs[outKey(plugin, name)] ?: 0f

    /** One behaviour step for each of [plugins] that has one, at frame time [nanos]. */
    fun tick(nanos: Long, plugins: List<PluginManifest>) {
        if (start < 0) start = nanos
        val t = (nanos - start) / 1e9
        val dt = (t - lastT).toFloat().coerceAtLeast(0f)
        lastT = t
        for (p in plugins) {
            val g = p.behaviour ?: continue
            val ins = PluginBehaviour.controls(p.widgets).map { value(p, it) }
            val outs = runs.getOrPut(p.id) { BehaviourRun() }.step(g, ins, t, dt)
            g.outputs.forEachIndexed { i, s ->
                val k = outKey(p, s.name)
                val v = outs[i]
                // Written only when it moves, so a still output does not redraw what shows it.
                if (outputs[k]?.let { abs(it - v) > 1e-5f } != false) outputs[k] = v
            }
            // A press lasts one step.
            p.widgets.filter { it.kind == ControlKind.Button && value(p, it) >= 0.5f }.forEach { set(p, it, 0f) }
        }
    }

    /** Steps the behaviours of [plugins] once a frame while the studio is on screen; idles when none has a behaviour. */
    suspend fun runBehaviours(plugins: () -> List<PluginManifest>, gate: RunGate = Foreground.gate) {
        while (true) {
            val list = plugins().filter { it.behaviour != null }
            if (!gate.isStarted || list.isEmpty()) {
                gate.sleep(250)
                continue
            }
            withFrameNanos { tick(it, list) }
        }
    }

    /**
     * The plugin as a module back for the UI Editor and its cables: sliders as knobs, toggles and buttons as buttons, and the
     * behaviour's outputs as knobs that show the output (turning one changes nothing).
     */
    fun spec(plugin: PluginManifest): ModuleBackSpec = ModuleBackSpec(
        "plugin-${plugin.id}", plugin.name, null,
        knobs = plugin.widgets.filter { it.kind == ControlKind.Slider }.map { w ->
            KnobSpec(w.label, value(plugin, w), w.min..w.max.coerceAtLeast(w.min + 1e-3f), onChange = { set(plugin, w, it) })
        } + PluginBehaviour.outputs(plugin).map { s ->
            KnobSpec(PluginBehaviour.outputLabel(s.name), output(plugin, s.name), s.min..s.max.coerceAtLeast(s.min + 1e-3f), onChange = {})
        },
        buttons = plugin.widgets.filter { it.kind == ControlKind.Toggle || it.kind == ControlKind.Button }.map { w ->
            if (w.kind == ControlKind.Toggle) ButtonSpec(w.label, on = value(plugin, w) >= 0.5f) { set(plugin, w, if (value(plugin, w) >= 0.5f) 0f else 1f) }
            else ButtonSpec(w.label) { set(plugin, w, 1f) }
        },
    )
}

/** The room of the switched-on plugins: pick one and use its controls, see its outputs, or its scope. */
@Composable
fun PluginRoom(plugins: List<PluginManifest>, state: PluginState, router: Router) {
    var pick by remember(plugins) { mutableStateOf(plugins.firstOrNull()?.id) }
    val shown = plugins.firstOrNull { it.id == pick } ?: plugins.firstOrNull()
    Column(Modifier.fillMaxSize().padding(12.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (shown == null) {
            Text("No plugin is switched on. Install or build one (Module Forge, Studio → Plugins) and switch it on.", style = MaterialTheme.typography.bodyMedium)
            return@Column
        }
        ScrollRow { plugins.forEach { p -> FilterChip(selected = p.id == shown.id, onClick = { pick = p.id }, label = { Text(p.name) }) } }
        if (shown.description.isNotBlank()) Text(shown.description, style = MaterialTheme.typography.labelMedium)
        Text("${shown.kind.name} · by ${shown.author.ifBlank { "unknown" }} · v${shown.version}", style = MaterialTheme.typography.labelSmall)
        if (shown.scope != null) Scope(shown, router)
        shown.widgets.forEach { w ->
            when (w.kind) {
                ControlKind.Slider -> LabeledSlider(w.label, state.value(shown, w), w.min..w.max.coerceAtLeast(w.min + 1e-3f), "") { state.set(shown, w, it) }
                ControlKind.Toggle -> FilterChip(selected = state.value(shown, w) >= 0.5f, onClick = { state.set(shown, w, if (state.value(shown, w) >= 0.5f) 0f else 1f) }, label = { Text(w.label) })
                ControlKind.Button -> Button(onClick = { state.set(shown, w, 1f) }) { Text(w.label) }
                ControlKind.Envelope -> {
                    var shape by remember(shown.id, w.label) { mutableStateOf(AdsrShape(50.0, 200.0, 0.6, 400.0)) }
                    Text(w.label, style = MaterialTheme.typography.labelMedium)
                    EnvelopeEditor(shape, Curve.Linear, onChange = { shape = it }, modifier = Modifier.fillMaxWidth())
                }
            }
        }
        val outs = PluginBehaviour.outputs(shown)
        if (outs.isNotEmpty()) {
            Text("Outputs", style = MaterialTheme.typography.titleSmall)
            outs.forEach { s ->
                val v = state.output(shown, s.name)
                val span = (s.max - s.min).takeIf { it > 0f } ?: 1f
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(s.name, Modifier.weight(0.35f), style = MaterialTheme.typography.labelMedium)
                    LinearProgressIndicator(progress = { ((v - s.min) / span).coerceIn(0f, 1f) }, modifier = Modifier.weight(0.45f))
                    Text(fmt(v), Modifier.weight(0.2f), style = MaterialTheme.typography.labelMedium)
                }
            }
            Text("Cables in the UI Editor can read these: they are on the plugin's panel as \"(out)\".", style = MaterialTheme.typography.labelSmall)
        }
    }
}

/** A scope plugin's face: its source jack, then the traces of whatever picture comes in. */
@Composable
private fun Scope(plugin: PluginManifest, router: Router) {
    val jack = Rack.input(PLUGINS_SCOPE_IN) ?: return
    SourcePicker(router, jack)
    val version = router.inputVersion(PLUGINS_SCOPE_IN)
    val traces by produceState<List<IntArray>?>(null, plugin.scope, version) {
        value = if (version < 0) null else withContext(Dispatchers.Default) {
            val b = router.inputBitmap(PLUGINS_SCOPE_IN, SCOPE_PX) ?: return@withContext null
            val px = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }
            PluginScopes.traces(plugin.scope, px, b.width, b.height)
        }
    }
    ScopeTraces(traces, PluginScopes.COLS, PluginScopes.BINS, Modifier.fillMaxWidth().height(220.dp))
    Text(
        when {
            version < 0 -> "Pick a picture above (Develop or Compose, say) to see its ${PluginScopes.label(plugin.scope) ?: "scope"}."
            traces == null -> "Reading the picture…"
            else -> "Left to right as in the picture; up is brighter. Lines along the top or bottom are clipping."
        },
        style = MaterialTheme.typography.labelSmall,
    )
}

private const val SCOPE_PX = 480
