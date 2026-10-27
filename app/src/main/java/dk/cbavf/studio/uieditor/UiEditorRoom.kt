package dk.cbavf.studio.uieditor

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import dk.cbavf.studio.routing.ModuleBackSpec
import dk.cbavf.studio.ui.Undoable
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * State of the UI Editor: the layout (undoable), the view, and what is selected or being cabled. The layout holds a front for
 * each orientation ([UiOrientation]); [layout] is the one for the way the phone is held now.
 */
class UiEditorState {
    val edit = Undoable(UiLayout())

    /** True while the window is wider than it is tall: the editor shows and edits the landscape front. */
    var landscape by mutableStateOf(false)

    /** The front for the current orientation, with the shared cables. */
    val layout: UiLayout get() = UiOrientation.view(edit.doc, landscape)

    /** An edit to the current orientation's front (or to the cables, which both share). */
    fun change(key: Any? = null, f: (UiLayout) -> UiLayout) = edit.edit(key) { UiOrientation.edit(it, landscape, f) }

    var flipped by mutableStateOf(false)
    var zoom by mutableFloatStateOf(0.55f)
    var panX by mutableFloatStateOf(12f)
    var panY by mutableFloatStateOf(12f)
    var selected by mutableStateOf<String?>(null)
    var viewW by mutableFloatStateOf(0f)
    var viewH by mutableFloatStateOf(0f)

    /** The cable being dragged: from this port to the finger at [dragPoint] (screen pixels). */
    var cableFrom by mutableStateOf<String?>(null)
    var dragPoint by mutableStateOf(Offset.Zero)

    /** Brings the layout up to date with the controls the modules offer now (not an undo step). */
    fun refresh(modules: List<ModuleSeed>) {
        val merged = UiOrientation.merge(edit.doc, modules)
        if (merged != edit.doc) edit.replace(merged)
    }

    /** Zooms and pans so the whole canvas is in view. */
    fun fit() {
        val l = layout
        val w = UiModel.worldWidth(l)
        val h = max(l.panels.maxOfOrNull { it.y + it.h } ?: 0f, l.controls.maxOfOrNull { it.y + it.h } ?: 0f)
        if (w <= 0f || h <= 0f || viewW <= 0f || viewH <= 0f) return
        val z = min((viewW - 24f) / w, (viewH - 24f) / h).coerceIn(0.1f, 3f)
        zoom = z
        panX = (viewW - w * z) / 2
        panY = 12f
    }
}

/** The modules' controls as the editor's seeds, with button labels that change ("Sort: Name ▸") reduced to their stable name. */
fun seedsOf(specs: List<ModuleBackSpec>): List<ModuleSeed> = specs.map { m ->
    ModuleSeed(
        m.roomId, m.title,
        m.knobs.map { ControlSeed(baseLabel(it.label), ControlKind.Slider) } +
            m.buttons.map { ControlSeed(baseLabel(it.label), if (it.on != null) ControlKind.Toggle else ControlKind.Button) },
    )
}

private fun baseLabel(label: String): String = label.substringBefore(':').removeSuffix("▸").trim().let { if (it == "Connected") "Connect" else it }

private sealed interface Hit {
    class Control(val id: String, val onHandle: Boolean) : Hit
    class Panel(val id: String) : Hit
}

private val RedZone = Color(0xFFD32F2F)
private val BlueZone = Color(0xFF1976D2)

/**
 * The whole UI on one big canvas. The front is the layout editor: drag a control or a module's header to
 * move it, drag a control's corner to resize it. The back is the same canvas turned over, so every control
 * sits where its mirror image is, as a jack: drag from any jack to any other to patch a cable.
 */
@Composable
fun UiEditorRoom(specs: List<ModuleBackSpec>, state: UiEditorState, extraModules: List<ModuleSeed> = emptyList()) {
    val seeds = seedsOf(specs) + extraModules
    LaunchedEffect(seeds.flatMap { m -> m.controls.map { m.id + "." + it.label } }) { state.refresh(seeds) }
    // Each orientation has its own front; the cables are the same in both.
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val landscape = configuration.screenWidthDp > configuration.screenHeightDp
    androidx.compose.runtime.SideEffect { state.landscape = landscape }
    val layout = UiOrientation.view(state.edit.doc, landscape)
    val flip by animateFloatAsState(if (state.flipped) 1f else 0f, tween(550), label = "flip")
    val showBack = flip > 0.5f
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val onSurface = MaterialTheme.colorScheme.onSurface
    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant
    val primary = MaterialTheme.colorScheme.primary
    val outline = MaterialTheme.colorScheme.outline
    val bg = MaterialTheme.colorScheme.surface

    Column(Modifier.fillMaxSize()) {
        Surface(tonalElevation = 2.dp) {
            androidx.compose.foundation.layout.Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                FilledTonalButton(onClick = { state.flipped = !state.flipped; state.cableFrom = null }) { Text(if (state.flipped) "⟲ Front" else "⟲ Back") }
                TextButton(onClick = state.edit::undo, enabled = state.edit.canUndo) { Text("Undo") }
                TextButton(onClick = state.edit::redo, enabled = state.edit.canRedo) { Text("Redo") }
                TextButton(onClick = state::fit) { Text("Fit") }
                if (showBack) {
                    val sel = state.selected
                    val n = sel?.let { UiModel.cablesOf(layout, it).size } ?: 0
                    TextButton(onClick = { sel?.let { id -> state.change { UiModel.disconnectPort(it, id) } } }, enabled = n > 0) { Text("Unplug ($n)") }
                }
                // The landscape front starts as the portrait one in wider rows; once moved, it can go back to that.
                if (!showBack && landscape && state.edit.doc.landscape != null) {
                    TextButton(onClick = { state.edit.edit { UiOrientation.resetLandscape(it) } }) { Text("Like portrait") }
                }
                Text(
                    if (showBack) "Back · ${layout.cables.size} cables"
                    else (if (landscape) "Landscape" else "Portrait") + " front · ${layout.controls.size} controls",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
        val up = rememberUpdatedState(layout)
        val back = rememberUpdatedState(showBack)
        Canvas(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .clipToBounds()
                .background(if (showBack) Color(0xFF101114) else bg)
                // The turn: the canvas narrows to a line at the half and widens again on the other face.
                .graphicsLayer { scaleX = max(0.02f, abs(cos(PI * flip).toFloat())) }
                .onSizeChanged { state.viewW = it.width.toFloat(); state.viewH = it.height.toFloat() }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val l = up.value
                        val worldW = UiModel.worldWidth(l)
                        // A point on screen as a point on the front, whichever face is up.
                        fun toFront(p: Offset): Offset {
                            val wx = (p.x - state.panX) / state.zoom
                            val wy = (p.y - state.panY) / state.zoom
                            return Offset(if (back.value) worldW - wx else wx, wy)
                        }
                        fun hit(p: Offset): Hit? {
                            val f = toFront(p)
                            val c = l.controls.lastOrNull { f.x in it.x..(it.x + it.w) && f.y in it.y..(it.y + it.h) }
                            if (c != null) {
                                val handle = !back.value && f.x > c.x + c.w - 16f && f.y > c.y + c.h - 16f
                                return Hit.Control(c.id, handle)
                            }
                            if (back.value) return null
                            return l.panels.lastOrNull { f.x in it.x..(it.x + it.w) && f.y in it.y..(it.y + PANEL_HEADER) }?.let { Hit.Panel(it.id) }
                        }
                        val h = hit(down.position)
                        if (h != null && back.value) {
                            // Back: pull a cable from this jack to any other.
                            if (h is Hit.Control) {
                                state.selected = h.id
                                state.cableFrom = h.id
                                state.dragPoint = down.position
                                down.consume()
                                do {
                                    val ev = awaitPointerEvent()
                                    val ch = ev.changes.first()
                                    state.dragPoint = ch.position
                                    ch.consume()
                                } while (ev.changes.any { it.pressed })
                                val drop = (hit(state.dragPoint) as? Hit.Control)?.id
                                if (drop != null && drop != h.id) {
                                    UiModel.connect(state.layout, h.id, drop)?.let { next -> state.change { next } }
                                    state.selected = drop
                                }
                                state.cableFrom = null
                            }
                        } else if (h != null) {
                            // Front: move or resize, from the layout as it was when the finger went down.
                            val start = l
                            var last = down.position
                            var ax = 0f
                            var ay = 0f
                            val key = "drag-${down.uptimeMillis}"
                            if (h is Hit.Control) state.selected = h.id
                            down.consume()
                            do {
                                val ev = awaitPointerEvent()
                                if (ev.changes.count { it.pressed } > 1) break
                                val ch = ev.changes.first()
                                ax += (ch.position.x - last.x) / state.zoom
                                ay += (ch.position.y - last.y) / state.zoom
                                last = ch.position
                                val next = when (h) {
                                    is Hit.Control -> if (h.onHandle) UiModel.resizeControl(start, h.id, ax, ay) else UiModel.moveControl(start, h.id, ax, ay)
                                    is Hit.Panel -> UiModel.movePanel(start, h.id, ax, ay)
                                }
                                state.change(key) { next }
                                ch.consume()
                            } while (ev.changes.any { it.pressed })
                        } else {
                            state.selected = null
                            // Empty canvas: pan, and pinch to zoom around the fingers.
                            do {
                                val ev = awaitPointerEvent()
                                val z = ev.calculateZoom()
                                val pan = ev.calculatePan()
                                val c = ev.calculateCentroid(useCurrent = true)
                                if (z != 1f || pan != Offset.Zero) {
                                    val nz = (state.zoom * z).coerceIn(0.1f, 3f)
                                    val k = nz / state.zoom
                                    state.panX = c.x - (c.x - state.panX) * k + pan.x
                                    state.panY = c.y - (c.y - state.panY) * k + pan.y
                                    state.zoom = nz
                                }
                                ev.changes.forEach { it.consume() }
                            } while (ev.changes.any { it.pressed })
                        }
                    }
                }
        ) {
            val worldW = UiModel.worldWidth(layout)
            val ink = if (showBack) Color(0xFFD9D6CC) else onSurface
            val textSize = with(density) { 11f.toSp() }
            val small = TextStyle(fontSize = textSize, color = ink)
            val title = TextStyle(fontSize = with(density) { 14f.toSp() }, color = ink)
            fun bx(x: Float, w: Float) = if (showBack) UiModel.mirrorX(x, w, worldW) else x
            withTransform({ translate(state.panX, state.panY); scale(state.zoom, state.zoom, Offset.Zero) }) {
                // Module panels.
                for (p in layout.panels) {
                    val x = bx(p.x, p.w)
                    drawRoundRect(if (showBack) Color(0xFF1B1D22) else surfaceVariant, Offset(x, p.y), Size(p.w, p.h), CornerRadius(12f))
                    drawRoundRect(outline.copy(alpha = 0.5f), Offset(x, p.y), Size(p.w, p.h), CornerRadius(12f), style = Stroke(1.5f))
                    label(measurer, p.title, title, Offset(x + 12f, p.y + 8f))
                }
                // Cables, under the jacks.
                if (showBack) {
                    layout.cables.forEachIndexed { i, c ->
                        val a = layout.controls.firstOrNull { it.id == c.from }
                        val b = layout.controls.firstOrNull { it.id == c.to }
                        if (a != null && b != null) cable(centerOf(a, worldW, true), centerOf(b, worldW, true), cableColor(i), highlight = state.selected == a.id || state.selected == b.id)
                    }
                    state.cableFrom?.let { id ->
                        layout.controls.firstOrNull { it.id == id }?.let { a ->
                            val end = Offset((state.dragPoint.x - state.panX) / state.zoom, (state.dragPoint.y - state.panY) / state.zoom)
                            cable(centerOf(a, worldW, true), end, primary, highlight = true)
                        }
                    }
                }
                // Controls: widgets on the front, jacks on the back.
                for (c in layout.controls) {
                    val x = bx(c.x, c.w)
                    val picked = state.selected == c.id
                    if (showBack) jack(measurer, small, c, Offset(x, c.y), picked, UiModel.cablesOf(layout, c.id).isNotEmpty(), primary)
                    else widget(measurer, small, c, Offset(x, c.y), picked, primary, onSurface, outline)
                }
            }
        }
    }
}

private fun centerOf(c: ControlDesc, worldW: Float, back: Boolean): Offset {
    val x = if (back) UiModel.mirrorX(c.x, c.w, worldW) else c.x
    return Offset(x + c.w / 2, c.y + c.h / 2)
}

private fun cableColor(i: Int): Color = Color.hsv((i * 47f) % 360f, 0.65f, 0.95f)

private fun DrawScope.label(m: TextMeasurer, text: String, style: TextStyle, at: Offset, maxW: Float = 180f) {
    val r = m.measure(text, style, maxLines = 1, softWrap = false, constraints = androidx.compose.ui.unit.Constraints(maxWidth = maxW.toInt().coerceAtLeast(1)))
    drawText(r, topLeft = at)
}

/** A control as a small copy of itself: slider with the two-zone thumb, toggle, or button. */
private fun DrawScope.widget(m: TextMeasurer, style: TextStyle, c: ControlDesc, at: Offset, picked: Boolean, primary: Color, ink: Color, outline: Color) {
    val size = Size(c.w, c.h)
    drawRoundRect(if (picked) primary.copy(alpha = 0.18f) else Color.Transparent, at, size, CornerRadius(8f))
    drawRoundRect(if (picked) primary else outline.copy(alpha = 0.6f), at, size, CornerRadius(8f), style = Stroke(if (picked) 2.5f else 1f))
    label(m, c.label, style, Offset(at.x + 8f, at.y + 3f), c.w - 16f)
    when (c.kind) {
        ControlKind.Slider -> {
            val y = at.y + c.h - 14f
            drawLine(outline, Offset(at.x + 14f, y), Offset(at.x + c.w - 14f, y), 3f, StrokeCap.Round)
            val cx = at.x + 14f + (c.w - 28f) * 0.4f
            drawRoundRect(BlueZone, Offset(cx - 7f, y - 14f), Size(14f, 28f), CornerRadius(4f))
            drawRoundRect(RedZone, Offset(cx - 7f, y - 14f), Size(14f, 14f), CornerRadius(4f))
        }
        ControlKind.Toggle -> {
            val w = 34f
            val x = at.x + c.w - w - 10f
            val y = at.y + c.h - 18f
            drawRoundRect(outline, Offset(x, y), Size(w, 14f), CornerRadius(7f))
            drawCircle(ink, 7f, Offset(x + 8f, y + 7f))
        }
        ControlKind.Button -> drawRoundRect(primary.copy(alpha = 0.35f), Offset(at.x + 8f, at.y + c.h - 17f), Size(c.w - 16f, 11f), CornerRadius(5f))
        ControlKind.Envelope -> {
            // A small attack, decay, sustain, release outline with its four handles.
            val left = at.x + 12f
            val width = c.w - 24f
            val top = at.y + 22f
            val bottom = at.y + c.h - 10f
            val pts = listOf(0f to 1f, 0.12f to 0f, 0.4f to 0.45f, 0.7f to 0.45f, 1f to 1f)
            val xy = pts.map { (px, py) -> Offset(left + width * px, top + (bottom - top) * py) }
            for (i in 0 until xy.lastIndex) drawLine(primary, xy[i], xy[i + 1], 3f, StrokeCap.Round)
            for (i in 1 until xy.size - 1) drawCircle(ink, 4f, xy[i])
        }
    }
    if (picked) drawRect(primary, Offset(at.x + c.w - 10f, at.y + c.h - 10f), Size(9f, 9f))
}

/** A control's port on the back: a jack in the middle of where the control is, with its name. */
private fun DrawScope.jack(m: TextMeasurer, style: TextStyle, c: ControlDesc, at: Offset, picked: Boolean, plugged: Boolean, primary: Color) {
    val size = Size(c.w, c.h)
    drawRoundRect(if (picked) primary.copy(alpha = 0.15f) else Color(0xFF16181C), at, size, CornerRadius(8f))
    drawRoundRect(if (picked) primary else Color(0xFF3A3D45), at, size, CornerRadius(8f), style = Stroke(if (picked) 2.5f else 1f))
    label(m, c.label, style, Offset(at.x + 8f, at.y + 3f), c.w - 16f)
    val centre = Offset(at.x + c.w / 2, at.y + c.h / 2 + 6f)
    drawCircle(Color(0xFF0A0B0D), 11f, centre)
    // The ring takes the colour of what the port carries: number grey, trigger white, gate cyan.
    drawCircle(Color(c.kind.portType().argb), 11f, centre, style = Stroke(2.5f))
    drawCircle(if (plugged) primary else Color(0xFF2A2C32), 5f, centre)
    // The kind of control it was on the front, so the back can be read without turning over.
    val tag = when (c.kind) { ControlKind.Slider -> "slider"; ControlKind.Toggle -> "toggle"; ControlKind.Button -> "button"; ControlKind.Envelope -> "envelope" }
    label(m, tag, style.copy(color = Color(0xFF8B8E98)), Offset(at.x + c.w - 52f, at.y + c.h - 16f), 48f)
}

/** A sagging cable between two jack centres: shadow, colour, highlight. */
private fun DrawScope.cable(a: Offset, b: Offset, color: Color, highlight: Boolean) {
    val sag = 18f + hypot(b.x - a.x, b.y - a.y) * 0.18f
    val path = Path().apply {
        moveTo(a.x, a.y)
        cubicTo(a.x, a.y + sag, b.x, b.y + sag, b.x, b.y)
    }
    drawPath(path, Color.Black.copy(alpha = 0.5f), style = Stroke(if (highlight) 7f else 5f, cap = StrokeCap.Round))
    drawPath(path, color, style = Stroke(if (highlight) 5f else 3.5f, cap = StrokeCap.Round))
    drawPath(path, Color.White.copy(alpha = 0.35f), style = Stroke(1.2f, cap = StrokeCap.Round))
}
