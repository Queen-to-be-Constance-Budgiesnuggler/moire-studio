package dk.cbavf.studio.compose

import androidx.compose.foundation.Canvas
import dk.cbavf.studio.compose.model.label
import dk.cbavf.studio.compose.model.TextLayerSource
import dk.cbavf.studio.compose.model.SolidLayerSource
import dk.cbavf.studio.compose.model.PaintLayerSource
import dk.cbavf.studio.compose.model.LayerFilter
import dk.cbavf.studio.compose.model.GradientLayerSource
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.runtime.produceState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dk.cbavf.studio.compose.model.AssetLayerSource
import dk.cbavf.studio.compose.model.Background
import dk.cbavf.studio.compose.model.CanvasLayerSource
import dk.cbavf.studio.compose.model.ComposeDoc
import dk.cbavf.studio.compose.model.ComposeLayer
import dk.cbavf.studio.develop.model.DevelopDoc
import dk.cbavf.studio.draw.model.Blend
import dk.cbavf.studio.draw.model.EllipseHatch
import dk.cbavf.studio.draw.model.Grating
import dk.cbavf.studio.draw.model.Layer
import dk.cbavf.studio.draw.model.Profile
import dk.cbavf.studio.draw.model.Pt
import dk.cbavf.studio.draw.model.RadialBurst
import dk.cbavf.studio.library.CanvasDoc
import dk.cbavf.studio.library.Lineage
import dk.cbavf.studio.library.ProjectStore
import dk.cbavf.studio.ui.LabeledSlider
import dk.cbavf.studio.ui.LibraryPicker
import dk.cbavf.studio.ui.ScrollRow
import dk.cbavf.studio.ui.renderSafely
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlin.math.ceil
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.roundToInt

/** Longest side of the live view's render, so editing stays quick on big documents. */
private const val VIEW_MAX_PX = 2400

@Composable
fun ComposeRoom(
    state: ComposeState,
    store: ProjectStore,
    lineage: Lineage,
    pixels: LayerPixels,
    busy: Boolean,
    drawCanvas: () -> CanvasDoc?,
    screen: Pair<Int, Int>,
    onSave: () -> Unit,
) {
    var picking by remember { mutableStateOf(false) }
    if (picking) {
        LibraryPicker(store, lineage) { ids ->
            picking = false
            state.addImages(ids.mapNotNull { lineage[it] })
        }
        return
    }
    val doc = state.doc
    var viewW by remember { mutableIntStateOf(1) }
    var viewH by remember { mutableIntStateOf(1) }
    val fit = minOf(viewW.toFloat() / doc.widthPx, viewH.toFloat() / doc.heightPx) * 0.95f
    val s = fit * state.zoom
    val ox = (viewW - doc.widthPx * s) / 2 + state.panX
    val oy = (viewH - doc.heightPx * s) / 2 + state.panY
    // Render at the next power of two above the on-screen scale: zooming doesn't re-render every frame.
    val renderScale = 2f.pow(ceil(log2(s.coerceAtLeast(1e-3f)))).coerceAtMost(1f)
        .coerceAtMost(VIEW_MAX_PX.toFloat() / maxOf(doc.widthPx, doc.heightPx))
    // The gesture handler lives across recompositions; it reads the view through this.
    val liveView by rememberUpdatedState(View(fit, s, ox, oy, viewW, viewH, doc.widthPx, doc.heightPx))
    var image by remember { mutableStateOf<ImageBitmap?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var lasso by remember { mutableStateOf<List<Pt>?>(null) }
    LaunchedEffect(doc, renderScale) {
        delay(30)
        renderSafely({ error = it }) { renderComposite(doc, renderScale, pixels) { ensureActive() }.asImageBitmap() }?.let {
            image = it
            error = null
        }
    }

    Column(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color(0xFF2B2B2B))
                .onSizeChanged {
                    viewW = it.width
                    viewH = it.height
                }
                .pointerInput(state.tool) {
                    gestures(state, { liveView }, onLasso = { lasso = it }) { p ->
                        // Eyedropper: the colour of the composite under the finger.
                        image?.asAndroidBitmap()?.let { b ->
                            val x = (p.x * renderScale).toInt().coerceIn(0, b.width - 1)
                            val y = (p.y * renderScale).toInt().coerceIn(0, b.height - 1)
                            state.color = b.getPixel(x, y).toLong() and 0xFFFFFFFFL
                        }
                    }
                }
        ) {
            Canvas(Modifier.fillMaxSize()) {
                // Checkerboard shows transparency.
                if (doc.background == Background.Transparent) {
                    val cell = 24f
                    var y = oy
                    var row = 0
                    while (y < oy + doc.heightPx * s) {
                        var x = ox + (row % 2) * cell
                        while (x < ox + doc.widthPx * s) {
                            drawRect(Color(0xFF555555), Offset(x, y), Size(minOf(cell, ox + doc.widthPx * s - x), minOf(cell, oy + doc.heightPx * s - y)))
                            x += cell * 2
                        }
                        y += cell
                        row++
                    }
                }
                image?.let {
                    drawImage(
                        it,
                        dstOffset = IntOffset(ox.roundToInt(), oy.roundToInt()),
                        dstSize = IntSize((doc.widthPx * s).roundToInt(), (doc.heightPx * s).roundToInt()),
                    )
                }
                // Selected layer outline.
                state.selected?.let { l ->
                    val pts = l.corners().map { Offset(ox + it.x * s, oy + it.y * s) }
                    val path = Path().apply {
                        moveTo(pts[0].x, pts[0].y)
                        pts.drop(1).forEach { lineTo(it.x, it.y) }
                        close()
                    }
                    drawPath(path, Color(0xFF00B0FF), style = Stroke(width = 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 6f))))
                }
                // Selection (or the lasso being drawn): black and white dashes, visible on any image.
                (lasso ?: state.selection)?.takeIf { it.size >= 2 }?.let { sel ->
                    val path = Path().apply {
                        moveTo(ox + sel[0].x * s, oy + sel[0].y * s)
                        sel.drop(1).forEach { lineTo(ox + it.x * s, oy + it.y * s) }
                        if (lasso == null) close()
                    }
                    drawPath(path, Color.Black, style = Stroke(width = 2f))
                    drawPath(path, Color.White, style = Stroke(width = 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f))))
                }
            }
            Surface(
                Modifier.align(Alignment.TopStart).padding(8.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                shape = MaterialTheme.shapes.small,
            ) {
                Text(
                    error ?: (doc.summary + " · ${(s * 100).roundToInt()}%" + (state.selected?.let { " · ${it.name}" } ?: "")),
                    Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            if (doc.layers.isEmpty()) {
                Text(
                    "Empty composite. Add layers below, or from Library → Use in → Compose.",
                    Modifier.align(Alignment.Center).padding(32.dp),
                    color = Color.LightGray,
                )
            }
        }
        Panel(state, pixels, busy, drawCanvas, screen, onSave, onPick = { picking = true })
    }
}

/** Where the document sits on screen right now. */
private data class View(val fit: Float, val s: Float, val ox: Float, val oy: Float, val viewW: Int, val viewH: Int, val docW: Int, val docH: Int)

/**
 * One-finger tools: View pans; Move moves the selected layer (tap picks one); Select drags a
 * rectangle; Lasso draws a selection; Brush and Eraser paint; Mask paints the layer mask;
 * Eyedropper picks a colour. Two fingers always pan and zoom the view, except in Move, where they
 * pinch and twist the layer. [view] is read fresh on every event, so a gesture keeps going while
 * the view changes under it.
 */
private suspend fun PointerInputScope.gestures(
    state: ComposeState,
    view: () -> View,
    onLasso: (List<Pt>?) -> Unit,
    onEyedropper: (Pt) -> Unit,
) {
    var gesture = 0
    awaitEachGesture {
        val down = awaitFirstDown()
        gesture++
        val key = "g$gesture"
        var moved = false
        val start = view()
        fun docOf(o: Offset, v: View = view()) = Pt((o.x - v.ox) / v.s, (o.y - v.oy) / v.s)
        val downDoc = docOf(down.position, start)
        val lassoPts = mutableListOf(downDoc)
        var painting = false
        when (state.tool) {
            ComposeTool.Paint, ComposeTool.Erase -> {
                state.beginStroke(downDoc, erase = state.tool == ComposeTool.Erase, key)
                painting = true
            }
            ComposeTool.Mask -> if (state.selected != null) state.paintMask(downDoc, key)
            ComposeTool.Pick -> onEyedropper(downDoc)
            else -> Unit
        }
        do {
            val event = awaitPointerEvent()
            val v = view()
            val fingers = event.changes.count { it.pressed }
            val pan = event.calculatePan()
            val zoom = event.calculateZoom()
            val rot = event.calculateRotation()
            val active = pan != Offset.Zero || zoom != 1f || rot != 0f
            if (active) moved = true
            val here = event.changes.firstOrNull()?.let { docOf(it.position, v) }
            when {
                fingers >= 2 && state.tool != ComposeTool.Move -> viewGesture(state, event, v, pan, zoom)
                state.tool == ComposeTool.Move && state.selected != null && active ->
                    state.transformSelected(Pt(pan.x / v.s, pan.y / v.s), zoom, rot, key)
                state.tool == ComposeTool.Select && here != null -> state.selectRect(downDoc, here)
                state.tool == ComposeTool.Lasso && here != null -> {
                    if (kotlin.math.hypot(here.x - lassoPts.last().x, here.y - lassoPts.last().y) * v.s > 4f) lassoPts += here
                    onLasso(lassoPts.toList())
                }
                painting && here != null -> state.extendStroke(here, key)
                state.tool == ComposeTool.Mask && state.selected != null && here != null -> state.paintMask(here, key)
                state.tool == ComposeTool.Pick && here != null -> onEyedropper(here)
                active -> viewGesture(state, event, v, pan, zoom)
            }
            event.changes.forEach { if (it.positionChanged()) it.consume() }
        } while (event.changes.any { it.pressed })
        when (state.tool) {
            ComposeTool.Lasso -> {
                onLasso(null)
                state.selection = lassoPts.takeIf { it.size >= 3 }
            }
            ComposeTool.Select -> if (!moved) state.selection = null
            ComposeTool.Move -> if (!moved) state.pick(downDoc)
            ComposeTool.View -> if (!moved) state.pick(downDoc)
            else -> Unit
        }
    }
}

/** Pan and zoom the view, keeping the document point under the fingers in place. */
private fun viewGesture(state: ComposeState, event: androidx.compose.ui.input.pointer.PointerEvent, v: View, pan: Offset, zoom: Float) {
    val c = event.calculateCentroid()
    if (c == Offset.Unspecified) return
    val px = (c.x - v.ox) / v.s
    val py = (c.y - v.oy) / v.s
    val newZoom = (state.zoom * zoom).coerceIn(0.05f, 64f)
    val sNew = v.fit * newZoom
    state.zoom = newZoom
    state.panX = c.x + pan.x - px * sNew - (v.viewW - v.docW * sNew) / 2
    state.panY = c.y + pan.y - py * sNew - (v.viewH - v.docH * sNew) / 2
}

@Composable
private fun Panel(
    state: ComposeState,
    pixels: LayerPixels,
    busy: Boolean,
    drawCanvas: () -> CanvasDoc?,
    screen: Pair<Int, Int>,
    onSave: () -> Unit,
    onPick: () -> Unit,
) {
    val doc = state.doc
    var texting by remember { mutableStateOf<ComposeLayer?>(null) }
    var newText by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    if (newText || texting != null) {
        val src = texting?.source as? TextLayerSource
        TextDialog(src?.text ?: "Text", src?.sizePx ?: (doc.heightPx / 12f), src?.bold ?: false, onDismiss = {
            newText = false
            texting = null
        }) { text, size, bold ->
            val (w, h) = measureText(text, size, bold)
            if (src == null) {
                state.addText(text, size, bold, w, h)
            } else {
                state.update { it.copy(source = src.copy(text = text, sizePx = size, bold = bold, widthPx = w, heightPx = h)) }
            }
            newText = false
            texting = null
        }
    }
    if (renaming) {
        state.selected?.let { l ->
            NameDialog(l.name, onDismiss = { renaming = false }) {
                state.rename(it)
                renaming = false
            }
        }
    }
    Surface(tonalElevation = 2.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
            ScrollRow {
                Button(onClick = onSave, enabled = doc.layers.isNotEmpty() && !busy) { Text(if (busy) "Saving…" else "Save") }
                TextButton(onClick = state.edit::undo, enabled = state.edit.canUndo) { Text("Undo") }
                TextButton(onClick = state::fitView) { Text("Fit view") }
                ComposeTool.entries.forEach { t -> FilterChip(selected = state.tool == t, onClick = { state.tool = t }, label = { Text(t.label) }) }
            }
            ScrollRow {
                Text("Add", Modifier.width(36.dp), style = MaterialTheme.typography.labelMedium)
                TextButton(onClick = onPick) { Text("Library…") }
                TextButton(onClick = { drawCanvas()?.let { state.addCanvas(it) } }) { Text("Draw canvas") }
                TextButton(onClick = state::addPaintLayer) { Text("Paint layer") }
                TextButton(onClick = { newText = true }) { Text("Text…") }
                TextButton(onClick = { state.addSolid(state.color) }) { Text("Fill") }
                TextButton(onClick = { state.addGradient(state.color, 0x00000000, radial = false) }) { Text("Gradient") }
                TextButton(onClick = { state.addGradient(state.color, 0x00000000, radial = true) }) { Text("Radial") }
                TextButton(onClick = { state.addGenerator(Grating()) }) { Text("Grating") }
                TextButton(onClick = { state.addGenerator(Grating(profile = Profile.Sine)) }) { Text("Sine") }
                TextButton(onClick = { state.addGenerator(RadialBurst()) }) { Text("Burst") }
                TextButton(onClick = { state.addGenerator(EllipseHatch()) }) { Text("Ellipses") }
            }
            LayerStrip(state, pixels)
            state.selection?.let { SelectionBar(state) }
            if (state.tool == ComposeTool.Select || state.tool == ComposeTool.Lasso) {
                ScrollRow {
                    TextButton(onClick = state::selectAll) { Text("Select all") }
                    if (state.selection != null) TextButton(onClick = { state.selection = null }) { Text("Deselect") }
                }
            }
            HorizontalDivider()
            Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState())) {
                if (state.tool in setOf(ComposeTool.Paint, ComposeTool.Erase, ComposeTool.Pick) || state.selection != null) BrushControls(state)
                state.selected?.let { LayerControls(state, it, onRename = { renaming = true }, onEditText = { texting = it }) }
                DocumentControls(state, doc, screen)
            }
        }
    }
}

/** Layers with thumbnails, top first (as in GIMP); tap to select, the eye toggles visibility. */
@Composable
private fun LayerStrip(state: ComposeState, pixels: LayerPixels) {
    val doc = state.doc
    if (doc.layers.isEmpty()) return
    androidx.compose.foundation.lazy.LazyRow(
        Modifier.fillMaxWidth().height(84.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(doc.layers.asReversed(), key = { it.id }) { l ->
            val thumb by produceState<ImageBitmap?>(null, l.source, l.adjust, l.filters) {
                value = renderSafely({}) { pixels.thumb(l)?.asImageBitmap() }
            }
            val selected = l.id == state.selectedId
            Column(
                Modifier
                    .width(64.dp)
                    .border(if (selected) 2.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)
                    .clickable { state.selectedId = l.id }
                    .padding(2.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(Modifier.fillMaxWidth().weight(1f).background(Color(0xFF555555))) {
                    thumb?.let { androidx.compose.foundation.Image(it, null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Fit) }
                    Text(
                        if (l.visible) "◉" else "○",
                        Modifier.align(Alignment.TopEnd).clickable { state.selectedId = l.id; state.update { it.copy(visible = !it.visible) } }.padding(2.dp),
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium,
                    )
                    if (l.clip) Text("↳", Modifier.align(Alignment.TopStart).padding(2.dp), color = Color.White)
                }
                Text(l.name, maxLines = 1, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun SelectionBar(state: ComposeState) {
    var feather by remember { mutableStateOf(0f) }
    ScrollRow {
        Text("Selection", Modifier.width(64.dp), style = MaterialTheme.typography.labelMedium)
        TextButton(onClick = state::cropToSelection) { Text("Crop") }
        TextButton(onClick = { state.maskFromSelection(keep = true, feather) }, enabled = state.selected != null) { Text("Keep (mask)") }
        TextButton(onClick = { state.maskFromSelection(keep = false, feather) }, enabled = state.selected != null) { Text("Cut out (mask)") }
        TextButton(onClick = { state.fillSelection(erase = false) }) { Text("Fill") }
        TextButton(onClick = { state.fillSelection(erase = true) }) { Text("Erase") }
        TextButton(onClick = { state.selection = null }) { Text("Deselect") }
    }
    LabeledSlider("Feather", feather, 0f..200f, " px") { feather = it }
}

/** Colour (with swatches, HSV and opacity), brush size and softness. */
@Composable
private fun BrushControls(state: ComposeState) {
    Text("Colour and brush", style = MaterialTheme.typography.labelLarge)
    ColorPicker(state.color) { state.color = it }
    LabeledSlider("Size", state.paintWidth, 1f..400f, " px") { state.paintWidth = it }
    LabeledSlider("Softness", state.paintSoftness, 0f..1f, "") { state.paintSoftness = it }
    HorizontalDivider()
}

/** Swatches plus hue, saturation, value and opacity sliders (each with exact input). */
@Composable
fun ColorPicker(argb: Long, onChange: (Long) -> Unit) {
    val hsv = FloatArray(3).also { android.graphics.Color.colorToHSV(argb.toInt(), it) }
    val alpha = (argb ushr 24 and 0xFF).toInt()
    fun set(h: Float = hsv[0], sat: Float = hsv[1], v: Float = hsv[2], a: Int = alpha) =
        onChange(android.graphics.Color.HSVToColor(a, floatArrayOf(h, sat, v)).toLong() and 0xFFFFFFFFL)
    ScrollRow {
        Box(Modifier.width(36.dp).height(28.dp).background(Color(argb.toInt())).border(1.dp, MaterialTheme.colorScheme.outline))
        listOf(0xFF000000, 0xFFFFFFFF, 0xFF808080, 0xFFFF0000, 0xFF00FF00, 0xFF0000FF, 0xFF00FFFF, 0xFFFF00FF, 0xFFFFFF00, 0xFFFF8000).forEach { c ->
            Box(Modifier.width(28.dp).height(28.dp).background(Color(c.toInt())).border(1.dp, MaterialTheme.colorScheme.outline).clickable { onChange(c) })
        }
    }
    LabeledSlider("Hue", hsv[0], 0f..360f, "°") { set(h = it) }
    LabeledSlider("Saturation", hsv[1] * 100, 0f..100f, "%") { set(sat = it / 100) }
    LabeledSlider("Value", hsv[2] * 100, 0f..100f, "%") { set(v = it / 100) }
    LabeledSlider("Opacity", alpha / 2.55f, 0f..100f, "%", format = { "${it.roundToInt()}" }) { set(a = (it * 2.55f).roundToInt()) }
}

@Composable
private fun LayerControls(state: ComposeState, l: ComposeLayer, onRename: () -> Unit, onEditText: (ComposeLayer) -> Unit) {
    val doc = state.doc
    val style = MaterialTheme.typography.labelMedium
    Text("Layer: ${l.name}", style = MaterialTheme.typography.labelLarge)
    ScrollRow {
        TextButton(onClick = onRename) { Text("Rename") }
        TextButton(onClick = { state.update { it.copy(visible = !it.visible) } }) { Text(if (l.visible) "Hide" else "Show") }
        TextButton(onClick = { state.move(1) }, enabled = l != doc.layers.last()) { Text("Raise") }
        TextButton(onClick = { state.move(-1) }, enabled = l != doc.layers.first()) { Text("Lower") }
        TextButton(onClick = state::duplicate) { Text("Duplicate") }
        TextButton(onClick = state::delete) { Text("Delete") }
    }

    Text("Blending", style = MaterialTheme.typography.labelLarge)
    LabeledSlider("Opacity", l.opacity * 100, 0f..100f, "%", format = { "${it.roundToInt()}" }) { v -> state.update("opacity") { it.copy(opacity = v / 100) } }
    ScrollRow {
        Text("Mode", Modifier.width(48.dp), style = style)
        Blend.entries.forEach { b -> FilterChip(selected = l.blend == b, onClick = { state.update { it.copy(blend = b) } }, label = { Text(b.name) }) }
    }
    ScrollRow {
        FilterChip(selected = l.clip, onClick = { state.update { it.copy(clip = !it.clip) } }, enabled = l != doc.layers.first(), label = { Text("Clip to layer below") })
        FilterChip(selected = l.blendIf != null, onClick = {
            state.update { it.copy(blendIf = if (it.blendIf == null) dk.cbavf.studio.compose.model.BlendIf() else null) }
        }, label = { Text("Blend if…") })
    }
    l.blendIf?.let { b ->
        ScrollRow {
            FilterChip(selected = !b.underlying, onClick = { state.update { it.copy(blendIf = b.copy(underlying = false)) } }, label = { Text("This layer") })
            FilterChip(selected = b.underlying, onClick = { state.update { it.copy(blendIf = b.copy(underlying = true)) } }, label = { Text("Underlying") })
        }
        LabeledSlider("Shadows", b.low * 100, 0f..100f, "%") { v -> state.update("bif-low") { it.copy(blendIf = b.copy(low = v / 100)) } }
        LabeledSlider("Highlights", b.high * 100, 0f..100f, "%") { v -> state.update("bif-high") { it.copy(blendIf = b.copy(high = v / 100)) } }
        LabeledSlider("Feather", b.feather * 100, 0f..50f, "%") { v -> state.update("bif-f") { it.copy(blendIf = b.copy(feather = v / 100)) } }
    }

    Text("Transform", style = MaterialTheme.typography.labelLarge)
    val t = l.transform
    LabeledSlider("X", t.tx, -doc.widthPx.toFloat()..doc.widthPx * 2f, " px") { v -> state.update("x") { it.copy(transform = it.transform.copy(tx = v)) } }
    LabeledSlider("Y", t.ty, -doc.heightPx.toFloat()..doc.heightPx * 2f, " px") { v -> state.update("y") { it.copy(transform = it.transform.copy(ty = v)) } }
    LabeledSlider("Scale", (kotlin.math.abs(t.sx) + kotlin.math.abs(t.sy)) / 2 * 100, 1f..400f, "%") { v ->
        // Uniform scale keeps the current stretch ratio (and any flip).
        state.update("scale") {
            val avg = ((kotlin.math.abs(it.transform.sx) + kotlin.math.abs(it.transform.sy)) / 2).coerceAtLeast(1e-4f)
            val k = v / 100 / avg
            it.copy(transform = it.transform.copy(sx = it.transform.sx * k, sy = it.transform.sy * k))
        }
    }
    LabeledSlider("Stretch X", kotlin.math.abs(t.sx) * 100, 1f..400f, "%") { v -> state.update("sx") { it.copy(transform = it.transform.copy(sx = kotlin.math.sign(it.transform.sx).let { s0 -> if (s0 == 0f) 1f else s0 } * v / 100)) } }
    LabeledSlider("Stretch Y", kotlin.math.abs(t.sy) * 100, 1f..400f, "%") { v -> state.update("sy") { it.copy(transform = it.transform.copy(sy = kotlin.math.sign(it.transform.sy).let { s0 -> if (s0 == 0f) 1f else s0 } * v / 100)) } }
    LabeledSlider("Rotation", t.rotationDeg, -180f..180f, "°") { v -> state.update("rot") { it.copy(transform = it.transform.copy(rotationDeg = v)) } }
    ScrollRow {
        val w = l.sourceWidth.toFloat()
        val h = l.sourceHeight.toFloat()
        fun place(k: Float) = state.update { it.copy(transform = it.transform.copy(tx = doc.widthPx / 2f, ty = doc.heightPx / 2f, rotationDeg = 0f, sx = k, sy = k)) }
        TextButton(onClick = { place(minOf(doc.widthPx / w, doc.heightPx / h)) }) { Text("Fit") }
        TextButton(onClick = { place(maxOf(doc.widthPx / w, doc.heightPx / h)) }) { Text("Fill") }
        TextButton(onClick = { place(1f) }) { Text("1:1") }
        TextButton(onClick = { state.update { it.copy(transform = it.transform.copy(tx = doc.widthPx / 2f, ty = doc.heightPx / 2f)) } }) { Text("Centre") }
        TextButton(onClick = { state.flip(horizontal = true) }) { Text("Flip ↔") }
        TextButton(onClick = { state.flip(horizontal = false) }) { Text("Flip ↕") }
    }

    SourceControls(state, l, onEditText)

    Text("Filters", style = MaterialTheme.typography.labelLarge)
    l.filters.forEachIndexed { i, f -> FilterControls(state, i, f) }
    ScrollRow {
        Text("Add", Modifier.width(36.dp), style = style)
        listOf(
            LayerFilter.Blur(), LayerFilter.Sharpen(), LayerFilter.Noise(), LayerFilter.Invert, LayerFilter.Desaturate,
            LayerFilter.Posterize(), LayerFilter.Threshold(), LayerFilter.FftPass(), LayerFilter.Glitch(),
        ).forEach { f -> TextButton(onClick = { state.addFilter(f) }) { Text(f.label) } }
    }

    Text("Mask", style = MaterialTheme.typography.labelLarge)
    val m = l.mask
    ScrollRow {
        FilterChip(selected = state.tool == ComposeTool.Mask && !state.brushReveal, onClick = {
            state.tool = ComposeTool.Mask
            state.brushReveal = false
        }, label = { Text("Paint: hide") })
        FilterChip(selected = state.tool == ComposeTool.Mask && state.brushReveal, onClick = {
            state.tool = ComposeTool.Mask
            state.brushReveal = true
        }, label = { Text("Paint: reveal") })
        if (m != null) {
            FilterChip(selected = m.enabled, onClick = { state.update { it.copy(mask = m.copy(enabled = !m.enabled)) } }, label = { Text("Mask on") })
            FilterChip(selected = m.invert, onClick = { state.update { it.copy(mask = m.copy(invert = !m.invert)) } }, label = { Text("Invert") })
            TextButton(onClick = { state.update { it.copy(mask = null) } }) { Text("Remove mask") }
        }
    }
    LabeledSlider("Mask brush", state.brushRadius, 2f..maxOf(doc.widthPx, doc.heightPx) / 4f, " px") { state.brushRadius = it }
    LabeledSlider("Softness", state.brushSoftness, 0f..1f, "") { state.brushSoftness = it }

    Text("Adjust", style = MaterialTheme.typography.labelLarge)
    val a = l.adjust ?: DevelopDoc()
    fun adjust(key: String, f: (DevelopDoc) -> DevelopDoc) = state.update(key) { it.copy(adjust = f(it.adjust ?: DevelopDoc())) }
    LabeledSlider("Exposure", a.exposureEv, -3f..3f, " EV") { v -> adjust("ev") { it.copy(exposureEv = v) } }
    LabeledSlider("Temperature", a.temperature, -1f..1f, "") { v -> adjust("temp") { it.copy(temperature = v) } }
    LabeledSlider("Tint", a.tint, -1f..1f, "") { v -> adjust("tint") { it.copy(tint = v) } }
    LabeledSlider("Black", a.black, 0f..0.5f, "") { v -> adjust("black") { it.copy(black = v) } }
    LabeledSlider("White", a.white, 0.5f..1f, "") { v -> adjust("white") { it.copy(white = v) } }
    LabeledSlider("Mid-tones", a.gamma, 0.3f..3f, "") { v -> adjust("gamma") { it.copy(gamma = v) } }
    if (l.adjust != null) TextButton(onClick = { state.update { it.copy(adjust = null) } }) { Text("Reset adjustments") }
    HorizontalDivider()
}

/** Parameters of one filter, with a remove button. */
@Composable
private fun FilterControls(state: ComposeState, i: Int, f: LayerFilter) {
    val key = "filter$i"
    ScrollRow {
        Text("${i + 1}. ${f.label}", style = MaterialTheme.typography.labelMedium)
        TextButton(onClick = { state.removeFilter(i) }) { Text("Remove") }
    }
    when (f) {
        is LayerFilter.Blur -> LabeledSlider("Radius", f.radius, 0f..200f, " px") { state.setFilter(i, key, f.copy(radius = it)) }
        is LayerFilter.Sharpen -> {
            LabeledSlider("Amount", f.amount, 0f..5f, "") { state.setFilter(i, key, f.copy(amount = it)) }
            LabeledSlider("Radius", f.radius, 1f..50f, " px") { state.setFilter(i, key, f.copy(radius = it)) }
        }
        is LayerFilter.Noise -> {
            LabeledSlider("Amount", f.amount * 100, 0f..100f, "%") { state.setFilter(i, key, f.copy(amount = it / 100)) }
            ScrollRow {
                FilterChip(selected = f.mono, onClick = { state.setFilter(i, "$key.m", f.copy(mono = !f.mono)) }, label = { Text("Mono") })
                TextButton(onClick = { state.setFilter(i, "$key.s", f.copy(seed = f.seed + 1)) }) { Text("New grain") }
            }
        }
        is LayerFilter.Posterize -> LabeledSlider("Levels", f.levels.toFloat(), 2f..32f, "", format = { "${it.roundToInt()}" }) { state.setFilter(i, key, f.copy(levels = it.roundToInt())) }
        is LayerFilter.Threshold -> LabeledSlider("Level", f.level * 100, 0f..100f, "%") { state.setFilter(i, key, f.copy(level = it / 100)) }
        is LayerFilter.FftPass -> {
            ScrollRow {
                dk.cbavf.studio.fft.model.FilterShape.entries.filter { it != dk.cbavf.studio.fft.model.FilterShape.None }.forEach { sh ->
                    FilterChip(selected = f.filter == sh, onClick = { state.setFilter(i, "$key.f", f.copy(filter = sh)) }, label = { Text(sh.name) })
                }
            }
            LabeledSlider("Radius", f.radius, 0.005f..0.5f, "") { state.setFilter(i, key, f.copy(radius = it)) }
            if (f.filter == dk.cbavf.studio.fft.model.FilterShape.BandPass) LabeledSlider("Band", f.band, 0.005f..0.3f, "") { state.setFilter(i, "$key.b", f.copy(band = it)) }
        }
        is LayerFilter.Glitch -> {
            val g = f.doc
            ScrollRow {
                listOf(1, 2, 3, 4).forEach { b -> FilterChip(selected = g.bytesPerPixel == b, onClick = { state.setFilter(i, "$key.b", f.copy(doc = g.copy(bytesPerPixel = b))) }, label = { Text("$b B/px") }) }
            }
            LabeledSlider("Width", g.widthDelta.toFloat(), -128f..128f, "", format = { "${it.roundToInt()}" }) { state.setFilter(i, "$key.w", f.copy(doc = g.copy(widthDelta = it.roundToInt()))) }
            LabeledSlider("Chroma X", g.chromaX.toFloat(), -64f..64f, "", format = { "${it.roundToInt()}" }) { state.setFilter(i, "$key.c", f.copy(doc = g.copy(chromaX = it.roundToInt()))) }
            LabeledSlider("JPEG gens", g.generations.toFloat(), 0f..30f, "", format = { "${it.roundToInt()}" }) { state.setFilter(i, "$key.g", f.copy(doc = g.copy(generations = it.roundToInt()))) }
        }
        LayerFilter.Invert, LayerFilter.Desaturate -> Unit
    }
}

/** What can be changed about the layer's own content. */
@Composable
private fun SourceControls(state: ComposeState, l: ComposeLayer, onEditText: (ComposeLayer) -> Unit) {
    when (val src = l.source) {
        is CanvasLayerSource -> CanvasSourceControls(state, src)
        is SolidLayerSource -> {
            Text("Fill colour", style = MaterialTheme.typography.labelLarge)
            ColorPicker(src.argb) { c -> state.update("solid") { it.copy(source = src.copy(argb = c)) } }
        }
        is GradientLayerSource -> {
            Text("Gradient", style = MaterialTheme.typography.labelLarge)
            ScrollRow {
                FilterChip(selected = !src.radial, onClick = { state.update { it.copy(source = src.copy(radial = false)) } }, label = { Text("Linear") })
                FilterChip(selected = src.radial, onClick = { state.update { it.copy(source = src.copy(radial = true)) } }, label = { Text("Radial") })
                TextButton(onClick = { state.update { it.copy(source = src.copy(fromArgb = src.toArgb, toArgb = src.fromArgb)) } }) { Text("Swap colours") }
            }
            if (!src.radial) LabeledSlider("Angle", src.angleDeg, -180f..180f, "°") { v -> state.update("gangle") { it.copy(source = src.copy(angleDeg = v)) } }
            Text("From", style = MaterialTheme.typography.labelMedium)
            ColorPicker(src.fromArgb) { c -> state.update("gfrom") { it.copy(source = src.copy(fromArgb = c)) } }
            Text("To", style = MaterialTheme.typography.labelMedium)
            ColorPicker(src.toArgb) { c -> state.update("gto") { it.copy(source = src.copy(toArgb = c)) } }
        }
        is TextLayerSource -> {
            ScrollRow { TextButton(onClick = { onEditText(l) }) { Text("Edit text…") } }
            ColorPicker(src.argb) { c -> state.update("tcolor") { it.copy(source = src.copy(argb = c)) } }
        }
        is PaintLayerSource -> ScrollRow {
            Text("${src.strokes.size} strokes", style = MaterialTheme.typography.labelMedium)
            TextButton(onClick = { state.tool = ComposeTool.Paint }) { Text("Brush") }
            TextButton(onClick = { state.tool = ComposeTool.Erase }) { Text("Eraser") }
            TextButton(onClick = { state.update { it.copy(source = src.copy(strokes = src.strokes.dropLast(1))) } }, enabled = src.strokes.isNotEmpty()) { Text("Remove last stroke") }
        }
        is AssetLayerSource -> Unit
    }
}

@Composable
private fun TextDialog(initial: String, size: Float, bold: Boolean, onDismiss: () -> Unit, onDone: (String, Float, Boolean) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    var px by remember { mutableStateOf(size) }
    var b by remember { mutableStateOf(bold) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Text") },
        text = {
            Column {
                androidx.compose.material3.OutlinedTextField(text, { text = it }, label = { Text("Text (new line = Enter)") })
                LabeledSlider("Size", px, 8f..2000f, " px") { px = it }
                FilterChip(selected = b, onClick = { b = !b }, label = { Text("Bold") })
            }
        },
        confirmButton = { TextButton(onClick = { onDone(text, px, b) }, enabled = text.isNotBlank()) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun NameDialog(initial: String, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Layer name") },
        text = { androidx.compose.material3.OutlinedTextField(text, { text = it }, singleLine = true) },
        confirmButton = { TextButton(onClick = { onDone(text) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Paper for canvas layers, and the main parameters of a single generator. */
@Composable
private fun CanvasSourceControls(state: ComposeState, src: CanvasLayerSource) {
    ScrollRow {
        FilterChip(selected = src.paper, onClick = { state.update { it.copy(source = src.copy(paper = !src.paper)) } }, label = { Text("White paper") })
    }
    val only = src.doc.layers.singleOrNull() ?: return
    fun set(key: String, f: (Layer) -> Layer) = state.update(key) { it.copy(source = src.copy(doc = src.doc.copy(layers = listOf(f(only))))) }
    when (val c = only.content) {
        is Grating -> {
            LabeledSlider("Spacing", c.spacingPx, 2f..80f, " px") { v -> set("gs") { it.copy(content = c.copy(spacingPx = v)) } }
            LabeledSlider("Angle", c.angleDeg, -180f..180f, "°") { v -> set("ga") { it.copy(content = c.copy(angleDeg = v)) } }
            LabeledSlider("Line width", c.duty, 0.05f..0.95f, "") { v -> set("gd") { it.copy(content = c.copy(duty = v)) } }
            LabeledSlider("Offset", c.offsetPx.mod(c.spacingPx), 0f..c.spacingPx, " px") { v -> set("go") { it.copy(content = c.copy(offsetPx = v)) } }
        }
        is RadialBurst -> {
            LabeledSlider("Rays", c.rays.toFloat(), 8f..720f, "", format = { "${it.roundToInt()}" }) { v -> set("br") { it.copy(content = c.copy(rays = v.roundToInt())) } }
            LabeledSlider("Rotation", c.rotationDeg, -180f..180f, "°") { v -> set("bo") { it.copy(content = c.copy(rotationDeg = v)) } }
        }
        is EllipseHatch -> {
            LabeledSlider("Spacing", c.spacingPx, 2f..80f, " px") { v -> set("es") { it.copy(content = c.copy(spacingPx = v)) } }
            LabeledSlider("Aspect", c.aspect, 0.1f..3f, "") { v -> set("ea") { it.copy(content = c.copy(aspect = v)) } }
            LabeledSlider("Rotation", c.rotationDeg, -180f..180f, "°") { v -> set("er") { it.copy(content = c.copy(rotationDeg = v)) } }
        }
        else -> Unit
    }
}

@Composable
private fun DocumentControls(state: ComposeState, doc: ComposeDoc, screen: Pair<Int, Int>) {
    Text("Document", style = MaterialTheme.typography.labelLarge)
    LabeledSlider("Width", doc.widthPx.toFloat(), 64f..8192f, " px", format = { "${it.roundToInt()}" }) { v ->
        state.edit.edit("docw") { it.copy(widthPx = v.roundToInt()) }
    }
    LabeledSlider("Height", doc.heightPx.toFloat(), 64f..8192f, " px", format = { "${it.roundToInt()}" }) { v ->
        state.edit.edit("doch") { it.copy(heightPx = v.roundToInt()) }
    }
    ScrollRow {
        state.selected?.let { l ->
            TextButton(onClick = {
                // Document = this layer's size, with the layer filling it 1:1.
                state.edit.edit { d -> d.copy(widthPx = l.sourceWidth, heightPx = l.sourceHeight) }
                state.update { it.copy(transform = it.transform.copy(tx = l.sourceWidth / 2f, ty = l.sourceHeight / 2f, sx = 1f, sy = 1f, rotationDeg = 0f)) }
            }) { Text("Size of layer") }
        }
        TextButton(onClick = { state.edit.edit { it.copy(widthPx = screen.first, heightPx = screen.second) } }) { Text("Screen ${screen.first}×${screen.second}") }
    }
    ScrollRow {
        Text("Background", style = MaterialTheme.typography.labelMedium)
        Background.entries.forEach { b -> FilterChip(selected = doc.background == b, onClick = { state.edit.edit { it.copy(background = b) } }, label = { Text(b.name) }) }
    }
}

/** Full-size render for Save; transparent or line-art composites stay PNG, photo composites JPEG. */
fun composeOutputIsPng(doc: ComposeDoc, lineage: Lineage): Boolean =
    doc.background == Background.Transparent || doc.layers.none { (it.source as? AssetLayerSource)?.let { s -> lineage[s.assetId]?.file?.endsWith(".png") == false } == true }
