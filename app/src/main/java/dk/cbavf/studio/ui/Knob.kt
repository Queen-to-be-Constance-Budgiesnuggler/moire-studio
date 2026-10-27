package dk.cbavf.studio.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * A rotary knob, as on a rack unit's panel: drag up or down to turn it (the whole range in about
 * a hand's height), tap for the exact value. The arc runs from 7 to 5 o'clock.
 */
@Composable
fun Knob(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    whole: Boolean = false,
    format: (Float) -> String = { if (whole) "${it.roundToInt()}" else fmt(it) },
) {
    var editing by remember { mutableStateOf(false) }
    val current by rememberUpdatedState(value)
    val change by rememberUpdatedState(onChange)
    val accent = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.outline
    val face = MaterialTheme.colorScheme.surfaceVariant
    Column(Modifier.width(76.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(
            Modifier
                .size(56.dp)
                .pointerInput(range) {
                    detectVerticalDragGestures { ch, dy ->
                        ch.consume()
                        var v = (current - dy / (size.height * 4f) * (range.endInclusive - range.start)).coerceIn(range.start, range.endInclusive)
                        if (whole) v = v.roundToInt().toFloat()
                        change(v)
                    }
                }
                .pointerInput(Unit) { detectTapGestures { editing = true } }
        ) {
            val t = ((value - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f)
            val r = size.minDimension / 2
            val c = Offset(size.width / 2, size.height / 2)
            val start = 135f
            val sweep = 270f
            drawArc(track, start, sweep, false, topLeft = Offset(c.x - r + 3, c.y - r + 3), size = Size(2 * r - 6, 2 * r - 6), style = Stroke(5f, cap = StrokeCap.Round))
            drawArc(accent, start, sweep * t, false, topLeft = Offset(c.x - r + 3, c.y - r + 3), size = Size(2 * r - 6, 2 * r - 6), style = Stroke(5f, cap = StrokeCap.Round))
            drawCircle(face, r * 0.68f, c)
            drawCircle(Color.Black.copy(alpha = 0.35f), r * 0.68f, c, style = Stroke(2f))
            val a = Math.toRadians((start + sweep * t).toDouble())
            drawLine(accent, c + Offset((cos(a) * r * 0.2f).toFloat(), (sin(a) * r * 0.2f).toFloat()), c + Offset((cos(a) * r * 0.62f).toFloat(), (sin(a) * r * 0.62f).toFloat()), 4f, cap = StrokeCap.Round)
        }
        Text(format(value), style = MaterialTheme.typography.labelSmall, color = accent, textAlign = TextAlign.Center)
        Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 2, textAlign = TextAlign.Center)
    }
    if (editing) NumberDialog(label, value, range, whole, onChange = onChange, onDismiss = { editing = false })
}
