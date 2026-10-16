package dk.cbavf.studio.develop

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dk.cbavf.studio.develop.model.BandAdjust
import dk.cbavf.studio.develop.model.ColorMath
import dk.cbavf.studio.develop.model.CurvePoint
import dk.cbavf.studio.develop.model.Dab
import dk.cbavf.studio.develop.model.HueBand
import dk.cbavf.studio.develop.model.Lut
import dk.cbavf.studio.develop.model.MaskKind
import dk.cbavf.studio.develop.model.Preset
import dk.cbavf.studio.library.Lineage
import dk.cbavf.studio.library.ProjectStore
import dk.cbavf.studio.ui.LabeledSlider
import dk.cbavf.studio.ui.LibraryPicker
import dk.cbavf.studio.ui.ScrollRow
import dk.cbavf.studio.ui.renderSafely
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.math.hypot

@Composable
fun DevelopRoom(
    state: DevelopState,
    store: ProjectStore,
    lineage: Lineage,
    busy: Boolean,
    onSave: () -> Unit,
    onSendToGallery: (String) -> Unit,
    onMessage: (String) -> Unit,
) {
    var picking by remember { mutableStateOf<String?>(null) } // "source" or "style"
    picking?.let { what ->
        LibraryPicker(store, lineage, single = true) { ids ->
            picking = null
            val id = ids.firstOrNull() ?: return@LibraryPicker
            if (what == "source") lineage[id]?.let { state.open(it) } else state.edit { it.copy(styleAssetId = id, styleAmount = it.styleAmount.takeIf { a -> a > 0f } ?: 0.5f) }
        }
        return
    }

    val doc = state.doc
    val source by produceState<Bitmap?>(null, doc.sourceAssetId, lineage) {
        value = withContext(Dispatchers.IO) { doc.sourceAssetId?.let { lineage[it] }?.let { decodeAsset(store, it, DevelopState.PREVIEW_PX) } }
    }
    val style by produceState<Bitmap?>(null, doc.styleAssetId) {
        value = withContext(Dispatchers.IO) { doc.styleAssetId?.let { lineage[it] }?.let { decodeAsset(store, it, DevelopState.PREVIEW_PX) } }
    }
    val cube by produceState<Lut?>(null, doc.lutFile) { value = withContext(Dispatchers.IO) { loadCube(store, doc) } }
    var result by remember { mutableStateOf<ImageBitmap?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    // Re-render the preview after edits settle; a newer edit cancels the render in flight.
    LaunchedEffect(doc, source, style, cube) {
        val src = source ?: return@LaunchedEffect
        delay(40)
        renderSafely({ error = it }) { renderDevelop(src, doc, Lut.build(doc), cube, style) { ensureActive() }.asImageBitmap() }?.let {
            result = it
            error = null
        }
    }
    var before by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            val src = source
            if (doc.sourceAssetId == null || src == null) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Pick a Library image to develop.", style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { picking = "source" }) { Text("Pick image") }
                }
            } else {
                Box(Modifier.aspectRatio(src.width.toFloat() / src.height)) {
                    val shown = if (before) src.asImageBitmap() else result ?: src.asImageBitmap()
                    Image(shown, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                    if (state.tab == DevelopTab.Mask) MaskLayer(state, src)
                }
            }
            Surface(
                Modifier.align(Alignment.TopStart).padding(8.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                shape = MaterialTheme.shapes.small,
            ) {
                Text(if (before) "Before" else error ?: doc.summary, Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium)
            }
        }
        Surface(tonalElevation = 2.dp) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                ScrollRow {
                    Button(onClick = onSave, enabled = doc.sourceAssetId != null && !busy) { Text(if (busy) "Saving…" else "Save") }
                    // Hold to see the original.
                    Box(
                        Modifier.padding(horizontal = 8.dp).pointerInput(Unit) {
                            awaitEachGesture {
                                awaitFirstDown()
                                before = true
                                do { val e = awaitPointerEvent() } while (e.changes.any { it.pressed })
                                before = false
                            }
                        }
                    ) { Text("Hold: before", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) }
                    TextButton(onClick = state::undo, enabled = state.canUndo) { Text("Undo") }
                    TextButton(onClick = state::reset) { Text("Reset") }
                    TextButton(onClick = { picking = "source" }) { Text("Image…") }
                }
                ScrollRow {
                    DevelopTab.entries.forEach { t -> FilterChip(selected = state.tab == t, onClick = { state.tab = t }, label = { Text(t.name) }) }
                }
                HorizontalDivider()
                Column(Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState())) {
                    when (state.tab) {
                        DevelopTab.Light -> LightTab(state)
                        DevelopTab.Color -> ColorTab(state)
                        DevelopTab.Mixer -> MixerTab(state)
                        DevelopTab.Curve -> CurveTab(state)
                        DevelopTab.Lut -> LutTab(state, store, onMessage)
                        DevelopTab.Mask -> MaskTab(state)
                        DevelopTab.Style -> StyleTab(state, lineage, onPick = { picking = "style" }, onSendToGallery)
                        DevelopTab.Presets -> PresetsTab(state, store)
                    }
                }
            }
        }
    }
}

@Composable
private fun LightTab(state: DevelopState) {
    val d = state.doc
    LabeledSlider("Exposure", d.exposureEv, -3f..3f, " EV") { v -> state.edit("ev") { it.copy(exposureEv = v) } }
    LabeledSlider("Black", d.black, 0f..0.5f, "") { v -> state.edit("black") { it.copy(black = v) } }
    LabeledSlider("White", d.white, 0.5f..1f, "") { v -> state.edit("white") { it.copy(white = v) } }
    LabeledSlider("Mid-tones", d.gamma, 0.3f..3f, "") { v -> state.edit("gamma") { it.copy(gamma = v) } }
}

@Composable
private fun ColorTab(state: DevelopState) {
    val d = state.doc
    LabeledSlider("Temperature", d.temperature, -1f..1f, "") { v -> state.edit("temp") { it.copy(temperature = v) } }
    LabeledSlider("Tint", d.tint, -1f..1f, "") { v -> state.edit("tint") { it.copy(tint = v) } }
}

@Composable
private fun MixerTab(state: DevelopState) {
    var band by remember { mutableStateOf(HueBand.Red) }
    ScrollRow {
        HueBand.entries.forEach { b ->
            val rgb = FloatArray(3).also { ColorMath.hslToRgb(b.centerDeg, 0.8f, 0.5f, it) }
            FilterChip(
                selected = band == b,
                onClick = { band = b },
                label = { Text(b.name) },
                leadingIcon = { Box(Modifier.size(12.dp).background(Color(rgb[0], rgb[1], rgb[2]))) },
            )
        }
    }
    val adj = state.doc.bands[band] ?: BandAdjust()
    fun set(key: String, f: (BandAdjust) -> BandAdjust) = state.edit("$band.$key") { it.copy(bands = it.bands + (band to f(adj))) }
    LabeledSlider("Hue", adj.hue, -45f..45f, "°") { v -> set("h") { it.copy(hue = v) } }
    LabeledSlider("Saturation", adj.saturation, -1f..1f, "") { v -> set("s") { it.copy(saturation = v) } }
    LabeledSlider("Lightness", adj.lightness, -1f..1f, "") { v -> set("l") { it.copy(lightness = v) } }
}

/** Tone curve: drag points, tap to add, long-press to remove (the ends stay). */
@Composable
private fun CurveTab(state: DevelopState) {
    val points = state.doc.curve.sortedBy { it.x }
    var dragging by remember { mutableStateOf<Int?>(null) }
    // The point the number fields below edit: the last one touched.
    var editingPoint by remember { mutableStateOf(0) }
    val line = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    Text("Drag points · tap to add · long-press to remove", style = MaterialTheme.typography.labelMedium)
    Canvas(
        Modifier
            .padding(vertical = 8.dp)
            .size(220.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .pointerInput(points) {
                detectTapGestures(
                    onTap = { o ->
                        val p = CurvePoint((o.x / size.width).coerceIn(0f, 1f), (1 - o.y / size.height).coerceIn(0f, 1f))
                        state.edit { it.copy(curve = (it.curve + p).sortedBy { c -> c.x }) }
                    },
                    onLongPress = { o ->
                        val i = nearest(points, o, size.width.toFloat(), size.height.toFloat()) ?: return@detectTapGestures
                        if (i != 0 && i != points.lastIndex) state.edit { it.copy(curve = points.filterIndexed { j, _ -> j != i }) }
                    },
                )
            }
            .pointerInput(points) {
                detectDragGestures(
                    onDragStart = { o ->
                        dragging = nearest(points, o, size.width.toFloat(), size.height.toFloat())
                        dragging?.let { editingPoint = it }
                    },
                    onDragEnd = { dragging = null },
                    onDrag = { change, _ ->
                        val i = dragging ?: return@detectDragGestures
                        val pts = state.doc.curve.sortedBy { it.x }.toMutableList()
                        if (i !in pts.indices) return@detectDragGestures
                        val lo = if (i == 0) 0f else pts[i - 1].x + 0.01f
                        val hi = if (i == pts.lastIndex) 1f else pts[i + 1].x - 0.01f
                        // The end points slide only up and down.
                        val x = if (i == 0) 0f else if (i == pts.lastIndex) 1f else (change.position.x / size.width).coerceIn(lo, hi)
                        pts[i] = CurvePoint(x, (1 - change.position.y / size.height).coerceIn(0f, 1f))
                        state.edit("curve$i") { it.copy(curve = pts) }
                    },
                )
            }
    ) {
        for (k in 1..3) {
            drawLine(grid, Offset(size.width * k / 4, 0f), Offset(size.width * k / 4, size.height))
            drawLine(grid, Offset(0f, size.height * k / 4), Offset(size.width, size.height * k / 4))
        }
        val curve = ColorMath.ToneCurve(points)
        val path = Path()
        for (i in 0..100) {
            val x = i / 100f
            val o = Offset(x * size.width, (1 - curve(x)) * size.height)
            if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y)
        }
        drawPath(path, line, style = Stroke(width = 3f))
        points.forEach { drawCircle(line, 8f, Offset(it.x * size.width, (1 - it.y) * size.height)) }
    }
    // Exact values for the last point touched (the ends stay at x 0 and 1).
    val i = editingPoint.coerceIn(0, points.lastIndex)
    val p = points[i]
    Text("Point ${i + 1} of ${points.size}", style = MaterialTheme.typography.labelMedium)
    if (i != 0 && i != points.lastIndex) {
        val lo = points[i - 1].x + 0.01f
        val hi = points[i + 1].x - 0.01f
        if (hi > lo) LabeledSlider("Input", p.x, lo..hi, "") { v ->
            state.edit("curve$i") { d -> d.copy(curve = points.mapIndexed { j, q -> if (j == i) q.copy(x = v) else q }) }
        }
    }
    LabeledSlider("Output", p.y, 0f..1f, "") { v ->
        state.edit("curve$i") { d -> d.copy(curve = points.mapIndexed { j, q -> if (j == i) q.copy(y = v) else q }) }
    }
    ScrollRow {
        TextButton(onClick = { editingPoint = (i - 1).coerceAtLeast(0) }, enabled = i > 0) { Text("◀ Point") }
        TextButton(onClick = { editingPoint = (i + 1).coerceAtMost(points.lastIndex) }, enabled = i < points.lastIndex) { Text("Point ▶") }
        TextButton(onClick = { state.edit { it.copy(curve = listOf(CurvePoint(0f, 0f), CurvePoint(1f, 1f))) } }) { Text("Straighten") }
    }
}

private fun nearest(points: List<CurvePoint>, o: Offset, w: Float, h: Float): Int? =
    points.indices.minByOrNull { hypot(points[it].x * w - o.x, (1 - points[it].y) * h - o.y) }
        ?.takeIf { hypot(points[it].x * w - o.x, (1 - points[it].y) * h - o.y) < 60f }

@Composable
private fun LutTab(state: DevelopState, store: ProjectStore, onMessage: (String) -> Unit) {
    val context = LocalContext.current
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            val text = context.contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() }
            Lut.parseCube(text) // validate before keeping it
            val name = uri.lastPathSegment?.substringAfterLast('/') ?: "lut.cube"
            val path = store.addLut(name, text)
            state.edit { it.copy(lutFile = path, lutAmount = 1f) }
        }.onFailure { onMessage("Not a usable .cube file: ${it.message}") }
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.openOutputStream(uri)!!.use { it.write(Lut.build(state.doc).toCube("Moire Studio look").toByteArray()) }
            onMessage("Look exported as .cube")
        }.onFailure { onMessage("Export failed: ${it.message}") }
    }
    val d = state.doc
    Text(d.lutFile?.substringAfterLast('/')?.let { "LUT: $it" } ?: "No LUT imported", style = MaterialTheme.typography.labelMedium)
    ScrollRow {
        TextButton(onClick = { import.launch(arrayOf("*/*")) }) { Text("Import .cube…") }
        if (d.lutFile != null) TextButton(onClick = { state.edit { it.copy(lutFile = null) } }) { Text("Remove") }
        TextButton(onClick = { export.launch("studio-look.cube") }) { Text("Export look as .cube…") }
    }
    if (d.lutFile != null) LabeledSlider("Amount", d.lutAmount, 0f..1f, "") { v -> state.edit("lut") { it.copy(lutAmount = v) } }
    Text(
        "The export holds every global adjustment (light, colour, mixer, curve), not the LUT, mask or style.",
        style = MaterialTheme.typography.labelSmall,
    )
}

@Composable
private fun MaskTab(state: DevelopState) {
    val m = state.doc.mask
    ScrollRow {
        MaskKind.entries.forEach { k ->
            FilterChip(selected = m.kind == k, onClick = { state.edit { it.copy(mask = it.mask.copy(kind = k)) } }, label = { Text(k.name) })
        }
        FilterChip(selected = m.invert, onClick = { state.edit { it.copy(mask = it.mask.copy(invert = !it.mask.invert)) } }, label = { Text("Invert") })
        TextButton(onClick = { state.edit { it.copy(mask = it.mask.copy(lasso = emptyList(), dabs = emptyList())) } }) { Text("Clear") }
    }
    Text(
        when (m.kind) {
            MaskKind.None -> "The edit applies everywhere."
            MaskKind.Lasso -> "Draw around the area on the image."
            MaskKind.Brush -> "Paint the area on the image."
            MaskKind.Spot -> "Tap a colour on the image; similar hues are edited."
        },
        style = MaterialTheme.typography.labelMedium,
    )
    when (m.kind) {
        MaskKind.Spot -> {
            LabeledSlider("Range", m.spotRange, 2f..90f, "°") { v -> state.edit("spot") { it.copy(mask = it.mask.copy(spotRange = v)) } }
            LabeledSlider("Soft edge", m.feather, 0f..0.2f, "") { v -> state.edit("feather") { it.copy(mask = it.mask.copy(feather = v)) } }
        }
        MaskKind.Lasso, MaskKind.Brush -> {
            LabeledSlider("Soft edge", m.feather, 0f..0.1f, "") { v -> state.edit("feather") { it.copy(mask = it.mask.copy(feather = v)) } }
            if (m.kind == MaskKind.Brush) LabeledSlider("Brush", brushSize.floatValue, 0.01f..0.2f, "") { brushSize.floatValue = it }
        }
        MaskKind.None -> Unit
    }
}

private val brushSize = mutableFloatStateOf(0.05f)

/** Draws the mask outline over the image and takes lasso, brush and spot input. */
@Composable
private fun MaskLayer(state: DevelopState, source: Bitmap) {
    val m = state.doc.mask
    val outline = MaterialTheme.colorScheme.primary
    var lasso by remember { mutableStateOf<List<CurvePoint>?>(null) }
    var stroke by remember { mutableIntStateOf(0) }
    Canvas(
        Modifier
            .fillMaxSize()
            .pointerInput(m.kind) {
                when (m.kind) {
                    MaskKind.Spot -> detectTapGestures { o ->
                        val x = (o.x / size.width * source.width).toInt().coerceIn(0, source.width - 1)
                        val y = (o.y / size.height * source.height).toInt().coerceIn(0, source.height - 1)
                        val c = source.getPixel(x, y)
                        val hsl = FloatArray(3)
                        ColorMath.rgbToHsl((c shr 16 and 0xFF) / 255f, (c shr 8 and 0xFF) / 255f, (c and 0xFF) / 255f, hsl)
                        state.edit { it.copy(mask = it.mask.copy(spotHue = hsl[0])) }
                    }
                    MaskKind.Lasso -> detectDragGestures(
                        onDragStart = { o -> lasso = listOf(CurvePoint(o.x / size.width, o.y / size.height)) },
                        onDragEnd = {
                            lasso?.takeIf { it.size >= 3 }?.let { pts -> state.edit { it.copy(mask = it.mask.copy(lasso = pts)) } }
                            lasso = null
                        },
                        onDrag = { change, _ -> lasso = lasso.orEmpty() + CurvePoint(change.position.x / size.width, change.position.y / size.height) },
                    )
                    MaskKind.Brush -> detectDragGestures(
                        onDragStart = { stroke++ },
                        onDrag = { change, _ ->
                            val dab = Dab(change.position.x / size.width, change.position.y / size.height, brushSize.floatValue)
                            // Dabs a third of a radius apart are enough for a solid stroke.
                            val last = state.doc.mask.dabs.lastOrNull()
                            if (last == null || hypot(last.x - dab.x, (last.y - dab.y) * size.height / size.width) > dab.radius / 3) {
                                state.edit("brush$stroke") { it.copy(mask = it.mask.copy(dabs = it.mask.dabs + dab)) }
                            }
                        },
                    )
                    MaskKind.None -> Unit
                }
            }
    ) {
        val pts = lasso ?: m.lasso.takeIf { m.kind == MaskKind.Lasso }
        if (pts != null && pts.size >= 2) {
            val path = Path().apply {
                moveTo(pts[0].x * size.width, pts[0].y * size.height)
                pts.drop(1).forEach { lineTo(it.x * size.width, it.y * size.height) }
                if (lasso == null) close()
            }
            drawPath(path, outline, style = Stroke(width = 3f))
        }
        if (m.kind == MaskKind.Brush) m.dabs.forEach { drawCircle(outline.copy(alpha = 0.25f), it.radius * size.width, Offset(it.x * size.width, it.y * size.height)) }
    }
}

@Composable
private fun StyleTab(state: DevelopState, lineage: Lineage, onPick: () -> Unit, onSendToGallery: (String) -> Unit) {
    val d = state.doc
    Text(
        "Mix in another image, e.g. what Samsung Gallery's AI Style made of this one: send it there, apply a style, " +
            "save, import it from the Bridge inbox, then pick it here.",
        style = MaterialTheme.typography.labelMedium,
    )
    ScrollRow {
        d.sourceAssetId?.let { id -> TextButton(onClick = { onSendToGallery(id) }) { Text("Send to Samsung Gallery") } }
        TextButton(onClick = onPick) { Text(if (d.styleAssetId == null) "Pick style image…" else "Change style image…") }
        if (d.styleAssetId != null) TextButton(onClick = { state.edit { it.copy(styleAssetId = null, styleAmount = 0f) } }) { Text("Remove") }
    }
    d.styleAssetId?.let { id ->
        Text("Style: g${lineage.generation(id)} ${id.takeLast(6)}", style = MaterialTheme.typography.labelMedium)
        LabeledSlider("Amount", d.styleAmount * 100, 0f..100f, "", format = { "${it.toInt()}" }) { v -> state.edit("style") { it.copy(styleAmount = v / 100) } }
    }
}

@Composable
private fun PresetsTab(state: DevelopState, store: ProjectStore) {
    ScrollRow {
        state.presets.forEach { p ->
            FilterChip(selected = false, onClick = { state.edit { it.withPreset(p.look) } }, label = { Text(p.name) })
        }
        if (state.presets.isEmpty()) Text("No presets yet.", style = MaterialTheme.typography.labelMedium)
    }
    ScrollRow {
        TextButton(onClick = {
            state.presets = state.presets + Preset("Look ${state.presets.size + 1}", state.doc.asPreset())
            store.savePresets(state.presets)
        }) { Text("Save current look as preset") }
        if (state.presets.isNotEmpty()) TextButton(onClick = {
            state.presets = state.presets.dropLast(1)
            store.savePresets(state.presets)
        }) { Text("Delete last") }
    }
    Text("A preset keeps light, colour, mixer, curve and LUT; not the mask or the style image.", style = MaterialTheme.typography.labelSmall)
}
