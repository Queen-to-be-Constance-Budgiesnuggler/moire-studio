package dk.cbavf.studio.develop

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dk.cbavf.studio.develop.model.Analysis
import dk.cbavf.studio.develop.model.ColorMath
import dk.cbavf.studio.ui.LabeledSlider
import dk.cbavf.studio.ui.ScrollRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import dk.cbavf.studio.channels.ChannelValue
import dk.cbavf.studio.channels.ColorSpace
import kotlin.math.ln
import kotlin.math.min

enum class DeepTab(val label: String) { Probe("Probe"), Line("Line"), Region("Region"), Clipping("Clipping"), Waveform("Waveform"), Height("3D") }

/** An image's pixels, read once for every tool. */
private class Pixels(val w: Int, val h: Int, val px: IntArray, val shown: ImageBitmap)

/**
 * Deep analysis: a translucent card over the canvas with interactive tools. Probe reads one
 * pixel (RGB, hex, HSL, Lab, brightness, with a loupe); Line plots channels along a drawn line;
 * Region gives statistics inside a dragged box; Clipping marks lost shadows and highlights;
 * Waveform shows brightness (or an RGB parade) across the width; 3D turns the image into a height
 * map you can turn and tilt.
 */
@Composable
fun DeepAnalysis(image: Bitmap, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val data by produceState<Pixels?>(null, image) {
        value = withContext(Dispatchers.Default) {
            val k = min(1f, 1200f / maxOf(image.width, image.height))
            val b = if (k < 1f) Bitmap.createScaledBitmap(image, (image.width * k).toInt().coerceAtLeast(1), (image.height * k).toInt().coerceAtLeast(1), true) else image
            val px = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }
            Pixels(b.width, b.height, px, b.asImageBitmap())
        }
    }
    var tab by remember { mutableStateOf(DeepTab.Probe) }
    Surface(
        modifier,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 8.dp,
        shadowElevation = 12.dp,
    ) {
        Column(Modifier.fillMaxSize().padding(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Deep analysis", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = onClose) { Text("✕ Close") }
            }
            ScrollRow { DeepTab.entries.forEach { t -> FilterChip(selected = tab == t, onClick = { tab = t }, label = { Text(t.label) }) } }
            val d = data
            if (d == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Reading pixels…") }
                return@Column
            }
            when (tab) {
                DeepTab.Probe -> ProbeTool(d)
                DeepTab.Line -> LineTool(d)
                DeepTab.Region -> RegionTool(d)
                DeepTab.Clipping -> ClippingTool(d)
                DeepTab.Waveform -> WaveformTool(d)
                DeepTab.Height -> HeightTool(d)
            }
        }
    }
}

/** Where a w × h image sits when fitted into [size]. */
private fun fit(size: Size, w: Int, h: Int): Rect {
    val k = min(size.width / w, size.height / h)
    val dw = w * k
    val dh = h * k
    return Rect(Offset((size.width - dw) / 2, (size.height - dh) / 2), Size(dw, dh))
}

private fun DrawScope.drawFitted(img: ImageBitmap, r: Rect) =
    drawImage(img, dstOffset = IntOffset(r.left.toInt(), r.top.toInt()), dstSize = IntSize(r.width.toInt(), r.height.toInt()))

/** The image, fitted, reporting touches in image pixels (down, move, up). */
@Composable
private fun ImageArea(
    d: Pixels,
    modifier: Modifier,
    onTouch: (start: Offset, now: Offset, done: Boolean) -> Unit,
    overlay: DrawScope.(Rect) -> Unit,
) {
    Canvas(
        modifier.pointerInput(d) {
            awaitEachGesture {
                val r = fit(Size(size.width.toFloat(), size.height.toFloat()), d.w, d.h)
                fun img(o: Offset) = Offset(((o.x - r.left) / r.width * d.w).coerceIn(0f, d.w - 1f), ((o.y - r.top) / r.height * d.h).coerceIn(0f, d.h - 1f))
                val down = awaitFirstDown()
                val start = img(down.position)
                onTouch(start, start, false)
                do {
                    val e = awaitPointerEvent()
                    e.changes.firstOrNull()?.let { onTouch(start, img(it.position), !it.pressed); it.consume() }
                } while (e.changes.any { it.pressed })
            }
        }
    ) {
        val r = fit(size, d.w, d.h)
        drawFitted(d.shown, r)
        overlay(r)
    }
}

private fun toScreen(r: Rect, d: Pixels, p: Offset) = Offset(r.left + p.x / d.w * r.width, r.top + p.y / d.h * r.height)

@Composable
private fun Mono(text: String) = Text(text, fontFamily = FontFamily.Monospace, fontSize = 12.sp)

// --- Probe ----------------------------------------------------------------------------------------

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.ProbeTool(d: Pixels) {
    var at by remember { mutableStateOf(Offset(d.w / 2f, d.h / 2f)) }
    ImageArea(d, Modifier.weight(1f).fillMaxWidth(), { _, now, _ -> at = now }) { r ->
        val p = toScreen(r, d, at)
        drawLine(Color.White, Offset(p.x - 16, p.y), Offset(p.x + 16, p.y), 2f)
        drawLine(Color.White, Offset(p.x, p.y - 16), Offset(p.x, p.y + 16), 2f)
        drawCircle(Color.Black, 18f, p, style = Stroke(1.5f))
    }
    val x = at.x.toInt()
    val y = at.y.toInt()
    val c = d.px[y * d.w + x]
    val rr = Analysis.r(c); val gg = Analysis.g(c); val bb = Analysis.b(c)
    val hsl = FloatArray(3).also { ColorMath.rgbToHsl(rr / 255f, gg / 255f, bb / 255f, it) }
    val lab = Analysis.lab(rr, gg, bb)
    Row(Modifier.fillMaxWidth().height(156.dp).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        // Loupe: 11 × 11 pixels around the probe.
        Canvas(Modifier.size(120.dp).background(Color.Black)) {
            val n = 11
            val cell = size.width / n
            for (j in 0 until n) for (i in 0 until n) {
                val px = (x + i - n / 2).coerceIn(0, d.w - 1)
                val py = (y + j - n / 2).coerceIn(0, d.h - 1)
                drawRect(Color(d.px[py * d.w + px]), Offset(i * cell, j * cell), Size(cell, cell))
            }
            drawRect(Color.White, Offset(n / 2 * cell, n / 2 * cell), Size(cell, cell), style = Stroke(2f))
        }
        Column {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(22.dp).background(Color(c)))
                Mono("#%02X%02X%02X  at %d, %d".format(rr, gg, bb, x, y))
            }
            Mono("RGB  %3d %3d %3d".format(rr, gg, bb))
            Mono("%%    %5.1f %5.1f %5.1f".format(rr / 2.55f, gg / 2.55f, bb / 2.55f))
            Mono("HSL  %5.1f° %4.0f%% %4.0f%%".format(hsl[0], hsl[1] * 100, hsl[2] * 100))
            Mono("Lab  %5.1f %6.1f %6.1f".format(lab[0], lab[1], lab[2]))
            Mono("Brightness %5.1f %%".format(Analysis.luma(c) * 100))
        }
    }
}

// --- Line -----------------------------------------------------------------------------------------

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.LineTool(d: Pixels) {
    var line by remember { mutableStateOf(Offset(0f, d.h / 2f) to Offset(d.w - 1f, d.h / 2f)) }
    ImageArea(d, Modifier.weight(1f).fillMaxWidth(), { start, now, _ -> line = start to now }) { r ->
        val a = toScreen(r, d, line.first)
        val b = toScreen(r, d, line.second)
        drawLine(Color.Black, a, b, 5f)
        drawLine(Color.White, a, b, 2.5f)
        drawCircle(Color.White, 7f, a)
        drawCircle(Color(0xFFFFC94D), 7f, b)
    }
    val profile = remember(line, d) { Analysis.lineProfile(d.px, d.w, d.h, line.first.x, line.first.y, line.second.x, line.second.y) }
    Text("Drag across the image: red, green, blue and brightness along the line (white dot → yellow dot).", style = MaterialTheme.typography.labelSmall)
    androidx.compose.material3.TextButton(onClick = { dk.cbavf.studio.sonify.Sonify.play(dk.cbavf.studio.sonify.Sonify.render(FloatArray(profile.size) { Analysis.luma(profile[it]) })) }) { Text("Hear this line") }
    Canvas(Modifier.fillMaxWidth().height(120.dp).background(Color(0xCC101010))) {
        for (q in 1..3) drawLine(Color(0x22FFFFFF), Offset(0f, size.height * q / 4), Offset(size.width, size.height * q / 4))
        fun curve(color: Color, v: (Int) -> Float) {
            val path = Path()
            profile.forEachIndexed { i, c ->
                val px = i / (profile.size - 1f) * size.width
                val py = size.height * (1f - v(c))
                if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            drawPath(path, color, style = Stroke(1.5f))
        }
        curve(Color(0xFFFF5050)) { Analysis.r(it) / 255f }
        curve(Color(0xFF50FF50)) { Analysis.g(it) / 255f }
        curve(Color(0xFF5090FF)) { Analysis.b(it) / 255f }
        curve(Color.White) { Analysis.luma(it) }
    }
}

// --- Region ---------------------------------------------------------------------------------------

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.RegionTool(d: Pixels) {
    var box by remember { mutableStateOf(Offset(d.w * 0.25f, d.h * 0.25f) to Offset(d.w * 0.75f, d.h * 0.75f)) }
    ImageArea(d, Modifier.weight(1f).fillMaxWidth(), { start, now, _ -> box = start to now }) { r ->
        val a = toScreen(r, d, box.first)
        val b = toScreen(r, d, box.second)
        val tl = Offset(min(a.x, b.x), min(a.y, b.y))
        val sz = Size(kotlin.math.abs(a.x - b.x), kotlin.math.abs(a.y - b.y))
        drawRect(Color(0x33FFC94D), tl, sz)
        drawRect(Color(0xFFFFC94D), tl, sz, style = Stroke(2f))
    }
    val stats by produceState<Analysis.Stats?>(null, box, d) {
        value = withContext(Dispatchers.Default) {
            Analysis.regionStats(d.px, d.w, d.h, box.first.x.toInt(), box.first.y.toInt(), box.second.x.toInt(), box.second.y.toInt())
        }
    }
    Column(Modifier.fillMaxWidth().height(140.dp).padding(top = 4.dp)) {
        val s = stats ?: return@Column
        Mono("Region ${s.count} px · ${s.colours} colours · entropy %.2f bits".format(s.entropy))
        Mono("        R      G      B   Light")
        Mono("mean  %5.1f  %5.1f  %5.1f  %5.1f".format(s.mean[0], s.mean[1], s.mean[2], s.mean[3]))
        Mono("std   %5.1f  %5.1f  %5.1f  %5.1f".format(s.std[0], s.std[1], s.std[2], s.std[3]))
        Mono("min   %5d  %5d  %5d  %5d".format(s.min[0], s.min[1], s.min[2], s.min[3]))
        Mono("med   %5d  %5d  %5d  %5d".format(s.median[0], s.median[1], s.median[2], s.median[3]))
        Mono("max   %5d  %5d  %5d  %5d".format(s.max[0], s.max[1], s.max[2], s.max[3]))
        Mono("clipped: highlights %.2f %% · shadows %.2f %%".format(s.clippedHigh * 100, s.clippedLow * 100))
    }
}

// --- Clipping -------------------------------------------------------------------------------------

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.ClippingTool(d: Pixels) {
    var hi by remember { mutableFloatStateOf(250f) }
    var lo by remember { mutableFloatStateOf(5f) }
    val marks by produceState<Triple<ImageBitmap, Float, Float>?>(null, d, hi, lo) {
        value = withContext(Dispatchers.Default) {
            var nh = 0
            var nl = 0
            val out = IntArray(d.px.size) { i ->
                val c = d.px[i]
                val r = Analysis.r(c); val g = Analysis.g(c); val b = Analysis.b(c)
                when {
                    r >= hi || g >= hi || b >= hi -> { nh++; 0xFFFF2040.toInt() }
                    r <= lo && g <= lo && b <= lo -> { nl++; 0xFF2060FF.toInt() }
                    else -> 0
                }
            }
            Triple(Bitmap.createBitmap(out, d.w, d.h, Bitmap.Config.ARGB_8888).asImageBitmap(), nh * 100f / out.size, nl * 100f / out.size)
        }
    }
    ImageArea(d, Modifier.weight(1f).fillMaxWidth(), { _, _, _ -> }) { r -> marks?.first?.let { drawFitted(it, r) } }
    marks?.let { (_, h, l) -> Mono("Red: a channel at %.0f+ · %.2f %%   Blue: all at %.0f or less · %.2f %%".format(hi, h, lo, l)) }
    LabeledSlider("Highlights from", hi, 200f..255f, "", format = { "${it.toInt()}" }) { hi = it }
    LabeledSlider("Shadows to", lo, 0f..60f, "", format = { "${it.toInt()}" }) { lo = it }
}

// --- Waveform -------------------------------------------------------------------------------------

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.WaveformTool(d: Pixels) {
    var parade by remember { mutableStateOf(false) }
    val bins = 128
    val cols = 256
    val waves by produceState<List<IntArray>?>(null, d, parade) {
        value = withContext(Dispatchers.Default) {
            if (parade) (0..2).map { Analysis.waveform(d.px, d.w, d.h, cols, bins, it) } else listOf(Analysis.waveform(d.px, d.w, d.h, cols, bins))
        }
    }
    ScrollRow {
        FilterChip(selected = !parade, onClick = { parade = false }, label = { Text("Brightness") })
        FilterChip(selected = parade, onClick = { parade = true }, label = { Text("RGB parade") })
    }
    dk.cbavf.studio.plugins.ScopeTraces(waves, cols, bins, Modifier.weight(1f).fillMaxWidth())
    Text("Left to right as in the image; up is brighter. Lines along the top or bottom are clipping.", style = MaterialTheme.typography.labelSmall)
}

// --- 3D height map --------------------------------------------------------------------------------

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.HeightTool(d: Pixels) {
    // Luma (the default) or one channel of RGB, CMYK or Lab as the height.
    var space by remember { mutableStateOf<ColorSpace?>(null) }
    var channel by remember { mutableIntStateOf(0) }
    val grid = remember(d, space, channel) { Analysis.heights(d.px, d.w, d.h, 64, space, channel) }
    var heat by remember { mutableStateOf(false) }
    var overlay by remember { mutableStateOf(false) }
    var scale by remember { mutableFloatStateOf(0.6f) }
    // The three channels of the chosen space as three meshes at once, each tinted and added together.
    val overlayGrids = remember(d, space, overlay) {
        val sp = space ?: ColorSpace.RGBA
        if (!overlay) emptyList() else {
            val tints = if (sp == ColorSpace.RGBA) listOf(Color.Red, Color.Green, Color.Blue) else listOf(Color.Cyan, Color.Magenta, Color.Yellow)
            (0 until 3).map { c -> Analysis.heights(d.px, d.w, d.h, 64, sp, c) to tints[c] }
        }
    }
    ScrollRow {
        Text("Height", Modifier.width(56.dp), style = MaterialTheme.typography.labelMedium)
        FilterChip(selected = space == null, onClick = { space = null; channel = 0 }, label = { Text("Luma") })
        ColorSpace.entries.forEach { sp ->
            FilterChip(selected = space == sp, onClick = { space = sp; channel = 0 }, label = { Text(sp.name) })
        }
        FilterChip(selected = overlay, onClick = { overlay = !overlay }, label = { Text("3 channels") })
    }
    space?.let { sp ->
        ScrollRow {
            sp.channelNames.take(ChannelValue.colorChannels(sp)).forEachIndexed { i, name ->
                FilterChip(selected = channel == i, onClick = { channel = i }, label = { Text(name) })
            }
        }
    }
    ScrollRow {
        FilterChip(selected = !heat, onClick = { heat = false }, label = { Text("Image colours") })
        FilterChip(selected = heat, onClick = { heat = true }, label = { Text("Heat") })
        Text("drag: turn and tilt · pinch: height", style = MaterialTheme.typography.labelSmall)
    }
    HeightMap(grid, heat, scale, Modifier.weight(1f).fillMaxWidth(), onScale = { scale = (scale * it).coerceIn(0.05f, 3f) }, overlay = overlayGrids)
    LabeledSlider("Height", scale * 100, 5f..300f, " %", format = { "${it.toInt()}" }) { scale = it / 100 }
}

/** Heat colour for a 0..1 value (black → red → yellow → white). */
fun heatColor(v: Float): Color {
    val t = v.coerceIn(0f, 1f)
    return Color(min(1f, t * 3f), (t * 3f - 1f).coerceIn(0f, 1f), (t * 3f - 2f).coerceIn(0f, 1f))
}

/**
 * The image as a 3D height map (brightness = height): a shaded mesh of the grid's cells drawn back
 * to front. Drag to turn (sideways) and tilt (up and down); pinch to change the height.
 */
@Composable
fun HeightMap(
    grid: Analysis.Heights,
    heat: Boolean,
    heightScale: Float,
    modifier: Modifier,
    onScale: (Float) -> Unit = {},
    /** When not empty: these grids are drawn instead, each in its tint, added together (the three-channel view). */
    overlay: List<Pair<Analysis.Heights, Color>> = emptyList(),
) {
    var yaw by remember { mutableFloatStateOf(35f) }
    var pitch by remember { mutableFloatStateOf(55f) }
    Canvas(
        modifier
            .background(Color(0xEE0A0A0C))
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown()
                    do {
                        val e = awaitPointerEvent()
                        val p = e.calculatePan()
                        if (e.changes.count { it.pressed } >= 2) onScale(e.calculateZoom())
                        else {
                            yaw += p.x * 0.4f
                            pitch = (pitch - p.y * 0.3f).coerceIn(10f, 89f)
                        }
                        e.changes.forEach { it.consume() }
                    } while (e.changes.any { it.pressed })
                }
            }
    ) {
        val k = min(size.width, size.height) * 0.42f
        val cx = size.width / 2
        val cy = size.height / 2 + k * 0.15f
        /** One mesh: cells farthest first, shaded by their slope towards a light from the upper left. */
        fun mesh(g: Analysis.Heights, colourOf: (Int, Float) -> Color, blend: androidx.compose.ui.graphics.BlendMode) {
            val gw = g.gw
            val gh = g.gh
            val pts = Array(gw * gh) { i -> Analysis.project((i % gw).toFloat(), (i / gw).toFloat(), g.height[i], gw, gh, yaw, pitch, heightScale) }
            fun s(i: Int) = Offset(cx + pts[i][0] * k, cy + pts[i][1] * k)
            val cells = ArrayList<Int>((gw - 1) * (gh - 1))
            for (y in 0 until gh - 1) for (x in 0 until gw - 1) cells += y * gw + x
            cells.sortByDescending { i -> pts[i][2] + pts[i + 1][2] + pts[i + gw][2] + pts[i + gw + 1][2] }
            val path = Path()
            for (i in cells) {
                val dzx = g.height[i + 1] - g.height[i]
                val dzy = g.height[i + gw] - g.height[i]
                val shade = (0.75f - (dzx + dzy) * 4f * heightScale).coerceIn(0.35f, 1.15f)
                val base = colourOf(i, g.height[i])
                val col = Color((base.red * shade).coerceIn(0f, 1f), (base.green * shade).coerceIn(0f, 1f), (base.blue * shade).coerceIn(0f, 1f))
                path.reset()
                val a = s(i); val b = s(i + 1); val c = s(i + gw + 1); val e = s(i + gw)
                path.moveTo(a.x, a.y); path.lineTo(b.x, b.y); path.lineTo(c.x, c.y); path.lineTo(e.x, e.y); path.close()
                drawPath(path, col, blendMode = blend)
            }
        }
        if (overlay.isEmpty()) {
            mesh(grid, { i, h -> if (heat) heatColor(h) else Color(grid.colour[i]) }, androidx.compose.ui.graphics.BlendMode.SrcOver)
        } else {
            // Added together: where two channels are high at once their tints mix (red and green make yellow).
            overlay.forEach { (g, tint) -> mesh(g, { _, h -> Color(tint.red * h, tint.green * h, tint.blue * h) }, androidx.compose.ui.graphics.BlendMode.Plus) }
        }
    }
}
