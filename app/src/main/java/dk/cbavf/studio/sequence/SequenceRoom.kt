package dk.cbavf.studio.sequence

import android.app.Activity
import android.util.LruCache
import androidx.compose.foundation.Image
import dk.cbavf.studio.sequence.model.FrameShape
import dk.cbavf.studio.sequence.model.FrameFit
import androidx.compose.runtime.mutableIntStateOf
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dk.cbavf.studio.library.AssetRecord
import dk.cbavf.studio.library.Lineage
import dk.cbavf.studio.library.ProjectStore
import dk.cbavf.studio.sequence.model.PlayMode
import dk.cbavf.studio.sequence.model.SeqSource
import dk.cbavf.studio.sequence.model.Span
import dk.cbavf.studio.ui.LibraryPicker
import dk.cbavf.studio.ui.LabeledSlider
import dk.cbavf.studio.ui.ScrollRow
import dk.cbavf.studio.ui.fmt
import dk.cbavf.studio.ui.renderSafely
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Export in progress (0..1) or null; owned by the app so it survives switching rooms. */
class ExportStatus {
    var progress by mutableStateOf<Float?>(null)
    var label by mutableStateOf("")
}

@Composable
fun SequenceRoom(
    state: SequenceState,
    store: ProjectStore,
    lineage: Lineage,
    export: ExportStatus,
    animateFrameCount: Int,
    onAddAnimateFrames: () -> Unit,
    onProject: () -> Unit,
    onExportGif: () -> Unit,
    onExportMp4: () -> Unit,
) {
    var picking by remember { mutableStateOf(false) }
    if (picking) {
        LibraryPicker(store, lineage, onDone = { ids ->
            picking = false
            state.addAssets(ids)
        })
        return
    }

    val context = LocalContext.current
    val decoder = remember(store, lineage) { FrameDecoder(store, lineage, screenSize(context)) }
    val frame = decoder.frameSize(state.toDoc())
    val preview = rememberPreviewFrames(state, decoder, frame)

    // In-room preview: approximate timing (delay-based); the projector is the exact one.
    var previewSpan by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(state.previewing) {
        previewSpan = null
        if (!state.previewing) return@LaunchedEffect
        snapshotFlow { preview.ready >= state.items.size }.first { it }
        val doc = state.toDoc()
        val spans = doc.timeline()
        if (spans.isEmpty()) return@LaunchedEffect
        do {
            for ((i, s) in spans.withIndex()) {
                previewSpan = i
                delay((s.ticks * doc.msPerTick()).roundToLong().coerceAtLeast(1))
            }
        } while (state.previewing && doc.mode != PlayMode.Once)
        state.previewing = false
    }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
            val doc = state.toDoc()
            val spans = doc.timeline()
            val shownItem = previewSpan?.let { spans.getOrNull(it)?.item } ?: state.selected
            if (state.items.isEmpty() || frame == null) {
                Text("Empty timeline. Add Animate frames or Library images below.", Modifier.padding(32.dp), style = MaterialTheme.typography.bodyMedium)
            } else {
                // The frame as it will be projected and exported: black where the picture does not reach.
                Box(
                    Modifier.aspectRatio(frame.first.toFloat() / frame.second).background(Color.Black)
                        .border(1.dp, MaterialTheme.colorScheme.outline),
                    contentAlignment = Alignment.Center,
                ) {
                    val image = if (shownItem == Span.BLACK) null else preview.frames.getOrNull(shownItem)
                    when {
                        shownItem == Span.BLACK -> Unit
                        image != null -> Image(image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit, filterQuality = FilterQuality.Low)
                        else -> Text("Preparing preview ${preview.ready}/${state.items.size}", color = Color.Gray)
                    }
                }
            }
            Summary(state, frame, Modifier.align(Alignment.TopStart))
        }
        Timeline(state, preview.frames)
        Controls(state, export, animateFrameCount, onAddAnimateFrames, onPick = { picking = true }, onProject, onExportGif, onExportMp4)
    }
}

/** Preview frames, made by the export path at preview size; reused per item until its look changes. */
private class PreviewFrames {
    var frames by mutableStateOf<List<ImageBitmap?>>(emptyList())
    var ready by mutableIntStateOf(0)
    val cache = HashMap<Any, ImageBitmap>()
}

@Composable
private fun rememberPreviewFrames(state: SequenceState, decoder: FrameDecoder, frame: Pair<Int, Int>?): PreviewFrames {
    // A new decoder means a reloaded Library (e.g. it finished loading after the app opened):
    // frames made before then may be black stand-ins for images it could not find yet.
    val p = remember(decoder) { PreviewFrames() }
    // What each frame looks like: changing a duration redoes nothing.
    val looks = state.items.map { Triple(it.source, it.effectiveFit, it.quarterTurns) }
    LaunchedEffect(looks, frame, decoder) {
        val (w, h) = frame ?: return@LaunchedEffect
        val scale = (PREVIEW_PX.toFloat() / maxOf(w, h)).coerceAtMost(1f)
        val items = state.items.toList()
        val keys = looks.map { it to frame }
        val out = MutableList(keys.size) { p.cache[keys[it]] }
        p.frames = out.toList()
        p.ready = out.count { it != null }
        keys.forEachIndexed { i, key ->
            if (out[i] != null) return@forEachIndexed
            val image = renderSafely({}) { decoder.decode(items[i], w, h, scale).asImageBitmap() } ?: return@forEachIndexed
            p.cache[key] = image
            out[i] = image
            p.frames = out.toList()
            p.ready++
        }
        // Forget frames no longer in the timeline.
        p.cache.keys.retainAll(keys.toSet())
    }
    return p
}

@Composable
private fun Summary(state: SequenceState, frame: Pair<Int, Int>?, modifier: Modifier) {
    if (state.items.isEmpty()) return
    val doc = state.toDoc()
    val ticks = doc.timeline().sumOf { it.ticks.toLong() }
    val item = state.selectedItem
    Surface(modifier = modifier, color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f), shape = MaterialTheme.shapes.small) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            val style = MaterialTheme.typography.labelMedium
            Text("${state.items.size} items · ${fmt(ticks * doc.msPerTick() / 1000)} s per pass · ${doc.rateHz} Hz", style = style)
            if (item != null) {
                Text(
                    "item ${state.selected + 1}: ${item.ticks} ticks = ${fmt(item.ticks * doc.msPerTick())} ms · ${item.effectiveFit.label}" +
                        if (item.quarterTurns != 0) " · turned ${item.quarterTurns * 90}°" else "",
                    style = style,
                )
            }
            frame?.let { Text("frame ${it.first}×${it.second} (${doc.shape.label})", style = style) }
        }
    }
}

@Composable
private fun Timeline(state: SequenceState, frames: List<ImageBitmap?>) {
    if (state.items.isEmpty()) return
    val list = rememberLazyListState()
    LaunchedEffect(state.selected) { list.animateScrollToItem(state.selected.coerceAtLeast(0)) }
    LazyRow(
        state = list,
        modifier = Modifier.fillMaxWidth().height(96.dp).padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        itemsIndexed(state.items) { i, item ->
            val selected = i == state.selected
            Column(
                Modifier
                    .width(52.dp)
                    .border(if (selected) 2.dp else 0.dp, MaterialTheme.colorScheme.primary)
                    .clickable { state.selected = i },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val thumb = frames.getOrNull(i)
                Box(Modifier.weight(1f).fillMaxWidth().background(Color.Black)) {
                    thumb?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
                }
                Text("${item.ticks}", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun Controls(
    state: SequenceState,
    export: ExportStatus,
    animateFrameCount: Int,
    onAddAnimateFrames: () -> Unit,
    onPick: () -> Unit,
    onProject: () -> Unit,
    onExportGif: () -> Unit,
    onExportMp4: () -> Unit,
) {
    val hasItems = state.items.isNotEmpty()
    val busy = export.progress != null
    Surface(tonalElevation = 2.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
            ScrollRow {
                TextButton(onClick = onProject, enabled = hasItems) { Text("Projector") }
                TextButton(onClick = { state.previewing = !state.previewing }, enabled = hasItems) {
                    Text(if (state.previewing) "Stop" else "Preview")
                }
                TextButton(onClick = onAddAnimateFrames, enabled = animateFrameCount > 0) { Text("+ Animate ($animateFrameCount)") }
                TextButton(onClick = onPick) { Text("+ Library") }
                TextButton(onClick = state::undo, enabled = state.canUndo) { Text("Undo") }
            }
            export.progress?.let {
                Text(export.label, style = MaterialTheme.typography.labelMedium)
                LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth())
            }
            HorizontalDivider()
            Column(Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
                val item = state.selectedItem
                if (item != null) {
                    ScrollRow {
                        TextButton(onClick = { state.move(-1) }, enabled = state.selected > 0) { Text("◀ Move") }
                        TextButton(onClick = { state.move(1) }, enabled = state.selected < state.items.lastIndex) { Text("Move ▶") }
                        TextButton(onClick = state::duplicate) { Text("Duplicate") }
                        TextButton(onClick = state::delete) { Text("Delete") }
                        TextButton(onClick = { state.setAllTicks(item.ticks) }) { Text("Same duration for all") }
                        TextButton(onClick = state::clear) { Text("Clear") }
                    }
                    LabeledSlider("Duration", item.ticks.toFloat(), 1f..(state.rateHz * 2f), " ticks", format = { "${it.roundToInt()}" }) {
                        state.setTicks(it.roundToInt())
                    }
                    ScrollRow {
                        Text("Picture", Modifier.width(48.dp), style = MaterialTheme.typography.labelMedium)
                        FrameFit.entries.forEach { f ->
                            FilterChip(selected = item.effectiveFit == f, onClick = { state.setFit(f) }, label = { Text(f.label) })
                        }
                        TextButton(onClick = state::turn) { Text("Turn 90°") }
                        TextButton(onClick = state::fitForAll) { Text("Same for all") }
                    }
                }
                val rates = supportedRates((LocalContext.current as Activity).display)
                ScrollRow {
                    Text("Rate", Modifier.width(48.dp), style = MaterialTheme.typography.labelMedium)
                    (rates + state.rateHz).distinct().sorted().forEach { hz ->
                        FilterChip(
                            selected = hz == state.rateHz,
                            onClick = { state.changeRate(hz) },
                            label = { Text(if (hz in rates) "$hz Hz" else "$hz Hz (not on this screen)") },
                        )
                    }
                }
                ScrollRow {
                    Text("Frame", Modifier.width(48.dp), style = MaterialTheme.typography.labelMedium)
                    FrameShape.entries.forEach { sh ->
                        FilterChip(selected = state.shape == sh, onClick = { state.changeShape(sh) }, label = { Text(sh.label) })
                    }
                }
                ScrollRow {
                    Text("Mode", Modifier.width(48.dp), style = MaterialTheme.typography.labelMedium)
                    PlayMode.entries.forEach { m ->
                        FilterChip(selected = m == state.mode, onClick = { state.mode = m }, label = { Text(m.label) })
                    }
                }
                LabeledSlider("Black frames", state.blackTicks.toFloat(), 0f..4f, " ticks", steps = 3, format = { "${it.roundToInt()}" }) {
                    state.blackTicks = it.roundToInt()
                }
                ScrollRow {
                    TextButton(onClick = onExportGif, enabled = hasItems && !busy) { Text("Export GIF") }
                    TextButton(onClick = onExportMp4, enabled = hasItems && !busy) { Text("Export MP4 (${mp4Fps(state.rateHz)} fps)") }
                }
            }
        }
    }
}

/** MP4 frame rate for a sequence: its own rate up to 60 fps, which every encoder here handles. */
fun mp4Fps(rateHz: Int) = rateHz.coerceAtMost(60)

private val PlayMode.label
    get() = when (this) {
        PlayMode.Loop -> "Loop"
        PlayMode.PingPong -> "Ping-pong"
        PlayMode.Once -> "Once"
    }

private const val PREVIEW_PX = 720
