package dk.cbavf.studio.animate

import dk.cbavf.studio.library.renderBitmap
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import dk.cbavf.studio.ui.HistoryPanel
import dk.cbavf.studio.abcompare.abHere
import dk.cbavf.studio.ui.layout.panelScroll
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import dk.cbavf.studio.animate.model.Animation
import dk.cbavf.studio.animate.model.Frame
import dk.cbavf.studio.animate.model.Paper
import dk.cbavf.studio.animate.model.PrintSize
import dk.cbavf.studio.draw.drawLayers
import dk.cbavf.studio.draw.model.Content
import dk.cbavf.studio.draw.model.label
import dk.cbavf.studio.draw.model.Grating
import dk.cbavf.studio.draw.model.Pt
import dk.cbavf.studio.draw.model.RadialBurst
import dk.cbavf.studio.draw.model.Stroke
import dk.cbavf.studio.library.CanvasDoc
import dk.cbavf.studio.library.Lineage
import dk.cbavf.studio.library.ProjectStore
import dk.cbavf.studio.draw.model.ImageLayer
import dk.cbavf.studio.draw.model.Layer
import dk.cbavf.studio.ui.LibraryPicker
import dk.cbavf.studio.ui.LabeledSlider
import dk.cbavf.studio.ui.ScrollRow
import dk.cbavf.studio.ui.fmt
import dk.cbavf.studio.ui.renderSafely
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@Composable
fun AnimateRoom(
    state: AnimateState,
    store: ProjectStore,
    lineage: Lineage,
    grabFromDraw: () -> CanvasDoc?,
    onOpenInDraw: (CanvasDoc) -> Unit,
    onSaveBarrier: () -> Unit,
    onSaveGif: () -> Unit = {},
) {
    var picking by remember { mutableStateOf(false) }
    if (picking) {
        // Each picked Library image becomes a frame of its own, fitted to the animation's canvas.
        LibraryPicker(store, lineage) { ids ->
            picking = false
            state.addImageFrames(ids.mapNotNull { lineage[it] }, grabFromDraw()?.let { it.widthPx to it.heightPx })
        }
        return
    }
    // Playback position in the hold-expanded order; only meaningful while playing.
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(state.playing, state.fps, state.view) {
        tick = 0
        while (state.playing) {
            // Off screen playback holds its frame instead of waking every tick.
            dk.cbavf.studio.foreground.Foreground.gate.sleep(1000L / state.fps.coerceAtLeast(1))
            if (state.view == AnimateView.Barrier) state.barrierShift++ else tick++
        }
    }

    val liveFrame by produceState<ImageBitmap?>(null, state.current, state.frames) {
        value = state.currentFrame?.doc?.let { d -> kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { dk.cbavf.studio.library.renderBitmap(d).asImageBitmap() } }
    }
    dk.cbavf.studio.ui.layout.RoomSplit(canvas = {
        Box(Modifier.fillMaxSize().clipToBounds().abHere(), contentAlignment = Alignment.Center) {
            when {
                state.frames.isEmpty() -> Text(
                    "No frames yet. Press \"Grab from Draw\" to add the Draw canvas as frame 1.",
                    Modifier.padding(32.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
                state.view == AnimateView.Frames -> {
                    val order = state.toDoc().playbackOrder()
                    val shown = if (state.playing && order.isNotEmpty()) order[tick % order.size] else state.current
                    FrameViewer(state, shown)
                }
                else -> BarrierViewer(state)
            }
            Readout(state, Modifier.align(Alignment.TopStart).padding(8.dp))
            dk.cbavf.studio.abcompare.AbLayer(liveFrame, null, "Animate has no input")
        }
    }, controls = {
        Controls(state, grabFromDraw, onOpenInDraw, onSaveBarrier, onPickFrames = { picking = true }, onSaveGif = onSaveGif, live = liveFrame)
    })
}

/** Shows a frame pixel for pixel (centred, cropped if the room is smaller than the canvas). */
@Composable
private fun FrameViewer(state: AnimateState, index: Int) {
    val frame = state.frames.getOrNull(index) ?: return
    val onion = state.previousFrame.takeIf { state.onion && !state.playing && index == state.current }
    val (w, h) = pixelSize(frame.doc)
    Box(Modifier.requiredSize(w, h).pointerInput(state) { handleDrag(state) }) {
        Canvas(Modifier.fillMaxSize().graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
            drawLayers(frame.doc.layers)
        }
        if (onion != null) {
            Canvas(
                Modifier.fillMaxSize().graphicsLayer {
                    alpha = ONION_ALPHA
                    compositingStrategy = CompositingStrategy.Offscreen
                }
            ) {
                drawLayers(onion.doc.layers)
            }
        }
    }
}

@Composable
private fun pixelSize(doc: CanvasDoc) = with(LocalDensity.current) { doc.widthPx.toDp() to doc.heightPx.toDp() }

private suspend fun PointerInputScope.handleDrag(state: AnimateState) {
    awaitEachGesture {
        val down = awaitFirstDown()
        var moved = false
        do {
            val event = awaitPointerEvent()
            val zoom = event.calculateZoom()
            val rotation = event.calculateRotation()
            val pan = event.calculatePan()
            val now = event.changes.firstOrNull()?.uptimeMillis ?: down.uptimeMillis
            if (zoom != 1f || rotation != 0f || pan != Offset.Zero) {
                // Start lazily so a plain tap adds no undo step and no recorded frame.
                if (!moved) state.beginDrag(now)
                moved = true
                state.drag(Pt(pan.x, pan.y), zoom, rotation, now)
            }
            event.changes.forEach { if (it.positionChanged()) it.consume() }
        } while (event.changes.any { it.pressed })
    }
}

/**
 * The interleaved image with the slit overlay on top. Dragging sideways (or playing) slides the
 * overlay one strip per frame, which is exactly what sliding a printed barrier does.
 */
@Composable
private fun BarrierViewer(state: AnimateState) {
    val doc = state.toDoc()
    val image by produceState<ImageBitmap?>(null, doc.frames, doc.stripPx) {
        value = null
        value = renderSafely({ state.barrierError = it }) { renderInterleaved(doc) { ensureActive() }?.asImageBitmap() }
    }
    val bitmap = image
    if (bitmap == null) {
        Text(state.barrierError ?: "Interleaving ${doc.frames.size} frames…")
        return
    }
    val (w, h) = pixelSize(doc.frames.first().doc)
    var dragPx by remember { mutableFloatStateOf(0f) }
    val strip = doc.stripPx.coerceAtLeast(1)
    val n = doc.frames.size
    Box(
        Modifier.requiredSize(w, h).pointerInput(strip) {
            detectHorizontalDragGestures { change, dx ->
                change.consume()
                dragPx += dx
                val strips = (dragPx / strip).toInt()
                if (strips != 0) {
                    state.barrierShift += strips
                    dragPx -= strips * strip
                }
            }
        }
    ) {
        // Pixel-exact: any resampling would smear the strips into each other.
        Image(bitmap, null, Modifier.fillMaxSize(), contentScale = ContentScale.None, filterQuality = FilterQuality.None)
        Canvas(Modifier.fillMaxSize()) {
            val period = strip * n
            val open = state.barrierShift.mod(n) * strip
            var x = -period
            while (x < size.width.toInt()) {
                // Opaque from the end of one slit to the start of the next.
                val slitEnd = x + open + strip
                drawRect(Color.Black, Offset(slitEnd.toFloat(), 0f), Size((period - strip).toFloat(), size.height))
                x += period
            }
        }
    }
}

@Composable
private fun Readout(state: AnimateState, modifier: Modifier) {
    val frame = state.currentFrame ?: return
    val lines = buildList {
        add("frame ${state.current + 1}/${state.frames.size} · hold ${frame.hold} · ${state.fps} fps")
        if (state.view == AnimateView.Barrier) {
            add("strip ${state.stripPx} px · slit every ${state.toDoc().slitSpacingPx} px")
            add("showing frame ${state.barrierShift.mod(state.frames.size) + 1}")
        }
        if (state.recording) add("● REC: drag to capture a frame every ${1000 / state.fps.coerceAtLeast(1)} ms")
    }
    Surface(modifier = modifier, color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f), shape = MaterialTheme.shapes.small) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            lines.forEach { Text(it, style = MaterialTheme.typography.labelMedium) }
        }
    }
}

@Composable
private fun Controls(
    state: AnimateState,
    grabFromDraw: () -> CanvasDoc?,
    onOpenInDraw: (CanvasDoc) -> Unit,
    onSaveBarrier: () -> Unit,
    onPickFrames: () -> Unit,
    onSaveGif: () -> Unit,
    live: ImageBitmap?,
) {
    val hasFrame = state.currentFrame != null
    var showHistory by remember { mutableStateOf(false) }
    if (showHistory) HistoryPanel("Animate", state) { showHistory = false }
    Surface(tonalElevation = 2.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
            ScrollRow {
                AnimateView.entries.forEach { v ->
                    FilterChip(selected = state.view == v, onClick = { state.view = v }, label = { Text(v.name) })
                }
                TextButton(onClick = { state.playing = !state.playing }, enabled = hasFrame) {
                    Text(if (state.playing) "Stop" else "Play")
                }
                TextButton(onClick = { grabFromDraw()?.let(state::insert) }) { Text("Grab from Draw") }
                TextButton(onClick = onPickFrames) { Text("+ Library frames") }
                TextButton(onClick = state::undo, enabled = state.canUndo) { Text("Undo") }
                TextButton(onClick = state::redo, enabled = state.canRedo) { Text("Redo") }
                TextButton(onClick = { showHistory = true }, enabled = state.canUndo || state.canRedo) { Text("History") }
                dk.cbavf.studio.abcompare.LocalAb.current?.let { abc -> TextButton(onClick = { abc.state.snapshot = live }, enabled = live != null) { Text("Snap B") } }
                TextButton(onClick = state::clearAll, enabled = state.frames.isNotEmpty()) { Text("Clear all") }
                // The frames at the Speed setting, holds included, looping.
                TextButton(onClick = onSaveGif, enabled = state.frames.isNotEmpty()) { Text("Save GIF") }
            }
            FrameStrip(state)
            HorizontalDivider()
            Column(Modifier.panelScroll(260.dp)) {
                LabeledSlider("Speed", state.fps.toFloat(), 1f..60f, " fps", format = ::whole) { state.fps = it.roundToInt() }
                when (state.view) {
                    AnimateView.Frames -> FrameTools(state, grabFromDraw, onOpenInDraw)
                    AnimateView.Barrier -> BarrierTools(state, onSaveBarrier)
                }
            }
        }
    }
}

/** The frames as a strip of little pictures, numbered, the current one outlined; tap one to go to it. */
@Composable
private fun FrameStrip(state: AnimateState) {
    if (state.frames.isEmpty()) return
    val frames = state.frames.toList()
    // Each frame drawn small once, again only when the frames change.
    val thumbs by produceState<List<ImageBitmap?>>(List(frames.size) { null }, frames) {
        value = withContext(Dispatchers.Default) {
            frames.map { f ->
                runCatching {
                    val w = f.doc.widthPx.coerceAtLeast(1)
                    val k = 120f / maxOf(w, f.doc.heightPx.coerceAtLeast(1))
                    renderBitmap(f.doc, sheet = true, scale = k).asImageBitmap()
                }.getOrNull()
            }
        }
    }
    ScrollRow {
        frames.forEachIndexed { i, f: Frame ->
            val picked = i == state.current
            androidx.compose.foundation.layout.Box(
                Modifier
                    .size(width = 64.dp, height = 72.dp)
                    .border(if (picked) 2.dp else 1.dp, if (picked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small)
                    .clickable {
                        state.playing = false
                        state.current = i
                    },
                contentAlignment = Alignment.BottomCenter,
            ) {
                thumbs.getOrNull(i)?.let { Image(it, null, Modifier.matchParentSize().padding(2.dp), contentScale = ContentScale.Fit) }
                Text(
                    if (f.hold > 1) "${i + 1}×${f.hold}" else "${i + 1}",
                    Modifier.background(MaterialTheme.colorScheme.surface.copy(alpha = 0.75f)).padding(horizontal = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

@Composable
private fun FrameTools(state: AnimateState, grabFromDraw: () -> CanvasDoc?, onOpenInDraw: (CanvasDoc) -> Unit) {
    val frame = state.currentFrame ?: return
    ScrollRow {
        TextButton(onClick = { state.moveCurrent(-1) }, enabled = state.current > 0) { Text("◀ Move") }
        TextButton(onClick = { state.moveCurrent(1) }, enabled = state.current < state.frames.lastIndex) { Text("Move ▶") }
        TextButton(onClick = state::duplicateCurrent) { Text("Duplicate") }
        TextButton(onClick = state::deleteCurrent) { Text("Delete") }
        TextButton(onClick = { state.changeHold(-1) }, enabled = frame.hold > 1) { Text("Hold −") }
        TextButton(onClick = { state.changeHold(1) }, enabled = frame.hold < AnimateState.MAX_HOLD) { Text("Hold +") }
    }
    ScrollRow {
        FilterChip(selected = state.onion, onClick = { state.onion = !state.onion }, label = { Text("Onion skin") })
        FilterChip(selected = state.recording, onClick = { state.recording = !state.recording }, label = { Text("Rec drag") })
        TextButton(onClick = { onOpenInDraw(frame.doc) }) { Text("Edit in Draw") }
        TextButton(onClick = { grabFromDraw()?.let(state::replaceCurrent) }) { Text("Replace from Draw") }
    }
    ScrollRow {
        Text("Move", Modifier.width(48.dp), style = MaterialTheme.typography.labelMedium)
        val target = state.target
        frame.doc.layers.forEach { layer ->
            FilterChip(
                selected = layer.id == target?.id,
                onClick = { state.targetId = layer.id },
                label = { Text("${layer.id} ${layer.content.label}") },
            )
        }
    }
    StepHelper(state)
}

@Composable
private fun StepHelper(state: AnimateState) {
    val s = state.step
    Text("Step helper", style = MaterialTheme.typography.labelLarge)
    LabeledSlider("Shift X", s.dx, -20f..20f, " px") { state.step = s.copy(dx = it) }
    LabeledSlider("Shift Y", s.dy, -20f..20f, " px") { state.step = s.copy(dy = it) }
    LabeledSlider("Turn", s.turnDeg, -10f..10f, "°") { state.step = s.copy(turnDeg = it) }
    LabeledSlider("Frames", state.stepCount.toFloat(), 1f..60f, "", format = ::whole) { state.stepCount = it.roundToInt() }
    val frame = state.currentFrame
    val target = state.target
    if (frame != null && target != null) {
        Animation.aliasingCheck(frame.doc, target.id, s)?.let { (shift, limit) ->
            val over = shift >= limit
            Text(
                "lines move ${fmt(shift)} px/frame · limit p/2 = ${fmt(limit)} px" + if (over) " · will alias" else "",
                style = MaterialTheme.typography.labelMedium,
                color = if (over) MaterialTheme.colorScheme.error else Color.Unspecified,
            )
        }
    }
    TextButton(onClick = state::generateSteps, enabled = target != null) { Text("+ ${state.stepCount} frames") }
}

@Composable
private fun BarrierTools(state: AnimateState, onSaveBarrier: () -> Unit) {
    LabeledSlider("Strip", state.stripPx.toFloat(), 1f..12f, " px", steps = 10, format = ::whole) { state.stripPx = it.roundToInt() }
    Text(
        "${state.frames.size} frames · slit spacing = ${state.frames.size} × ${state.stripPx} = ${state.toDoc().slitSpacingPx} px. " +
            "Drag sideways or press Play to slide the overlay.",
        style = MaterialTheme.typography.labelMedium,
    )
    // Print size of the saved image and barrier; the preview above stays at screen size.
    ScrollRow {
        Text("Paper", Modifier.width(48.dp), style = MaterialTheme.typography.labelMedium)
        Paper.entries.forEach { p ->
            FilterChip(selected = state.printPaper == p, onClick = { state.printPaper = p }, label = { Text(p.label) })
        }
    }
    ScrollRow {
        Text("DPI", Modifier.width(48.dp), style = MaterialTheme.typography.labelMedium)
        PrintSize.DPIS.forEach { d ->
            FilterChip(
                selected = state.printDpi == d,
                onClick = { state.printDpi = d },
                enabled = state.printPaper != Paper.Screen,
                label = { Text("$d") },
            )
        }
    }
    state.printPlan()?.let { plan ->
        Text(
            PrintSize.describe(plan, state.printDpi),
            style = MaterialTheme.typography.labelMedium,
            color = if (plan.clamped) MaterialTheme.colorScheme.error else Color.Unspecified,
        )
    }
    TextButton(onClick = onSaveBarrier, enabled = state.frames.size >= 2) { Text("Save image + barrier to Library") }
}


private fun whole(x: Float) = x.roundToInt().toString()

private const val ONION_ALPHA = 0.35f
