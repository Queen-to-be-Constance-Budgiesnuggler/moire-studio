package dk.cbavf.studio.sequence

import android.app.Activity
import android.view.Display
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import dk.cbavf.studio.sequence.model.SequenceDoc
import dk.cbavf.studio.sequence.model.Span
import dk.cbavf.studio.sequence.model.TimelineIndex
import dk.cbavf.studio.ui.renderSafely
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** What a projector run measured, shown when it closes. */
data class ProjectorResult(val displayHz: Float, val vsyncs: Long, val dropped: Long)

/** Refresh rates this display offers at its current resolution. */
fun supportedRates(display: Display?): List<Int> {
    val d = display ?: return listOf(60)
    val mode = d.mode
    return d.supportedModes
        .filter { it.physicalWidth == mode.physicalWidth && it.physicalHeight == mode.physicalHeight }
        .map { it.refreshRate.roundToInt() }
        .distinct()
        .sorted()
        .ifEmpty { listOf(60) }
}

/**
 * Full-screen playback for projecting or filming. Every frame is decoded before the clock
 * starts; the shown frame is chosen from the vsync timestamp, so a late frame never shifts the
 * ones after it. Tap or Back to close.
 */
@Composable
fun Projector(doc: SequenceDoc, decoder: FrameDecoder, onClose: (ProjectorResult?) -> Unit) {
    val activity = LocalContext.current as Activity

    FullScreenWindow(doc.rateHz, doc.projectorBrightness)

    var result by remember { mutableStateOf<ProjectorResult?>(null) }
    BackHandler { onClose(result) }

    val frameSize = remember(doc) { decoder.frameSize(doc) }
    var decoded by remember { mutableIntStateOf(0) }
    var decodeError by remember { mutableStateOf<String?>(null) }
    val frames by produceState<List<ImageBitmap>?>(null, doc) {
        val (w, h) = frameSize ?: return@produceState
        value = renderSafely({ decodeError = it }) {
            doc.items.map { item ->
                decoder.decode(item, w, h).asImageBitmap().also {
                    it.prepareToDraw()
                    decoded++
                }
            }
        }
    }

    val index = remember(doc) { TimelineIndex.of(doc) }
    var span by remember { mutableLongStateOf(0L) }
    var ended by remember { mutableStateOf(false) }

    LaunchedEffect(frames) {
        if (frames == null) return@LaunchedEffect
        val displayHz = activity.display?.refreshRate ?: 60f
        val period = 1e9 / displayHz
        var t0 = -1L
        var last = 0L
        var vsyncs = 0L
        var dropped = 0L
        while (true) {
            // Off screen nothing is shown, so the timing waits too.
            dk.cbavf.studio.foreground.Foreground.gate.awaitStarted()
            val t = withFrameNanos { it }
            if (t0 < 0) {
                t0 = t
            } else {
                val gap = ((t - last) / period).roundToLong()
                vsyncs += gap
                if (gap > 1) dropped += gap - 1
            }
            last = t
            // Whole vsyncs since the start, then sequence ticks: at 60 Hz on a 120 Hz panel every
            // tick gets exactly two vsyncs, the first one included.
            val vsync = ((t - t0) / period).roundToLong()
            val tick = (vsync * doc.rateHz / displayHz.toDouble() + 1e-6).toLong()
            val s = index.spanAt(tick)
            result = ProjectorResult(displayHz, vsyncs, dropped)
            if (s == null) {
                ended = true
                break
            }
            span = s.toLong()
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) { detectTapGestures { onClose(result) } },
        contentAlignment = Alignment.Center,
    ) {
        val list = frames
        if (list == null) {
            Text(decodeError?.let { "Could not decode the frames: $it Tap to close." } ?: "Decoding $decoded/${doc.items.size}…", color = Color.Gray)
        } else {
            Canvas(Modifier.fillMaxSize()) {
                // Read in the draw phase only: a new frame redraws without recomposing.
                if (ended) return@Canvas
                val item = index.spans.getOrNull(span.toInt())?.item ?: return@Canvas
                if (item == Span.BLACK) return@Canvas
                val image = list[item]
                // 1:1 pixels, centred: frames are made at the screen size by default. A frame shape
                // bigger than the screen is scaled down to fit, never cropped.
                val k = minOf(1f, size.width / image.width, size.height / image.height)
                val w = (image.width * k).toInt()
                val h = (image.height * k).toInt()
                drawImage(
                    image,
                    dstOffset = IntOffset(((size.width - w) / 2).toInt(), ((size.height - h) / 2).toInt()),
                    dstSize = IntSize(w, h),
                    filterQuality = if (k == 1f) FilterQuality.None else FilterQuality.Medium,
                )
            }
        }
    }
}

/**
 * Full screen, screen kept on and, with [rateHz], the display locked to that refresh rate; with
 * [brightness] (0..1) the screen brightness is set too, otherwise the phone's own setting stays.
 * All undone when this leaves the composition. For projecting onto the other phone.
 */
@Composable
fun FullScreenWindow(rateHz: Int?, brightness: Float? = null) {
    val activity = LocalContext.current as Activity
    val view = LocalView.current
    DisposableEffect(Unit) {
        val window = activity.window
        val before = WindowManager.LayoutParams().apply { copyFrom(window.attributes) }
        val controller = WindowCompat.getInsetsController(window, view)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply {
            if (brightness != null) screenBrightness = brightness.coerceIn(0.01f, 1f)
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            if (rateHz != null) {
                lockRefresh(activity.display, rateHz)?.let { preferredDisplayModeId = it }
                preferredRefreshRate = rateHz.toFloat()
            }
        }
        onDispose {
            controller.show(WindowInsetsCompat.Type.systemBars())
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            window.attributes = before
        }
    }
}

/** Display mode at the current resolution whose refresh rate is closest to [hz]. */
private fun lockRefresh(display: Display?, hz: Int): Int? {
    val d = display ?: return null
    val mode = d.mode
    return d.supportedModes
        .filter { it.physicalWidth == mode.physicalWidth && it.physicalHeight == mode.physicalHeight }
        .minByOrNull { abs(it.refreshRate - hz) }
        ?.modeId
}
