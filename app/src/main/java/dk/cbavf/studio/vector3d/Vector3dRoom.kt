package dk.cbavf.studio.vector3d

import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import dk.cbavf.studio.ui.LabeledSlider
import dk.cbavf.studio.ui.ScrollRow
import dk.cbavf.studio.ui.layout.panelScroll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** The 3D room's scene, view and selection. Kept while the studio is open. */
class Vector3dState {
    var scene by mutableStateOf(Scene.DEFAULT)
    var camera by mutableStateOf(OrbitCamera())

    /** Index of the shape the controls change; -1 when there is none. */
    var selected by mutableIntStateOf(1)
    var tab by mutableStateOf(Vector3dTab.Shape)

    val current: SceneObject? get() = scene.objects.getOrNull(selected)

    fun edit(f: (SceneObject) -> SceneObject) {
        scene = scene.update(selected, f)
    }

    fun editGrating(f: (Grating) -> Grating) = edit { it.copy(grating = f(it.grating)) }
}

enum class Vector3dTab(val label: String) { Shape("Shape"), Grating("Grating"), View("View"), Out("Save") }

/** Degrees the view turns per pixel dragged. */
private const val DEG_PER_PX = 0.35

/** The long side of a saved picture, in pixels. */
private const val SAVE_PX = 2048

/**
 * The 3D room: low-poly shapes (cube, ball, plane, column, cone) with parallel lines on their faces, seen through a camera that
 * circles them. Drag to turn the view, pinch to zoom. Faces are flat shaded by one light and drawn far to near; see-through
 * shapes show their back faces' lines through the front ones, which is where the moiré is. The view saves as a picture in
 * the Library or as an SVG file.
 */
@Composable
fun Vector3dRoom(
    state: Vector3dState,
    busy: Boolean,
    /** Files a picture in the Library. */
    onSaveImage: (Bitmap) -> Unit,
    onMessage: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    var preparing by remember { mutableStateOf(false) }

    dk.cbavf.studio.ui.layout.RoomSplit(canvas = {
        Canvas(
            Modifier.fillMaxSize()
                .onSizeChanged { viewSize = it }
                .pointerInput(state) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        state.camera = state.camera.orbit(-pan.x * DEG_PER_PX, pan.y * DEG_PER_PX).zoom(zoom.toDouble())
                    }
                },
        ) {
            // Reading the scene and camera here redraws on every change; the sums are small enough for a frame.
            drawFrame(Render3d.frame(state.scene, state.camera, size.width.toInt(), size.height.toInt()))
        }
    }, controls = {
        Surface(tonalElevation = 2.dp) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                ScrollRow {
                    Vector3dTab.entries.forEach { t -> FilterChip(selected = state.tab == t, onClick = { state.tab = t }, label = { Text(t.label) }) }
                }
                ScrollRow {
                    state.scene.objects.forEachIndexed { i, o ->
                        FilterChip(selected = i == state.selected, onClick = { state.selected = i }, label = { Text("${i + 1} ${o.shape.label}") })
                    }
                }
                Column(Modifier.panelScroll(260.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    when (state.tab) {
                        Vector3dTab.Shape -> ShapeControls(state)
                        Vector3dTab.Grating -> GratingControls(state)
                        Vector3dTab.View -> ViewControls(state)
                        Vector3dTab.Out -> {
                            Text("Saves the view as it is now, ${SAVE_PX} pixels on the long side.", style = MaterialTheme.typography.bodySmall)
                            ScrollRow {
                                Button(enabled = !busy && !preparing, onClick = {
                                    val (w, h) = saveSize(viewSize)
                                    val scene = state.scene
                                    val camera = state.camera
                                    preparing = true
                                    scope.launch {
                                        val bmp = withContext(Dispatchers.Default) { runCatching { renderBitmap(Render3d.frame(scene, camera, w, h)) }.getOrNull() }
                                        preparing = false
                                        if (bmp != null) onSaveImage(bmp) else onMessage("Could not make the picture.")
                                    }
                                }) { Text(if (busy || preparing) "Saving…" else "Save image") }
                                TextButton(onClick = {
                                    val (w, h) = saveSize(viewSize)
                                    val scene = state.scene
                                    val camera = state.camera
                                    scope.launch {
                                        val uri = withContext(Dispatchers.IO) {
                                            runCatching { saveSvg(context, svgName(), SvgWriter.write(Render3d.frame(scene, camera, w, h))) }.getOrNull()
                                        }
                                        onMessage(if (uri != null) "Saved to Download/Studio." else "Could not save the SVG.")
                                    }
                                }) { Text("Save SVG") }
                                TextButton(onClick = {
                                    val (w, h) = saveSize(viewSize)
                                    val scene = state.scene
                                    val camera = state.camera
                                    scope.launch {
                                        val uri = withContext(Dispatchers.IO) {
                                            runCatching { shareSvgFile(context, svgName(), SvgWriter.write(Render3d.frame(scene, camera, w, h))) }.getOrNull()
                                        }
                                        if (uri == null || !openShareSheet(context, uri)) onMessage("No app can take an SVG.")
                                    }
                                }) { Text("Share SVG") }
                            }
                        }
                    }
                }
            }
        }
    })
}

@Composable
private fun ShapeControls(state: Vector3dState) {
    ScrollRow {
        Shape.entries.forEach { s ->
            TextButton(onClick = { state.scene = state.scene.add(s); state.selected = state.scene.objects.lastIndex }) { Text("Add ${s.label.lowercase()}") }
        }
    }
    val o = state.current
    if (o == null) {
        Text("Add a shape to start.", style = MaterialTheme.typography.bodySmall)
        return
    }
    LabeledSlider("Left/right", o.position.x.toFloat(), -3f..3f, "") { v -> state.edit { it.copy(position = it.position.copy(x = v.toDouble())) } }
    LabeledSlider("Up/down", o.position.y.toFloat(), -3f..3f, "") { v -> state.edit { it.copy(position = it.position.copy(y = v.toDouble())) } }
    LabeledSlider("Near/far", o.position.z.toFloat(), -3f..3f, "") { v -> state.edit { it.copy(position = it.position.copy(z = v.toDouble())) } }
    LabeledSlider("Size", o.scale.x.toFloat(), 0.1f..6f, "") { v ->
        state.edit { it.copy(scale = Vec3(v.toDouble(), it.scale.y / it.scale.x * v, v.toDouble())) }
    }
    LabeledSlider("Height", (o.scale.y / o.scale.x).toFloat(), 0.1f..4f, "×") { v -> state.edit { it.copy(scale = it.scale.copy(y = it.scale.x * v)) } }
    LabeledSlider("Turn", o.yawDeg.toFloat(), 0f..360f, "°", format = { "${it.toInt()}" }) { v -> state.edit { it.copy(yawDeg = v.toDouble()) } }
    LabeledSlider("Tilt", o.tiltDeg.toFloat(), -90f..90f, "°", format = { "${it.toInt()}" }) { v -> state.edit { it.copy(tiltDeg = v.toDouble()) } }
    if (o.shape == Shape.Sphere || o.shape == Shape.Cylinder || o.shape == Shape.Cone) {
        LabeledSlider("Detail", o.detail.toFloat(), 0f..Shape.MAX_DETAIL.toFloat(), "", steps = Shape.MAX_DETAIL - 1, format = { "${it.toInt()}" }) { v ->
            state.edit { it.copy(detail = v.toInt()) }
        }
    }
    LabeledSlider("Opacity", (o.opacity * 100).toFloat(), 0f..100f, " %", format = { "${it.toInt()}" }) { v -> state.edit { it.copy(opacity = v / 100.0) } }
    Text("Below 100 % the back faces show through.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    ScrollRow {
        Scene.PALETTE.forEach { c ->
            FilterChip(selected = o.color == c, onClick = { state.edit { it.copy(color = c) } }, label = { Text("■", color = Color(c)) })
        }
        TextButton(onClick = { state.scene = state.scene.remove(state.selected); state.selected = minOf(state.selected, state.scene.objects.lastIndex) }) { Text("Remove") }
    }
}

@Composable
private fun GratingControls(state: Vector3dState) {
    val o = state.current
    if (o == null) {
        Text("Add a shape to start.", style = MaterialTheme.typography.bodySmall)
        return
    }
    val g = o.grating
    ScrollRow {
        FilterChip(selected = g.on, onClick = { state.editGrating { it.copy(on = !it.on) } }, label = { Text("Lines on") })
        FilterChip(selected = g.color == 0xFF000000.toInt(), onClick = { state.editGrating { it.copy(color = 0xFF000000.toInt()) } }, label = { Text("Black") })
        FilterChip(selected = g.color == 0xFFFFFFFF.toInt(), onClick = { state.editGrating { it.copy(color = 0xFFFFFFFF.toInt()) } }, label = { Text("White") })
    }
    LabeledSlider("Spacing", g.spacing.toFloat(), 0.01f..0.5f, "", format = { "%.3f".format(it) }, log = true) { v -> state.editGrating { it.copy(spacing = v.toDouble()) } }
    LabeledSlider("Line width", g.width.toFloat(), 0.002f..0.2f, "", format = { "%.3f".format(it) }, log = true) { v -> state.editGrating { it.copy(width = v.toDouble()) } }
    LabeledSlider("Angle", g.angleDeg.toFloat(), 0f..180f, "°", format = { "${it.toInt()}" }) { v -> state.editGrating { it.copy(angleDeg = v.toDouble()) } }
    LabeledSlider("Phase", g.phase.toFloat(), 0f..1f, "") { v -> state.editGrating { it.copy(phase = v.toDouble()) } }
}

@Composable
private fun ViewControls(state: Vector3dState) {
    Text("Drag the picture to turn the view. Pinch to zoom.", style = MaterialTheme.typography.bodySmall)
    val c = state.camera
    LabeledSlider("View turn", c.yawDeg.toFloat(), 0f..360f, "°", format = { "${it.toInt()}" }) { v -> state.camera = state.camera.copy(yawDeg = v.toDouble()) }
    LabeledSlider("View height", c.pitchDeg.toFloat(), -89f..89f, "°", format = { "${it.toInt()}" }) { v -> state.camera = state.camera.copy(pitchDeg = v.toDouble()) }
    LabeledSlider("Distance", c.distance.toFloat(), OrbitCamera.MIN_DISTANCE.toFloat()..OrbitCamera.MAX_DISTANCE.toFloat(), "", log = true) { v -> state.camera = state.camera.copy(distance = v.toDouble()) }
    LabeledSlider("Lens", c.fovDeg.toFloat(), 15f..100f, "°", format = { "${it.toInt()}" }) { v -> state.camera = state.camera.copy(fovDeg = v.toDouble()) }
    val l = state.scene.light
    LabeledSlider("Light turn", l.azimuthDeg.toFloat(), 0f..360f, "°", format = { "${it.toInt()}" }) { v -> state.scene = state.scene.copy(light = state.scene.light.copy(azimuthDeg = v.toDouble())) }
    LabeledSlider("Light height", l.elevationDeg.toFloat(), 0f..90f, "°", format = { "${it.toInt()}" }) { v -> state.scene = state.scene.copy(light = state.scene.light.copy(elevationDeg = v.toDouble())) }
    LabeledSlider("Ambient", (l.ambient * 100).toFloat(), 0f..100f, " %", format = { "${it.toInt()}" }) { v -> state.scene = state.scene.copy(light = state.scene.light.copy(ambient = v / 100.0)) }
    ScrollRow {
        TextButton(onClick = { state.camera = OrbitCamera() }) { Text("Reset view") }
        TextButton(onClick = { state.scene = Scene.DEFAULT; state.selected = 1 }) { Text("Start again") }
    }
}

/** The faces in order, each filled and then lined, so nearer faces cover farther ones and their lines. */
private fun DrawScope.drawFrame(frame: Frame) {
    drawRect(Color(frame.background))
    val path = Path()
    for (f in frame.faces) {
        path.reset()
        path.moveTo(f.outline[0], f.outline[1])
        for (i in 1 until f.outline.size / 2) path.lineTo(f.outline[2 * i], f.outline[2 * i + 1])
        path.close()
        drawPath(path, Color(f.fill))
        val lc = Color(f.lineColor)
        val lw = f.lineWidth.coerceAtLeast(0.5f)
        for (i in 0 until f.lines.size / 4) {
            drawLine(lc, Offset(f.lines[4 * i], f.lines[4 * i + 1]), Offset(f.lines[4 * i + 2], f.lines[4 * i + 3]), lw)
        }
    }
}

/** The same picture as [drawFrame], into a bitmap. Call it off the main thread. */
private fun renderBitmap(frame: Frame): Bitmap {
    val bmp = Bitmap.createBitmap(frame.width, frame.height, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bmp)
    canvas.drawColor(frame.background)
    val fill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { style = android.graphics.Paint.Style.FILL }
    val line = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { style = android.graphics.Paint.Style.STROKE }
    val path = android.graphics.Path()
    for (f in frame.faces) {
        path.reset()
        path.moveTo(f.outline[0], f.outline[1])
        for (i in 1 until f.outline.size / 2) path.lineTo(f.outline[2 * i], f.outline[2 * i + 1])
        path.close()
        fill.color = f.fill
        canvas.drawPath(path, fill)
        if (f.lines.isNotEmpty()) {
            line.color = f.lineColor
            line.strokeWidth = f.lineWidth.coerceAtLeast(0.5f)
            canvas.drawLines(f.lines, line)
        }
    }
    return bmp
}

/** The saved size: the view's shape with [SAVE_PX] on its long side (square before the view has a size). */
private fun saveSize(view: IntSize): Pair<Int, Int> {
    if (view.width <= 0 || view.height <= 0) return SAVE_PX to SAVE_PX
    val k = SAVE_PX.toDouble() / maxOf(view.width, view.height)
    return maxOf(1, (view.width * k).toInt()) to maxOf(1, (view.height * k).toInt())
}

private fun svgName() = "studio-3d-${System.currentTimeMillis()}.svg"

/** Writes the SVG to Download/Studio, where file apps and vector editors find it. */
private fun saveSvg(context: Context, name: String, text: String): Uri? {
    val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, name)
        put(MediaStore.MediaColumns.MIME_TYPE, SvgWriter.MIME)
        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Studio")
        put(MediaStore.MediaColumns.IS_PENDING, 1)
    }
    val resolver = context.contentResolver
    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
    return runCatching {
        resolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) } ?: error("no stream")
        resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        uri
    }.getOrElse {
        resolver.delete(uri, null, null)
        null
    }
}

/** Writes the SVG into cache/shared and returns a content:// uri other apps may read. */
private fun shareSvgFile(context: Context, name: String, text: String): Uri {
    val dir = File(context.cacheDir, "shared").apply { mkdirs() }
    val file = File(dir, name)
    file.writeText(text, Charsets.UTF_8)
    return FileProvider.getUriForFile(context, context.packageName + ".files", file)
}

private fun openShareSheet(context: Context, uri: Uri): Boolean {
    val send = Intent(Intent.ACTION_SEND).setType(SvgWriter.MIME).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    return try {
        context.startActivity(Intent.createChooser(send, "Share SVG").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        true
    } catch (e: ActivityNotFoundException) {
        false
    }
}
