package dk.cbavf.studio.routing

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dk.cbavf.studio.routing.model.JackDef
import dk.cbavf.studio.routing.model.Rack
import dk.cbavf.studio.routing.model.RackPreset
import dk.cbavf.studio.routing.model.RackPresets
import dk.cbavf.studio.ui.Knob
import dk.cbavf.studio.ui.LocalCanvasColors
import kotlinx.serialization.Serializable

/** A knob on a module's back panel. */
class KnobSpec(
    val label: String,
    val value: Float,
    val range: ClosedFloatingPointRange<Float>,
    val whole: Boolean = false,
    val format: ((Float) -> String)? = null,
    val onChange: (Float) -> Unit,
)

/** A button on a module's back panel; [on] lights it (a toggle). */
class ButtonSpec(val label: String, val on: Boolean? = null, val onClick: () -> Unit)

/** A saved state of one module (its whole document), recalled from the back panel. */
@Serializable
data class ModulePreset(val name: String, val json: String, val at: Long)

/** How a module's state is saved as a preset and brought back (undoably, in the room itself). */
class ModulePort(val snapshot: () -> String, val restore: (String) -> Unit)

/** One module's back: its rack jacks (if it has any), knobs, buttons and presets. */
class ModuleBackSpec(
    val roomId: String,
    val title: String,
    /** The rack module behind this room, for its jacks. */
    val rackId: String?,
    val knobs: List<KnobSpec> = emptyList(),
    val buttons: List<ButtonSpec> = emptyList(),
    val port: ModulePort? = null,
)

/**
 * The studio's back side (Node Lab → Studio routing): the whole app turned round. The room bar is
 * mirrored like the back of a rack, and each module shows its back where its front would be: jacks
 * and cables on the panel above (where the canvas is on the front), its knobs, buttons and presets
 * below (where its controls are). "All cables" shows every module's back at once.
 */
@Composable
fun StudioBack(
    router: Router,
    specs: List<ModuleBackSpec>,
    selected: String?,
    onSelect: (String?) -> Unit,
    presets: Map<String, List<ModulePreset>>,
    onSavePreset: (String, String) -> Unit,
    onDeletePreset: (String, ModulePreset) -> Unit,
    rackPresets: List<RackPreset>,
    onSaveRackPreset: (String) -> Unit,
    onDeleteRackPreset: (RackPreset) -> Unit,
    onLoadRackPreset: (RackPreset) -> Unit,
    onFlip: () -> Unit,
) {
    // Mirrored in the y axis: rows run right to left, as a rack seen from behind. Text itself stays
    // left to right (numbers, signs and punctuation would otherwise be reordered).
    val t = MaterialTheme.typography
    fun TextStyle.ltr() = copy(textDirection = TextDirection.Ltr)
    val ltrTypography = t.copy(
        displayLarge = t.displayLarge.ltr(), displayMedium = t.displayMedium.ltr(), displaySmall = t.displaySmall.ltr(),
        headlineLarge = t.headlineLarge.ltr(), headlineMedium = t.headlineMedium.ltr(), headlineSmall = t.headlineSmall.ltr(),
        titleLarge = t.titleLarge.ltr(), titleMedium = t.titleMedium.ltr(), titleSmall = t.titleSmall.ltr(),
        bodyLarge = t.bodyLarge.ltr(), bodyMedium = t.bodyMedium.ltr(), bodySmall = t.bodySmall.ltr(),
        labelLarge = t.labelLarge.ltr(), labelMedium = t.labelMedium.ltr(), labelSmall = t.labelSmall.ltr(),
    )
    MaterialTheme(colorScheme = MaterialTheme.colorScheme, typography = ltrTypography) {
    CompositionLocalProvider(
        LocalLayoutDirection provides LayoutDirection.Rtl,
        LocalTextStyle provides LocalTextStyle.current.ltr(),
    ) {
        Column(Modifier.fillMaxSize().background(Color(LocalCanvasColors.current.rackBack))) {
            Surface(tonalElevation = 3.dp) {
                Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilledTonalButton(onClick = onFlip) { Text("⟲ Flip to front") }
                    Text(
                        "  Back of " + (specs.firstOrNull { it.roomId == selected }?.title ?: "the studio"),
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    TextButton(onClick = router.edit::undo, enabled = router.edit.canUndo) { Text("Undo") }
                    TextButton(onClick = router.edit::redo, enabled = router.edit.canRedo) { Text("Redo") }
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                val spec = specs.firstOrNull { it.roomId == selected }
                if (spec == null) {
                    // The overview's cable layout reads left to right.
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        Column(Modifier.fillMaxSize()) {
                            RackPresetBar(router, rackPresets, onSaveRackPreset, onDeleteRackPreset, onLoadRackPreset)
                            Box(Modifier.weight(1f).fillMaxWidth()) { RackOverview(router) }
                        }
                    }
                } else {
                    ModuleBack(router, spec, presets[spec.roomId].orEmpty(), onSavePreset = { onSavePreset(spec.roomId, it) }, onDeletePreset = { onDeletePreset(spec.roomId, it) })
                }
            }
            // The room bar, from behind.
            LazyRow(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).navigationBarsPadding().padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item { FilterChip(selected = selected == null, onClick = { onSelect(null) }, label = { Text("All cables (${router.doc.cables.size})") }) }
                items(specs) { s ->
                    val cables = s.rackId?.let { id -> router.doc.cables.count { Rack.moduleOf(it.from) == id || Rack.moduleOf(it.to) == id } } ?: 0
                    FilterChip(selected = selected == s.roomId, onClick = { onSelect(s.roomId) }, label = { Text(s.title + if (cables > 0) " ·$cables" else "") })
                }
            }
        }
    }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModuleBack(router: Router, spec: ModuleBackSpec, presets: List<ModulePreset>, onSavePreset: (String) -> Unit, onDeletePreset: (ModulePreset) -> Unit) {
    val m = spec.rackId?.let { id -> Rack.modules.firstOrNull { it.id == id } }
    var naming by remember { mutableStateOf(false) }
    val cc = LocalCanvasColors.current
    val silk = Color(cc.silk)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Upper panel (the canvas on the front): the jacks.
        Column(
            Modifier.fillMaxWidth().heightIn(min = 220.dp).background(Color(cc.panel), RoundedCornerShape(6.dp)).border(1.dp, Color(cc.panelEdge), RoundedCornerShape(6.dp)).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(12.dp).background(Color(cc.screw), CircleShape))
                Text("  ${spec.title.uppercase()}  ·  REAR PANEL", Modifier.weight(1f), color = silk, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                Box(Modifier.size(12.dp).background(Color(cc.screw), CircleShape))
            }
            if (m == null || (m.inputs.isEmpty() && m.outputs.isEmpty())) {
                Text("No jacks on this module yet: it doesn't send or take pictures in the studio routing.", color = silk.copy(alpha = 0.5f), style = MaterialTheme.typography.labelMedium)
            } else {
                if (m.outputs.isNotEmpty()) Text("OUT", color = silk.copy(alpha = 0.5f), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                m.outputs.forEach { j -> OutputJack(router, j) }
                if (m.inputs.isNotEmpty()) Text("IN", color = silk.copy(alpha = 0.5f), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                m.inputs.forEach { j -> InputJack(router, j) }
            }
        }
        // Lower panel (the controls on the front): knobs, buttons, presets. A knob's exact-value box opens over this panel.
        dk.cbavf.studio.ui.layout.ControlsPanel(Modifier.fillMaxWidth(), cardDirection = LayoutDirection.Ltr) {
        Surface(tonalElevation = 2.dp, shape = RoundedCornerShape(6.dp)) {
            Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (spec.knobs.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        spec.knobs.forEach { k -> Knob(k.label, k.value, k.range, k.onChange, k.whole, k.format ?: { v -> if (k.whole) "${v.toInt()}" else dk.cbavf.studio.ui.fmt(v) }) }
                    }
                }
                if (spec.buttons.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        spec.buttons.forEach { b ->
                            if (b.on == null) OutlinedButton(onClick = b.onClick) { Text(b.label) }
                            else FilterChip(selected = b.on, onClick = b.onClick, label = { Text(b.label) })
                        }
                    }
                }
                if (spec.port != null) {
                    Text("Module presets", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { naming = true }) { Text("+ Save current state…") }
                        presets.forEach { p ->
                            Row(
                                Modifier.border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp)).padding(start = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(p.name, Modifier.clickable { spec.port.restore(p.json) }.padding(vertical = 6.dp), style = MaterialTheme.typography.labelLarge)
                                Text(" ✕ ", Modifier.clickable { onDeletePreset(p) }.padding(6.dp), color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                    Text("Tap a preset to recall the module's whole state (Undo in the module brings back what was there).", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        }
    }
    if (naming) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { naming = false },
            title = { Text("Save ${spec.title} as a preset") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Name") }) },
            confirmButton = { TextButton(onClick = { onSavePreset(name.trim()); naming = false }, enabled = name.isNotBlank()) { Text("Save") } },
            dismissButton = { TextButton(onClick = { naming = false }) { Text("Cancel") } },
        )
    }
}

/**
 * Rack presets, above all the cables: yours (saved with the project; ✕ deletes) and the built-in ones (always there). Tapping one
 * replaces every cable in the rack, as one undo step. The preset the rack matches is ticked.
 */
@Composable
private fun RackPresetBar(
    router: Router,
    saved: List<RackPreset>,
    onSave: (String) -> Unit,
    onDelete: (RackPreset) -> Unit,
    onLoad: (RackPreset) -> Unit,
) {
    var naming by remember { mutableStateOf(false) }
    var asking by remember { mutableStateOf<RackPreset?>(null) }
    // Loading drops the cables in the rack now, so it asks first when there are any.
    fun pick(p: RackPreset) {
        if (router.doc.cables.isEmpty()) onLoad(p) else asking = p
    }
    Surface(tonalElevation = 2.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Rack presets", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                TextButton(onClick = { naming = true }) { Text("+ Save cables…") }
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(saved) { p ->
                    Row(
                        Modifier.border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp)).padding(start = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text((if (RackPresets.matches(p, router.doc)) "✓ " else "") + p.name, Modifier.clickable { pick(p) }.padding(vertical = 6.dp), style = MaterialTheme.typography.labelLarge)
                        Text(" ✕ ", Modifier.clickable { onDelete(p) }.padding(6.dp), color = MaterialTheme.colorScheme.error)
                    }
                }
                items(RackPresets.BUILT_IN) { p ->
                    FilterChip(selected = RackPresets.matches(p, router.doc), onClick = { pick(p) }, label = { Text(p.name) })
                }
            }
            Text("Yours first, then the built-in ones. A preset replaces every cable. Undo brings them back.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    asking?.let { p ->
        fun cables(n: Int) = if (n == 1) "1 cable" else "$n cables"
        AlertDialog(
            onDismissRequest = { asking = null },
            title = { Text("Load ${p.name}?") },
            text = { Text("It replaces the ${cables(router.doc.cables.size)} in the rack with ${cables(p.cables.size)}. Undo brings them back.") },
            confirmButton = { TextButton(onClick = { onLoad(p); asking = null }) { Text("Load") } },
            dismissButton = { TextButton(onClick = { asking = null }) { Text("Cancel") } },
        )
    }
    if (naming) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { naming = false },
            title = { Text("Save the cables as a rack preset") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Name") }) },
            confirmButton = { TextButton(onClick = { onSave(name.trim()); naming = false }, enabled = name.isNotBlank()) { Text("Save") } },
            dismissButton = { TextButton(onClick = { naming = false }) { Text("Cancel") } },
        )
    }
}

/** A jack with its socket ring in its type's colour, the cable plugs that leave it, and its label. */
@Composable
private fun Socket(j: JackDef, plugged: Boolean) {
    Box(Modifier.size(30.dp).background(Color(LocalCanvasColors.current.hole), CircleShape).border(3.dp, Color(j.type.argb).copy(alpha = if (plugged) 1f else 0.5f), CircleShape), contentAlignment = Alignment.Center) {
        if (plugged) Box(Modifier.size(12.dp).background(Color(j.type.argb), CircleShape))
    }
}

/** An output: where its cables go; tap to add or remove targets. */
@Composable
private fun OutputJack(router: Router, j: JackDef) {
    var open by remember { mutableStateOf(false) }
    val targets = router.doc.targetsOf(j.id)
    Box {
        Row(Modifier.fillMaxWidth().clickable { open = true }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Socket(j, targets.isNotEmpty())
            Column(Modifier.weight(1f)) {
                Text("${j.label} (${j.type.label})", color = Color(LocalCanvasColors.current.silk), style = MaterialTheme.typography.labelLarge)
                Text(if (targets.isEmpty()) "not patched · tap to patch" else "⟶ " + targets.joinToString { jackLabel(it) }, color = Color(j.type.argb), style = MaterialTheme.typography.labelSmall)
            }
        }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            Rack.modules.filter { it.id != Rack.moduleOf(j.id) }.forEach { mm ->
                mm.inputs.filter { it.type.accepts(j.type) }.forEach { inp ->
                    val on = router.doc.sourceOf(inp.id) == j.id
                    val fits = on || router.doc.connect(j.id, inp.id) != router.doc
                    DropdownMenuItem(
                        text = { Text((if (on) "✓ " else "   ") + jackLabel(inp.id) + if (!fits) "  (would loop)" else "") },
                        enabled = fits,
                        onClick = { if (on) router.disconnect(inp.id) else router.connect(j.id, inp.id) },
                    )
                }
            }
        }
    }
}

/** An input: what feeds it; tap to choose. */
@Composable
private fun InputJack(router: Router, j: JackDef) {
    val src = router.doc.sourceOf(j.id)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Socket(j, src != null)
        Column(Modifier.weight(1f)) { SourcePicker(router, j) }
    }
}
