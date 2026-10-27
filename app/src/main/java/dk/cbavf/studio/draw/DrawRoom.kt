package dk.cbavf.studio.draw

import dk.cbavf.studio.ui.layout.panelScroll
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import dk.cbavf.studio.abcompare.abHere
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.asImageBitmap
import dk.cbavf.studio.library.renderBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import dk.cbavf.studio.blend.isSubtract
import dk.cbavf.studio.draw.model.Blend
import dk.cbavf.studio.draw.model.Cap
import dk.cbavf.studio.draw.model.Content
import dk.cbavf.studio.draw.model.EllipseHatch
import dk.cbavf.studio.draw.model.ImageLayer
import dk.cbavf.studio.library.Lineage
import dk.cbavf.studio.library.ProjectStore
import dk.cbavf.studio.ui.LibraryPicker
import dk.cbavf.studio.draw.model.Profile
import dk.cbavf.studio.draw.model.label
import dk.cbavf.studio.draw.model.Grating
import dk.cbavf.studio.draw.model.Ink
import dk.cbavf.studio.draw.model.Layer
import dk.cbavf.studio.draw.model.MoireMath
import dk.cbavf.studio.draw.model.Pen
import dk.cbavf.studio.draw.model.Pt
import dk.cbavf.studio.draw.model.RadialBurst
import dk.cbavf.studio.draw.model.RenderSize
import dk.cbavf.studio.draw.model.RenderSizes
import dk.cbavf.studio.draw.model.Stroke
import dk.cbavf.studio.ui.LabeledSlider
import dk.cbavf.studio.ui.ScrollRow
import dk.cbavf.studio.ui.fmt
import kotlin.math.roundToInt

@Composable
fun DrawRoom(state: DrawState, store: ProjectStore, lineage: Lineage, onSave: () -> Unit) {
    var picking by remember { mutableStateOf(false) }
    if (picking) {
        // Library images become image layers, fitted to the canvas, in the order picked.
        LibraryPicker(store, lineage) { ids ->
            picking = false
            state.addImages(ids.mapNotNull { id ->
                lineage[id]?.let { ImageLayer.fitted(id, it.widthPx, it.heightPx, state.canvasWidth, state.canvasHeight) }
            })
        }
        return
    }
    // Finished layers as pictures, so drawing a stroke over a busy canvas does not paint it all again.
    val cache = remember(state) { LayerCache() }
    dk.cbavf.studio.ui.layout.RoomSplit(canvas = {
        Box(Modifier.fillMaxSize()) {
            Canvas(
                Modifier
                    .fillMaxSize()
                    .onSizeChanged {
                        state.canvasWidth = it.width
                        state.canvasHeight = it.height
                    }
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .abHere()
                    .pointerInput(state) { handleGestures(state) }
            ) {
                drawLayersCached(state.visibleLayers(), cache)
                when (state.tool) {
                    Tool.Pen -> drawPenDraft(state.draft, state.pen.stack)
                    Tool.Lasso -> drawLassoDraft(state.draft)
                    Tool.Move -> Unit
                }
                drawSelection(state.layers, state.selection)
                if (state.showFringes) drawFringes(state)
            }
            Readouts(state, Modifier.align(Alignment.TopStart).padding(8.dp))
            // A/B: the canvas as it is now against B, only while held.
            val ab = dk.cbavf.studio.abcompare.LocalAb.current
            val live by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, ab?.state?.active != null) {
                value = if (ab?.state?.active != null) withContext(Dispatchers.Default) {
                    renderBitmap(state.toDoc()).asImageBitmap()
                } else null
            }
            dk.cbavf.studio.abcompare.AbLayer(live, input = null, inputNote = "Draw has no input")
        }
    }, controls = {
        Controls(state, onSave, onPickImages = { picking = true })
    })
}

private val Offset.pt get() = Pt(x, y)

private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.handleGestures(state: DrawState) {
    awaitEachGesture {
        val down = awaitFirstDown()
        when (state.tool) {
            Tool.Pen, Tool.Lasso -> {
                state.draftStart(down.position.pt)
                do {
                    val event = awaitPointerEvent()
                    event.changes.firstOrNull { it.id == down.id }?.let {
                        state.draftMove(it.position.pt)
                        it.consume()
                    }
                } while (event.changes.any { it.pressed })
                if (state.tool == Tool.Pen) state.penUp() else state.lassoUp()
            }
            Tool.Move -> {
                // A corner handle of a selected stroke: drag to stretch it unevenly.
                val handle = state.handleAt(down.position.pt)
                if (handle != null) {
                    state.beginStretch(handle, down.position.pt)
                    do {
                        val event = awaitPointerEvent()
                        event.changes.firstOrNull { it.id == down.id }?.let {
                            state.stretchTo(it.position.pt)
                            it.consume()
                        }
                    } while (event.changes.any { it.pressed })
                    state.endStretch()
                    return@awaitEachGesture
                }
                var moved = false
                do {
                    val event = awaitPointerEvent()
                    val zoom = event.calculateZoom()
                    val rotation = event.calculateRotation()
                    val pan = event.calculatePan()
                    if (zoom != 1f || rotation != 0f || pan != Offset.Zero) {
                        // Checkpoint lazily so a plain tap doesn't add an undo step.
                        if (!moved) state.beginTransform()
                        moved = true
                        state.onTransform(pan.pt, zoom, rotation)
                    }
                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                } while (event.changes.any { it.pressed })
                if (!moved) state.tapSelect(down.position.pt)
            }
        }
    }
}

@Composable
private fun Readouts(state: DrawState, modifier: Modifier) {
    val lines = when (val c = state.primary?.content) {
        is Grating -> gratingReadout(state)
        is Stroke -> strokeReadout(c)
        is ImageLayer -> listOf(
            "image · ${c.widthPx}×${c.heightPx} px" + when {
                dk.cbavf.studio.routing.model.RouteLayers.isRoute(c.assetId) -> " · live from the rack"
                LayerImages.isMissing(c.assetId) -> " · can't load"
                else -> ""
            },
            "scale ${fmt(c.transform.sx)} × ${fmt(c.transform.sy)} · rot ${fmt(c.transform.rotationDeg)}°",
        )
        else -> emptyList()
    }
    if (lines.isEmpty()) return
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
        shape = MaterialTheme.shapes.small,
    ) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            lines.forEach { Text(it, style = MaterialTheme.typography.labelMedium) }
        }
    }
}

/** The selected grating and the one it is compared with: the visible grating below it, or the first other one. */
private fun gratingPair(state: DrawState): Pair<Grating, Grating>? {
    val gratings = state.visibleLayers().filter { it.content is Grating }
    val moving = gratings.firstOrNull { it.id == state.primaryId } ?: return null
    val index = gratings.indexOf(moving)
    val other = gratings.getOrNull(index - 1) ?: gratings.firstOrNull { it !== moving } ?: return null
    return (other.content as Grating) to (moving.content as Grating)
}

/**
 * The predicted fringes as thin cyan lines over the canvas: spaced by the beat period and turned to the beat's angle, so you can
 * see where the moiré will fall before it forms (and watch it move as a slider moves).
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawFringes(state: DrawState) {
    val (a, b) = gratingPair(state) ?: return
    val beat = MoireMath.beat(a, b)
    val period = beat.spacingPx.toFloat()
    if (period.isInfinite() || period.isNaN() || period < dk.cbavf.studio.math.MathParams.get("fringe.minPeriodPx").toFloat()) return
    val rad = Math.toRadians(beat.angleDeg.toDouble())
    val dx = Math.cos(rad).toFloat()
    val dy = Math.sin(rad).toFloat()
    // along the lines is (dx, dy); across them (-dy, dx)
    val diag = kotlin.math.hypot(size.width, size.height)
    val count = (diag / period).toInt().coerceAtMost(300)
    val cx = size.width / 2
    val cy = size.height / 2
    for (k in -count / 2..count / 2) {
        val ox = cx - dy * k * period
        val oy = cy + dx * k * period
        drawLine(
            androidx.compose.ui.graphics.Color(0x8800E5FF),
            androidx.compose.ui.geometry.Offset(ox - dx * diag, oy - dy * diag),
            androidx.compose.ui.geometry.Offset(ox + dx * diag, oy + dy * diag),
            strokeWidth = 2f,
        )
    }
}

/** Compares the selected grating with the visible grating below it (or the first other one). */
private fun gratingReadout(state: DrawState): List<String> {
    val (a, b) = gratingPair(state) ?: return emptyList()
    val moving = state.layers.first { it.id == state.primaryId }
    val other = state.layers.first { it.content === a }
    val beat = MoireMath.beat(a, b)
    return buildList {
        add("layer ${moving.id} vs ${other.id}")
        add("Δangle ${fmt(MoireMath.angleBetween(a, b))}°")
        add("spacing ${fmt(a.spacingPx)} / ${fmt(b.spacingPx)} px")
        if (beat.spacingPx.isInfinite()) {
            add("beat: none (identical)")
        } else {
            add("beat ${fmt(beat.spacingPx)} px @ ${fmt(beat.angleDeg)}°")
            add("shift ×${fmt(MoireMath.shiftAmplification(beat, b))}")
        }
    }
}

private fun strokeReadout(s: Stroke): List<String> = buildList {
    val t = s.transform
    add("stroke · ${s.stack.size} lines · ${s.points.size} pts")
    add("rot ${fmt(t.rotationDeg)}° · stretch ${fmt(t.sx)} × ${fmt(t.sy)}")
    // Dash beat between the first two dash trains with different periods.
    val dashed = s.stack.filter { it.dashed }
    val pair = dashed.firstNotNullOfOrNull { a -> dashed.firstOrNull { it.dashPeriod != a.dashPeriod }?.let { a to it } }
    if (pair != null) {
        add("dash beat ${fmt(MoireMath.dashBeat(pair.first.dashPeriod.toDouble(), pair.second.dashPeriod.toDouble()))} px")
    }
}

/**
 * One row of layer tabs, and below it a row for each open group in it (and below those, their open
 * subgroups), so the tabs branch downwards. Tap a layer to pick it, hold to add it to the selection,
 * hold and drag to move it in the stack. Tap a group's tab to fold or open it, hold to pick all of it.
 */
@Composable
private fun LayerTabs(state: DrawState, parent: Int?, depth: Int) {
    val chipW = with(androidx.compose.ui.platform.LocalDensity.current) { 96.dp.toPx() }
    val items = state.tabItems(parent)
    if (items.isEmpty() && depth == 0) return
    ScrollRow {
        if (depth > 0) {
            Text(
                "↳".repeat(depth) + " " + (state.groups.firstOrNull { it.id == parent }?.let { it.name.ifBlank { "Group ${it.id}" } } ?: ""),
                style = MaterialTheme.typography.labelMedium,
            )
        }
        items.forEach { item ->
            val layer = item.layer
            val group = item.group
            if (layer != null) {
                dk.cbavf.studio.ui.HoldChip(
                    label = "${layer.id} ${layer.content.label}" + if (layer.visible) "" else " ◌",
                    selected = layer.id in state.selection,
                    onTap = { state.selectSingle(layer.id) },
                    onHold = { state.toggleSingle(layer.id) },
                    onDragEnd = { dx -> state.moveLayerBy(layer.id, (dx / chipW).roundToInt()) },
                )
            } else if (group != null) {
                val ids = state.groupLayerIds(group.id)
                dk.cbavf.studio.ui.HoldChip(
                    label = (if (group.collapsed) "▸ " else "▾ ") + group.name.ifBlank { "Group ${group.id}" } + " (${item.count})" + if (group.visible) "" else " ◌",
                    selected = ids.isNotEmpty() && ids.all { it in state.selection },
                    onTap = { state.toggleGroupCollapsed(group.id) },
                    onHold = { state.selectGroup(group.id) },
                    onDragEnd = { },
                )
            }
        }
    }
    items.mapNotNull { it.group }.filter { !it.collapsed }.forEach { LayerTabs(state, it.id, depth + 1) }
}

/** Show or hide whole groups, and rename the group the selected layer is in. */
@Composable
private fun GroupTools(state: DrawState) {
    var renaming by remember { mutableStateOf(false) }
    val group = state.primary?.group?.let { id -> state.groups.firstOrNull { it.id == id } }
    ScrollRow {
        Text("Groups", Modifier.width(72.dp), style = MaterialTheme.typography.labelMedium)
        TextButton(onClick = { state.setGroupedVisible(true) }) { Text("Show all") }
        TextButton(onClick = { state.setGroupedVisible(false) }) { Text("Hide all") }
        if (group != null) {
            TextButton(onClick = { state.toggleGroupVisible(group.id) }) { Text(if (group.visible) "Hide group" else "Show group") }
            TextButton(onClick = { state.selectGroup(group.id) }) { Text("Pick group") }
            TextButton(onClick = { renaming = true }) { Text("Rename") }
        }
    }
    if (renaming && group != null) {
        var text by remember(group.id) { mutableStateOf(group.name) }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Group name") },
            text = { androidx.compose.material3.OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { state.renameGroup(group.id, text.trim()); renaming = false }) { Text("OK") } },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Controls(state: DrawState, onSave: () -> Unit, onPickImages: () -> Unit) {
    var expanded by remember { mutableStateOf(true) }
    var showHistory by remember { mutableStateOf(false) }
    if (showHistory) dk.cbavf.studio.ui.HistoryPanel("Draw", state) { showHistory = false }
    Surface(tonalElevation = 2.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
            ScrollRow {
                Tool.entries.forEach { t ->
                    FilterChip(selected = state.tool == t, onClick = { state.tool = t }, label = { Text(t.name) })
                }
                TextButton(onClick = onSave) { Text("Save") }
                SaveSizeButton(state)
                TextButton(onClick = state::newCanvas) { Text("New") }
                TextButton(onClick = state::undo, enabled = state.canUndo) { Text("Undo") }
                TextButton(onClick = state::redo, enabled = state.canRedo) { Text("Redo") }
                TextButton(onClick = { showHistory = true }, enabled = state.canUndo || state.canRedo) { Text("History") }
                val abc = dk.cbavf.studio.abcompare.LocalAb.current
                val abScope = androidx.compose.runtime.rememberCoroutineScope()
                if (abc != null) TextButton(onClick = {
                    abScope.launch { abc.state.snapshot = withContext(Dispatchers.Default) { renderBitmap(state.toDoc()).asImageBitmap() } }
                }) { Text("Snap B") }
                TextButton(onClick = state::copy, enabled = state.selection.isNotEmpty()) { Text("Copy") }
                TextButton(onClick = state::paste, enabled = state.canPaste) { Text("Paste") }
                TextButton(onClick = state::duplicate, enabled = state.selection.isNotEmpty()) { Text("Duplicate") }
                TextButton(onClick = state::deleteSelection, enabled = state.selection.isNotEmpty()) { Text("Delete") }
                TextButton(onClick = state::groupSelection, enabled = state.selection.size >= 2) { Text("Group") }
                TextButton(onClick = state::ungroupSelection, enabled = state.layers.any { it.id in state.selection && it.group != null }) { Text("Ungroup") }
                FilterChip(selected = state.showFringes, onClick = { state.showFringes = !state.showFringes }, label = { Text("Fringes") })
            }
            if (state.groups.isNotEmpty()) GroupTools(state)
            // Always shown so the canvas keeps its size (and gratings their centre) across tools.
            ScrollRow {
                Pen.entries.forEach { p ->
                    FilterChip(
                        selected = state.tool == Tool.Pen && state.pen == p,
                        onClick = {
                            state.pen = p
                            state.tool = Tool.Pen
                        },
                        label = { Text(p.label) },
                    )
                }
                FilterChip(selected = state.straight, onClick = { state.straight = !state.straight }, label = { Text("Straight line") })
            }
            LayerTabs(state, parent = null, depth = 0)
            ScrollRow {
                TextButton(onClick = { state.add(Grating()) }) { Text("+ Grating") }
                TextButton(onClick = { state.add(RadialBurst()) }) { Text("+ Burst") }
                TextButton(onClick = onPickImages) { Text("+ Image") }
                TextButton(onClick = { state.add(EllipseHatch()) }) { Text("+ Ellipses") }
                // Two sine gratings a few percent apart: the classic smooth beat.
                TextButton(onClick = {
                    state.add(Grating(spacingPx = 10f, profile = Profile.Sine))
                    state.add(Grating(spacingPx = 10.6f, profile = Profile.Sine))
                    state.updatePrimary { it.copy(blend = Blend.Multiply) }
                }) { Text("+ Sine pair") }
                TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide" else "Edit") }
            }
            val layer = state.primary
            if (expanded && layer != null) {
                HorizontalDivider()
                Column(Modifier.panelScroll(260.dp)) {
                    LayerEditor(layer, state)
                }
            }
        }
    }
}

/**
 * The size Save renders at: screen-exact, or larger. A menu, so the controls (and with them the canvas) keep their size.
 * Each choice shows the pixel size it will save at, lowered when it would not fit in memory.
 */
@Composable
private fun SaveSizeButton(state: DrawState) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) { Text("Size: ${state.renderSize.label}") }
        androidx.compose.material3.DropdownMenu(open, onDismissRequest = { open = false }) {
            Text(
                "Bigger sizes draw the same picture with more pixels. Fine moiré can look a little different from the screen.",
                Modifier.padding(horizontal = 12.dp, vertical = 4.dp).width(280.dp),
                style = MaterialTheme.typography.labelMedium,
            )
            RenderSize.entries.forEach { size ->
                val plan = state.renderPlan(size)
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text((if (size == state.renderSize) "✓ " else "   ") + RenderSizes.describe(plan)) },
                    onClick = {
                        state.renderSize = size
                        open = false
                    },
                )
            }
        }
    }
}

/** Edits the primary layer's content if it is a [T]; consecutive moves of one slider are one undo step. */
private inline fun <reified T : Content> DrawState.edit(key: String, crossinline f: (T) -> T) =
    updateContent(primaryId to key) { (it as? T)?.let(f) ?: it }

@Composable
private fun LayerEditor(layer: Layer, state: DrawState) {
    when (val c = layer.content) {
        is Grating -> {
            LabeledSlider("Spacing", c.spacingPx, DrawState.MIN_SPACING..DrawState.MAX_SPACING, "px") { v ->
                state.edit<Grating>("spacing") { it.copy(spacingPx = v) }
            }
            LabeledSlider("Angle", c.angleDeg, -180f..180f, "°") { v ->
                state.edit<Grating>("angle") { it.copy(angleDeg = v) }
            }
            LabeledSlider("Line width", c.duty, 0.05f..0.95f, "") { v ->
                state.edit<Grating>("duty") { it.copy(duty = v) }
            }
            LabeledSlider("Offset", c.offsetPx.mod(c.spacingPx), 0f..c.spacingPx, "px") { v ->
                state.edit<Grating>("offset") { it.copy(offsetPx = v) }
            }
            ChipRow("Profile", Profile.entries, c.profile) { p -> state.edit<Grating>("profile") { it.copy(profile = p) } }
            LayerInk(layer, state)
        }
        is RadialBurst -> {
            LabeledSlider("Rays", c.rays.toFloat(), 8f..720f, "") { v ->
                state.edit<RadialBurst>("rays") { it.copy(rays = v.roundToInt()) }
            }
            LabeledSlider("Rotation", c.rotationDeg, -180f..180f, "°") { v ->
                state.edit<RadialBurst>("rotation") { it.copy(rotationDeg = v) }
            }
            LabeledSlider("Ray width", c.duty, 0.05f..0.95f, "") { v ->
                state.edit<RadialBurst>("duty") { it.copy(duty = v) }
            }
            LayerInk(layer, state)
        }
        is EllipseHatch -> {
            LabeledSlider("Spacing", c.spacingPx, DrawState.MIN_SPACING..DrawState.MAX_SPACING, "px") { v ->
                state.edit<EllipseHatch>("spacing") { it.copy(spacingPx = v) }
            }
            LabeledSlider("Aspect", c.aspect, 0.1f..3f, "") { v -> state.edit<EllipseHatch>("aspect") { it.copy(aspect = v) } }
            LabeledSlider("Rotation", c.rotationDeg, -180f..180f, "°") { v -> state.edit<EllipseHatch>("rotation") { it.copy(rotationDeg = v) } }
            LabeledSlider("Line width", c.duty, 0.05f..0.95f, "") { v -> state.edit<EllipseHatch>("duty") { it.copy(duty = v) } }
            LabeledSlider("Offset", c.offsetPx.mod(c.spacingPx), 0f..c.spacingPx, "px") { v ->
                state.edit<EllipseHatch>("offset") { it.copy(offsetPx = v) }
            }
            LayerInk(layer, state)
        }
        is Stroke -> StrokeEditor(c, state)
        is ImageLayer -> ImageEditor(c, state)
    }
    ChipRow("Blend", Blend.entries, layer.blend) { b -> state.updatePrimary { it.copy(blend = b) } }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        TextButton(onClick = { state.updatePrimary { it.copy(visible = !it.visible) } }) {
            Text(if (layer.visible) "Hide layer" else "Show layer")
        }
        val index = state.layers.indexOfFirst { it.id == layer.id }
        TextButton(onClick = { state.moveLayer(1) }, enabled = index < state.layers.lastIndex) { Text("Up") }
        TextButton(onClick = { state.moveLayer(-1) }, enabled = index > 0) { Text("Down") }
    }
}

@Composable
private fun LayerInk(layer: Layer, state: DrawState) {
    ChipRow("Ink", Ink.entries, layer.ink) { i -> state.updatePrimary { it.copy(ink = i) } }
}

@Composable
private fun ImageEditor(c: ImageLayer, state: DrawState) {
    val t = c.transform
    LabeledSlider("Opacity", c.opacity, 0f..1f, "") { v -> state.edit<ImageLayer>("opacity") { it.copy(opacity = v) } }
    LabeledSlider("Rotation", t.rotationDeg, -180f..180f, "°") { v ->
        state.edit<ImageLayer>("rot") { it.copy(transform = it.transform.copy(rotationDeg = v)) }
    }
    LabeledSlider("Stretch X", t.sx, 0.05f..5f, "") { v -> state.edit<ImageLayer>("sx") { it.copy(transform = it.transform.copy(sx = v)) } }
    LabeledSlider("Stretch Y", t.sy, 0.05f..5f, "") { v -> state.edit<ImageLayer>("sy") { it.copy(transform = it.transform.copy(sy = v)) } }
    ScrollRow {
        val cw = state.canvasWidth
        val ch = state.canvasHeight
        TextButton(onClick = { state.edit<ImageLayer>("fit") { ImageLayer.fitted(it.assetId, it.widthPx, it.heightPx, cw, ch, 1f).copy(opacity = it.opacity) } }) { Text("Fit") }
        TextButton(onClick = {
            // Cover the whole canvas (crops the long side).
            val k = maxOf(cw.toFloat() / c.widthPx, ch.toFloat() / c.heightPx)
            state.edit<ImageLayer>("fill") { it.copy(transform = it.transform.copy(tx = cw / 2f, ty = ch / 2f, rotationDeg = 0f, sx = k, sy = k)) }
        }) { Text("Fill") }
        // One image pixel per screen pixel (as far as the image was stored at full size).
        TextButton(onClick = { state.edit<ImageLayer>("one") { it.copy(transform = it.transform.copy(sx = 1f, sy = 1f)) } }) { Text("1:1") }
        TextButton(onClick = { state.edit<ImageLayer>("straight") { it.copy(transform = it.transform.copy(rotationDeg = 0f, sx = (it.transform.sx + it.transform.sy) / 2, sy = (it.transform.sx + it.transform.sy) / 2)) } }) { Text("Straighten") }
    }
}

@Composable
private fun StrokeEditor(s: Stroke, state: DrawState) {
    val t = s.transform
    LabeledSlider("Rotation", t.rotationDeg, -180f..180f, "°") { v ->
        state.edit<Stroke>("rot") { it.copy(transform = it.transform.copy(rotationDeg = v)) }
    }
    LabeledSlider("Stretch X", t.sx, 0.1f..5f, "") { v ->
        state.edit<Stroke>("sx") { it.copy(transform = it.transform.copy(sx = v)) }
    }
    LabeledSlider("Stretch Y", t.sy, 0.1f..5f, "") { v ->
        state.edit<Stroke>("sy") { it.copy(transform = it.transform.copy(sy = v)) }
    }

    ScrollRow {
        Text("Lines", Modifier.width(48.dp), style = MaterialTheme.typography.labelMedium)
        s.stack.indices.forEach { i ->
            FilterChip(selected = i == state.selectedSub, onClick = { state.selectedSub = i }, label = { Text("${i + 1}") })
        }
        TextButton(onClick = state::addSub) { Text("+ line") }
        TextButton(onClick = state::removeSub, enabled = s.stack.size > 1) { Text("− line") }
    }

    val sub = s.stack.getOrNull(state.selectedSub) ?: return
    val key = "sub${state.selectedSub}"
    LabeledSlider("Width", sub.widthPx, 0.5f..40f, "px") { v -> state.updateSub("$key.w") { it.copy(widthPx = v) } }
    LabeledSlider("Offset", sub.offsetPx, -60f..60f, "px") { v -> state.updateSub("$key.o") { it.copy(offsetPx = v) } }
    LabeledSlider("Dash", sub.dashPx, 0f..60f, "px") { v -> state.updateSub("$key.d") { it.copy(dashPx = v) } }
    LabeledSlider("Gap", sub.gapPx, 0f..60f, "px") { v -> state.updateSub("$key.g") { it.copy(gapPx = v) } }
    if (sub.dashed) {
        LabeledSlider("Phase", sub.dashPhasePx.mod(sub.dashPeriod), 0f..sub.dashPeriod, "px") { v ->
            state.updateSub("$key.p") { it.copy(dashPhasePx = v) }
        }
    }
    ChipRow("Ink", Ink.entries, sub.ink) { i -> state.updateSub { it.copy(ink = i) } }
    ChipRow("Cap", Cap.entries, sub.cap) { c -> state.updateSub { it.copy(cap = c) } }
}

@Composable
private fun <T : Enum<T>> ChipRow(label: String, options: List<T>, selected: T, onSelect: (T) -> Unit) {
    ScrollRow {
        Text(label, Modifier.width(48.dp), style = MaterialTheme.typography.labelMedium)
        options.forEach { o ->
            FilterChip(selected = o == selected, onClick = { onSelect(o) }, label = { Text(o.name) })
        }
    }
}

