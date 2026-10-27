package dk.cbavf.studio.patch

import dk.cbavf.studio.ui.layout.panelScroll
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dk.cbavf.studio.draw.DrawState
import dk.cbavf.studio.draw.drawLayers
import dk.cbavf.studio.draw.model.Content
import dk.cbavf.studio.draw.model.label
import dk.cbavf.studio.draw.model.Grating
import dk.cbavf.studio.draw.model.RadialBurst
import dk.cbavf.studio.draw.model.Stroke
import dk.cbavf.studio.patch.model.Curve
import dk.cbavf.studio.patch.model.Lfo
import dk.cbavf.studio.patch.model.LfoShape
import dk.cbavf.studio.patch.model.Param
import dk.cbavf.studio.patch.model.Route
import dk.cbavf.studio.patch.model.SourceKind
import dk.cbavf.studio.ui.LabeledSlider
import dk.cbavf.studio.ui.ScrollRow
import dk.cbavf.studio.ui.fmt

@Composable
fun PatchRoom(engine: PatchEngine, draw: DrawState, sensors: SensorHub, linked: Boolean, onOpenAsGraph: () -> Unit = {}) {
    val context = LocalContext.current
    var selected by remember { mutableStateOf<Int?>(engine.doc.routes.firstOrNull()?.id) }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        engine.micAllowed = it
        engine.refreshHardware()
    }
    LaunchedEffect(Unit) {
        engine.micAllowed = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }

    dk.cbavf.studio.ui.layout.RoomSplit(canvas = {
        // The live canvas doubles as the touch pad: finger position = Touch X / Y.
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(engine) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        fun set(p: Offset) {
                            engine.touchX = (p.x / size.width).coerceIn(0f, 1f)
                            engine.touchY = (p.y / size.height).coerceIn(0f, 1f)
                        }
                        set(down.position)
                        do {
                            val e = awaitPointerEvent()
                            e.changes.firstOrNull()?.let { set(it.position); it.consume() }
                        } while (e.changes.any { it.pressed })
                    }
                }
        ) {
            Canvas(Modifier.fillMaxSize().graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
                drawLayers(draw.visibleLayers())
            }
            Surface(
                Modifier.align(Alignment.TopStart).padding(8.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                shape = MaterialTheme.shapes.small,
            ) {
                Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                    val style = MaterialTheme.typography.labelMedium
                    Text(
                        when {
                            engine.recording -> "● Recording · ${engine.recorded} frames to Animate"
                            engine.live -> "Live · drag here for Touch X/Y"
                            else -> "Stopped · ${engine.doc.routes.size} routes"
                        },
                        style = style,
                    )
                    if (engine.live) Text("touch ${fmt(engine.touchX)}, ${fmt(engine.touchY)}", style = style)
                }
            }
        }
    }, controls = {
        Surface(tonalElevation = 2.dp) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                ScrollRow {
                    Button(onClick = { if (engine.live) engine.stop() else engine.start() }) { Text(if (engine.live) "Stop" else "Go live") }
                    FilterChip(selected = engine.recording, onClick = engine::toggleRecording, label = { Text("Record to Animate") })
                    TextButton(onClick = {
                        val layer = draw.primary ?: draw.layers.lastOrNull() ?: return@TextButton
                        selected = engine.addRoute(SourceKind.Lfo1, layer.id, Param.forContent(layer.content).first()).id
                    }, enabled = draw.layers.isNotEmpty()) { Text("+ Route") }
                    TextButton(onClick = draw::undo, enabled = draw.canUndo && !engine.live) { Text("Undo in Draw") }
                    TextButton(onClick = draw::redo, enabled = draw.canRedo && !engine.live) { Text("Redo") }
                    // The same routes as Node Lab: Sensor/LFO → Drive Layer nodes.
                    TextButton(onClick = onOpenAsGraph, enabled = engine.doc.routes.isNotEmpty()) { Text("Open as graph") }
                }
                RouteList(engine, draw, selected) { selected = it }
                HorizontalDivider()
                Column(Modifier.panelScroll(300.dp)) {
                    engine.doc.routes.firstOrNull { it.id == selected }?.let { r ->
                        RouteEditor(engine, draw, sensors, r, linked, onNeedMic = { micPermission.launch(Manifest.permission.RECORD_AUDIO) })
                    }
                    LfoEditor("LFO 1", engine.doc.lfo1) { engine.doc = engine.doc.copy(lfo1 = it) }
                    LfoEditor("LFO 2", engine.doc.lfo2) { engine.doc = engine.doc.copy(lfo2 = it) }
                }
            }
        }
    })
}

@Composable
private fun RouteList(engine: PatchEngine, draw: DrawState, selected: Int?, onSelect: (Int) -> Unit) {
    engine.doc.routes.forEach { r ->
        val layer = draw.layers.firstOrNull { it.id == r.layerId }
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { onSelect(r.id) }
                .background(if (r.id == selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                (if (r.remote) "other phone · " else "") + "${r.source.label} → ${layer?.let { "${it.id} ${it.content.label}" } ?: "missing layer"} · ${r.param.label}" +
                    if (!r.enabled) " (off)" else "",
                Modifier.weight(1f),
                style = MaterialTheme.typography.labelMedium,
            )
            val (n, v) = engine.meters[r.id] ?: (0f to Float.NaN)
            LinearProgressIndicator(progress = { n }, modifier = Modifier.width(56.dp).height(4.dp))
            Text(if (v.isNaN()) "" else " ${fmt(v)}", Modifier.width(56.dp), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun RouteEditor(engine: PatchEngine, draw: DrawState, sensors: SensorHub, r: Route, linked: Boolean, onNeedMic: () -> Unit) {
    val style = MaterialTheme.typography.labelMedium
    ScrollRow {
        Text("From", Modifier.width(56.dp), style = style)
        SourceKind.entries.forEach { s ->
            val here = r.remote || sensors.available(s)
            FilterChip(
                selected = r.source == s,
                onClick = {
                    engine.update(r.copy(source = s))
                    if (s == SourceKind.Mic && !engine.micAllowed) onNeedMic()
                },
                enabled = here,
                label = { Text(s.label) },
            )
        }
    }
    if (!r.source.isLfo && !r.source.isTouch) {
        ScrollRow {
            FilterChip(selected = !r.remote, onClick = { engine.update(r.copy(remote = false)) }, label = { Text("This phone") })
            FilterChip(
                selected = r.remote,
                onClick = { engine.update(r.copy(remote = true)) },
                enabled = linked,
                label = { Text(if (linked) "Other phone (Patch live there too)" else "Other phone (connect in Loop)") },
            )
        }
    }
    ScrollRow {
        Text("To", Modifier.width(56.dp), style = style)
        draw.layers.forEach { layer ->
            FilterChip(
                selected = layer.id == r.layerId,
                onClick = {
                    val params = Param.forContent(layer.content)
                    val p = if (r.param in params) r.param else params.first()
                    engine.update(r.copy(layerId = layer.id, param = p, min = p.defaultRange.start, max = p.defaultRange.endInclusive))
                },
                label = { Text("${layer.id} ${layer.content.label}") },
            )
        }
    }
    val layer = draw.layers.firstOrNull { it.id == r.layerId }
    if (layer != null) {
        ScrollRow {
            Text("", Modifier.width(56.dp))
            Param.forContent(layer.content).forEach { p ->
                FilterChip(
                    selected = p == r.param,
                    onClick = { engine.update(r.copy(param = p, min = p.defaultRange.start, max = p.defaultRange.endInclusive)) },
                    label = { Text(p.label) },
                )
            }
        }
    }
    // Ranges span twice the default range either way, so a route can also run backwards.
    val d = r.param.defaultRange
    val span = (d.endInclusive - d.start)
    val range = (d.start - span)..(d.endInclusive + span)
    LabeledSlider("Min", r.min, range, " ${r.param.unit}") { engine.update(r.copy(min = it)) }
    LabeledSlider("Max", r.max, range, " ${r.param.unit}") { engine.update(r.copy(max = it)) }
    ScrollRow {
        Text("Curve", Modifier.width(56.dp), style = style)
        Curve.entries.forEach { c -> FilterChip(selected = r.curve == c, onClick = { engine.update(r.copy(curve = c)) }, label = { Text(c.name) }) }
    }
    LabeledSlider("Smoothing", r.smoothing, 0f..1f, "") { engine.update(r.copy(smoothing = it)) }
    ScrollRow {
        FilterChip(selected = r.enabled, onClick = { engine.update(r.copy(enabled = !r.enabled)) }, label = { Text("On") })
        TextButton(onClick = { engine.remove(r.id) }) { Text("Delete route") }
    }
}

@Composable
private fun LfoEditor(name: String, lfo: Lfo, onChange: (Lfo) -> Unit) {
    ScrollRow {
        Text(name, Modifier.width(56.dp), style = MaterialTheme.typography.labelMedium)
        LfoShape.entries.forEach { s -> FilterChip(selected = lfo.shape == s, onClick = { onChange(lfo.copy(shape = s)) }, label = { Text(s.name) }) }
    }
    LabeledSlider("Rate", lfo.rateHz, 0.01f..5f, " Hz") { onChange(lfo.copy(rateHz = it)) }
}

