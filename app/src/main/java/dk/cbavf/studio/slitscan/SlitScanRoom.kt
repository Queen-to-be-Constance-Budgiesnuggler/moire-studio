package dk.cbavf.studio.slitscan

import dk.cbavf.studio.ui.layout.panelScrollOpen
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import dk.cbavf.studio.animate.AnimateState
import dk.cbavf.studio.library.ProjectStore
import dk.cbavf.studio.library.Recipe
import dk.cbavf.studio.library.renderBitmap
import dk.cbavf.studio.library.saveBitmapAsset
import dk.cbavf.studio.ui.LabeledSlider
import dk.cbavf.studio.ui.ScrollRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Settings of the slit-scan room. */
class SlitScanState {
    var vertical by mutableStateOf(true)
    var slitPx by mutableIntStateOf(3)
    var mode by mutableStateOf(OffsetMode.None)
    var shift by mutableFloatStateOf(1f)
    var seed by mutableIntStateOf(1)

    /** Width of the picture in pixels. */
    var widthPx by mutableIntStateOf(720)

    /** Where the frames come from. */
    var source by mutableStateOf(SlitSource.Animate)

    /** Library images picked as frames (a camera burst, for one), in order. */
    var libraryIds by mutableStateOf(listOf<String>())

    /** A Sequence timeline read by its timing (longer items give more frames) instead of one frame per item. */
    var sequenceByTime by mutableStateOf(false)

    /** Frames collected from the rack's Stream input, oldest first; [streamVersion] changes with each. */
    val stream = androidx.compose.runtime.mutableStateListOf<Bitmap>()
    var streamVersion by mutableIntStateOf(0)
    var collecting by mutableStateOf(false)

    fun addStreamFrame(b: Bitmap) {
        stream.add(b)
        while (stream.size > MAX_STREAM) stream.removeAt(0).recycle()
        streamVersion++
    }

    fun clearStream() {
        stream.clear()
        streamVersion++
    }

    companion object {
        /** A collected stream holds this many frames at most (they are kept in memory). */
        const val MAX_STREAM = 32
    }
}

/** The frames the slits are taken from. */
enum class SlitSource(val label: String) { Animate("Animate"), Sequence("Sequence"), Library("Library images"), Stream("Rack stream") }

/**
 * Slit-scan: every slit (a strip, [SlitScanState.slitPx] wide) of the picture is taken from a different frame of
 * the Animate room's sequence. One frame per slit in order is the barrier-grid interleave; a time offset
 * per slit (ramp, wave, random) bends the picture through time instead.
 */
@Composable
fun SlitScanRoom(
    state: SlitScanState,
    animate: AnimateState,
    store: ProjectStore,
    lineage: dk.cbavf.studio.library.Lineage,
    incoming: Bitmap? = null,
    incomingVersion: Int = -1,
    sequence: dk.cbavf.studio.sequence.SequenceState? = null,
    onSaved: (String) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val frames = animate.frames.toList()
    // The Sequence timeline, framed as the projector frames it (its shape, each item's fit and turn).
    val seqDoc = sequence?.toDoc()
    val seqOrder = seqDoc?.let { d -> SequenceSlits.frameOrder(d.items.map { it.ticks }, state.sequenceByTime) }.orEmpty()
    val decoder = androidx.compose.runtime.remember(store, lineage) {
        dk.cbavf.studio.sequence.FrameDecoder(store, lineage, dk.cbavf.studio.sequence.screenSize(context))
    }
    var busy by androidx.compose.runtime.remember { mutableStateOf(false) }
    var picking by androidx.compose.runtime.remember { mutableStateOf(false) }
    if (picking) {
        dk.cbavf.studio.ui.LibraryPicker(store, lineage) { ids -> picking = false; if (ids.isNotEmpty()) state.libraryIds = ids }
        return
    }
    // A picture arriving on the rack's Stream input is collected while "Collect" is on.
    androidx.compose.runtime.LaunchedEffect(incomingVersion) {
        val b = incoming ?: return@LaunchedEffect
        if (!state.collecting) return@LaunchedEffect
        val k = minOf(1f, 480f / maxOf(b.width, b.height))
        state.addStreamFrame(if (k >= 1f) b.copy(Bitmap.Config.ARGB_8888, false) else Bitmap.createScaledBitmap(b, (b.width * k).toInt().coerceAtLeast(1), (b.height * k).toInt().coerceAtLeast(1), true))
    }
    val frameCount = when (state.source) {
        SlitSource.Animate -> frames.size
        // One item held for a long time is still one picture: no scan until there are two.
        SlitSource.Sequence -> SequenceSlits.itemsUsed(seqOrder).size.let { if (it < 2) it else seqOrder.size }
        SlitSource.Library -> state.libraryIds.size
        SlitSource.Stream -> state.stream.size
    }
    val picture by produceState<Bitmap?>(
        null, frames.size, frames.hashCode(), state.vertical, state.slitPx, state.mode, state.shift, state.seed, state.widthPx,
        state.source, state.libraryIds, state.streamVersion,
        if (state.source == SlitSource.Sequence) seqDoc else null, state.sequenceByTime,
    ) {
        value = if (frameCount < 2) null else withContext(Dispatchers.Default) {
            runCatching {
                when (state.source) {
                    SlitSource.Animate -> scan(frames.map { it.doc }, state)
                    SlitSource.Sequence -> seqDoc?.let { scanSequence(it, decoder, state) }
                    SlitSource.Library -> scanBitmaps(state.libraryIds.mapNotNull { id -> lineage[id]?.let { dk.cbavf.studio.develop.decodeAsset(store, it, 1080) } }, state)
                    SlitSource.Stream -> scanBitmaps(state.stream.toList(), state)
                }
            }.getOrNull()
        }
    }
    dk.cbavf.studio.ui.layout.RoomSplit(canvas = {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val bmp = picture
            if (bmp != null) Image(bmp.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            else Text(
                if (frameCount < 2) when (state.source) {
                    SlitSource.Animate -> "Make at least two frames in Animate first: the slits are taken from them."
                    SlitSource.Sequence -> "Put at least two items on the Sequence timeline first."
                    SlitSource.Library -> "Pick at least two Library images (a camera burst, for one) with \"Pick images…\" below."
                    SlitSource.Stream -> "Patch a picture into Slit-scan's Stream jack (Studio back), then press Collect and let it change."
                } else "Scanning…",
                modifier = Modifier.padding(24.dp), style = MaterialTheme.typography.bodyMedium,
            )
            Surface(Modifier.align(Alignment.TopStart).padding(8.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f), shape = MaterialTheme.shapes.small) {
                Text(
                    "$frameCount frames · ${SlitScan.slitCount(state.widthPx, state.widthPx, state.slitPx, state.vertical)} slits",
                    Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }, controls = {
        Surface(tonalElevation = 2.dp) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).panelScrollOpen(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                ScrollRow {
                    Text("Frames", Modifier.width(72.dp), style = MaterialTheme.typography.labelMedium)
                    SlitSource.entries.forEach { s -> FilterChip(selected = state.source == s, onClick = { state.source = s }, label = { Text(s.label) }) }
                }
                ScrollRow {
                    when (state.source) {
                        SlitSource.Animate -> Text("The frames of the Animate room.", style = MaterialTheme.typography.labelMedium)
                        SlitSource.Sequence -> {
                            FilterChip(selected = !state.sequenceByTime, onClick = { state.sequenceByTime = false }, label = { Text("One per item") })
                            FilterChip(selected = state.sequenceByTime, onClick = { state.sequenceByTime = true }, label = { Text("By timing") })
                            val n = seqDoc?.items?.size ?: 0
                            if (n > SequenceSlits.MAX_ITEMS) Text("First ${SequenceSlits.MAX_ITEMS} of $n items", style = MaterialTheme.typography.labelMedium)
                        }
                        SlitSource.Library -> androidx.compose.material3.TextButton(onClick = { picking = true }) { Text(if (state.libraryIds.isEmpty()) "Pick images…" else "Pick images… (${state.libraryIds.size})") }
                        SlitSource.Stream -> {
                            FilterChip(selected = state.collecting, onClick = { state.collecting = !state.collecting }, label = { Text(if (state.collecting) "Collecting" else "Collect") })
                            androidx.compose.material3.TextButton(onClick = { state.clearStream() }, enabled = state.stream.isNotEmpty()) { Text("Clear (${state.stream.size})") }
                        }
                    }
                }
                ScrollRow {
                    Text("Slits", Modifier.width(72.dp), style = MaterialTheme.typography.labelMedium)
                    FilterChip(selected = state.vertical, onClick = { state.vertical = true }, label = { Text("Vertical") })
                    FilterChip(selected = !state.vertical, onClick = { state.vertical = false }, label = { Text("Horizontal") })
                    listOf(360, 720, 1080).forEach { w -> FilterChip(selected = state.widthPx == w, onClick = { state.widthPx = w }, label = { Text("$w px") }) }
                }
                LabeledSlider("Slit width", state.slitPx.toFloat(), 1f..64f, " px", format = { "${it.toInt()}" }) { state.slitPx = it.toInt() }
                ScrollRow {
                    Text("Time", Modifier.width(72.dp), style = MaterialTheme.typography.labelMedium)
                    OffsetMode.entries.forEach { m -> FilterChip(selected = state.mode == m, onClick = { state.mode = m }, label = { Text(m.name) }) }
                }
                if (state.mode != OffsetMode.None) {
                    LabeledSlider("Strength", state.shift, 0f..16f, " frames") { state.shift = it }
                }
                if (state.mode == OffsetMode.Random) {
                    LabeledSlider("Seed", state.seed.toFloat(), 1f..999f, "", format = { "${it.toInt()}" }) { state.seed = it.toInt() }
                }
                Button(
                    enabled = picture != null && !busy,
                    onClick = {
                        val bmp = picture ?: return@Button
                        busy = true
                        scope.launch {
                            val record = withContext(Dispatchers.IO) { saveBitmapAsset(context, store, bmp, emptyList(), "slitscan.render", Recipe(), png = true) }
                            busy = false
                            onSaved("Saved ${record.widthPx}×${record.heightPx} to the Library")
                        }
                    },
                ) { Text(if (busy) "Saving…" else "Save to Library") }
            }
        }
    })
}

/** Takes each slit from its picture (any pictures: Library images, a collected stream), fitted to the first one at the room's width. */
private fun scanBitmaps(bitmaps: List<Bitmap>, state: SlitScanState): Bitmap {
    val first = bitmaps.first()
    val w = state.widthPx
    val h = (w.toFloat() * first.height / first.width.coerceAtLeast(1)).toInt().coerceAtLeast(1)
    val pixels = bitmaps.map { b ->
        val scaled = if (b.width == w && b.height == h) b else Bitmap.createScaledBitmap(b, w, h, true)
        IntArray(w * h).also { scaled.getPixels(it, 0, w, 0, 0, w, h) }
    }
    val slits = SlitScan.slitCount(w, h, state.slitPx, state.vertical)
    val offsets = slitOffsets(state.mode, slits, state.shift, state.seed.toLong())
    val out = SlitScan.scan(pixels, w, h, state.slitPx, state.vertical, offsets)
    return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
}

/**
 * Frames a Sequence timeline as the projector would (its frame shape, each item's fit and turn) at the room's width, and takes
 * each slit from its frame. Each item is decoded once, however many frames it gives.
 */
private fun scanSequence(doc: dk.cbavf.studio.sequence.model.SequenceDoc, decoder: dk.cbavf.studio.sequence.FrameDecoder, state: SlitScanState): Bitmap? {
    val (fw, fh) = decoder.frameSize(doc) ?: return null
    val order = SequenceSlits.frameOrder(doc.items.map { it.ticks }, state.sequenceByTime)
    if (order.isEmpty()) return null
    val w = state.widthPx
    val h = (w.toFloat() * fh / fw.coerceAtLeast(1)).toInt().coerceAtLeast(1)
    val pixels = HashMap<Int, IntArray>()
    for (i in SequenceSlits.itemsUsed(order)) {
        val b = decoder.decode(doc.items[i], fw, fh, w.toFloat() / fw.coerceAtLeast(1))
        val scaled = if (b.width == w && b.height == h) b else Bitmap.createScaledBitmap(b, w, h, true)
        pixels[i] = IntArray(w * h).also { scaled.getPixels(it, 0, w, 0, 0, w, h) }
        if (scaled !== b) scaled.recycle()
        b.recycle()
    }
    val slits = SlitScan.slitCount(w, h, state.slitPx, state.vertical)
    val offsets = slitOffsets(state.mode, slits, state.shift, state.seed.toLong())
    val out = SlitScan.scan(order.map { pixels.getValue(it) }, w, h, state.slitPx, state.vertical, offsets)
    return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
}

/** Renders the frames at the room's width and takes each slit from its frame. */
private fun scan(docs: List<dk.cbavf.studio.library.CanvasDoc>, state: SlitScanState): Bitmap {
    val first = docs.first()
    val w = state.widthPx
    val h = (w.toFloat() * first.heightPx / first.widthPx.coerceAtLeast(1)).toInt().coerceAtLeast(1)
    val pixels = docs.map { doc ->
        val k = w.toFloat() / doc.widthPx.coerceAtLeast(1)
        val b = renderBitmap(doc, sheet = true, scale = k)
        val scaled = if (b.width == w && b.height == h) b else Bitmap.createScaledBitmap(b, w, h, true)
        IntArray(w * h).also { scaled.getPixels(it, 0, w, 0, 0, w, h) }
    }
    val slits = SlitScan.slitCount(w, h, state.slitPx, state.vertical)
    val offsets = slitOffsets(state.mode, slits, state.shift, state.seed.toLong())
    val out = SlitScan.scan(pixels, w, h, state.slitPx, state.vertical, offsets)
    return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
}
