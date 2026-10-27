package dk.cbavf.studio.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * A chip for things in a row (layers, objects): tap picks only it; hold adds it to (or takes it
 * out of) the picked ones; hold and drag sideways moves it, reporting how far in pixels. A drag
 * that starts without holding scrolls the row as usual.
 */
@Composable
fun HoldChip(
    label: String,
    selected: Boolean,
    onTap: () -> Unit,
    onHold: () -> Unit,
    onDragEnd: (dxPx: Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    var dragX by remember { mutableFloatStateOf(0f) }
    val haptics = LocalHapticFeedback.current
    val tap by rememberUpdatedState(onTap)
    val hold by rememberUpdatedState(onHold)
    val dragged by rememberUpdatedState(onDragEnd)
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier
            .graphicsLayer { translationX = dragX; alpha = if (dragX != 0f) 0.8f else 1f }
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent, shape)
            .border(1.dp, if (selected) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outline, shape)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val long = awaitLongPressOrCancellation(down.id)
                    if (long == null) {
                        val up = currentEvent.changes.firstOrNull { it.id == down.id }
                        if (up != null && !up.pressed && (up.position - down.position).getDistance() < viewConfiguration.touchSlop) tap()
                        return@awaitEachGesture
                    }
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    var dx = 0f
                    drag(long.id) { change ->
                        dx += change.position.x - change.previousPosition.x
                        dragX = dx
                        change.consume()
                    }
                    dragX = 0f
                    if (abs(dx) < viewConfiguration.touchSlop) hold() else dragged(dx)
                }
            }
            .padding(horizontal = 12.dp, vertical = 7.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}
