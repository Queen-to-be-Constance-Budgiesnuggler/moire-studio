package dk.cbavf.studio.camera

import dk.cbavf.studio.ui.layout.panelScroll
import dk.cbavf.studio.debug.Debug
import android.Manifest
import android.content.pm.PackageManager
import android.hardware.camera2.CameraAccessException
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.ui.input.pointer.pointerInput
import dk.cbavf.studio.camera.model.CaptureInfo
import dk.cbavf.studio.camera.model.WhiteBalance
import dk.cbavf.studio.draw.LayerImages
import dk.cbavf.studio.draw.drawLayers
import dk.cbavf.studio.draw.model.Blend
import dk.cbavf.studio.draw.model.Grating
import dk.cbavf.studio.draw.model.Ink
import dk.cbavf.studio.draw.model.Layer
import dk.cbavf.studio.ui.LabeledSlider
import dk.cbavf.studio.ui.ScrollRow
import kotlinx.coroutines.launch
import kotlin.math.ln
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** What the overlay can show besides a plain grating: the Draw canvas and the Animate barrier. */
class OverlaySources(
    val drawLayers: () -> List<Layer>,
    val barrierStripPx: () -> Int,
    val barrierFrames: () -> Int,
    /** What the rack cable into the camera's Overlay input carries, if anything. */
    val routed: () -> androidx.compose.ui.graphics.ImageBitmap? = { null },
)

@Composable
fun CameraRoom(
    state: CameraState,
    controller: CameraController,
    overlays: OverlaySources,
    onCapture: () -> Unit,
    onOpenFv5: () -> Unit,
    /** Opens the newest capture in the Library; null when there is none yet. */
    onOpenLast: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    if (!granted) {
        Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("The Camera room needs camera access.", style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { permission.launch(Manifest.permission.CAMERA) }) { Text("Allow camera") }
            TextButton(onClick = onOpenFv5) { Text("Open FV-5 instead") }
        }
        return
    }

    dk.cbavf.studio.ui.layout.RoomSplit(canvas = {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CameraPreview(state, controller, overlays)
            Readout(state, Modifier.align(Alignment.TopStart).padding(8.dp))
        }
    }, controls = {
        Controls(state, onCapture, onOpenFv5, onOpenLast)
    })
}

/** Live preview with the overlay on top, sized to the camera's aspect (also used by Loop). */
@Composable
fun CameraPreview(state: CameraState, controller: CameraController, overlays: OverlaySources?) {
    LaunchedEffect(Unit) {
        if (state.cameras.isEmpty()) {
            state.cameras = controller.cameras()
            if (state.cameraId == null) state.cameraId = state.cameras.firstOrNull()?.id
        }
    }
    // Settings follow the sliders live.
    LaunchedEffect(state.settings) { controller.apply(state.settings) }
    val spec = state.spec ?: return
    // Portrait: the preview buffer is landscape, shown turned upright.
    val aspect = spec.previewSize.height.toFloat() / spec.previewSize.width
    Box(
        Modifier
            .aspectRatio(aspect)
            // The overlay is burnt into captures at this size's proportions.
            .onSizeChanged { state.previewWidthPx = it.width; state.previewHeightPx = it.height }
            // Pinch to zoom (within what the camera allows).
            .pointerInput(spec.id) {
                detectTransformGestures { _, _, z, _ ->
                    if (z != 1f) state.zoom = (state.zoom * z).coerceIn(spec.zoomRange.start, spec.zoomRange.endInclusive)
                }
            }
    ) {
        // A new surface per camera: the buffer size differs between cameras.
        key(spec.id) { Preview(state, controller, spec) }
        if (overlays != null) OverlayCanvas(state, overlays)
    }
}

@Composable
private fun Preview(state: CameraState, controller: CameraController, spec: CameraSpec) {
    val scope = rememberCoroutineScope()
    DisposableEffect(spec.id) { onDispose { controller.close() } }
    AndroidView(
        factory = { ctx ->
            SurfaceView(ctx).apply {
                // Ask for the camera's preview size up front; the view scales it to fit.
                holder.setFixedSize(spec.previewSize.width, spec.previewSize.height)
                holder.addCallback(object : SurfaceHolder.Callback {
                    private var opened = false

                    override fun surfaceCreated(h: SurfaceHolder) {
                        h.setFixedSize(spec.previewSize.width, spec.previewSize.height)
                    }

                    override fun surfaceChanged(h: SurfaceHolder, format: Int, width: Int, height: Int) {
                        Debug.i("Studio", "preview surface ${width}x$height, want ${spec.previewSize}")
                        if (opened) return
                        opened = true
                        scope.launch {
                            runCatching { controller.open(spec.id, h.surface) }
                                .onFailure {
                                    Debug.w("Studio", "camera open", it)
                                    state.message = if (it is CameraAccessException && it.reason == CameraAccessException.CAMERA_DISABLED) {
                                        "Camera access is off for all apps: turn it on in quick settings."
                                    } else {
                                        "Camera: ${it.message}"
                                    }
                                }
                            controller.apply(state.settings)
                        }
                    }

                    override fun surfaceDestroyed(h: SurfaceHolder) {
                        opened = false
                        controller.close()
                    }
                })
            }
        },
        modifier = Modifier.fillMaxSize(),
    )
}

@Composable
private fun OverlayCanvas(state: CameraState, overlays: OverlaySources) {
    val routed = overlays.routed()
    if (state.overlay == Overlay.None && routed == null) return
    Canvas(
        Modifier.fillMaxSize().graphicsLayer {
            alpha = state.overlayOpacity
            compositingStrategy = CompositingStrategy.Offscreen
        }
    ) {
        // The same drawing code burns the overlay into the capture (OverlayComposite.kt).
        drawOverlay(state.overlaySnapshot(overlays), size.width, size.height, LayerImages::forScreen)
    }
}

@Composable
private fun Readout(state: CameraState, modifier: Modifier) {
    val text = listOfNotNull(
        state.spec?.let { "${it.label} · ${it.jpegSize.width}×${it.jpegSize.height}" + if (it.rawSize != null) " · RAW" else "" },
        state.info().summary,
        state.message,
    )
    Surface(modifier = modifier, color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f), shape = MaterialTheme.shapes.small) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            text.forEach { Text(it, style = MaterialTheme.typography.labelMedium) }
        }
    }
}

@Composable
private fun Controls(state: CameraState, onCapture: () -> Unit, onOpenFv5: () -> Unit, onOpenLast: (() -> Unit)?) {
    val spec = state.spec
    Surface(tonalElevation = 2.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
            ScrollRow {
                Button(onClick = onCapture, enabled = !state.busy && spec != null) {
                    Text(if (state.busy) "Saving…" else if (state.burst > 1) "Capture ×${state.burst}" else "Capture")
                }
                state.cameras.forEach { c ->
                    FilterChip(selected = c.id == state.cameraId, onClick = { state.cameraId = c.id }, label = { Text(c.label) })
                }
                TextButton(onClick = onOpenFv5) { Text("FV-5") }
                TextButton(onClick = { onOpenLast?.invoke() }, enabled = onOpenLast != null) { Text("Last in Library") }
            }
            HorizontalDivider()
            Column(Modifier.panelScroll(280.dp)) {
                if (spec != null) Zoom(state, spec)
                if (spec != null) Exposure(state, spec)
                Shooting(state, spec)
                OverlayControls(state)
            }
        }
    }
}

@Composable
private fun Zoom(state: CameraState, spec: CameraSpec) {
    val r = spec.zoomRange
    if (r.endInclusive <= r.start) return
    LabeledSlider("Zoom", state.zoom.coerceIn(r.start, r.endInclusive), r, "×", format = { "%.2f".format(it) }) { state.zoom = it }
}

@Composable
private fun Exposure(state: CameraState, spec: CameraSpec) {
    ScrollRow {
        Text("Exposure", Modifier.width(72.dp), style = MaterialTheme.typography.labelMedium)
        FilterChip(selected = !state.manualExposure, onClick = { state.manualExposure = false }, label = { Text("Auto") })
        FilterChip(
            selected = state.manualExposure,
            onClick = { state.manualExposure = true },
            enabled = spec.manualSensor,
            label = { Text(if (spec.manualSensor) "Manual" else "Manual (not on this camera)") },
        )
    }
    val exp = spec.exposureRangeNs
    val isoRange = spec.isoRange
    if (state.manualExposure && exp != null && isoRange != null) {
        // Shutter on a log scale: equal slider steps = equal stops.
        val lo = ln(exp.first.toDouble())
        val hi = ln(exp.last.toDouble())
        val pos = ((ln(state.exposureNs.coerceIn(exp.first, exp.last).toDouble()) - lo) / (hi - lo)).toFloat()
        val toNs = { p: Float -> exp(lo + p * (hi - lo)).roundToLong() }
        LabeledSlider(
            "Shutter", pos, 0f..1f, "",
            format = { CaptureInfo.shutterLabel(toNs(it)) },
            // Typed as 1/125, 0.008, 8ms or 2s; nudged a third of a stop at a time.
            parse = { t -> CaptureInfo.parseShutterNs(t)?.let { ns -> ((ln(ns) - lo) / (hi - lo)).toFloat() } },
            editText = { CaptureInfo.shutterLabel(toNs(it)) },
            step = (ln(2.0) / 3 / (hi - lo)).toFloat(),
            keyboard = androidx.compose.ui.text.input.KeyboardType.Text,
        ) {
            state.exposureNs = toNs(it)
        }
        LabeledSlider("ISO", state.iso.toFloat(), isoRange.first.toFloat()..isoRange.last.toFloat(), "", format = { "${it.roundToInt()}" }) {
            state.iso = it.roundToInt()
        }
    }
    if (spec.minFocusDiopters > 0f) {
        ScrollRow {
            Text("Focus", Modifier.width(72.dp), style = MaterialTheme.typography.labelMedium)
            FilterChip(selected = !state.manualFocus, onClick = { state.manualFocus = false }, label = { Text("Auto") })
            FilterChip(selected = state.manualFocus, onClick = { state.manualFocus = true }, label = { Text("Manual") })
        }
        if (state.manualFocus) {
            LabeledSlider("Distance", state.focusDiopters, 0f..spec.minFocusDiopters, "", format = {
                if (it <= 0.01f) "∞" else "${(100 / it).roundToInt()} cm"
            }) { state.focusDiopters = it }
        }
    }
    if (spec.apertures.size > 1) {
        ScrollRow {
            Text("Aperture", Modifier.width(72.dp), style = MaterialTheme.typography.labelMedium)
            spec.apertures.forEach { a ->
                FilterChip(selected = state.aperture == a, onClick = { state.aperture = a }, label = { Text("f/$a") })
            }
        }
    }
    ScrollRow {
        Text("White", Modifier.width(72.dp), style = MaterialTheme.typography.labelMedium)
        WhiteBalance.entries.forEach { wb ->
            FilterChip(selected = state.whiteBalance == wb, onClick = { state.whiteBalance = wb }, label = { Text(wb.name) })
        }
    }
    if (spec.hasFlash) {
        ScrollRow {
            FilterChip(selected = state.torch, onClick = { state.torch = !state.torch }, label = { Text("Torch") })
        }
    }
}

@Composable
private fun Shooting(state: CameraState, spec: CameraSpec?) {
    LabeledSlider("Burst", state.burst.toFloat(), 1f..CameraController.MAX_BURST.toFloat(), "", steps = CameraController.MAX_BURST - 2, format = { "${it.roundToInt()}" }) {
        state.burst = it.roundToInt()
    }
    if (state.burst > 1) {
        ScrollRow {
            Text("Stack", Modifier.width(72.dp), style = MaterialTheme.typography.labelMedium)
            StackKind.entries.forEach { k ->
                FilterChip(selected = state.stackKind == k, onClick = { state.stackKind = k }, label = { Text(k.name) })
            }
        }
        when (state.stackKind) {
            StackKind.Additive -> LabeledSlider("Gain", state.gain, 0.1f..1f, "") { state.gain = it }
            StackKind.Interlace -> LabeledSlider("Rows/frame", state.rowsPerFrame, 1f..1024f, "", format = { "${it.roundToInt()}" }) {
                state.rowsPerFrame = it
            }
            StackKind.Mean -> Unit
        }
    }
    ScrollRow {
        if (spec?.rawSize != null) {
            FilterChip(selected = state.raw, onClick = { state.raw = !state.raw }, label = { Text("RAW (DNG)") })
        }
        FilterChip(selected = state.half, onClick = { state.half = !state.half }, label = { Text("Half resolution") })
    }
}

@Composable
private fun OverlayControls(state: CameraState) {
    ScrollRow {
        Text("Overlay", Modifier.width(72.dp), style = MaterialTheme.typography.labelMedium)
        Overlay.entries.forEach { o ->
            FilterChip(
                selected = state.overlay == o,
                onClick = { state.overlay = o },
                // An image comes from Library → Use in → Camera.
                enabled = o != Overlay.Image || state.overlayAssetId != null,
                label = { Text(o.name) },
            )
        }
    }
    if (state.overlay == Overlay.Image && state.overlayAssetId == null) {
        Text("Pick one in Library → Use in → Camera.", style = MaterialTheme.typography.labelMedium)
    }
    if (state.overlay == Overlay.None) return
    LabeledSlider("Opacity", state.overlayOpacity, 0.1f..1f, "") { state.overlayOpacity = it }
    ScrollRow {
        Text("Blend", Modifier.width(72.dp), style = MaterialTheme.typography.labelMedium)
        Blend.entries.forEach { b ->
            FilterChip(selected = state.overlayBlend == b, onClick = { state.overlayBlend = b }, label = { Text(b.name) })
        }
    }
    ScrollRow {
        FilterChip(selected = state.burnIn, onClick = { state.burnIn = !state.burnIn }, label = { Text("Burn into capture") })
        Text(
            if (state.burnIn) "The overlay is composited into the saved image with this blend. The preview shows plain opacity."
            else "The overlay is only recorded with the capture, not in the pixels.",
            style = MaterialTheme.typography.labelMedium,
        )
    }
    if (state.overlay == Overlay.Grating) {
        LabeledSlider("Spacing", state.overlaySpacing, 2f..80f, " px") { state.overlaySpacing = it }
        LabeledSlider("Angle", state.overlayAngle, -90f..90f, "°") { state.overlayAngle = it }
    }
}
