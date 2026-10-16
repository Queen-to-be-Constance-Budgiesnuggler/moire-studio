package dk.cbavf.studio.loop

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dk.cbavf.studio.camera.CameraController
import dk.cbavf.studio.camera.CameraPreview
import dk.cbavf.studio.camera.CameraState
import dk.cbavf.studio.link.LoopMode
import dk.cbavf.studio.link.LoopRole
import dk.cbavf.studio.sequence.FullScreenWindow
import dk.cbavf.studio.ui.LabeledSlider
import dk.cbavf.studio.ui.ScrollRow
import kotlin.math.roundToInt
import kotlin.math.roundToLong

@Composable
fun LoopRoom(engine: LoopEngine, cameraState: CameraState, camera: CameraController) {
    // Projector phone while running: the frame, full screen; tap to stop.
    if (engine.role == LoopRole.Projector && engine.running) {
        FullScreenWindow(rateHz = null)
        Box(
            Modifier.fillMaxSize().background(Color.Black).pointerInput(Unit) { detectTapGestures { engine.stop() } },
            contentAlignment = Alignment.Center,
        ) {
            engine.shown?.let {
                Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit, filterQuality = FilterQuality.None)
            }
        }
        return
    }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            if (engine.role == LoopRole.Camera) {
                val granted = LocalContext.current.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                if (granted) {
                    CameraPreview(cameraState, camera, overlays = null)
                } else {
                    Text("Allow the camera in the Camera room first.", style = MaterialTheme.typography.bodyMedium)
                }
            } else {
                Text(
                    "This phone shows the frames. Point the other phone (Camera role) at this screen, then Start.",
                    Modifier.padding(32.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Surface(
                Modifier.align(Alignment.TopStart).padding(8.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                shape = MaterialTheme.shapes.small,
            ) {
                Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                    Text(engine.status, style = MaterialTheme.typography.labelMedium)
                    if (engine.done > 0) Text("${engine.done} captures", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        Controls(engine)
    }
}

@Composable
private fun Controls(engine: LoopEngine) {
    val addresses = remember { engine.ownAddresses() }
    Surface(tonalElevation = 2.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
            ScrollRow {
                Text("This phone", Modifier.width(72.dp), style = MaterialTheme.typography.labelMedium)
                LoopRole.entries.forEach { r ->
                    FilterChip(
                        selected = engine.role == r,
                        onClick = { engine.role = r },
                        enabled = !engine.connected,
                        label = { Text(r.name) },
                    )
                }
            }
            Text("Address: ${addresses.joinToString(", ").ifEmpty { "no network" }}", style = MaterialTheme.typography.labelMedium)
            OutlinedTextField(
                value = engine.host,
                onValueChange = { engine.host = it },
                enabled = !engine.connected,
                singleLine = true,
                label = { Text("Other phone's address (empty: wait for it)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            ScrollRow {
                if (engine.connected) {
                    TextButton(onClick = engine::disconnect) { Text("Disconnect") }
                } else {
                    TextButton(onClick = engine::connect) { Text(if (engine.host.isBlank()) "Wait for other phone" else "Connect") }
                }
                if (engine.role == LoopRole.Projector) {
                    Button(onClick = { if (engine.running) engine.stop() else engine.start() }, enabled = engine.connected) {
                        Text(if (engine.running) "Stop" else "Start loop")
                    }
                }
            }
            if (engine.role == LoopRole.Projector) {
                HorizontalDivider()
                Column(Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState())) {
                    ScrollRow {
                        Text("Timing", Modifier.width(72.dp), style = MaterialTheme.typography.labelMedium)
                        LoopMode.entries.forEach { m ->
                            FilterChip(selected = engine.mode == m, onClick = { engine.mode = m }, label = { Text(if (m == LoopMode.FreeRun) "Free-run" else "Synced") })
                        }
                    }
                    ScrollRow {
                        Text("Next frame", Modifier.width(72.dp), style = MaterialTheme.typography.labelMedium)
                        FilterChip(selected = engine.source == LoopSource.Feedback, onClick = { engine.source = LoopSource.Feedback }, label = { Text("The capture (feedback)") })
                        FilterChip(selected = engine.source == LoopSource.Sequence, onClick = { engine.source = LoopSource.Sequence }, label = { Text("Next Sequence item") })
                    }
                    LabeledSlider("Generations", engine.generations.toFloat(), 1f..100f, "", format = { "${it.roundToInt()}" }) {
                        engine.generations = it.roundToInt()
                    }
                    if (engine.mode == LoopMode.Synced) {
                        LabeledSlider("Settle", engine.settleMs.toFloat(), 100f..3000f, " ms", format = { "${it.roundToInt()}" }) {
                            engine.settleMs = it.roundToLong()
                        }
                    } else {
                        LabeledSlider("Interval", engine.intervalMs.toFloat(), 300f..10000f, " ms", format = { "${it.roundToInt()}" }) {
                            engine.intervalMs = it.roundToLong()
                        }
                    }
                    ScrollRow {
                        FilterChip(selected = engine.strobe, onClick = { engine.strobe = !engine.strobe }, label = { Text("Torch strobe on camera phone") })
                    }
                    engine.startAssetId?.let { id ->
                        ScrollRow {
                            Text("Starts from Library image ${id.takeLast(6)}", style = MaterialTheme.typography.labelMedium)
                            TextButton(onClick = { engine.startAssetId = null }) { Text("Use Sequence instead") }
                        }
                    }
                    Text(
                        "Starts from the image picked in Library (Use in → Loop), else the first Sequence item, else the newest " +
                            "Library image. Captures use the camera phone's " +
                            "Camera room settings and are filed here as children of the frame on screen.",
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
    }
}
