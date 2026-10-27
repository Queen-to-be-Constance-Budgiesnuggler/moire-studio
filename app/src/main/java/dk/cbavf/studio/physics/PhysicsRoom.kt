package dk.cbavf.studio.physics

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import dk.cbavf.studio.animate.AnimateState
import dk.cbavf.studio.draw.drawLayers
import dk.cbavf.studio.library.CanvasDoc
import dk.cbavf.studio.physics.model.BodyKind
import dk.cbavf.studio.physics.model.BodyShape
import dk.cbavf.studio.physics.model.GravitySource
import dk.cbavf.studio.physics.model.PhysicsFrames
import dk.cbavf.studio.physics.model.Pose
import dk.cbavf.studio.physics.model.ShapeMapper
import dk.cbavf.studio.ui.LabeledSlider
import dk.cbavf.studio.ui.ScrollRow
import dk.cbavf.studio.ui.layout.panelScrollOpen
import kotlinx.coroutines.launch
import kotlin.math.hypot

/**
 * The Physics room: the Draw canvas's shapes fall, bounce and collide. Tilt the phone to tip them. Record what runs, or bake a
 * few seconds at once, and the frames go to Animate. The world is stepped by [PhysicsState.run], which the studio keeps going.
 */
@Composable
fun PhysicsRoom(
    state: PhysicsState,
    animate: AnimateState,
    /** The Draw canvas as other rooms see it, or null (with a message) when it has no size yet. */
    grabFromDraw: () -> CanvasDoc?,
    onOpenAnimate: () -> Unit,
    onMessage: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var baking by remember { mutableStateOf(false) }
    val specs = state.specs
    // Record goes off by itself when it is full: say so once.
    androidx.compose.runtime.LaunchedEffect(state.recordFull) { if (state.recordFull) onMessage(RECORD_FULL) }
    dk.cbavf.studio.ui.layout.RoomSplit(canvas = {
        Box(Modifier.fillMaxSize().clipToBounds(), contentAlignment = Alignment.Center) {
            val doc = state.frame
            if (doc == null) {
                Text(
                    "Draw a few shapes first, then press \"Take from Draw\". A closed round stroke is a ball, a closed square a box, " +
                        "an open line a fixed floor or ramp, and a picture a box.",
                    Modifier.padding(24.dp), style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                Canvas(Modifier.fillMaxSize().graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
                    fitted(doc) { drawLayers(doc.layers) }
                }
                if (state.outlines) {
                    val colour = MaterialTheme.colorScheme.primary
                    val fixed = MaterialTheme.colorScheme.tertiary
                    Canvas(Modifier.fillMaxSize()) {
                        fitted(doc) {
                            for (spec in specs) {
                                if (spec.kind == BodyKind.Off) continue
                                val pose = state.poses[spec.layerId] ?: Pose(spec.centre.x, spec.centre.y, spec.angleDeg)
                                val pts = PhysicsFrames.outline(spec.shape, pose)
                                if (pts.size < 2) continue
                                val path = Path().apply {
                                    moveTo(pts[0].x, pts[0].y)
                                    for (p in pts.drop(1)) lineTo(p.x, p.y)
                                    if (spec.shape !is BodyShape.Chain) close()
                                }
                                drawPath(path, if (spec.kind == BodyKind.Moving) colour else fixed, style = Stroke(width = 3f / scaleOf(doc)))
                            }
                        }
                    }
                }
            }
            Surface(Modifier.align(Alignment.TopStart).padding(8.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f), shape = MaterialTheme.shapes.small) {
                val g = state.gravityNow
                val pull = when {
                    !state.running -> ""
                    state.gravitySource == GravitySource.Tilt && !state.tiltInUse -> " · no tilt reading: straight down"
                    else -> " · pull %.1f m/s²".format(java.util.Locale.US, hypot(g.x, g.y))
                }
                Text(
                    "${specs.count { it.kind != BodyKind.Off }} bodies · ${state.hits} hits" +
                        (if (state.hits > 0) " · last %.2f".format(java.util.Locale.US, state.lastStrength) else "") + pull,
                    Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }, controls = {
        Surface(tonalElevation = 2.dp) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).panelScrollOpen(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                ScrollRow {
                    TextButton(onClick = {
                        grabFromDraw()?.let { d ->
                            state.load(d)
                            if (state.specs.isEmpty()) onMessage("No shapes on the Draw canvas: draw strokes or add a picture.")
                        }
                    }) { Text("Take from Draw") }
                    FilterChip(selected = state.running, enabled = state.source != null, onClick = { state.running = !state.running },
                        label = { Text(if (state.running) "Running" else "Run") })
                    TextButton(onClick = { state.reset() }, enabled = state.source != null) { Text("Reset") }
                    FilterChip(selected = state.recording, enabled = state.source != null,
                        onClick = { if (state.recordFull) onMessage(RECORD_FULL) else state.recording = !state.recording },
                        label = {
                            Text(when {
                                state.recordFull -> "Record (${state.recordedCount}, full)"
                                state.recordedCount > 0 -> "Record (${state.recordedCount})"
                                else -> "Record"
                            })
                        })
                    TextButton(enabled = state.recordedCount > 0, onClick = {
                        val frames = state.takeRecorded()
                        state.recording = false
                        animate.insertAll(frames)
                        onMessage("${frames.size} frames sent to Animate.")
                        onOpenAnimate()
                    }) { Text("To Animate") }
                }
                ScrollRow {
                    Text("Gravity", Modifier.width(72.dp), style = MaterialTheme.typography.labelMedium)
                    GravitySource.entries.forEach { g -> FilterChip(selected = state.gravitySource == g, onClick = { state.gravitySource = g }, label = { Text(g.label) }) }
                    FilterChip(selected = state.settings.walls, onClick = { state.updateSettings(state.settings.copy(walls = !state.settings.walls)) }, label = { Text("Walls") })
                    FilterChip(selected = state.settings.wallsTrigger, onClick = { state.updateSettings(state.settings.copy(wallsTrigger = !state.settings.wallsTrigger)) }, label = { Text("Wall hits count") })
                    FilterChip(selected = state.outlines, onClick = { state.outlines = !state.outlines }, label = { Text("Outlines") })
                }
                LabeledSlider("Gravity", state.gravityScale, 0f..3f, "×") { state.gravityScale = it }
                LabeledSlider("Bounce", state.settings.bounce, 0f..1f, "") { state.updateSettings(state.settings.copy(bounce = it)) }
                LabeledSlider("Friction", state.settings.friction, 0f..1f, "") { state.updateSettings(state.settings.copy(friction = it)) }
                LabeledSlider("Frame rate", state.settings.fps.toFloat(), 6f..60f, " fps", format = { "${it.toInt()}" }) { state.updateSettings(state.settings.copy(fps = it.toInt())) }
                if (specs.isNotEmpty()) {
                    Text("Tap a body to change what it does. Changing a body or a setting starts again.", style = MaterialTheme.typography.labelSmall)
                    ScrollRow {
                        specs.forEach { spec ->
                            val kinds = ShapeMapper.kindsFor(spec.shape)
                            FilterChip(
                                selected = spec.kind != BodyKind.Off,
                                onClick = { state.setKind(spec.layerId, kinds[(kinds.indexOf(spec.kind) + 1) % kinds.size]) },
                                label = { Text("${shapeName(spec.shape)} ${spec.layerId}: ${spec.kind.label}") },
                            )
                        }
                    }
                }
                LabeledSlider("Bake length", state.bakeSeconds, 1f..20f, " s") { state.bakeSeconds = it }
                if (state.bakeCut) {
                    Text("A bake keeps ${PhysicsState.MAX_RECORDED} frames at most: this one stops early.", style = MaterialTheme.typography.labelSmall)
                }
                Button(
                    enabled = state.source != null && !baking,
                    onClick = {
                        baking = true
                        val cut = state.bakeCut
                        scope.launch {
                            val frames = runCatching { state.bake() }.getOrElse { onMessage("Bake failed: ${it.message}"); emptyList() }
                            baking = false
                            if (frames.isNotEmpty()) {
                                animate.insertAll(frames)
                                onMessage(
                                    if (cut) "${frames.size} frames sent to Animate (the most a bake keeps, so it stopped early)."
                                    else "${frames.size} frames sent to Animate."
                                )
                                onOpenAnimate()
                            }
                        }
                    },
                ) { Text(if (baking) "Baking…" else "Bake ${state.bakeFrames} frames to Animate") }
            }
        }
    })
}

private const val RECORD_FULL = "Record is full (${PhysicsState.MAX_RECORDED} frames). Press To Animate to keep them and record more."

private fun shapeName(s: BodyShape) = when (s) {
    is BodyShape.Circle -> "Ball"
    is BodyShape.Box -> "Box"
    is BodyShape.Polygon -> "Shape"
    is BodyShape.Chain -> "Line"
}

/** How much a canvas is scaled to fit this area. */
private fun DrawScope.scaleOf(doc: CanvasDoc): Float =
    minOf(size.width / doc.widthPx.coerceAtLeast(1), size.height / doc.heightPx.coerceAtLeast(1))

/** Draws [block] in canvas pixels, the canvas fitted and centred in this area and clipped to its edges. */
private fun DrawScope.fitted(doc: CanvasDoc, block: DrawScope.() -> Unit) {
    val k = scaleOf(doc)
    withTransform({
        translate((size.width - doc.widthPx * k) / 2, (size.height - doc.heightPx * k) / 2)
        scale(k, k, Offset.Zero)
        clipRect(0f, 0f, doc.widthPx.toFloat(), doc.heightPx.toFloat())
    }) { block() }
}
