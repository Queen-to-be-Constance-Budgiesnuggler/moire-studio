package dk.cbavf.studio.math

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import dk.cbavf.studio.library.Lineage
import dk.cbavf.studio.library.ProjectStore
import dk.cbavf.studio.ui.LabeledSlider
import dk.cbavf.studio.ui.ScrollRow
import dk.cbavf.studio.ui.layout.panelScrollOpen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the Math room shows: the function, the picture it is tried on, and how. Kept while the studio is open. */
class MathState {
    var functionId by mutableStateOf(MathFunctions.all.first().id)

    /** The Library image the curve is tried on; null means the newest one. */
    var sourceId by mutableStateOf<String?>(null)
    var mix by mutableFloatStateOf(1f)
    var channel by mutableStateOf(MathChannel.All)

    /** Shows the picture as it was, without the curve. */
    var before by mutableStateOf(false)
}

/** Points along a plotted curve. */
private const val PLOT_SAMPLES = 200

/** The picture is tried at this size; saving uses [SAVE_PX]. */
private const val PREVIEW_PX = 1080
private const val SAVE_PX = 2048

/** A decoded picture and its pixels, read once so each change only maps levels. */
private class Source(val id: String, val bitmap: Bitmap, val pixels: IntArray)

private fun Bitmap.argb(): IntArray = IntArray(width * height).also { getPixels(it, 0, width, 0, 0, width, height) }

/**
 * The Math room: the studio's curves (envelope shapes, the clock gate, the sensor average) listed with what they mean,
 * plotted as they are with the [MathParams] now, with those parameters changeable here. The chosen curve can be used as
 * a tone curve on a Library image and saved as a child of it.
 */
@Composable
fun MathRoom(
    state: MathState,
    store: ProjectStore,
    lineage: Lineage,
    busy: Boolean,
    /** A parameter was changed here: save the overrides, as Studio → Config does. */
    onParamsChanged: () -> Unit,
    /** Files the result in the Library with the source image as its parent. */
    onSave: (Bitmap, String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val fn = MathFunctions.byId(state.functionId) ?: MathFunctions.all.first()
    var picking by remember { mutableStateOf(false) }
    var preparing by remember { mutableStateOf(false) }
    if (picking) {
        dk.cbavf.studio.ui.LibraryPicker(store, lineage, single = true) { ids -> picking = false; ids.firstOrNull()?.let { state.sourceId = it } }
        return
    }
    val record = (state.sourceId?.let { lineage[it] } ?: lineage.newestFirst().firstOrNull { it.isStill })?.takeIf { it.isStill }
    val source by produceState<Source?>(null, record?.id) {
        val r = record
        value = if (r == null) null else withContext(Dispatchers.IO) {
            runCatching { dk.cbavf.studio.develop.decodeAsset(store, r, PREVIEW_PX)?.let { Source(r.id, it, it.argb()) } }.getOrNull()
        }
    }
    // Reading the function here reads its MathParams, so a changed parameter gives a new table and a new picture.
    val table = MathPlot.table(fn::invoke)
    val tableKey = table.contentHashCode()
    val result by produceState<Bitmap?>(null, source, tableKey, state.mix, state.channel) {
        val s = source
        value = if (s == null) null else withContext(Dispatchers.Default) {
            Bitmap.createBitmap(MathPlot.apply(s.pixels, table, state.mix, state.channel), s.bitmap.width, s.bitmap.height, Bitmap.Config.ARGB_8888)
        }
    }

    dk.cbavf.studio.ui.layout.RoomSplit(canvas = {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val shown = if (state.before) source?.bitmap else result
            if (shown != null) Image(shown.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            else Text(
                if (record == null) "Add an image to the Library first. The curve is tried on it here." else "Reading the image…",
                modifier = Modifier.padding(24.dp), style = MaterialTheme.typography.bodyMedium,
            )
        }
    }, controls = {
        Surface(tonalElevation = 2.dp) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).panelScrollOpen(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                ScrollRow {
                    MathFunctions.all.forEach { f -> FilterChip(selected = f.id == fn.id, onClick = { state.functionId = f.id }, label = { Text(f.title) }) }
                }
                Text(fn.description, style = MaterialTheme.typography.bodySmall)
                Plot(fn)
                fn.params.mapNotNull(MathParams::param).forEach { p ->
                    LabeledSlider(
                        p.key.substringAfter('.'), MathParams.get(p.key).toFloat(), p.min.toFloat()..p.max.toFloat(), if (p.unit.isEmpty()) "" else " " + p.unit,
                        format = { "%.3f".format(it) },
                    ) { v -> MathParams.set(p.key, v.toDouble()); onParamsChanged() }
                    Text(p.description, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { MathParams.reset(p.key); onParamsChanged() }, enabled = MathParams.isChanged(p.key)) { Text("Reset to ${p.default}") }
                }
                ScrollRow {
                    Text("Apply to", Modifier.width(72.dp), style = MaterialTheme.typography.labelMedium)
                    MathChannel.entries.forEach { c -> FilterChip(selected = state.channel == c, onClick = { state.channel = c }, label = { Text(c.label) }) }
                    FilterChip(selected = state.before, onClick = { state.before = !state.before }, label = { Text("Before") })
                }
                LabeledSlider("Mix", state.mix * 100, 0f..100f, " %", format = { "${it.toInt()}" }) { state.mix = it / 100 }
                ScrollRow {
                    TextButton(onClick = { picking = true }) { Text("Pick image…") }
                    source?.let { Text("${it.bitmap.width}×${it.bitmap.height} preview", style = MaterialTheme.typography.labelMedium) }
                }
                Button(
                    enabled = record != null && source != null && !busy && !preparing,
                    onClick = {
                        val r = record ?: return@Button
                        val mix = state.mix
                        val channel = state.channel
                        preparing = true
                        scope.launch {
                            // The saved picture is made larger than the preview, from the file again.
                            val out = withContext(Dispatchers.Default) {
                                runCatching {
                                    dk.cbavf.studio.develop.decodeAsset(store, r, SAVE_PX)?.let { b ->
                                        Bitmap.createBitmap(MathPlot.apply(b.argb(), table, mix, channel), b.width, b.height, Bitmap.Config.ARGB_8888)
                                    }
                                }.getOrNull()
                            }
                            preparing = false
                            if (out != null) onSave(out, r.id)
                        }
                    },
                ) { Text(if (busy || preparing) "Saving…" else "Save to Library") }
            }
        }
    })
}

/** The function from x = 0 to 1 in a square, with the straight line y = x behind it for comparison. */
@Composable
private fun Plot(fn: MathFunction) {
    val line = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    Canvas(Modifier.fillMaxWidth().height(160.dp).padding(vertical = 4.dp)) {
        val pad = 6.dp.toPx()
        val side = minOf(size.width, size.height)
        val left = (size.width - side) / 2
        // Drawing reads the function, and so its MathParams: a changed parameter redraws the plot.
        val pts = MathPlot.points(MathPlot.samples(fn::invoke, PLOT_SAMPLES), side, side, pad)
        drawRect(grid, Offset(left + pad, pad), androidx.compose.ui.geometry.Size(side - 2 * pad, side - 2 * pad), style = Stroke(1.dp.toPx()))
        drawLine(grid, Offset(left + pad, side - pad), Offset(left + side - pad, pad), strokeWidth = 1.dp.toPx())
        val path = Path().apply {
            moveTo(left + pts[0], pts[1])
            for (i in 1 until pts.size / 2) lineTo(left + pts[2 * i], pts[2 * i + 1])
        }
        drawPath(path, line, style = Stroke(2.dp.toPx()))
    }
}
