package dk.cbavf.studio.develop

import dk.cbavf.studio.ui.HistoryPanel
import dk.cbavf.studio.ui.layout.panelScroll
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import dk.cbavf.studio.abcompare.abHere
import dk.cbavf.studio.channels.ChannelValue
import dk.cbavf.studio.channels.ColorSpace
import dk.cbavf.studio.develop.model.ArtStyle
import dk.cbavf.studio.develop.model.ChannelMix
import dk.cbavf.studio.develop.model.ArtStyles
import dk.cbavf.studio.develop.model.EditBlend
import dk.cbavf.studio.develop.model.MaskCombine
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
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dk.cbavf.studio.develop.model.BandAdjust
import dk.cbavf.studio.develop.model.ColorMath
import dk.cbavf.studio.develop.model.CurvePoint
import dk.cbavf.studio.develop.model.Dab
import dk.cbavf.studio.develop.model.DevelopDoc
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
import kotlinx.coroutines.launch
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
    /** What the rack cable into Develop carries, if one is plugged in: it replaces the picked image. */
    routed: Bitmap? = null,
    /** Where that cable comes from, and how to unplug it. */
    liveFrom: String? = null,
    onUnplug: () -> Unit = {},
) {
    var picking by remember { mutableStateOf<String?>(null) } // "source" or "style"
    picking?.let { what ->
        LibraryPicker(store, lineage, single = true) { ids ->
            picking = null
            val id = ids.firstOrNull() ?: return@LibraryPicker
            if (what == "source") lineage[id]?.let { onUnplug(); state.open(it) } else state.edit { it.copy(styleAssetId = id, styleAmount = it.styleAmount.takeIf { a -> a > 0f } ?: 0.5f) }
        }
        return
    }

    val doc = state.doc
    val fileSource by produceState<Bitmap?>(null, doc.sourceAssetId, lineage) {
        value = withContext(Dispatchers.IO) { doc.sourceAssetId?.let { lineage[it] }?.let { decodeAsset(store, it, DevelopState.PREVIEW_PX) } }
    }
    val source = routed ?: fileSource
    val style by produceState<Bitmap?>(null, doc.styleAssetId) {
        value = withContext(Dispatchers.IO) { doc.styleAssetId?.let { lineage[it] }?.let { decodeAsset(store, it, DevelopState.PREVIEW_PX) } }
    }
    val cube by produceState<Lut?>(null, doc.lutFile) { value = withContext(Dispatchers.IO) { loadCube(store, doc) } }
    var result by remember { mutableStateOf<ImageBitmap?>(null) }
    // The developed image without an artistic style: what the style thumbnails show.
    var styleBase by remember { mutableStateOf<Bitmap?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    // Re-render the preview after edits settle; a newer edit cancels the render in flight.
    LaunchedEffect(doc, source, style, cube) {
        val src = source ?: return@LaunchedEffect
        delay(40)
        renderSafely({ error = it }) { renderDevelop(src, doc, Lut.build(doc), cube, style) { ensureActive() }.asImageBitmap() }?.let {
            result = it
            error = null
            if (doc.art == null) styleBase = it.asAndroidBitmap()
        }
    }
    var before by remember { mutableStateOf(false) }
    // Scopes of what is shown, worked out off the main thread whenever it changes.
    val scopeData by produceState<ScopeData?>(null, result, state.scope) {
        val shown = result ?: return@produceState
        if (state.scope == ScopeKind.Off) return@produceState
        value = withContext(Dispatchers.Default) { ScopeData.of(shown.asAndroidBitmap()) }
    }

    dk.cbavf.studio.ui.layout.RoomSplit(canvas = {
        Box(Modifier.fillMaxSize().abHere(), contentAlignment = Alignment.Center) {
            val src = source
            if ((doc.sourceAssetId == null && routed == null) || src == null) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Pick a Library image to develop.", style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { picking = "source" }) { Text("Pick image") }
                }
            } else {
                Box(Modifier.aspectRatio(src.width.toFloat() / src.height)) {
                    val shown = if (before) src.asImageBitmap() else result ?: src.asImageBitmap()
                    Image(shown, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                    if (state.tab == DevelopTab.Mask) MaskOverlay(state, src)
                }
            }
            Surface(
                Modifier.align(Alignment.TopStart).padding(8.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                shape = MaterialTheme.shapes.small,
            ) {
                Text((if (routed != null) "Live from ${liveFrom ?: "a cable"} · " else "") + if (before) "Before" else error ?: doc.summary, Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium)
            }
            ScopePanel(state.scope, scopeData, Modifier.align(Alignment.TopEnd).padding(8.dp))
            // A/B: the developed image against B; the input view shows the source it was made from.
            dk.cbavf.studio.abcompare.AbLayer(result, source?.asImageBitmap(), if (routed != null) "routed source" else "Library image")
            // The last step of the scope cycle: deep analysis, expanding from the scope's corner over the canvas.
            androidx.compose.animation.AnimatedVisibility(
                visible = state.scope == ScopeKind.Deep && result != null,
                modifier = Modifier.matchParentSize(),
                enter = androidx.compose.animation.scaleIn(transformOrigin = androidx.compose.ui.graphics.TransformOrigin(1f, 0f)) + androidx.compose.animation.fadeIn(),
                exit = androidx.compose.animation.scaleOut(transformOrigin = androidx.compose.ui.graphics.TransformOrigin(1f, 0f)) + androidx.compose.animation.fadeOut(),
            ) {
                result?.let { r -> DeepAnalysis(r.asAndroidBitmap(), onClose = { state.scope = ScopeKind.Off }, modifier = Modifier.fillMaxSize().padding(6.dp)) }
            }
        }
    }, controls = {
        var showHistory by remember { mutableStateOf(false) }
        if (showHistory) HistoryPanel("Develop", state) { showHistory = false }
        Surface(tonalElevation = 2.dp) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                ScrollRow {
                    Button(onClick = onSave, enabled = (doc.sourceAssetId != null || routed != null) && !busy) { Text(if (busy) "Saving…" else "Save") }
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
                    TextButton(onClick = state::redo, enabled = state.canRedo) { Text("Redo") }
                    TextButton(onClick = { showHistory = true }, enabled = state.canUndo || state.canRedo) { Text("History") }
                    TextButton(onClick = state::reset) { Text("Reset") }
                    dk.cbavf.studio.abcompare.LocalAb.current?.let { abc -> TextButton(onClick = { abc.state.snapshot = result }, enabled = result != null) { Text("Snap B") } }
                    TextButton(onClick = { picking = "source" }) { Text("Image…") }
                    dk.cbavf.studio.ui.LiveInputButton(liveFrom, onUnplug)
                    // Off → histogram → vectorscope.
                    FilterChip(
                        selected = state.scope != ScopeKind.Off,
                        onClick = { state.scope = ScopeKind.entries[(state.scope.ordinal + 1) % ScopeKind.entries.size] },
                        label = { Text(state.scope.label) },
                    )
                }
                ScrollRow {
                    DevelopTab.entries.forEach { t -> FilterChip(selected = state.tab == t, onClick = { state.tab = t }, label = { Text(t.name) }) }
                }
                HorizontalDivider()
                Column(Modifier.panelScroll(280.dp)) {
                    when (state.tab) {
                        DevelopTab.Light -> LightControls(doc, state::edit)
                        DevelopTab.Color -> ColorControls(doc, state::edit)
                        DevelopTab.Mixer -> MixerControls(doc, state::edit)
                        DevelopTab.Curve -> CurveControls(doc, state::edit)
                        DevelopTab.Lut -> LutControls(doc, state::edit, store, onMessage)
                        DevelopTab.Mask -> MaskTab(state)
                        DevelopTab.Style -> StyleTab(state, lineage, styleBase ?: source, onPick = { picking = "style" }, onSendToGallery)
                        DevelopTab.Presets -> PresetsTab(state, store)
                    }
                }
            }
        }
    })
}

/**
 * Where a Develop control sends a change: [key] groups one slider drag into one undo step.
 * The Develop room passes its own history; Compose passes a layer's adjustments.
 */
typealias DevelopEdit = (key: Any?, f: (DevelopDoc) -> DevelopDoc) -> Unit

@Composable
fun LightControls(d: DevelopDoc, edit: DevelopEdit) {
    LabeledSlider("Exposure", d.exposureEv, -3f..3f, " EV") { v -> edit("ev") { it.copy(exposureEv = v) } }
    LabeledSlider("Black", d.black, 0f..0.5f, "") { v -> edit("black") { it.copy(black = v) } }
    LabeledSlider("White", d.white, 0.5f..1f, "") { v -> edit("white") { it.copy(white = v) } }
    LabeledSlider("Mid-tones", d.gamma, 0.3f..3f, "") { v -> edit("gamma") { it.copy(gamma = v) } }
}

@Composable
fun ColorControls(d: DevelopDoc, edit: DevelopEdit) {
    LabeledSlider("Temperature", d.temperature, -1f..1f, "") { v -> edit("temp") { it.copy(temperature = v) } }
    LabeledSlider("Tint", d.tint, -1f..1f, "") { v -> edit("tint") { it.copy(tint = v) } }
}

@Composable
fun MixerControls(doc: DevelopDoc, edit: DevelopEdit) {
    var channelMode by remember { mutableStateOf(false) }
    ScrollRow {
        FilterChip(selected = !channelMode, onClick = { channelMode = false }, label = { Text("Hue bands") })
        FilterChip(selected = channelMode, onClick = { channelMode = true }, label = { Text("Channels") })
    }
    if (channelMode) { ChannelMixer(doc, edit); return }
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
    val adj = doc.bands[band] ?: BandAdjust()
    fun set(key: String, f: (BandAdjust) -> BandAdjust) = edit("$band.$key") { it.copy(bands = it.bands + (band to f(adj))) }
    LabeledSlider("Hue", adj.hue, -45f..45f, "°") { v -> set("h") { it.copy(hue = v) } }
    LabeledSlider("Saturation", adj.saturation, -1f..1f, "") { v -> set("s") { it.copy(saturation = v) } }
    LabeledSlider("Lightness", adj.lightness, -1f..1f, "") { v -> set("l") { it.copy(lightness = v) } }
}

/** The channel mixer: pick RGBA, CMYK or Lab, then gain and offset for each channel of it. */
@Composable
private fun ChannelMixer(doc: DevelopDoc, edit: DevelopEdit) {
    val mix = doc.channels
    ScrollRow {
        ColorSpace.entries.forEach { sp ->
            FilterChip(
                selected = mix.space == sp,
                // The channels mean something else in another space, so the settings start over.
                onClick = { if (mix.space != sp) edit(null) { it.copy(channels = ChannelMix(space = sp)) } },
                label = { Text(sp.name) },
            )
        }
        TextButton(onClick = { edit(null) { it.copy(channels = ChannelMix(space = mix.space)) } }, enabled = !mix.isNeutral) { Text("Reset") }
    }
    for (i in 0 until ChannelValue.colorChannels(mix.space)) {
        val name = mix.space.channelNames[i]
        LabeledSlider("$name gain", mix.gainOf(i), 0f..2f, "×") { v -> edit("cg$i") { it.copy(channels = it.channels.withGain(i, v)) } }
        LabeledSlider("$name offset", mix.offsetOf(i), -0.5f..0.5f, "") { v -> edit("co$i") { it.copy(channels = it.channels.withOffset(i, v)) } }
    }
    Text(
        "Gain multiplies the channel; offset moves it by a fraction of its range. CMYK here is the plain " +
            "formula (no printer profile); Lab is D65. Alpha is not part of Develop.",
        style = MaterialTheme.typography.labelSmall,
    )
}

/** Tone curve: drag points, tap to add, long-press to remove (the ends stay). */
@Composable
fun CurveControls(doc: DevelopDoc, edit: DevelopEdit) {
    // The drag reads the newest curve, not the one the gesture started with.
    val current by androidx.compose.runtime.rememberUpdatedState(doc)
    val send by androidx.compose.runtime.rememberUpdatedState(edit)
    val points = doc.curve.sortedBy { it.x }
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
                        send(null) { it.copy(curve = (it.curve + p).sortedBy { c -> c.x }) }
                    },
                    onLongPress = { o ->
                        val i = nearest(points, o, size.width.toFloat(), size.height.toFloat()) ?: return@detectTapGestures
                        if (i != 0 && i != points.lastIndex) send(null) { it.copy(curve = points.filterIndexed { j, _ -> j != i }) }
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
                        val pts = current.curve.sortedBy { it.x }.toMutableList()
                        if (i !in pts.indices) return@detectDragGestures
                        val lo = if (i == 0) 0f else pts[i - 1].x + 0.01f
                        val hi = if (i == pts.lastIndex) 1f else pts[i + 1].x - 0.01f
                        // The end points slide only up and down.
                        val x = if (i == 0) 0f else if (i == pts.lastIndex) 1f else (change.position.x / size.width).coerceIn(lo, hi)
                        pts[i] = CurvePoint(x, (1 - change.position.y / size.height).coerceIn(0f, 1f))
                        send("curve$i") { it.copy(curve = pts) }
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
            edit("curve$i") { d -> d.copy(curve = points.mapIndexed { j, q -> if (j == i) q.copy(x = v) else q }) }
        }
    }
    LabeledSlider("Output", p.y, 0f..1f, "") { v ->
        edit("curve$i") { d -> d.copy(curve = points.mapIndexed { j, q -> if (j == i) q.copy(y = v) else q }) }
    }
    ScrollRow {
        TextButton(onClick = { editingPoint = (i - 1).coerceAtLeast(0) }, enabled = i > 0) { Text("◀ Point") }
        TextButton(onClick = { editingPoint = (i + 1).coerceAtMost(points.lastIndex) }, enabled = i < points.lastIndex) { Text("Point ▶") }
        TextButton(onClick = { edit(null) { it.copy(curve = listOf(CurvePoint(0f, 0f), CurvePoint(1f, 1f))) } }) { Text("Straighten") }
    }
}

private fun nearest(points: List<CurvePoint>, o: Offset, w: Float, h: Float): Int? =
    points.indices.minByOrNull { hypot(points[it].x * w - o.x, (1 - points[it].y) * h - o.y) }
        ?.takeIf { hypot(points[it].x * w - o.x, (1 - points[it].y) * h - o.y) < 60f }

@Composable
fun LutControls(d: DevelopDoc, edit: DevelopEdit, store: ProjectStore, onMessage: (String) -> Unit) {
    val context = LocalContext.current
    val current by androidx.compose.runtime.rememberUpdatedState(d)
    val send by androidx.compose.runtime.rememberUpdatedState(edit)
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    // Reading, checking and copying a .cube (tens of thousands of lines) happens off the main thread.
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                val path = withContext(Dispatchers.IO) {
                    val text = context.contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() }
                    Lut.parseCube(text) // validate before keeping it
                    val name = uri.lastPathSegment?.substringAfterLast('/') ?: "lut.cube"
                    store.addLut(name, text)
                }
                send(null) { it.copy(lutFile = path, lutAmount = 1f) }
            }.onFailure { onMessage("Not a usable .cube file: ${it.message}") }
        }
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val look = current
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)!!.use { it.write(Lut.build(look).toCube("Moire Studio look").toByteArray()) }
                }
                onMessage("Look exported as .cube")
            }.onFailure { onMessage("Export failed: ${it.message}") }
        }
    }
    Text(d.lutFile?.substringAfterLast('/')?.let { "LUT: $it" } ?: "No LUT imported", style = MaterialTheme.typography.labelMedium)
    ScrollRow {
        TextButton(onClick = { import.launch(arrayOf("*/*")) }) { Text("Import .cube…") }
        if (d.lutFile != null) TextButton(onClick = { edit(null) { it.copy(lutFile = null) } }) { Text("Remove") }
        TextButton(onClick = { export.launch("studio-look.cube") }) { Text("Export look as .cube…") }
    }
    if (d.lutFile != null) LabeledSlider("Amount", d.lutAmount, 0f..1f, "") { v -> edit("lut") { it.copy(lutAmount = v) } }
    Text(
        "The export holds every global adjustment (light, colour, mixer, curve), not the LUT, mask or style.",
        style = MaterialTheme.typography.labelSmall,
    )
}

@Composable
private fun MaskTab(state: DevelopState) {
    val d = state.doc
    // Blending options for the whole edit (darktable's blend modes and opacity).
    Text("Blending", style = MaterialTheme.typography.labelLarge)
    ScrollRow {
        EditBlend.entries.forEach { b ->
            FilterChip(selected = d.blend == b, onClick = { state.edit { it.copy(blend = b) } }, label = { Text(b.label) })
        }
    }
    LabeledSlider("Opacity", d.opacity * 100, 0f..100f, " %", format = { "${it.toInt()}" }) { v -> state.edit("opacity") { it.copy(opacity = v / 100) } }

    Text("Masks", style = MaterialTheme.typography.labelLarge)
    val layers = d.maskLayers
    ScrollRow {
        layers.forEachIndexed { i, l ->
            FilterChip(
                selected = i == state.maskIndex,
                onClick = { state.maskIndex = i },
                label = { Text("${i + 1} ${l.mask.kind.name}" + (if (i > 0) " · ${l.combine.label.lowercase()}" else "") + if (!l.enabled) " (off)" else "") },
            )
        }
        listOf(MaskKind.Lasso, MaskKind.Brush, MaskKind.Spot, MaskKind.Luminance).forEach { k ->
            TextButton(onClick = { state.addMask(k) }) { Text("+ ${k.name}") }
        }
    }
    val layer = state.currentMaskLayer
    if (layer == null) {
        Text("No masks: the edit applies everywhere. Add one to limit it; masks stack and combine.", style = MaterialTheme.typography.labelMedium)
        return
    }
    val m = layer.mask
    ScrollRow {
        if (state.maskIndex > 0) {
            MaskCombine.entries.forEach { c ->
                FilterChip(selected = layer.combine == c, onClick = { state.editMaskLayer { it.copy(combine = c) } }, label = { Text(c.label) })
            }
        }
        FilterChip(selected = m.invert, onClick = { state.editMask { it.copy(invert = !it.invert) } }, label = { Text("Invert") })
        FilterChip(selected = layer.enabled, onClick = { state.editMaskLayer { it.copy(enabled = !it.enabled) } }, label = { Text("On") })
        TextButton(onClick = { state.editMask { it.copy(lasso = emptyList(), dabs = emptyList()) } }) { Text("Clear") }
        TextButton(onClick = state::removeMask) { Text("Delete mask") }
    }
    Text(
        when (m.kind) {
            MaskKind.None -> "Covers everything."
            MaskKind.Lasso -> "Draw around the area on the image."
            MaskKind.Brush -> "Paint the area on the image."
            MaskKind.Spot -> "Tap a colour on the image; similar hues are edited."
            MaskKind.Luminance -> "Tap the image to centre the range on that brightness, or set it below."
        },
        style = MaterialTheme.typography.labelMedium,
    )
    LabeledSlider("Mask opacity", layer.opacity * 100, 0f..100f, " %", format = { "${it.toInt()}" }) { v -> state.editMaskLayer("mopacity${state.maskIndex}") { it.copy(opacity = v / 100) } }
    val key = state.maskIndex
    when (m.kind) {
        MaskKind.Spot -> {
            LabeledSlider("Range", m.spotRange, 2f..90f, "°") { v -> state.editMask("spot$key") { it.copy(spotRange = v) } }
            LabeledSlider("Soft edge", m.feather, 0f..0.2f, "") { v -> state.editMask("feather$key") { it.copy(feather = v) } }
        }
        MaskKind.Lasso, MaskKind.Brush -> {
            LabeledSlider("Soft edge", m.feather, 0f..0.1f, "") { v -> state.editMask("feather$key") { it.copy(feather = v) } }
            if (m.kind == MaskKind.Brush) LabeledSlider("Brush", brushSize.floatValue, 0.01f..0.2f, "") { brushSize.floatValue = it }
        }
        MaskKind.Luminance -> {
            LabeledSlider("From", m.lumaLow * 100, 0f..100f, " %", format = { "${it.toInt()}" }) { v -> state.editMask("llow$key") { it.copy(lumaLow = (v / 100).coerceAtMost(it.lumaHigh)) } }
            LabeledSlider("To", m.lumaHigh * 100, 0f..100f, " %", format = { "${it.toInt()}" }) { v -> state.editMask("lhigh$key") { it.copy(lumaHigh = (v / 100).coerceAtLeast(it.lumaLow)) } }
            LabeledSlider("Soft edge", m.feather * 100, 0f..50f, " %", format = { "${it.toInt()}" }) { v -> state.editMask("lsoft$key") { it.copy(feather = v / 100) } }
        }
        MaskKind.None -> Unit
    }
}

private val brushSize = mutableFloatStateOf(0.05f)

/** Draws the current mask's outline over the image and takes lasso, brush, spot and luminance input. */
@Composable
private fun MaskOverlay(state: DevelopState, source: Bitmap) {
    val layer = state.currentMaskLayer ?: return
    val m = layer.mask
    val outline = MaterialTheme.colorScheme.primary
    var lasso by remember { mutableStateOf<List<CurvePoint>?>(null) }
    var stroke by remember { mutableIntStateOf(0) }
    Canvas(
        Modifier
            .fillMaxSize()
            .pointerInput(m.kind, state.maskIndex) {
                fun sample(o: Offset): FloatArray {
                    val x = (o.x / size.width * source.width).toInt().coerceIn(0, source.width - 1)
                    val y = (o.y / size.height * source.height).toInt().coerceIn(0, source.height - 1)
                    val c = source.getPixel(x, y)
                    return floatArrayOf((c shr 16 and 0xFF) / 255f, (c shr 8 and 0xFF) / 255f, (c and 0xFF) / 255f)
                }
                when (m.kind) {
                    MaskKind.Spot -> detectTapGestures { o ->
                        val (r, g, b) = sample(o).toList()
                        val hsl = FloatArray(3)
                        ColorMath.rgbToHsl(r, g, b, hsl)
                        state.editMask { it.copy(spotHue = hsl[0]) }
                    }
                    MaskKind.Luminance -> detectTapGestures { o ->
                        val (r, g, b) = sample(o).toList()
                        val l = 0.2126f * r + 0.7152f * g + 0.0722f * b
                        state.editMask { it.copy(lumaLow = (l - 0.15f).coerceAtLeast(0f), lumaHigh = (l + 0.15f).coerceAtMost(1f)) }
                    }
                    MaskKind.Lasso -> detectDragGestures(
                        onDragStart = { o -> lasso = listOf(CurvePoint(o.x / size.width, o.y / size.height)) },
                        onDragEnd = {
                            lasso?.takeIf { it.size >= 3 }?.let { pts -> state.editMask { it.copy(lasso = pts) } }
                            lasso = null
                        },
                        onDrag = { change, _ -> lasso = lasso.orEmpty() + CurvePoint(change.position.x / size.width, change.position.y / size.height) },
                    )
                    MaskKind.Brush -> detectDragGestures(
                        onDragStart = { stroke++ },
                        onDrag = { change, _ ->
                            val dab = Dab(change.position.x / size.width, change.position.y / size.height, brushSize.floatValue)
                            // Dabs a third of a radius apart are enough for a solid stroke.
                            val last = state.currentMaskLayer?.mask?.dabs?.lastOrNull()
                            if (last == null || hypot(last.x - dab.x, (last.y - dab.y) * size.height / size.width) > dab.radius / 3) {
                                state.editMask("brush$stroke") { it.copy(dabs = it.dabs + dab) }
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

/**
 * Artistic styles, as a strip of thumbnails of this very image (like a gallery app's styles),
 * with an amount; below it, mixing in another image as before.
 */
@Composable
private fun StyleTab(state: DevelopState, lineage: Lineage, source: Bitmap?, onPick: () -> Unit, onSendToGallery: (String) -> Unit) {
    val d = state.doc
    Text("Style", style = MaterialTheme.typography.labelLarge)
    // Thumbnails of every style, made once per image (small, so they are quick).
    val thumbs by produceState<Map<ArtStyle, ImageBitmap>>(emptyMap(), source) {
        val src = source ?: return@produceState
        value = withContext(Dispatchers.Default) {
            val k = 160f / maxOf(src.width, src.height)
            val small = Bitmap.createScaledBitmap(src, (src.width * k).toInt().coerceAtLeast(1), (src.height * k).toInt().coerceAtLeast(1), true)
            val px = IntArray(small.width * small.height).also { small.getPixels(it, 0, small.width, 0, 0, small.width, small.height) }
            ArtStyle.entries.associateWith { s ->
                Bitmap.createBitmap(ArtStyles.apply(s, px, small.width, small.height), small.width, small.height, Bitmap.Config.ARGB_8888).asImageBitmap()
            }
        }
    }
    androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
        item {
            StyleTile("None", null, d.art == null) { state.edit { it.copy(art = null) } }
        }
        items(ArtStyle.entries.size) { i ->
            val s = ArtStyle.entries[i]
            StyleTile(s.label, thumbs[s], d.art == s) { state.edit { it.copy(art = s) } }
        }
    }
    if (d.art != null) {
        LabeledSlider("Amount", d.artAmount * 100, 0f..100f, "", format = { "${it.toInt()}" }) { v -> state.edit("artAmount") { it.copy(artAmount = v / 100) } }
    }

    HorizontalDivider(Modifier.padding(vertical = 6.dp))
    Text("Mix in another image", style = MaterialTheme.typography.labelLarge)
    Text(
        "E.g. what another app's style filter made of this one: send it there, apply a style, save, import it from the Bridge inbox, then pick it here.",
        style = MaterialTheme.typography.labelMedium,
    )
    ScrollRow {
        d.sourceAssetId?.let { id -> TextButton(onClick = { onSendToGallery(id) }) { Text("Send to a photo editor") } }
        TextButton(onClick = onPick) { Text(if (d.styleAssetId == null) "Pick image…" else "Change image…") }
        if (d.styleAssetId != null) TextButton(onClick = { state.edit { it.copy(styleAssetId = null, styleAmount = 0f) } }) { Text("Remove") }
    }
    d.styleAssetId?.let { id ->
        Text("Image: g${lineage.generation(id)} ${id.takeLast(6)}", style = MaterialTheme.typography.labelMedium)
        LabeledSlider("Mix", d.styleAmount * 100, 0f..100f, "", format = { "${it.toInt()}" }) { v -> state.edit("style") { it.copy(styleAmount = v / 100) } }
    }
}

@Composable
private fun StyleTile(label: String, thumb: ImageBitmap?, selected: Boolean, onClick: () -> Unit) {
    Column(
        Modifier
            .width(76.dp)
            .border(if (selected) 2.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)
            .clickable(onClick = onClick)
            .padding(3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(70.dp).background(Color(0xFF444444)), contentAlignment = Alignment.Center) {
            if (thumb != null) Image(thumb, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Text(if (label == "None") "Off" else "…", color = Color.White)
        }
        Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}

@Composable
private fun PresetsTab(state: DevelopState, store: ProjectStore) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    // The list on screen changes at once; the file is written in the background, always with the
    // latest list, so two quick taps can't leave the older one on disk. Leaving the tab does not stop a write.
    fun save() {
        scope.launch(Dispatchers.IO + kotlinx.coroutines.NonCancellable) { synchronized(store) { store.savePresets(state.presets) } }
    }
    ScrollRow {
        state.presets.forEach { p ->
            FilterChip(selected = false, onClick = { state.edit { it.withPreset(p.look) } }, label = { Text(p.name) })
        }
        if (state.presets.isEmpty()) Text("No presets yet.", style = MaterialTheme.typography.labelMedium)
    }
    ScrollRow {
        TextButton(onClick = {
            state.presets = state.presets + Preset("Look ${state.presets.size + 1}", state.doc.asPreset())
            save()
        }) { Text("Save current look as preset") }
        if (state.presets.isNotEmpty()) TextButton(onClick = {
            state.presets = state.presets.dropLast(1)
            save()
        }) { Text("Delete last") }
    }
    Text("A preset keeps light, colour, mixer, curve and LUT; not the mask or the style image.", style = MaterialTheme.typography.labelSmall)
}
