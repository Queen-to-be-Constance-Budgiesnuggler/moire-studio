package dk.cbavf.studio.develop

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlin.math.ln
import kotlin.math.min

/** Which scope Develop shows over the image (darktable's histogram / vectorscope). */
enum class ScopeKind(val label: String) {
    Off("Scope: off"), Histogram("Histogram"), Vectorscope("Vectorscope"), Height("3D height"),
    /** The last step: a card over the canvas with the interactive tools (DeepAnalysis). */
    Deep("Deep analysis"),
}

/**
 * What the scopes show for one image: 256-bin histograms of red, green, blue and brightness,
 * and a [VECTOR_N]² density of chroma (BT.709 Cb across, Cr up).
 */
class ScopeData(
    val red: IntArray, val green: IntArray, val blue: IntArray, val luma: IntArray, val vector: IntArray,
    /** Brightness as a small height grid, for the 3D view (null when read from bare pixels). */
    val heights: dk.cbavf.studio.develop.model.Analysis.Heights? = null,
) {
    companion object {
        const val VECTOR_N = 96

        /** BT.709 chroma of an RGB colour (0..1), each about −0.5..0.5. */
        fun chroma(r: Float, g: Float, b: Float): Pair<Float, Float> {
            val y = 0.2126f * r + 0.7152f * g + 0.0722f * b
            return (b - y) / 1.8556f to (r - y) / 1.5748f
        }

        /** Scopes of [bmp], read from at most 256 px on the long side (plenty for a scope). */
        fun of(bmp: Bitmap): ScopeData {
            val k = min(1f, 256f / maxOf(bmp.width, bmp.height))
            val small = if (k < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * k).toInt().coerceAtLeast(1), (bmp.height * k).toInt().coerceAtLeast(1), true) else bmp
            val px = IntArray(small.width * small.height).also { small.getPixels(it, 0, small.width, 0, 0, small.width, small.height) }
            val w = small.width
            val h = small.height
            if (small !== bmp) small.recycle()
            val d = of(px)
            return ScopeData(d.red, d.green, d.blue, d.luma, d.vector, dk.cbavf.studio.develop.model.Analysis.heights(px, w, h, 40))
        }

        fun of(px: IntArray): ScopeData {
            val r = IntArray(256); val g = IntArray(256); val b = IntArray(256); val l = IntArray(256)
            val v = IntArray(VECTOR_N * VECTOR_N)
            for (c in px) {
                if (c ushr 24 == 0) continue
                val ri = c shr 16 and 0xFF
                val gi = c shr 8 and 0xFF
                val bi = c and 0xFF
                r[ri]++; g[gi]++; b[bi]++
                l[(ri * 54 + gi * 183 + bi * 19) shr 8]++
                val (cb, cr) = chroma(ri / 255f, gi / 255f, bi / 255f)
                val x = ((cb + 0.5f) * VECTOR_N).toInt().coerceIn(0, VECTOR_N - 1)
                val y = ((0.5f - cr) * VECTOR_N).toInt().coerceIn(0, VECTOR_N - 1)
                v[y * VECTOR_N + x]++
            }
            return ScopeData(r, g, b, l, v)
        }
    }
}

/** The chosen scope, drawn small and translucent over the image's corner. */
@Composable
fun ScopePanel(kind: ScopeKind, data: ScopeData?, modifier: Modifier = Modifier) {
    if (kind == ScopeKind.Off || kind == ScopeKind.Deep || data == null) return
    if (kind == ScopeKind.Height) {
        val grid = data.heights ?: return
        HeightMap(grid, heat = false, heightScale = 0.6f, modifier = modifier.size(190.dp, 150.dp))
        return
    }
    Box(modifier.size(if (kind == ScopeKind.Histogram) 180.dp else 150.dp, if (kind == ScopeKind.Histogram) 100.dp else 150.dp).background(Color(0xB0101010), RoundedCornerShape(6.dp))) {
        Canvas(Modifier.size(if (kind == ScopeKind.Histogram) 180.dp else 150.dp, if (kind == ScopeKind.Histogram) 100.dp else 150.dp)) {
            if (kind == ScopeKind.Histogram) histogram(data) else vectorscope(data)
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.histogram(d: ScopeData) {
    // Scale to the tallest bin away from the ends, so clipped shadows/highlights don't flatten the rest.
    val peak = listOf(d.red, d.green, d.blue).maxOf { h -> (2..253).maxOf { h[it] } }.coerceAtLeast(1)
    fun curve(h: IntArray, color: Color) {
        val path = Path().apply {
            moveTo(0f, size.height)
            for (i in 0..255) lineTo(i / 255f * size.width, size.height * (1f - (h[i].toFloat() / peak).coerceAtMost(1f)))
            lineTo(size.width, size.height)
            close()
        }
        drawPath(path, color)
    }
    // Quarter lines, as in darktable.
    for (q in 1..3) drawLine(Color(0x33FFFFFF), Offset(size.width * q / 4, 0f), Offset(size.width * q / 4, size.height))
    curve(d.red, Color(0x66FF4040))
    curve(d.green, Color(0x6640FF40))
    curve(d.blue, Color(0x664080FF))
    val lumaPath = Path().apply {
        for (i in 0..255) {
            val y = size.height * (1f - (d.luma[i].toFloat() / peak).coerceAtMost(1f))
            if (i == 0) moveTo(0f, y) else lineTo(i / 255f * size.width, y)
        }
    }
    drawPath(lumaPath, Color(0xCCFFFFFF), style = Stroke(width = 1.5f))
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.vectorscope(d: ScopeData) {
    val n = ScopeData.VECTOR_N
    val cell = size.width / n
    val centre = Offset(size.width / 2, size.height / 2)
    // Graticule: the gamut circle and targets for the primaries and secondaries at 75 %.
    drawCircle(Color(0x44FFFFFF), size.width / 2 * 0.95f, centre, style = Stroke(1f))
    drawLine(Color(0x22FFFFFF), Offset(0f, centre.y), Offset(size.width, centre.y))
    drawLine(Color(0x22FFFFFF), Offset(centre.x, 0f), Offset(centre.x, size.height))
    fun at(r: Float, g: Float, b: Float): Offset {
        val (cb, cr) = ScopeData.chroma(r, g, b)
        return Offset((cb + 0.5f) * size.width, (0.5f - cr) * size.height)
    }
    // Skin-tone line: every complexion falls near it.
    val skin = at(0.85f, 0.62f, 0.48f)
    val dir = (skin - centre).let { it / it.getDistance() }
    drawLine(Color(0x55FFB080), centre, centre + dir * (size.width / 2), strokeWidth = 1f)
    listOf(Triple(0.75f, 0f, 0f), Triple(0.75f, 0.75f, 0f), Triple(0f, 0.75f, 0f), Triple(0f, 0.75f, 0.75f), Triple(0f, 0f, 0.75f), Triple(0.75f, 0f, 0.75f)).forEach { (r, g, b) ->
        val p = at(r, g, b)
        drawRect(Color(r, g, b), p - Offset(4f, 4f), Size(8f, 8f), style = Stroke(1.5f))
    }
    // Density, log-scaled, each cell in the colour it stands for.
    val peak = ln(1f + (d.vector.maxOrNull() ?: 1))
    for (y in 0 until n) for (x in 0 until n) {
        val c = d.vector[y * n + x]
        if (c == 0) continue
        val a = (ln(1f + c) / peak).coerceIn(0.15f, 1f)
        val cb = (x + 0.5f) / n - 0.5f
        val cr = 0.5f - (y + 0.5f) / n
        val yv = 0.6f
        val r = (yv + 1.5748f * cr).coerceIn(0f, 1f)
        val b = (yv + 1.8556f * cb).coerceIn(0f, 1f)
        val g = ((yv - 0.2126f * r - 0.0722f * b) / 0.7152f).coerceIn(0f, 1f)
        drawRect(Color(r, g, b, a), Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
    }
}
