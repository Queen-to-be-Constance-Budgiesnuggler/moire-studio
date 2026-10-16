package dk.cbavf.studio.glitch

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
import dk.cbavf.studio.glitch.model.SweepParam
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

/** Glitches are made at this size at most: a full 12 MP byte stream would not fit the S9+'s heap twice. */
const val GLITCH_PX = 2048
private const val SWEEP_PX = 360

@Composable
fun GlitchRoom(state: GlitchState, store: ProjectStore, lineage: Lineage, busy: Boolean, onSave: (Bitmap) -> Unit) {
    var picking by remember { mutableStateOf(false) }
    if (picking) {
        LibraryPicker(store, lineage, single = true) { ids ->
            picking = false
            ids.firstOrNull()?.let { id -> state.edit.open(state.edit.doc.copy(sourceAssetId = id)) }
        }
        return
    }
    val doc = state.edit.doc
    var error by remember { mutableStateOf<String?>(null) }
    val source by produceState<Bitmap?>(null, doc.sourceAssetId) {
        value = withContext(Dispatchers.IO) { doc.sourceAssetId?.let { lineage[it] }?.let { decodeAsset(store, it, GLITCH_PX) } }
        if (value == null && doc.sourceAssetId != null) error = "Can't read this image (deleted, or a format like DNG). Pick another."
    }
    var result by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(doc, source) {
        val src = source ?: return@LaunchedEffect
        delay(60)
        renderSafely({ error = it }) { renderGlitch(src, doc) { ensureActive() } }?.let {
            result = it
            error = null
        }
    }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            val r = result
            when {
                doc.sourceAssetId == null || (source == null && error != null) -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(error ?: "Pick a Library image to glitch.", style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { picking = true }) { Text("Pick image") }
                }
                state.sweep != null && source != null -> SweepWall(state, source!!)
                r != null -> Image(r.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit, filterQuality = FilterQuality.None)
                else -> Text("Glitching…")
            }
            Surface(
                Modifier.align(Alignment.TopStart).padding(8.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                shape = MaterialTheme.shapes.small,
            ) {
                Text(error ?: doc.summary, Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium)
            }
        }
        Surface(tonalElevation = 2.dp) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                ScrollRow {
                    Button(onClick = { result?.let(onSave) }, enabled = result != null && !busy) { Text(if (busy) "Saving…" else "Save") }
                    TextButton(onClick = state.edit::undo, enabled = state.edit.canUndo) { Text("Undo") }
                    TextButton(onClick = { state.edit.edit { GlitchDoc(sourceAssetId = it.sourceAssetId) } }) { Text("Reset") }
                    TextButton(onClick = { picking = true }) { Text("Image…") }
                }
                ScrollRow {
                    Text("Sweep", style = MaterialTheme.typography.labelMedium)
                    FilterChip(selected = state.sweep == null, onClick = { state.sweep = null }, label = { Text("Off") })
                    SweepParam.entries.forEach { p -> FilterChip(selected = state.sweep == p, onClick = { state.sweep = p }, label = { Text(p.label) }) }
                }
                HorizontalDivider()
                Column(Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState())) {
                    if (state.sweep != null) {
                        LabeledSlider("Sweep step", state.sweepStep.toFloat(), 1f..64f, "", format = { "${it.roundToInt()}" }) { state.sweepStep = it.roundToInt() }
                    }
                    Controls(state)
                }
            }
        }
    }
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
        if (d.corruptBytes > 0) ScrollRow { TextButton(onClick = { state.edit.edit { it.copy(seed = it.seed + 1) } }) { Text("New corruption (seed ${d.seed})") } }
    }
}

@Composable
private fun IntSlider(state: GlitchState, label: String, key: String, value: Int, range: IntRange, set: (GlitchDoc, Int) -> GlitchDoc) {
    LabeledSlider(label, value.toFloat(), range.first.toFloat()..range.last.toFloat(), "", format = { "${it.roundToInt()}" }) { v ->
        state.edit.edit(key) { set(it, v.roundToInt()) }
    }
}

/** 3×3 variants of the current glitch along one parameter, at thumbnail size; tap one to take it. */
@Composable
private fun SweepWall(state: GlitchState, source: Bitmap) {
    val param = state.sweep ?: return
    val doc = state.edit.doc
    val variants = remember(doc, param, state.sweepStep) { param.variants(doc, 9, state.sweepStep) }
    val thumbs by produceState<List<ImageBitmap>?>(null, variants, source) {
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
                renderGlitch(small, sv) { ensureActive() }.asImageBitmap()
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
