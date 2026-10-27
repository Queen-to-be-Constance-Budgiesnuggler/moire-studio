package dk.cbavf.studio.ui.gestures

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.max

/**
 * Watches every touch over what it wraps, without taking any of them, and reports the studio-wide
 * gestures: taps with two, three or four fingers, a three-finger swipe, and a swipe in from the right
 * edge. One-finger taps and holds are never reported, so tools and sliders keep working as they do.
 */
fun Modifier.studioGestures(onGesture: (Gesture) -> Unit): Modifier = composed {
    val current = rememberUpdatedState(onGesture)
    pointerInput(Unit) {
        val density = this.density
        awaitEachGesture {
            val first = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val startTime = first.uptimeMillis
            val start = first.position
            var last = first.position
            var lastTime = startTime
            var fingers = 1
            var maxMovePx = 0f
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val down = event.changes.filter { it.pressed }
                fingers = max(fingers, down.size)
                if (down.isNotEmpty()) {
                    val c = Offset(down.map { it.position.x }.average().toFloat(), down.map { it.position.y }.average().toFloat())
                    last = c
                    maxMovePx = max(maxMovePx, (c - start).getDistance())
                }
                lastTime = event.changes.maxOf { it.uptimeMillis }
            } while (event.changes.any { it.pressed })
            val summary = TouchSummary(
                fingers = fingers,
                durationMs = lastTime - startTime,
                maxMoveDp = (maxMovePx / density).toDouble(),
                dx = ((last.x - start.x) / density).toDouble(),
                dy = ((last.y - start.y) / density).toDouble(),
                startX = (start.x / density).toDouble(),
                width = (size.width / density).toDouble(),
            )
            val g = GestureClassifier.classify(summary)
            if (g != Gesture.None && g != Gesture.Tap1 && g != Gesture.LongPress1) current.value(g)
        }
    }
}

/** What the studio-wide gestures do; opened with a four-finger tap. */
@Composable
fun GestureCheatSheet(onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Gestures") },
        text = {
            Column {
                listOf(
                    "Two-finger tap" to "Undo in the open room",
                    "Three-finger tap" to "Redo",
                    "Three-finger swipe left / right" to "Next / previous room",
                    "Swipe in from the right edge" to "History: every step of the open room, tap one to jump to it",
                    "Four-finger tap" to "This list",
                ).forEach { (gesture, does) ->
                    Text(gesture, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                    Text(does, style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    "Sliders: the blue half of the thumb moves across the whole range, the red half takes single fine steps (tap a side, hold to repeat).",
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 12.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } },
    )
}
