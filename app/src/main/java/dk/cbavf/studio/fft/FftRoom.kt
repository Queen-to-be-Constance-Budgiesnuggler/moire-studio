package dk.cbavf.studio.fft

import dk.cbavf.studio.abcompare.abHere
import dk.cbavf.studio.ui.layout.panelScroll
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import dk.cbavf.studio.develop.decodeAsset
import dk.cbavf.studio.fft.model.Colormap
import dk.cbavf.studio.fft.model.FftDoc
import dk.cbavf.studio.fft.model.FftView
import dk.cbavf.studio.fft.model.FilterShape
import dk.cbavf.studio.fft.model.SpectrumBrush
import dk.cbavf.studio.fft.model.SpectrumDab
import dk.cbavf.studio.fft.model.SpectrumMask
import dk.cbavf.studio.library.Lineage
import dk.cbavf.studio.library.ProjectStore
import dk.cbavf.studio.ui.LabeledSlider
import dk.cbavf.studio.ui.LibraryPicker
import dk.cbavf.studio.ui.ScrollRow
import dk.cbavf.studio.debug.Debug
import dk.cbavf.studio.ui.Undoable
import dk.cbavf.studio.ui.renderSafely
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** FFT lab state that is not part of the recipe. */
class FftState {
    val edit = Undoable(FftDoc())
    var view by mutableStateOf(FftView.Magnitude)
    var colormap by mutableStateOf(Colormap.Heat)
    var brush by mutableStateOf(SpectrumBrush.Erase)
    var brushRadius by mutableFloatStateOf(0.02f)
}

/** Spectra are computed once per image and size; edits only re-run the inverse. */
private class Analysis(val spectra: Spectra, val phase: Spectra?)

@Composable
fun FftRoom(state: FftState, store: ProjectStore, lineage: Lineage, busy: Boolean, routed: Bitmap? = null, liveFrom: String? = null, onUnplug: () -> Unit = {}, onSave: (Bitmap) -> Unit) {
    var picking by remember { mutableStateOf<String?>(null) }
    picking?.let { what ->
        LibraryPicker(store, lineage, single = true) { ids ->
            picking = null
            val id = ids.firstOrNull() ?: return@LibraryPicker
            // Picking an image means you want that image: a cable into FFT would override it.
            if (what == "source") onUnplug()
            if (what == "source") state.edit.open(FftDoc(sourceAssetId = id, size = state.edit.doc.size))
            else state.edit.edit { it.copy(phaseFromAssetId = id) }
        }
        return
    }
    val doc = state.edit.doc
    var error by remember { mutableStateOf<String?>(null) }

    val analysis by produceState<Analysis?>(null, doc.sourceAssetId, doc.phaseFromAssetId, doc.size, routed) {
        value = null
        error = null
        value = renderSafely({ error = it }) {
            fun spectraOf(id: String?): Spectra? = id?.let { lineage[it] }?.let { decodeAsset(store, it, doc.size * 2) }?.let { bmp ->
                val sq = squareCrop(bmp, doc.size)
                forward(sq).also { sq.recycle(); bmp.recycle() }
            }
            // A rack cable into FFT replaces the picked image.
            val live = routed?.let { r -> squareCrop(r, doc.size).let { sq -> forward(sq).also { if (sq !== r) sq.recycle() } } }
            (live ?: spectraOf(doc.sourceAssetId))?.let { Analysis(it, spectraOf(doc.phaseFromAssetId)) }
        }
        if (value == null && error == null) error = "Can't read this image (deleted, or a format like DNG). Pick another."
    }
    // Gain, spectrum picture and result follow every edit.
    // The third part is the edit the pictures were made from, so the room can say when they are out of date.
    val pictures by produceState<Triple<ImageBitmap, Bitmap, FftDoc>?>(null, analysis, doc, state.view, state.colormap) {
        val a = analysis ?: return@produceState
        val started = System.nanoTime()
        value = renderSafely({ error = it }) {
            val gain = SpectrumMask.build(doc)
            ensureActive()
            val result = inverse(a.spectra, gain, a.phase) { ensureActive() }
            val shown = when (state.view) {
                FftView.Result -> result
                FftView.Magnitude -> spectrumBitmap(a.spectra, gain, phase = false, state.colormap)
                FftView.Phase -> spectrumBitmap(a.spectra, gain, phase = true, Colormap.Twilight)
            }
            Triple(shown.asImageBitmap(), result, doc)
        }
        Debug.t("fft", "rendered ${doc.size}x${doc.size} ${state.view.name} with ${doc.dabs.size} dabs in ${(System.nanoTime() - started) / 1_000_000} ms")
    }
    val updating = pictures != null && pictures?.third != doc
    var stroke by remember { mutableIntStateOf(0) }

    dk.cbavf.studio.ui.layout.RoomSplit(canvas = {
        Box(Modifier.fillMaxSize().abHere(), contentAlignment = Alignment.Center) {
            when {
                doc.sourceAssetId == null && routed == null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Pick a Library image to see its spectrum.", style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { picking = "source" }) { Text("Pick image") }
                }
                pictures == null && error != null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(error!!, Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { picking = "source" }) { Text("Pick image") }
                }
                pictures == null -> Text("Transforming ${doc.size}×${doc.size}…")
                else -> Box(Modifier.aspectRatio(1f)) {
                    Image(pictures!!.first, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit, filterQuality = FilterQuality.None)
                    if (state.view == FftView.Result) {
                        // The result is an image, not a spectrum: a touch here goes to the spectrum to paint.
                        Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { state.view = FftView.Magnitude } })
                    } else {
                        // Painting on the spectrum: centred coordinates, −0.5..0.5.
                        Box(
                            Modifier.fillMaxSize()
                                .pointerInput(state.brush, state.brushRadius) {
                                    fun dab(o: Offset) = SpectrumDab(o.x / size.width - 0.5f, o.y / size.height - 0.5f, state.brushRadius, state.brush)
                                    detectTapGestures { o -> state.edit.edit { it.copy(dabs = it.dabs + dab(o)) } }
                                }
                                .pointerInput(state.brush, state.brushRadius) {
                                    detectDragGestures(onDragStart = { stroke++ }) { change, _ ->
                                        val d = SpectrumDab(change.position.x / size.width - 0.5f, change.position.y / size.height - 0.5f, state.brushRadius, state.brush)
                                        val last = state.edit.doc.dabs.lastOrNull()
                                        if (last == null || kotlin.math.hypot(last.x - d.x, last.y - d.y) > d.radius / 2) {
                                            state.edit.edit("spectrum$stroke") { it.copy(dabs = it.dabs + d) }
                                        }
                                    }
                                }
                        )
                        // Where paint went, with its mirror through the centre.
                        Canvas(Modifier.fillMaxSize()) {
                            for (d in doc.dabs) {
                                val color = when (d.brush) {
                                    SpectrumBrush.Erase -> Color(0xFFFF5252)
                                    SpectrumBrush.Keep -> Color(0xFF69F0AE)
                                    SpectrumBrush.Boost -> Color(0xFFFFD740)
                                }
                                for (sign in listOf(1f, -1f)) {
                                    val c = Offset((0.5f + sign * d.x) * size.width, (0.5f + sign * d.y) * size.height)
                                    drawCircle(color.copy(alpha = 0.6f), d.radius * size.width, c, style = Stroke(width = 2f))
                                }
                            }
                        }
                    }
                }
            }
            Surface(
                Modifier.align(Alignment.TopStart).padding(8.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                shape = MaterialTheme.shapes.small,
            ) {
                Text(
                    (if (liveFrom != null) "Live from $liveFrom · " else "") + doc.summary + (if (state.view != FftView.Result) " · paint to ${state.brush.name.lowercase()}" else " · tap to paint on the spectrum") +
                        if (updating) " · updating…" else "",
                    Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            dk.cbavf.studio.abcompare.AbLayer(pictures?.first, null, "Library image")
        }
    }, controls = {
        Surface(tonalElevation = 2.dp) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                ScrollRow {
                    Button(onClick = { pictures?.second?.let(onSave) }, enabled = pictures != null && !busy) { Text(if (busy) "Saving…" else "Save") }
                    dk.cbavf.studio.abcompare.LocalAb.current?.let { abc -> TextButton(onClick = { abc.state.snapshot = pictures?.first }, enabled = pictures != null) { Text("Snap B") } }
                    FftView.entries.forEach { v -> FilterChip(selected = state.view == v, onClick = { state.view = v }, label = { Text(v.name) }) }
                    TextButton(onClick = state.edit::undo, enabled = state.edit.canUndo) { Text("Undo") }
                    TextButton(onClick = state.edit::redo, enabled = state.edit.canRedo) { Text("Redo") }
                    TextButton(onClick = { picking = "source" }) { Text("Image…") }
                    dk.cbavf.studio.ui.LiveInputButton(liveFrom, onUnplug)
                }
                HorizontalDivider()
                Column(Modifier.panelScroll(280.dp)) {
                    ScrollRow {
                        Text("Size", style = MaterialTheme.typography.labelMedium)
                        listOf(256, 512, 1024).forEach { n ->
                            FilterChip(selected = doc.size == n, onClick = { state.edit.edit { it.copy(size = n) } }, label = { Text("$n") })
                        }
                        Text("Map", style = MaterialTheme.typography.labelMedium)
                        Colormap.entries.forEach { m -> FilterChip(selected = state.colormap == m, onClick = { state.colormap = m }, label = { Text(m.name) }) }
                    }
                    ScrollRow {
                        Text("Filter", style = MaterialTheme.typography.labelMedium)
                        FilterShape.entries.forEach { f -> FilterChip(selected = doc.filter == f, onClick = { state.edit.edit { it.copy(filter = f) } }, label = { Text(f.name) }) }
                    }
                    if (doc.filter != FilterShape.None) {
                        LabeledSlider("Radius", doc.radius, 0.005f..0.5f, "") { v -> state.edit.edit("radius") { it.copy(radius = v) } }
                        if (doc.filter == FilterShape.BandPass) {
                            LabeledSlider("Band", doc.bandWidth, 0.005f..0.3f, "") { v -> state.edit.edit("band") { it.copy(bandWidth = v) } }
                        }
                    }
                    ScrollRow {
                        Text("Brush", style = MaterialTheme.typography.labelMedium)
                        SpectrumBrush.entries.forEach { b ->
                            FilterChip(selected = state.brush == b, onClick = {
                                state.brush = b
                                // Brushes paint on the spectrum, so show it.
                                if (state.view == FftView.Result) state.view = FftView.Magnitude
                            }, label = { Text(b.name) })
                        }
                        TextButton(onClick = { state.edit.edit { it.copy(dabs = emptyList()) } }, enabled = doc.dabs.isNotEmpty()) { Text("Clear paint") }
                    }
                    LabeledSlider("Brush size", state.brushRadius, 0.002f..0.1f, "") { state.brushRadius = it }
                    if (state.brush == SpectrumBrush.Boost) {
                        LabeledSlider("Boost", doc.boost, 1f..20f, "×", format = { "${(it * 10).roundToInt() / 10f}" }) { v -> state.edit.edit("boost") { it.copy(boost = v) } }
                    }
                    ScrollRow {
                        Text("Phase from", style = MaterialTheme.typography.labelMedium)
                        TextButton(onClick = { picking = "phase" }) { Text(if (doc.phaseFromAssetId == null) "Another image… (swap)" else "Change…") }
                        if (doc.phaseFromAssetId != null) TextButton(onClick = { state.edit.edit { it.copy(phaseFromAssetId = null) } }) { Text("Own phase") }
                    }
                    Text(
                        "Paint is mirrored through the centre, since a real image's spectrum is symmetric. " +
                            "The phase swap keeps this image's magnitude and takes the other's phase.",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    })
}
