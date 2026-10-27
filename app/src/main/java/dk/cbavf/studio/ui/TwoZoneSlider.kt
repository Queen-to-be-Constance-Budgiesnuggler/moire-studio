package dk.cbavf.studio.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import dk.cbavf.studio.math.MathParams
import dk.cbavf.studio.math.Sliders
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.pow

private val FineRed = Color(0xFFD32F2F)
private val FullBlue = Color(0xFF1976D2)

/**
 * A slider whose thumb is a small portrait pill split in two, so one control does both jobs:
 * the **blue bottom half** moves the value across the whole range (drag along the track), the
 * **red top half** moves it by single fine steps (tap the right or left side of it, hold to repeat
 * and speed up by a decade each second, or drag a little sideways, one step per 8 dp).
 *
 * The fine step is [Sliders.fineStep] (about a hundredth to a thousandth of the range) unless [step] is
 * given. With [log] the blue zone maps through a logarithmic curve and a fine step is a 1 % change.
 */
@Composable
fun TwoZoneSlider(
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    steps: Int = 0,
    step: Float? = null,
    whole: Boolean = false,
    log: Boolean = false,
) {
    val density = LocalDensity.current
    val thumbW = with(density) { THUMB_W.toPx() }
    val thumbH = with(density) { THUMB_H.toPx() }
    val stepPxPerFine = with(density) { MathParams.get("slider.dragPxPerStep").dp.toPx() }
    val slop = with(density) { 6.dp.toPx() }
    var widthPx by remember { mutableFloatStateOf(0f) }
    val current by rememberUpdatedState(value)
    val change by rememberUpdatedState(onChange)
    // A small tick for every fine step, and a firmer one when the step grows a decade: the detents of the red zone (off with Haptics).
    val haptic by rememberUpdatedState(androidx.compose.ui.platform.LocalHapticFeedback.current)
    fun tick(strong: Boolean = false) =
        haptic.performHapticFeedback(if (strong) androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress else androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)

    val min = range.start.toDouble()
    val max = range.endInclusive.toDouble()
    val useLog = log && min > 0.0
    val span = max - min
    val fine: Double = when {
        step != null -> step.toDouble()
        steps > 0 -> span / (steps + 1)
        else -> Sliders.fineStep(min, max, whole)
    }

    fun fraction(v: Double): Double = if (useLog) Sliders.toFractionLog(v, min, max) else Sliders.toFraction(v, min, max)
    fun valueAt(t: Double): Float {
        val raw = if (useLog) Sliders.fromFractionLog(t, min, max) else Sliders.fromFraction(t, min, max)
        return (if (steps > 0) Sliders.snapToGrid(raw, min, max, span / (steps + 1)) else raw).toFloat()
    }
    /** One fine step up or down, [times] steps of the current decade. */
    fun nudge(dir: Int, times: Double): Float {
        val v = current.toDouble()
        return if (useLog) (v * (1 + MathParams.get("slider.logStepPercent") / 100).pow(dir * times)).coerceIn(min, max).toFloat()
        else Sliders.nextStep(v, min, max, fine * times, dir).toFloat()
    }

    val trackColor = MaterialTheme.colorScheme.outlineVariant
    val activeColor = MaterialTheme.colorScheme.primary
    val dividerColor = Color.White

    Canvas(
        modifier
            .fillMaxWidth()
            .height(THUMB_H + 4.dp)
            .onSizeChanged { widthPx = it.width.toFloat() }
            .pointerInput(range, steps, step, whole, log, thumbW, thumbH) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val trackLen = (size.width - thumbW).coerceAtLeast(1f)
                    val centerX = thumbW / 2 + fraction(current.toDouble()).toFloat() * trackLen
                    val centerY = size.height / 2f
                    val onThumb = abs(down.position.x - centerX) <= thumbW / 2 + slop &&
                        abs(down.position.y - centerY) <= thumbH / 2 + slop
                    val inRed = onThumb && down.position.y < centerY
                    down.consume()

                    if (inRed) {
                        // Fine zone: tap a side = one step, hold = repeat and accelerate, drag = steps.
                        val dir = if (down.position.x >= centerX) 1 else -1
                        val holdMs = MathParams.get("slider.holdMs").toLong()
                        val repeatMs = MathParams.get("slider.repeatMs").toLong()
                        val accelMs = MathParams.get("slider.accelMs")
                        val t0 = System.currentTimeMillis()
                        var lastRepeat = t0
                        var moved = false
                        var accum = 0f
                        var lastDecade = 0
                        var travelled = 0f
                        while (true) {
                            val event = withTimeoutOrNull(50) { awaitPointerEvent(PointerEventPass.Main) }
                            val now = System.currentTimeMillis()
                            val ch = event?.changes?.firstOrNull { it.id == down.id }
                            if (ch != null) {
                                if (!ch.pressed) {
                                    if (!moved && now - t0 < holdMs) { change(nudge(dir, 1.0)); tick() }
                                    ch.consume()
                                    break
                                }
                                val dx = ch.position.x - ch.previousPosition.x
                                if (dx != 0f) {
                                    travelled += abs(dx)
                                    if (travelled > slop) moved = true
                                    if (moved) {
                                        accum += dx
                                        while (abs(accum) >= stepPxPerFine) {
                                            val d = if (accum > 0) 1 else -1
                                            change(nudge(d, 1.0))
                                            tick()
                                            accum -= d * stepPxPerFine
                                        }
                                    }
                                    ch.consume()
                                }
                            }
                            if (!moved && now - t0 >= holdMs && now - lastRepeat >= repeatMs) {
                                // Each second held, ten times bigger, but never more than a tenth of the range.
                                val seconds = ((now - t0 - holdMs) / accelMs).toInt()
                                val times = 10.0.pow(seconds).coerceAtMost((span / 10 / fine).coerceAtLeast(1.0))
                                change(nudge(dir, times))
                                tick(strong = seconds != lastDecade)
                                lastDecade = seconds
                                lastRepeat = now
                            }
                        }
                    } else {
                        // Full-scale zone: follow the finger across the range. Grabbing the thumb keeps
                        // its offset; touching the bare track jumps the value to the finger.
                        val grab = if (onThumb) centerX - down.position.x else 0f
                        fun setFrom(x: Float) = change(valueAt(((x + grab - thumbW / 2) / trackLen).toDouble().coerceIn(0.0, 1.0)))
                        setFrom(down.position.x)
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Main)
                            val ch = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!ch.pressed) { ch.consume(); break }
                            setFrom(ch.position.x)
                            ch.consume()
                        }
                    }
                }
            },
    ) {
        val trackLen = (size.width - thumbW).coerceAtLeast(1f)
        val cy = size.height / 2
        val cx = thumbW / 2 + fraction(value.toDouble()).toFloat() * trackLen
        drawLine(trackColor, Offset(thumbW / 2, cy), Offset(size.width - thumbW / 2, cy), strokeWidth = 4.dp.toPx(), cap = StrokeCap.Round)
        drawLine(activeColor, Offset(thumbW / 2, cy), Offset(cx, cy), strokeWidth = 4.dp.toPx(), cap = StrokeCap.Round)
        val left = cx - thumbW / 2
        val top = cy - thumbH / 2
        val r = 8.dp.toPx()
        // Whole pill in blue, then the same pill clipped to its top half in red.
        drawRoundRect(FullBlue, Offset(left, top), Size(thumbW, thumbH), CornerRadius(r))
        clipRect(left, top, left + thumbW, cy) {
            drawRoundRect(FineRed, Offset(left, top), Size(thumbW, thumbH), CornerRadius(r))
        }
        drawLine(dividerColor, Offset(left + 3.dp.toPx(), cy), Offset(left + thumbW - 3.dp.toPx(), cy), strokeWidth = 1.5.dp.toPx())
    }
}

private val THUMB_W = 28.dp
private val THUMB_H = 44.dp
