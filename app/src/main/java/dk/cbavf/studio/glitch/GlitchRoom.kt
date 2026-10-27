package dk.cbavf.studio.glitch

import dk.cbavf.studio.abcompare.abHere
import dk.cbavf.studio.ui.layout.panelScroll
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import dk.cbavf.studio.develop.decodeAsset
import dk.cbavf.studio.glitch.model.ChannelOrder
import dk.cbavf.studio.glitch.model.FillMode
import dk.cbavf.studio.glitch.model.GlitchDoc
import dk.cbavf.studio.glitch.model.SortField
import dk.cbavf.studio.glitch.model.SortKey
import dk.cbavf.studio.glitch.model.SweepParam
import dk.cbavf.studio.glitch.model.TransferSpace
import dk.cbavf.studio.library.Lineage
import dk.cbavf.studio.library.ProjectStore
import dk.cbavf.studio.ui.LabeledSlider
import dk.cbavf.studio.ui.LibraryPicker
import dk.cbavf.studio.ui.ScrollRow
import dk.cbavf.studio.ui.Undoable
import dk.cbavf.studio.ui.renderSafely
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

class GlitchState {
    val edit = Undoable(GlitchDoc())
    var sweep by mutableStateOf<SweepParam?>(null)
    var sweepStep by mutableIntStateOf(4)
}

/** Glitches are made at this size at most: a full 12 MP byte stream would not fit in the app's heap twice on a phone with a small heap. */
const val GLITCH_PX = 2048

/**
 * The longest side a glitch is made at on this phone: the bigger its app heap (the memory class Android reports), the bigger the
 * picture. 2048 is the floor, for a phone with a small heap.
 */
fun glitchMaxPx(context: android.content.Context): Int {
    val mb = context.getSystemService(android.app.ActivityManager::class.java)?.memoryClass ?: 0
    return when {
        mb >= 512 -> 4096
        mb >= 384 -> 3072
        else -> GLITCH_PX
    }
}
private const val SWEEP_PX = 360

@Composable
fun GlitchRoom(state: GlitchState, store: ProjectStore, lineage: Lineage, busy: Boolean, routed: Bitmap? = null, liveFrom: String? = null, seedFrom: Int? = null, onUnplug: () -> Unit = {}, onSave: (Bitmap) -> Unit) {
    // "source" or "colour": which image the picker is choosing.
    var picking by remember { mutableStateOf<String?>(null) }
    picking?.let { what ->
        LibraryPicker(store, lineage, single = true) { ids ->
            picking = null
            ids.firstOrNull()?.let { id ->
                if (what == "colour") state.edit.edit { it.copy(colorFromAssetId = id) }
                else {
                    // Picking an image means you want that image: a cable into the lab would override it.
                    onUnplug()
                    state.edit.open(state.edit.doc.copy(sourceAssetId = id))
                }
            }
        }
        return
    }
    val doc = state.edit.doc
    val maxPx = glitchMaxPx(androidx.compose.ui.platform.LocalContext.current)
    var error by remember { mutableStateOf<String?>(null) }
    val fileSource by produceState<Bitmap?>(null, doc.sourceAssetId) {
        value = withContext(Dispatchers.IO) { doc.sourceAssetId?.let { lineage[it] }?.let { decodeAsset(store, it, maxPx) } }
        if (value == null && doc.sourceAssetId != null) error = "Can't read this image (deleted, or a format like DNG). Pick another."
    }
    // The colour image is only read for its statistics, so a small copy is enough.
    // Kept with the id it was read for, so a render never uses a palette that is still loading.
    val loaded by produceState<Pair<String, IntArray?>?>(null, doc.colorFromAssetId) {
        val id = doc.colorFromAssetId ?: return@produceState
        value = id to withContext(Dispatchers.IO) { runCatching { decodePalette(store, lineage, id) }.getOrNull() }
    }
    val paletteReady = doc.colorFromAssetId == null || loaded?.first == doc.colorFromAssetId
    val palette = loaded?.takeIf { it.first == doc.colorFromAssetId }?.second
    // A rack cable into Glitch replaces the picked image.
    val source = routed ?: fileSource
    var result by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(doc, source, seedFrom, palette, paletteReady) {
        val src = source ?: return@LaunchedEffect
        // Wait for the colour image, so the first render is not shown in the wrong colours.
        if (!paletteReady) return@LaunchedEffect
        delay(60)
        // A number patched into the Seed jack (a sensor, say) replaces the corruption seed.
        val shown = if (seedFrom != null) doc.copy(seed = seedFrom) else doc
        renderSafely({ error = it }) { renderGlitch(src, shown, palette) { ensureActive() } }?.let {
            result = it
            error = null
        }
    }

    dk.cbavf.studio.ui.layout.RoomSplit(canvas = {
        Box(Modifier.fillMaxSize().abHere(), contentAlignment = Alignment.Center) {
            val r = result
            when {
                (doc.sourceAssetId == null && routed == null) || (source == null && error != null) -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(error ?: "Pick a Library image to glitch.", style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { picking = "source" }) { Text("Pick image") }
                }
                state.sweep != null && source != null && paletteReady -> SweepWall(state, source!!, palette)
                r != null -> Image(r.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit, filterQuality = FilterQuality.None)
                else -> Text("Glitching…")
            }
            Surface(
                Modifier.align(Alignment.TopStart).padding(8.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                shape = MaterialTheme.shapes.small,
            ) {
                Text(error ?: ((if (liveFrom != null) "Live from $liveFrom · " else "") + doc.summary), Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium)
            }
            dk.cbavf.studio.abcompare.AbLayer(result?.asImageBitmap(), source?.asImageBitmap(), if (routed != null) "routed source" else "Library image")
        }
    }, controls = {
        Surface(tonalElevation = 2.dp) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                ScrollRow {
                    Button(onClick = { result?.let(onSave) }, enabled = result != null && !busy) { Text(if (busy) "Saving…" else "Save") }
                    dk.cbavf.studio.abcompare.LocalAb.current?.let { abc -> TextButton(onClick = { abc.state.snapshot = result?.asImageBitmap() }, enabled = result != null) { Text("Snap B") } }
                    TextButton(onClick = state.edit::undo, enabled = state.edit.canUndo) { Text("Undo") }
                    TextButton(onClick = state.edit::redo, enabled = state.edit.canRedo) { Text("Redo") }
                    TextButton(onClick = { state.edit.edit { GlitchDoc(sourceAssetId = it.sourceAssetId) } }) { Text("Reset") }
                    TextButton(onClick = { picking = "source" }) { Text("Image…") }
                    dk.cbavf.studio.ui.LiveInputButton(liveFrom, onUnplug)
                }
                ScrollRow {
                    Text("Sweep", style = MaterialTheme.typography.labelMedium)
                    FilterChip(selected = state.sweep == null, onClick = { state.sweep = null }, label = { Text("Off") })
                    SweepParam.entries.forEach { p -> FilterChip(selected = state.sweep == p, onClick = { state.sweep = p }, label = { Text(p.label) }) }
                }
                HorizontalDivider()
                Column(Modifier.panelScroll(280.dp)) {
                    if (state.sweep != null) {
                        LabeledSlider("Sweep step", state.sweepStep.toFloat(), 1f..64f, "", format = { "${it.roundToInt()}" }) { state.sweepStep = it.roundToInt() }
                    }
                    Controls(state)
                    HorizontalDivider()
                    SortControls(state)
                    HorizontalDivider()
                    ColourControls(state, lineage) { picking = "colour" }
                }
            }
        }
    })
}

@Composable
private fun Controls(state: GlitchState) {
    val d = state.edit.doc
    ScrollRow {
        Text("Bytes/px", style = MaterialTheme.typography.labelMedium)
        listOf(1, 2, 3, 4).forEach { b -> FilterChip(selected = d.bytesPerPixel == b, onClick = { state.edit.edit { it.copy(bytesPerPixel = b) } }, label = { Text("$b") }) }
    }
    IntSlider(state, "Width", "w", d.widthDelta, -128..128) { g, v -> g.copy(widthDelta = v) }
    IntSlider(state, "Stride", "s", d.strideDelta, -512..512) { g, v -> g.copy(strideDelta = v) }
    IntSlider(state, "Offset", "o", d.offset, 0..400_000) { g, v -> g.copy(offset = v) }
    ScrollRow {
        Text("Channels", style = MaterialTheme.typography.labelMedium)
        ChannelOrder.entries.forEach { c -> FilterChip(selected = d.channels == c, onClick = { state.edit.edit { it.copy(channels = c) } }, label = { Text(c.name) }) }
    }
    IntSlider(state, "Chroma X", "cx", d.chromaX, -64..64) { g, v -> g.copy(chromaX = v) }
    IntSlider(state, "Chroma Y", "cy", d.chromaY, -64..64) { g, v -> g.copy(chromaY = v) }
    ScrollRow {
        Text("Fill", style = MaterialTheme.typography.labelMedium)
        FillMode.entries.forEach { f -> FilterChip(selected = d.fill == f, onClick = { state.edit.edit { it.copy(fill = f) } }, label = { Text(f.name) }) }
    }
    if (d.fill != FillMode.None) {
        LabeledSlider("Fill start", d.fillStart, 0f..1f, "") { v -> state.edit.edit("fs") { it.copy(fillStart = v) } }
        LabeledSlider("Fill length", d.fillLength, 0f..0.5f, "") { v -> state.edit.edit("fl") { it.copy(fillLength = v) } }
    }
    IntSlider(state, "JPEG gens", "gen", d.generations, 0..MAX_GENERATIONS) { g, v -> g.copy(generations = v) }
    if (d.generations > 0) {
        IntSlider(state, "Quality", "q", d.quality, 1..95) { g, v -> g.copy(quality = v) }
        IntSlider(state, "Corrupt", "c", d.corruptBytes, 0..64) { g, v -> g.copy(corruptBytes = v) }
        // Error correction: pull back towards the misread image, at a scale, every N generations.
        IntSlider(state, "Correction %", "fix", (d.correction * 100).toInt(), 0..100) { g, v -> g.copy(correction = v / 100f) }
        if (d.correction > 0f) {
            dk.cbavf.studio.ui.ScrollRow {
                Text("Correct at", style = MaterialTheme.typography.labelMedium)
                listOf(1, 2, 4, 8, 16, 32).forEach { k ->
                    FilterChip(selected = d.correctScale == k, onClick = { state.edit.edit { it.copy(correctScale = k) } }, label = { Text("1/$k") })
                }
            }
            IntSlider(state, "Every N gens", "every", d.correctEvery, 1..MAX_GENERATIONS) { g, v -> g.copy(correctEvery = v) }
        }
        if (d.corruptBytes > 0) ScrollRow { TextButton(onClick = { state.edit.edit { it.copy(seed = it.seed + 1) } }) { Text("New corruption (seed ${d.seed})") } }
    }
}

/** Pixel sort: what steers the lines, the key, and the key range that gets sorted. */
@Composable
private fun SortControls(state: GlitchState) {
    val d = state.edit.doc
    ScrollRow {
        Text("Pixel sort", style = MaterialTheme.typography.labelMedium)
        SortField.entries.forEach { f ->
            val label = when (f) {
                SortField.Off -> "Off"
                SortField.Angle -> "Angle"
                SortField.Edges -> "Along edges"
            }
            FilterChip(selected = d.sortField == f, onClick = { state.edit.edit { it.copy(sortField = f) } }, label = { Text(label) })
        }
    }
    if (d.sortField == SortField.Off) return
    ScrollRow {
        Text("Sort by", style = MaterialTheme.typography.labelMedium)
        SortKey.entries.forEach { k -> FilterChip(selected = d.sortKey == k, onClick = { state.edit.edit { it.copy(sortKey = k) } }, label = { Text(k.name) }) }
    }
    ScrollRow {
        Text("Direction", style = MaterialTheme.typography.labelMedium)
        // Quick picks: rows and columns, each way.
        listOf(0f to "Rows →", 180f to "Rows ←", 90f to "Columns ↓", 270f to "Columns ↑").forEach { (a, label) ->
            FilterChip(selected = d.sortAngle == a, onClick = { state.edit.edit { it.copy(sortAngle = a) } }, label = { Text(label) })
        }
    }
    LabeledSlider(if (d.sortField == SortField.Edges) "Turn" else "Angle", d.sortAngle, 0f..360f, "°", format = { "${it.roundToInt()}" }) { v ->
        state.edit.edit("sa") { it.copy(sortAngle = v.roundToInt().toFloat()) }
    }
    LabeledSlider("From", d.sortLow, 0f..1f, "") { v -> state.edit.edit("slo") { it.copy(sortLow = v) } }
    LabeledSlider("To", d.sortHigh, 0f..1f, "") { v -> state.edit.edit("shi") { it.copy(sortHigh = v) } }
    if (d.sortField == SortField.Edges) IntSlider(state, "Smooth edges", "ss", d.sortSmooth, 0..16) { g, v -> g.copy(sortSmooth = v) }
}

/** Colour transfer: lay the colours of a second Library image over this one. */
@Composable
private fun ColourControls(state: GlitchState, lineage: Lineage, onPick: () -> Unit) {
    val d = state.edit.doc
    val picked = d.colorFromAssetId?.let { lineage[it] } != null
    ScrollRow {
        Text("Colours from", style = MaterialTheme.typography.labelMedium)
        TextButton(onClick = onPick) { Text(if (d.colorFromAssetId == null) "Another image…" else "Change…") }
        if (d.colorFromAssetId != null) TextButton(onClick = { state.edit.edit { it.copy(colorFromAssetId = null) } }) { Text("Own colours") }
    }
    if (d.colorFromAssetId == null) return
    if (!picked) {
        Text("Can't find that image. Pick another.", style = MaterialTheme.typography.bodySmall)
        return
    }
    ScrollRow {
        Text("Match in", style = MaterialTheme.typography.labelMedium)
        TransferSpace.entries.forEach { s -> FilterChip(selected = d.colorSpace == s, onClick = { state.edit.edit { it.copy(colorSpace = s) } }, label = { Text(s.name) }) }
    }
    IntSlider(state, "Amount %", "camt", (d.colorAmount * 100).roundToInt(), 0..100) { g, v -> g.copy(colorAmount = v / 100f) }
}

@Composable
private fun IntSlider(state: GlitchState, label: String, key: String, value: Int, range: IntRange, set: (GlitchDoc, Int) -> GlitchDoc) {
    LabeledSlider(label, value.toFloat(), range.first.toFloat()..range.last.toFloat(), "", format = { "${it.roundToInt()}" }) { v ->
        state.edit.edit(key) { set(it, v.roundToInt()) }
    }
}

/** 3×3 variants of the current glitch along one parameter, at thumbnail size; tap one to take it. */
@Composable
private fun SweepWall(state: GlitchState, source: Bitmap, palette: IntArray?) {
    val param = state.sweep ?: return
    val doc = state.edit.doc
    val variants = remember(doc, param, state.sweepStep) { param.variants(doc, 9, state.sweepStep) }
    val thumbs by produceState<List<ImageBitmap>?>(null, variants, source, palette) {
        value = null
        value = renderSafely({}) {
            val scale = SWEEP_PX.toFloat() / maxOf(source.width, source.height)
            val small = Bitmap.createScaledBitmap(source, (source.width * scale).toInt().coerceAtLeast(16), (source.height * scale).toInt().coerceAtLeast(16), true)
            // Offsets and widths are in source pixels; scale them so the thumbnails look like the real thing.
            variants.map { v ->
                val sv = v.copy(
                    widthDelta = (v.widthDelta * scale).roundToInt(),
                    strideDelta = (v.strideDelta * scale).roundToInt(),
                    offset = (v.offset * scale * scale).roundToInt(),
                    chromaX = (v.chromaX * scale).roundToInt(),
                    chromaY = (v.chromaY * scale).roundToInt(),
                )
                renderGlitch(small, sv, palette) { ensureActive() }.asImageBitmap()
            }
        }
    }
    val list = thumbs ?: return Text("Rendering 9 variants…")
    Column(Modifier.fillMaxSize().padding(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (row in 0 until 3) {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (col in 0 until 3) {
                    val i = row * 3 + col
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxSize()
                            .border(if (i == 4) 2.dp else 0.dp, MaterialTheme.colorScheme.primary)
                            .clickable {
                                state.edit.edit { variants[i] }
                                state.sweep = null
                            }
                    ) {
                        Image(list[i], null, Modifier.fillMaxSize().aspectRatio(1f, matchHeightConstraintsFirst = true), contentScale = ContentScale.Fit)
                    }
                }
            }
        }
    }
}
