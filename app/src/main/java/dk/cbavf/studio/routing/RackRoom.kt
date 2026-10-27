package dk.cbavf.studio.routing

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dk.cbavf.studio.routing.model.JackDef
import dk.cbavf.studio.routing.model.ModuleDef
import dk.cbavf.studio.routing.model.Rack
import dk.cbavf.studio.ui.LocalCanvasColors
import dk.cbavf.studio.ui.ScrollRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** "Module · Jack" for a jack id. */
fun jackLabel(jack: String): String {
    val m = Rack.modules.firstOrNull { it.id == Rack.moduleOf(jack) }
    return "${m?.title ?: jack} · ${Rack.jack(jack)?.label ?: jack}"
}

/** The quick way to patch an input without turning the rack: pick its source from a list. */
@Composable
fun SourcePicker(router: Router, inp: JackDef) {
    var open by remember { mutableStateOf(false) }
    val src = router.doc.sourceOf(inp.id)
    Box {
        TextButton(onClick = { open = true }) {
            Text("${inp.label} ⟵ " + (src?.let { jackLabel(it) } ?: "nothing") + " ▾", style = MaterialTheme.typography.labelMedium)
        }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Nothing (unplug)") }, onClick = { router.disconnect(inp.id); open = false })
            Rack.modules.filter { it.id != Rack.moduleOf(inp.id) }.forEach { m ->
                m.outputs.filter { inp.type.accepts(it.type) }.forEach { out ->
                    val fits = router.doc.connect(out.id, inp.id) != router.doc || src == out.id
                    DropdownMenuItem(
                        text = { Text(jackLabel(out.id) + if (!fits) "  (would loop)" else "") },
                        enabled = fits,
                        onClick = { router.connect(out.id, inp.id); open = false },
                    )
                }
            }
        }
    }
}

// --- Back -----------------------------------------------------------------------------------------

/** A cable being dragged out of a jack, in the back panel's coordinates. */
private data class Dragging(val from: String, val to: Offset)

/** Every module's back with every cable: the whole studio routing at a glance. */
@Composable
fun RackOverview(router: Router) {
    val jacks = remember { mutableStateMapOf<String, Offset>() }
    var root by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var dragging by remember { mutableStateOf<Dragging?>(null) }
    val density = LocalDensity.current.density
    val hole = Color(LocalCanvasColors.current.hole)
    val hit = 26f * density
    Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Box(
            Modifier
                .fillMaxWidth()
                .onGloballyPositioned { root = it }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val near = jacks.minByOrNull { (it.value - down.position).getDistance() }?.takeIf { (it.value - down.position).getDistance() < hit } ?: return@awaitEachGesture
                        // From an output: a new cable. From a plugged input: pick its cable up.
                        val from = when {
                            Rack.output(near.key) != null -> near.key
                            else -> router.doc.sourceOf(near.key)?.also { router.disconnect(near.key) } ?: return@awaitEachGesture
                        }
                        down.consume()
                        dragging = Dragging(from, down.position)
                        do {
                            val e = awaitPointerEvent()
                            e.changes.firstOrNull()?.let { dragging = Dragging(from, it.position); it.consume() }
                        } while (e.changes.any { it.pressed })
                        val end = dragging?.to
                        dragging = null
                        if (end != null) {
                            jacks.filterKeys { Rack.input(it) != null }.minByOrNull { (it.value - end).getDistance() }
                                ?.takeIf { (it.value - end).getDistance() < hit * 1.4f }
                                ?.let { router.connect(from, it.key) }
                        }
                    }
                }
        ) {
            Column(Modifier.fillMaxWidth().padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Rack.modules.forEach { m -> BackUnit(m, router) { id, coords -> root?.let { r -> jacks[id] = r.localPositionOf(coords, Offset.Zero) + Offset(coords.size.width / 2f, coords.size.height / 2f) } } }
                Spacer(Modifier.height(40.dp))
            }
            // Cables hang in front of the units, sagging with their length.
            Canvas(Modifier.matchParentSize()) {
                fun cable(a: Offset, b: Offset, color: Color) {
                    val sag = 30f * density + (b - a).getDistance() * 0.25f
                    val path = Path().apply {
                        moveTo(a.x, a.y)
                        cubicTo(a.x, a.y + sag, b.x, b.y + sag, b.x, b.y)
                    }
                    drawPath(path, Color(0x99000000), style = Stroke(width = 9f * density, cap = StrokeCap.Round))
                    drawPath(path, color, style = Stroke(width = 6f * density, cap = StrokeCap.Round))
                    drawPath(path, Color.White.copy(alpha = 0.25f), style = Stroke(width = 1.5f * density, cap = StrokeCap.Round))
                    listOf(a, b).forEach { drawCircle(color, 7f * density, it); drawCircle(hole, 3f * density, it) }
                }
                router.doc.cables.forEach { c ->
                    val a = jacks[c.from] ?: return@forEach
                    val b = jacks[c.to] ?: return@forEach
                    cable(a, b, Color(Rack.output(c.from)?.type?.argb ?: 0xFFAAAAAA))
                }
                dragging?.let { d -> jacks[d.from]?.let { a -> cable(a, d.to, Color(Rack.output(d.from)?.type?.argb ?: 0xFFAAAAAA).copy(alpha = 0.8f)) } }
            }
        }
    }
}

@Composable
private fun BackUnit(m: ModuleDef, router: Router, onJack: (String, LayoutCoordinates) -> Unit) {
    val cc = LocalCanvasColors.current
    val silk = Color(cc.silk)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 76.dp).background(Color(cc.panel), RoundedCornerShape(4.dp)).border(1.dp, Color(cc.unitEdge), RoundedCornerShape(4.dp)).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Inputs on the left, outputs on the right, as on a rack's back panel.
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            m.inputs.forEach { j -> Jack(j, plugged = router.doc.sourceOf(j.id) != null, output = false, onJack) }
            if (m.inputs.isEmpty()) Text("no inputs", color = silk.copy(alpha = 0.3f), style = MaterialTheme.typography.labelSmall)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 8.dp)) {
            Text(m.title.uppercase(), color = silk, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            Text("IN ◂   ▸ OUT", color = silk.copy(alpha = 0.4f), fontSize = 9.sp)
        }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            m.outputs.forEach { j -> Jack(j, plugged = router.doc.targetsOf(j.id).isNotEmpty(), output = true, onJack) }
        }
    }
}

@Composable
private fun Jack(j: JackDef, plugged: Boolean, output: Boolean, onJack: (String, LayoutCoordinates) -> Unit) {
    val cc = LocalCanvasColors.current
    val silk = Color(cc.silk)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (output) Text("${j.label} (${j.type.label})", color = silk, style = MaterialTheme.typography.labelSmall)
        Box(
            Modifier
                .size(28.dp)
                .background(Color(cc.hole), CircleShape)
                .border(3.dp, Color(j.type.argb).copy(alpha = if (plugged) 1f else 0.55f), CircleShape)
                .onGloballyPositioned { onJack(j.id, it) },
        )
        if (!output) Text("${j.label} (${j.type.label})", color = silk, style = MaterialTheme.typography.labelSmall)
    }
}
