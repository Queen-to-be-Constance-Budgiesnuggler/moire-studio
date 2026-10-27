package dk.cbavf.studio.abcompare

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A/B compare. Hold a canvas still: the quarter you hold decides what you see while you hold.
 * Top left: the module's input. Top right: A and B split down the middle (drag sideways to move the split).
 * Bottom left: A over B (drag up and down). Bottom right: B in full, as a flip. Let go and it is gone.
 */
class AbState {
    /** B kept with "Snap B"; a cable into the A/B module's B jack takes over from it. */
    var snapshot by mutableStateOf<ImageBitmap?>(null)

    /** What is being shown right now (null: nothing, the room as usual). */
    var active by mutableStateOf<AbMode?>(null)

    /** Where the split line is, 0..1 across (vertical split) or down (horizontal split). */
    var split by mutableFloatStateOf(0.5f)
}

/**
 * Watches the canvas it is on without taking its touches, until a hold (still for the hold time, one finger or two as
 * set in Studio → Config) begins A/B; from then on it takes the touch, so the tool underneath does not act.
 */
fun Modifier.abHold(ab: AbState, twoFingers: Boolean = false): Modifier = composed {
    val state = rememberUpdatedState(ab)
    val two = rememberUpdatedState(twoFingers)
    pointerInput(Unit) {
        val density = this.density
        awaitEachGesture {
            val first = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val t0 = first.uptimeMillis
            var fingers = 1
            var shown = false
            var movedPx = 0f
            while (true) {
                val event = withTimeoutOrNull(40) { awaitPointerEvent(PointerEventPass.Initial) }
                val pressed = event?.changes?.filter { it.pressed }.orEmpty()
                if (event != null && pressed.isEmpty()) break
                if (pressed.isNotEmpty()) {
                    fingers = maxOf(fingers, pressed.size)
                    val c = pressed.first().position
                    movedPx = maxOf(movedPx, (c - first.position).getDistance())
                    if (shown) {
                        val s = state.value
                        when (s.active) {
                            AbMode.SplitVertical -> s.split = (c.x / size.width).coerceIn(0.02f, 0.98f)
                            AbMode.SplitHorizontal -> s.split = (c.y / size.height).coerceIn(0.02f, 0.98f)
                            else -> Unit
                        }
                        event?.changes?.forEach { it.consume() }
                    }
                }
                val now = event?.changes?.maxOfOrNull { it.uptimeMillis } ?: (t0 + 1000)
                val wanted = if (two.value) 2 else 1
                if (!shown && fingers == wanted && AbGeometry.holdStarts(now - t0, (movedPx / density).toDouble()) && pressed.size == wanted) {
                    shown = true
                    state.value.active = AbGeometry.quadrantMode(first.position.x.toDouble(), first.position.y.toDouble(), size.width.toDouble(), size.height.toDouble())
                    state.value.split = 0.5f
                    event?.changes?.forEach { it.consume() }
                }
            }
            if (shown) state.value.active = null
        }
    }
}

/**
 * What to draw over the canvas while A/B is held: [a] is what the room shows, [b] is the other image, [input] is
 * what the room is fed (Develop's source; nothing for Draw), and [inputNote] says where it came from.
 */
@Composable
fun AbOverlay(ab: AbState, a: ImageBitmap?, b: ImageBitmap?, input: ImageBitmap?, inputNote: String, modifier: Modifier = Modifier) {
    val mode = ab.active ?: return
    val measurer = rememberTextMeasurer()
    Canvas(modifier.fillMaxSize()) {
        drawRect(Color.Black)
        val w = size.width.toDouble()
        val h = size.height.toDouble()
        val (boxA, boxB) = AbGeometry.splitBoxes(mode, w, h, ab.split.toDouble())
        fun tag(text: String, at: Offset) {
            drawText(measurer.measure(text, TextStyle(fontSize = 13.sp, color = Color.White.copy(alpha = 0.85f))), topLeft = at)
        }
        when (mode) {
            AbMode.Input -> {
                if (input != null) fit(input, Box(0.0, 0.0, w, h), Box(0.0, 0.0, w, h)) else tag("No input: $inputNote", Offset(24f, 24f))
                tag("Input · $inputNote", Offset(12f, size.height - 36f))
            }
            AbMode.Full -> {
                if (b != null) fit(b, Box(0.0, 0.0, w, h), Box(0.0, 0.0, w, h)) else tag("No B yet: Snap B, or patch a cable into B", Offset(24f, 24f))
                tag("B", Offset(12f, 12f))
            }
            AbMode.SplitVertical, AbMode.SplitHorizontal -> {
                if (a != null) fit(a, Box(0.0, 0.0, w, h), boxA)
                if (b != null) fit(b, Box(0.0, 0.0, w, h), boxB) else tag("No B yet: Snap B, or patch a cable into B", Offset(24f, size.height / 2))
                if (mode == AbMode.SplitVertical) {
                    val x = boxA.right.toFloat()
                    drawLine(Color.White, Offset(x, 0f), Offset(x, size.height), strokeWidth = 3f)
                    tag("A", Offset(12f, 12f)); tag("B", Offset(size.width - 28f, 12f))
                } else {
                    val y = boxA.bottom.toFloat()
                    drawLine(Color.White, Offset(0f, y), Offset(size.width, y), strokeWidth = 3f)
                    tag("A", Offset(12f, 12f)); tag("B", Offset(12f, size.height - 36f))
                }
            }
        }
    }
}

/** An image fitted and centred in [whole], drawn only inside [clip]. */
private fun DrawScope.fit(img: ImageBitmap, whole: Box, clip: Box) {
    val f = AbGeometry.fitCenter(img.width.toDouble(), img.height.toDouble(), whole.width, whole.height)
    if (f.width <= 0.0) return
    clipRect(clip.left.toFloat(), clip.top.toFloat(), clip.right.toFloat(), clip.bottom.toFloat()) {
        drawImage(img, dstOffset = IntOffset((whole.left + f.left).toInt(), (whole.top + f.top).toInt()), dstSize = IntSize(f.width.toInt(), f.height.toInt()))
    }
}

/**
 * What a room needs for A/B: the state, what the A and B jacks carry (if cabled), and whether a hold takes
 * two fingers. Provided once at the top of the studio, read by the rooms that have an image to compare.
 */
class AbContext(
    val state: AbState,
    val routedA: () -> ImageBitmap?,
    val routedB: () -> ImageBitmap?,
    val twoFingers: () -> Boolean,
)

val LocalAb = androidx.compose.runtime.staticCompositionLocalOf<AbContext?> { null }

/** Put on a room's canvas: holding it (see [AbState]) starts A/B. Does nothing where there is no A/B. */
fun Modifier.abHere(): Modifier = composed {
    val ab = LocalAb.current
    if (ab == null) this else this.abHold(ab.state, ab.twoFingers())
}

/**
 * The A/B picture over a room's canvas, only while held. [live] is what the room shows; a cable into the A jack
 * replaces it. B is a cable into the B jack, or what "Snap B" kept.
 */
@Composable
fun AbLayer(live: ImageBitmap?, input: ImageBitmap?, inputNote: String) {
    val ab = LocalAb.current ?: return
    AbOverlay(ab.state, a = ab.routedA() ?: live, b = ab.routedB() ?: ab.state.snapshot, input = input, inputNote = inputNote)
}
