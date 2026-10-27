package dk.cbavf.studio.plugins

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import kotlin.math.ln

/**
 * Waveform traces side by side on a dark scope face ([PluginScopes.traces]): one trace in pale green, three in red, green
 * and blue (the parade). Left to right as in the picture, up is brighter; a busy cell is brighter (on a log scale).
 */
@Composable
fun ScopeTraces(waves: List<IntArray>?, cols: Int, bins: Int, modifier: Modifier = Modifier) {
    Canvas(modifier.background(Color(0xEE080808))) {
        for (q in 0..4) drawLine(Color(0x33FFFFFF), Offset(0f, size.height * q / 4), Offset(size.width, size.height * q / 4))
        val ws = waves?.takeIf { it.isNotEmpty() } ?: return@Canvas
        val tints = if (ws.size == 1) listOf(Color(0xFFDDEEDD)) else listOf(Color(0xFFFF5050), Color(0xFF50FF50), Color(0xFF5090FF))
        val partW = size.width / ws.size
        ws.forEachIndexed { k, wv ->
            val peak = ln(1f + (wv.maxOrNull() ?: 1))
            val cw = partW / cols
            val ch = size.height / bins
            for (col in 0 until cols) for (bin in 0 until bins) {
                val n = wv[col * bins + bin]
                if (n == 0) continue
                val a = (ln(1f + n) / peak).coerceIn(0.08f, 1f)
                drawRect(tints[k % tints.size].copy(alpha = a), Offset(k * partW + col * cw, size.height - (bin + 1) * ch), Size(cw + 0.5f, ch + 0.5f))
            }
        }
    }
}
