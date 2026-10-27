package dk.cbavf.studio.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import dk.cbavf.studio.math.AdsrShape
import dk.cbavf.studio.math.Curve
import dk.cbavf.studio.math.Envelope
import dk.cbavf.studio.math.EnvelopePoints
import java.util.Locale

/** The time across the whole drawing, in milliseconds. */
const val ENVELOPE_AXIS_MS = 4000.0

/**
 * An attack, decay, sustain, release envelope drawn as a curve with four handles you drag: the top of the attack, the end of the
 * decay (which also sets the sustain level), the end of the sustain stretch (up and down only) and the end of the release.
 * The sustain stretch is always drawn the same width. The drawing follows [curve]. For exact numbers use sliders next to it.
 */
@Composable
fun EnvelopeEditor(
    shape: AdsrShape,
    curve: Curve,
    onChange: (AdsrShape) -> Unit,
    modifier: Modifier = Modifier,
    axisMs: Double = ENVELOPE_AXIS_MS,
) {
    val current by rememberUpdatedState(shape)
    val push by rememberUpdatedState(onChange)
    val line = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    val handle = MaterialTheme.colorScheme.tertiary
    Column(modifier) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(160.dp)
                .pointerInput(axisMs) {
                    var active = -1
                    detectDragGestures(
                        onDragStart = { at ->
                            val w = size.width.toFloat()
                            val h = size.height.toFloat()
                            val hs = EnvelopePoints.handles(current, axisMs)
                            // The nearest handle, if the finger is within about 44 px of it.
                            var best = -1
                            var bestD = 44f * 44f
                            hs.forEachIndexed { i, p ->
                                val dx = at.x - (p.x * w).toFloat()
                                val dy = at.y - ((1 - p.y) * h).toFloat()
                                val d = dx * dx + dy * dy
                                if (d < bestD) { bestD = d; best = i }
                            }
                            active = best
                        },
                        onDragEnd = { active = -1 },
                        onDragCancel = { active = -1 },
                    ) { change, _ ->
                        if (active >= 0) {
                            change.consume()
                            val nx = (change.position.x / size.width).toDouble().coerceIn(0.0, 1.0)
                            val ny = (1 - change.position.y / size.height).toDouble().coerceIn(0.0, 1.0)
                            push(EnvelopePoints.drag(current, active, nx, ny, axisMs))
                        }
                    }
                },
        ) {
            val w = size.width
            val h = size.height
            // Quarter lines.
            for (i in 1..3) {
                drawLine(grid, Offset(w * i / 4, 0f), Offset(w * i / 4, h), 1f)
                drawLine(grid, Offset(0f, h * i / 4), Offset(w, h * i / 4), 1f)
            }
            val holdEnd = shape.attackMs + shape.decayMs + EnvelopePoints.HOLD * axisMs
            val path = Path()
            val steps = 200
            for (i in 0..steps) {
                val x = w * i / steps
                val t = axisMs * i / steps
                val level = Envelope.adsr(t, shape.attackMs, shape.decayMs, shape.sustain, shape.releaseMs, curve, amount = dk.cbavf.studio.math.MathParams.get("envelope.curveAmount"), gateOffMs = holdEnd)
                val y = h * (1f - level.toFloat())
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, line, style = Stroke(width = 4f))
            EnvelopePoints.handles(shape, axisMs).forEachIndexed { i, p ->
                val c = Offset((p.x * w).toFloat(), ((1 - p.y) * h).toFloat())
                drawCircle(Color.Black.copy(alpha = 0.5f), 17f, c)
                drawCircle(if (i == 2) grid else handle, 13f, c)
            }
        }
        Text(
            String.format(Locale.US, "A %.0f ms · D %.0f ms · S %.2f · R %.0f ms", shape.attackMs, shape.decayMs, shape.sustain, shape.releaseMs),
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

