package dk.cbavf.studio.ui

import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.graphics.asImageBitmap
import dk.cbavf.studio.nodes.previewOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.snapshots.SnapshotStateList
import dk.cbavf.studio.Incoming
import dk.cbavf.studio.animate.AnimateRoom
import dk.cbavf.studio.animate.AnimateState
import dk.cbavf.studio.animate.renderBarrier
import dk.cbavf.studio.animate.renderInterleaved
import dk.cbavf.studio.bridge.BridgeController
import dk.cbavf.studio.bridge.BridgeEffects
import dk.cbavf.studio.bridge.BridgeRoom
import dk.cbavf.studio.bridge.BridgeState
import dk.cbavf.studio.bridge.RecordingStatus
import dk.cbavf.studio.bridge.ScreenRecordService
import dk.cbavf.studio.bridge.model.ExternalApp
import dk.cbavf.studio.camera.CameraController
import dk.cbavf.studio.camera.CameraRoom
import dk.cbavf.studio.camera.CameraState
import dk.cbavf.studio.camera.OverlaySources
import dk.cbavf.studio.camera.saveCapture
import dk.cbavf.studio.develop.DevelopRoom
import dk.cbavf.studio.develop.DevelopState
import dk.cbavf.studio.develop.saveDevelop
import dk.cbavf.studio.draw.DrawRoom
import dk.cbavf.studio.fft.FftRoom
import dk.cbavf.studio.fft.FftState
import dk.cbavf.studio.glitch.GlitchRoom
import dk.cbavf.studio.glitch.GlitchState
import dk.cbavf.studio.library.saveBitmapAsset
import dk.cbavf.studio.loop.LoopEngine
import dk.cbavf.studio.loop.LoopRoom
import dk.cbavf.studio.patch.PatchEngine
import dk.cbavf.studio.patch.PatchRoom
import dk.cbavf.studio.patch.SensorHub
import dk.cbavf.studio.draw.DrawState
import dk.cbavf.studio.library.Autosave
import dk.cbavf.studio.library.Lineage
import dk.cbavf.studio.library.LibraryRoom
import dk.cbavf.studio.library.ProjectStore
import dk.cbavf.studio.library.Recipe
import dk.cbavf.studio.library.encodePng
import dk.cbavf.studio.library.imageAssetIds
import dk.cbavf.studio.library.exportToMedia
import dk.cbavf.studio.library.exportToPictures
import dk.cbavf.studio.library.renderPng
import dk.cbavf.studio.sequence.ExportStatus
import dk.cbavf.studio.sequence.FrameDecoder
import dk.cbavf.studio.sequence.screenSize
import dk.cbavf.studio.sequence.Projector
import dk.cbavf.studio.sequence.SequenceRoom
import dk.cbavf.studio.sequence.SequenceState
import dk.cbavf.studio.sequence.export.ExportReport
import dk.cbavf.studio.sequence.export.exportGif
import dk.cbavf.studio.sequence.export.exportMp4
import dk.cbavf.studio.sequence.mp4Fps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val AUTOSAVE_DELAY_MS = 800L
private const val TAG = "Studio"

/** The rooms from docs/ARCHITECTURE.md, in build order. */
enum class Room(val title: String, val purpose: String) {
    Library("Library", "Lineage tree of every asset and how it was made"),
    Compose("Compose", "The main layered editor: composite and control it all"),
    Nodes("routing-nodes", "Node-based studio routing: custom pipelines and presets"),
    Draw("Draw", "Stroke objects, stroke stacks, live moiré while dragging"),
    Animate("Animate", "Moiré-shift stop motion and barrier-grid animation"),
    Sequence("Sequence", "Timeline and projector mode"),
    Bridge("Bridge", "Samsung Gallery / Notes / FV-5 round-trips and watch folders"),
    Camera("Camera", "Manual capture and software optical stack"),
    Loop("Loop", "Two-phone projector / camera loop"),
    Patch("Patch", "Sensors and LFOs routed to any parameter"),
    Develop("Develop", "Per-hue mixer, curves, LUTs, style"),
    Fft("FFT", "Spectrum view, spectrum painting, magnitude/phase swap"),
    Glitch("Glitch", "Byte-level reinterpretation and re-encode generations"),
}

@OptIn(FlowPreview::class)
@Composable
fun StudioApp(incoming: SnapshotStateList<Incoming>) {
    MaterialTheme(colorScheme = darkColorScheme()) {
        val context = LocalContext.current
        val prefs = remember { context.getSharedPreferences("studio", android.content.Context.MODE_PRIVATE) }
        // The last room survives restarts, not just rotation.
        var room by rememberSaveable {
            mutableStateOf(runCatching { Room.valueOf(prefs.getString("room", null) ?: "") }.getOrDefault(Room.Draw))
        }
        LaunchedEffect(room) { prefs.edit().putString("room", room.name).apply() }
        val drawState = remember { DrawState() }
        val animateState = remember { AnimateState() }
        val sequenceState = remember { SequenceState() }
        val exportStatus = remember { ExportStatus() }
        var projecting by remember { mutableStateOf(false) }
        val store = remember { ProjectStore.open(File(context.filesDir, "projects/default")).also { dk.cbavf.studio.draw.LayerImages.store = it } }
        var lineage by remember { mutableStateOf(Lineage(emptyList())) }
        val scope = rememberCoroutineScope()
        val bridge = remember {
            BridgeController(context, store, BridgeState(), scope) {
                lineage = withContext(Dispatchers.IO) { store.lineage() }
            }
        }
        BridgeEffects(bridge, store, incoming) { room = Room.Library }
        val libraryNav = remember { dk.cbavf.studio.library.LibraryNav() }
        val cameraState = remember { CameraState() }
        val camera = remember { CameraController(context) }
        DisposableEffect(camera) { onDispose { camera.release() } }
        val loop = remember {
            LoopEngine(
                context, store, scope, cameraState, camera,
                sequenceSources = { sequenceState.items.map { it.source } },
                lineage = { lineage },
                onLineageChanged = { lineage = withContext(Dispatchers.IO) { store.lineage() } },
            )
        }
        DisposableEffect(loop) { onDispose { loop.disconnect() } }
        val developState = remember { DevelopState() }
        var developBusy by remember { mutableStateOf(false) }
        LaunchedEffect(store) {
            withContext(Dispatchers.IO) { store.loadDevelop() }?.let(developState::load)
            developState.presets = withContext(Dispatchers.IO) { store.loadPresets() }
            snapshotFlow { developState.doc }
                .debounce(AUTOSAVE_DELAY_MS)
                .collect { withContext(Dispatchers.IO) { store.saveDevelop(it) } }
        }

        fun saveDeveloped() {
            if (developBusy) return
            developBusy = true
            val doc = developState.doc
            scope.launch {
                val message = runCatching {
                    val r = withContext(Dispatchers.Default) { saveDevelop(context, store, lineage, doc) }
                    lineage = withContext(Dispatchers.IO) { store.lineage() }
                    "Saved ${r.widthPx}×${r.heightPx} · generation ${lineage.generation(r.id)}"
                }.getOrElse { "Save failed: ${it.message}" }
                developBusy = false
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
        }

        val fftState = remember { FftState() }
        val glitchState = remember { GlitchState() }
        var labBusy by remember { mutableStateOf(false) }

        /** Files a lab result (FFT, Glitch) as a child of the images it was made from. */
        fun saveLab(bitmap: android.graphics.Bitmap, parents: List<String>, operation: String, recipe: Recipe, png: Boolean) {
            if (labBusy) return
            labBusy = true
            scope.launch {
                val message = runCatching {
                    val r = withContext(Dispatchers.IO) { saveBitmapAsset(context, store, bitmap, parents, operation, recipe, png) }
                    lineage = withContext(Dispatchers.IO) { store.lineage() }
                    "Saved ${r.widthPx}×${r.heightPx} · generation ${lineage.generation(r.id)}"
                }.getOrElse { "Save failed: ${it.message}" }
                labBusy = false
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
        }

        val composeState = remember { dk.cbavf.studio.compose.ComposeState() }
        val layerPixels = remember { dk.cbavf.studio.compose.LayerPixels(store) { lineage } }
        var composeBusy by remember { mutableStateOf(false) }
        LaunchedEffect(store) {
            withContext(Dispatchers.IO) { store.loadCompose() }?.let(composeState.edit::load)
            snapshotFlow { composeState.doc }
                .debounce(AUTOSAVE_DELAY_MS)
                .collect { withContext(Dispatchers.IO) { store.saveCompose(it) } }
        }

        /** Full-resolution render of the composite, filed with every image it uses as a parent. */
        fun saveComposite() {
            if (composeBusy) return
            composeBusy = true
            val doc = composeState.doc
            scope.launch {
                val message = runCatching {
                    val png = dk.cbavf.studio.compose.composeOutputIsPng(doc, lineage)
                    val r = withContext(Dispatchers.Default) {
                        val bitmap = dk.cbavf.studio.compose.renderComposite(doc, 1f, layerPixels)
                        saveBitmapAsset(context, store, bitmap, doc.assetIds(), "compose.render", Recipe(compose = doc), png)
                            .also { bitmap.recycle() }
                    }
                    lineage = withContext(Dispatchers.IO) { store.lineage() }
                    "Saved ${r.widthPx}×${r.heightPx} · generation ${lineage.generation(r.id)}"
                }.getOrElse { "Save failed: ${it.message}" }
                composeBusy = false
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
        }

        val sensors = remember { SensorHub(context) }
        val patch = remember { PatchEngine(sensors, drawState, animateState, loop, scope) }

        val nodesState = remember { dk.cbavf.studio.nodes.NodesState() }
        val nodeBackend = remember {
            dk.cbavf.studio.nodes.AndroidImageBackend(store, { lineage }, { drawState.toDoc() }, remote = { loop.remoteFrame to loop.remoteFrames }, roomFrames = { source ->
                when (source) {
                    // Held frames repeat, so a Clock steps through them as Animate plays them.
                    "animate" -> animateState.frames.toList().flatMap { f -> List(f.hold.coerceAtLeast(1)) { dk.cbavf.studio.nodes.model.FrameRef.Canvas(f.doc) } }
                    "sequence" -> sequenceState.items.toList().map { item ->
                        when (val src = item.source) {
                            is dk.cbavf.studio.sequence.model.AssetSource -> dk.cbavf.studio.nodes.model.FrameRef.Asset(src.assetId)
                            is dk.cbavf.studio.sequence.model.CanvasSource -> dk.cbavf.studio.nodes.model.FrameRef.Canvas(src.doc)
                        }
                    }
                    else -> emptyList()
                }
            })
        }
        // Recently used node images (least recently used dropped first: bitmaps are big).
        val nodeCache = remember {
            java.util.Collections.synchronizedMap(object : LinkedHashMap<String, dk.cbavf.studio.nodes.model.NodeValue>(32, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, dk.cbavf.studio.nodes.model.NodeValue>?) = size > 32
            })
        }
        val nodesLive = remember {
            dk.cbavf.studio.nodes.NodesLive(
                nodesState, nodeBackend, nodeCache, sensors, loop, animateState,
                micAllowed = { context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED },
                canvasSize = { drawState.toDoc().let { it.widthPx to it.heightPx } },
                screenPx = { screenSize(context).second },
            )
        }
        // Live and the Patch bay both drive sensors: one at a time. Live runs only in its room.
        LaunchedEffect(patch.live) { if (patch.live) nodesLive.stop() }
        LaunchedEffect(room) { if (room != Room.Nodes) nodesLive.stop() }
        var nodePreview by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
        var nodeStatus by remember { mutableStateOf("") }
        var nodeRunning by remember { mutableStateOf(false) }
        LaunchedEffect(store) {
            withContext(Dispatchers.IO) { store.loadNodes() }?.let(nodesState.edit::load)
            nodesState.presets = withContext(Dispatchers.IO) { store.loadNodePresets() }
            snapshotFlow { nodesState.top }
                .debounce(AUTOSAVE_DELAY_MS)
                .collect { withContext(Dispatchers.IO) { store.saveNodes(it) } }
        }
        // The backdrop: the previewed node, evaluated at preview size whenever the graph changes.
        LaunchedEffect(nodesState.top, nodesState.previewTarget, room == Room.Nodes, nodesLive.live) {
            if (room != Room.Nodes || nodesLive.live) return@LaunchedEffect
            val target = nodesState.previewTarget ?: run { nodePreview = null; nodeStatus = ""; return@LaunchedEffect }
            kotlinx.coroutines.delay(60)
            nodeStatus = "evaluating…"
            val img = dk.cbavf.studio.ui.renderSafely({ nodeStatus = it }) {
                val ev = dk.cbavf.studio.nodes.model.Evaluator(nodesState.top, nodeBackend, 1024, nodeCache, checkCancel = { ensureActive() })
                nodeBackend.previewOf(ev.value(target), 1024)?.asImageBitmap()
            }
            nodePreview = img
            if (img != null) nodeStatus = "preview"
            else if (nodeStatus == "evaluating…") nodeStatus = "nothing to show (unconnected?)"
        }

        /** Runs a Save / Send to Compose node at full size and files the result with the graph as recipe. */
        fun runNode(id: Int) {
            val graph = nodesState.top
            val type = graph.node(id)?.type
            // Projector: full screen (live if Live runs, otherwise the graph as it is).
            if (type == "projector") {
                nodesLive.projecting = id
                return
            }
            if (nodeRunning) return
            nodeRunning = true
            // Send to Other Phone, once, at the node's Size.
            if (type == "tophone") {
                scope.launch {
                    val message = runCatching {
                        if (!loop.connected) error("Link the phones in Loop first")
                        val n = graph.node(id)!!
                        val size = ((n.props["Size"] as? dk.cbavf.studio.nodes.model.Prop.Num)?.v ?: 1080f).toInt().coerceIn(128, 4096)
                        val bmp = withContext(Dispatchers.Default) {
                            nodeBackend.previewOf(dk.cbavf.studio.nodes.model.Evaluator(graph, nodeBackend, size, nodeCache).value(id), size)
                        } ?: error("Nothing connected to this node")
                        if (!loop.sendFrame(bmp, (n.props["Format"] as? dk.cbavf.studio.nodes.model.Prop.Choice)?.v == "PNG")) error("the link dropped")
                        "Sent ${bmp.width}×${bmp.height} to ${loop.peerDevice ?: "the other phone"}"
                    }.getOrElse { "Not sent: ${it.message}" }
                    nodeRunning = false
                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                }
                return
            }
            scope.launch {
                val message = runCatching {
                    val record = withContext(Dispatchers.Default) {
                        val v = dk.cbavf.studio.nodes.model.Evaluator(graph, nodeBackend, 4096).value(id) as? dk.cbavf.studio.nodes.model.NodeValue.Img
                            ?: error("Nothing connected to this node")
                        val parents = graph.nodes.mapNotNull { n -> (n.props["asset"] as? dk.cbavf.studio.nodes.model.Prop.Text)?.v?.takeIf { it.isNotBlank() } }.distinct()
                        saveBitmapAsset(context, store, v.handle as android.graphics.Bitmap, parents, "nodes.render", Recipe(graph = graph), png = true)
                    }
                    lineage = withContext(Dispatchers.IO) { store.lineage() }
                    if (type == "tocompose") {
                        composeState.addImages(listOf(record))
                        room = Room.Compose
                    }
                    "Saved ${record.widthPx}×${record.heightPx}" + if (type == "tocompose") " · added to Compose" else ""
                }.getOrElse { "Run failed: ${it.message}" }
                nodeRunning = false
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
        }

        DisposableEffect(patch) { onDispose { patch.stop() } }
        LaunchedEffect(store) {
            withContext(Dispatchers.IO) { store.loadPatch() }?.let(patch::load)
            snapshotFlow { patch.doc }
                .debounce(AUTOSAVE_DELAY_MS)
                .collect { withContext(Dispatchers.IO) { store.savePatch(it) } }
        }

        fun capture() {
            val spec = cameraState.spec ?: return
            if (cameraState.busy) return
            cameraState.busy = true
            cameraState.message = null
            val info = cameraState.info()
            val half = cameraState.half
            scope.launch {
                val message = runCatching {
                    val shots = camera.capture(cameraState.burst, cameraState.raw)
                    val record = withContext(Dispatchers.Default) { saveCapture(context, store, spec, shots, info, half) }
                    lineage = withContext(Dispatchers.IO) { store.lineage() }
                    "Saved ${record.widthPx}×${record.heightPx}" + if (shots.any { it.dng != null }) " + DNG" else ""
                }.getOrElse { "Capture failed: ${it.message}" }
                Log.i(TAG, message)
                cameraState.message = message
                cameraState.busy = false
            }
        }

        // Restore the last canvas, then autosave every change. Autosave must not start before the
        // restore, or it would overwrite the saved canvas with the default one.
        LaunchedEffect(store) {
            val saved = withContext(Dispatchers.IO) { store.loadCanvas() }
            lineage = withContext(Dispatchers.IO) { store.lineage() }
            if (saved != null) drawState.load(saved.doc, saved.sourceAssetId)
            snapshotFlow { Autosave(drawState.toDoc(), drawState.sourceAssetId) }
                .debounce(AUTOSAVE_DELAY_MS)
                .collect { withContext(Dispatchers.IO) { store.saveCanvas(it) } }
        }
        LaunchedEffect(store) {
            withContext(Dispatchers.IO) { store.loadAnimation() }?.let(animateState::load)
            snapshotFlow { animateState.toDoc() }
                .debounce(AUTOSAVE_DELAY_MS)
                .collect { withContext(Dispatchers.IO) { store.saveAnimation(it) } }
        }
        LaunchedEffect(store) {
            withContext(Dispatchers.IO) { store.loadSequence() }?.let(sequenceState::load)
            snapshotFlow { sequenceState.toDoc() }
                .debounce(AUTOSAVE_DELAY_MS)
                .collect { withContext(Dispatchers.IO) { store.saveSequence(it) } }
        }

        fun save() {
            val doc = drawState.toDoc()
            if (doc.widthPx <= 0 || doc.heightPx <= 0) return
            // What it branches from, plus every Library image placed on it.
            val parents = (listOfNotNull(drawState.sourceAssetId) + drawState.imageAssetIds()).distinct()
            scope.launch {
                val record = withContext(Dispatchers.Default) {
                    val png = renderPng(doc)
                    val r = store.addAsset(png, parents, "draw.render", Recipe(canvas = doc), Build.MODEL, doc.widthPx, doc.heightPx)
                    val uri = exportToPictures(context, "studio-${r.id}", png)
                    if (uri == null) r else r.copy(exportedUri = uri.toString()).also(store::update)
                }
                // Further edits now branch from what was just saved.
                drawState.sourceAssetId = record.id
                lineage = withContext(Dispatchers.IO) { store.lineage() }
                Toast.makeText(context, "Saved · generation ${lineage.generation(record.id)}", Toast.LENGTH_SHORT).show()
            }
        }

        /** Saves the interleaved image and its barrier overlay; the barrier is the image's child. */
        fun saveBarrier() {
            val anim = animateState.toDoc()
            if (anim.frames.size < 2) return
            scope.launch {
                val saved = withContext(Dispatchers.Default) {
                    val image = renderInterleaved(anim) ?: return@withContext null
                    val w = image.width
                    val h = image.height
                    val barrier = renderBarrier(w, h, anim.stripPx, anim.frames.size)
                    val imagePng = encodePng(image)
                    val barrierPng = encodePng(barrier)
                    val i = store.addAsset(imagePng, anim.frames.flatMap { it.doc.imageAssetIds() }.distinct(), "animate.interleave", Recipe(animation = anim), Build.MODEL, w, h)
                    val b = store.addAsset(barrierPng, listOf(i.id), "animate.barrier", null, Build.MODEL, w, h, recipeOf = i)
                    exportToPictures(context, "studio-${i.id}", imagePng)?.let { store.update(i.copy(exportedUri = it.toString())) }
                    exportToPictures(context, "studio-${b.id}-barrier", barrierPng)?.let { store.update(b.copy(exportedUri = it.toString())) }
                    i
                } ?: return@launch
                lineage = withContext(Dispatchers.IO) { store.lineage() }
                Toast.makeText(context, "Saved image + barrier · ${anim.frames.size} frames", Toast.LENGTH_SHORT).show()
            }
        }

        /**
         * Encodes the sequence in the background, then files it in the Library (parents = the
         * Library images it uses) and in the shared media store.
         */
        fun exportSequence(kind: String) {
            val doc = sequenceState.toDoc()
            if (doc.items.isEmpty() || exportStatus.progress != null) return
            val decoder = FrameDecoder(store, lineage, screenSize(context))
            exportStatus.label = "Exporting ${kind.uppercase()}…"
            exportStatus.progress = 0f
            scope.launch {
                val message = withContext(Dispatchers.Default) {
                    val tmp = File(store.tempDir, "export.$kind")
                    runCatching {
                        val report: ExportReport = when (kind) {
                            "gif" -> exportGif(doc, decoder, tmp) { exportStatus.progress = it }
                            else -> exportMp4(doc, decoder, tmp, mp4Fps(doc.rateHz)) { exportStatus.progress = it }
                        }
                        val record = store.addAssetFile(
                            tmp, kind, decoder.parentIds(doc), "sequence.$kind", Build.MODEL,
                            report.widthPx, report.heightPx, Recipe(sequence = doc),
                        )
                        val mime = if (kind == "gif") "image/gif" else "video/mp4"
                        exportToMedia(context, "studio-${record.id}.$kind", mime) { out ->
                            store.fileOf(record).inputStream().use { it.copyTo(out) }
                        }?.let { store.update(record.copy(exportedUri = it.toString())) }
                        (listOf("Saved ${kind.uppercase()} · ${report.frames} frames · ${report.widthPx}×${report.heightPx}") + report.notes)
                            .joinToString("\n")
                    }.getOrElse { "Export failed: ${it.message}" }.also { tmp.delete() }
                }
                exportStatus.progress = null
                lineage = withContext(Dispatchers.IO) { store.lineage() }
                Log.i(TAG, message)
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            }
        }

        /**
         * Library → a room. Every arrival is an ordinary, undoable edit in that room, and the
         * room opens so you see where the images went.
         */
        fun useIn(assets: List<dk.cbavf.studio.library.AssetRecord>, target: Room) {
            val first = assets.firstOrNull() ?: return
            fun placeInDraw() = drawState.addImages(assets.map {
                dk.cbavf.studio.draw.model.ImageLayer.fitted(it.id, it.widthPx, it.heightPx, drawState.canvasWidth, drawState.canvasHeight)
            })
            when (target) {
                Room.Nodes -> assets.forEachIndexed { i, a ->
                    nodesState.add("image", 20f, 20f + i * 140f, mapOf("asset" to dk.cbavf.studio.nodes.model.Prop.Text(a.id)))
                }
                Room.Compose -> composeState.addImages(assets)
                Room.Draw, Room.Patch -> placeInDraw()
                Room.Animate -> {
                    val size = drawState.toDoc().takeIf { it.widthPx > 0 }?.let { it.widthPx to it.heightPx }
                    if (!animateState.addImageFrames(assets, size)) {
                        Toast.makeText(context, "Open the Draw room once first, so frames have a size.", Toast.LENGTH_LONG).show()
                        return
                    }
                }
                Room.Sequence -> sequenceState.addAssets(assets.map { it.id })
                Room.Develop -> developState.open(first)
                Room.Fft -> fftState.edit.open(dk.cbavf.studio.fft.model.FftDoc(sourceAssetId = first.id, size = fftState.edit.doc.size))
                Room.Glitch -> glitchState.edit.open(glitchState.edit.doc.copy(sourceAssetId = first.id))
                Room.Camera -> {
                    cameraState.overlayAssetId = first.id
                    cameraState.overlay = dk.cbavf.studio.camera.Overlay.Image
                }
                Room.Loop -> loop.startAssetId = first.id
                else -> return
            }
            room = target
            if (assets.size > 1 && target !in setOf(Room.Nodes, Room.Compose, Room.Draw, Room.Patch, Room.Animate, Room.Sequence)) {
                Toast.makeText(context, "${target.title} works on one image: took the first.", Toast.LENGTH_SHORT).show()
            }
        }

        // routing-nodes Projector node: its input on the whole screen.
        nodesLive.projecting?.let { pid ->
            val screen = remember { screenSize(context).second }
            val still by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, nodesState.top, pid, nodesLive.live) {
                if (nodesLive.live) return@produceState
                value = dk.cbavf.studio.ui.renderSafely({ msg -> Toast.makeText(context, msg, Toast.LENGTH_SHORT).show() }) {
                    val ev = dk.cbavf.studio.nodes.model.Evaluator(nodesState.top, nodeBackend, screen, nodeCache, checkCancel = { ensureActive() })
                    nodeBackend.previewOf(ev.value(pid), screen)?.asImageBitmap()
                }
            }
            dk.cbavf.studio.nodes.NodeProjector(if (nodesLive.live) nodesLive.projection ?: still else still) { nodesLive.projecting = null }
            return@MaterialTheme
        }

        if (projecting) {
            Projector(sequenceState.toDoc(), remember(lineage) { FrameDecoder(store, lineage, screenSize(context)) }) { result ->
                projecting = false
                if (result != null) {
                    val message = "Display ${result.displayHz.toInt()} Hz · ${result.vsyncs} vsyncs · ${result.dropped} dropped"
                    Log.i(TAG, "Projector: $message")
                    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                }
            }
            return@MaterialTheme
        }

        Scaffold(
            bottomBar = {
                LazyRow(
                    modifier = Modifier.navigationBarsPadding().padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(Room.entries) { r ->
                        // What is running in the background shows on its room's chip.
                        val badge = when (r) {
                            Room.Bridge -> bridge.state.inbox.size.takeIf { it > 0 }?.let { " ($it)" }
                                ?: (ScreenRecordService.recording.collectAsState().value as? RecordingStatus.Recording)?.let { " ● rec" }
                            Room.Patch -> if (patch.live) " ●" else null
                            Room.Loop -> if (loop.running) " ●" else if (loop.connected) " ⇄" else null
                            Room.Sequence -> exportStatus.progress?.let { " ${(it * 100).toInt()}%" }
                            else -> null
                        }
                        FilterChip(
                            selected = r == room,
                            onClick = { room = r },
                            label = { Text(r.title + (badge ?: "")) },
                        )
                    }
                }
            }
        ) { padding ->
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                when (room) {
                    Room.Draw -> DrawRoom(drawState, store, lineage, onSave = ::save)
                    Room.Animate -> AnimateRoom(
                        animateState,
                        store,
                        lineage,
                        grabFromDraw = {
                            drawState.toDoc().takeIf { it.widthPx > 0 && it.heightPx > 0 }
                                ?: null.also { Toast.makeText(context, "Open the Draw room once first, so the canvas has a size.", Toast.LENGTH_LONG).show() }
                        },
                        onOpenInDraw = { doc ->
                            animateState.playing = false
                            drawState.open(doc, null)
                            room = Room.Draw
                        },
                        onSaveBarrier = ::saveBarrier,
                    )
                    Room.Sequence -> SequenceRoom(
                        sequenceState,
                        store,
                        lineage,
                        exportStatus,
                        animateFrameCount = animateState.frames.size,
                        onAddAnimateFrames = { sequenceState.addFrames(animateState.frames.toList(), animateState.fps) },
                        onProject = {
                            sequenceState.previewing = false
                            projecting = true
                        },
                        onExportGif = { exportSequence("gif") },
                        onExportMp4 = { exportSequence("mp4") },
                    )
                    Room.Camera -> CameraRoom(
                        cameraState,
                        camera,
                        OverlaySources(
                            drawLayers = { drawState.layers.toList() },
                            barrierStripPx = { animateState.stripPx },
                            barrierFrames = { animateState.frames.size },
                        ),
                        onCapture = ::capture,
                        onOpenFv5 = { bridge.launch(ExternalApp.Fv5) },
                    )
                    Room.Loop -> LoopRoom(loop, cameraState, camera)
                    Room.Compose -> dk.cbavf.studio.compose.ComposeRoom(
                        composeState,
                        store,
                        lineage,
                        layerPixels,
                        busy = composeBusy,
                        drawCanvas = { drawState.toDoc().takeIf { it.widthPx > 0 } },
                        screen = screenSize(context),
                        onSave = ::saveComposite,
                    )
                    Room.Nodes -> dk.cbavf.studio.nodes.NodesRoom(
                        nodesState,
                        store,
                        lineage,
                        preview = if (nodesLive.live) nodesLive.preview else nodePreview,
                        previewStatus = when {
                            nodesLive.live -> "live %.1f s · %.0f fps".format(nodesLive.seconds, nodesLive.fps) +
                                (if (nodesLive.armed.isNotEmpty()) " · rec ${nodesLive.recorded}" else "") +
                                (if (nodesLive.sent > 0) " · sent ${nodesLive.sent}" else "") +
                                (if (loop.remoteFrames > 0) " · received ${loop.remoteFrames}" else "")
                            nodesLive.error != null -> nodesLive.error!!
                            else -> nodeStatus
                        },
                        runner = nodesLive,
                        onLive = {
                            if (nodesLive.live) nodesLive.stop()
                            else {
                                patch.stop()
                                nodesLive.start(scope)
                            }
                        },
                        onArm = { id ->
                            if (!nodesLive.live) patch.stop()
                            nodesLive.toggleArm(id, scope)
                        },
                        running = nodeRunning,
                        onRun = ::runNode,
                        onSavePreset = { name ->
                            nodesState.presets = nodesState.presets.filterNot { it.name == name } + dk.cbavf.studio.nodes.model.GraphPreset(name, nodesState.top)
                            scope.launch(Dispatchers.IO) { store.saveNodePresets(nodesState.presets) }
                        },
                        onDeletePreset = { p ->
                            nodesState.presets = nodesState.presets - p
                            scope.launch(Dispatchers.IO) { store.saveNodePresets(nodesState.presets) }
                        },
                    )
                    Room.Develop -> DevelopRoom(
                        developState,
                        store,
                        lineage,
                        busy = developBusy,
                        onSave = ::saveDeveloped,
                        onSendToGallery = { id -> lineage[id]?.let { bridge.send(it, ExternalApp.Gallery) } },
                        onMessage = { Toast.makeText(context, it, Toast.LENGTH_LONG).show() },
                    )
                    Room.Fft -> FftRoom(fftState, store, lineage, busy = labBusy) { bitmap ->
                        val d = fftState.edit.doc
                        saveLab(bitmap, listOfNotNull(d.sourceAssetId, d.phaseFromAssetId), "fft.inverse", Recipe(fft = d), png = true)
                    }
                    Room.Glitch -> GlitchRoom(glitchState, store, lineage, busy = labBusy) { bitmap ->
                        val d = glitchState.edit.doc
                        // JPEG generations are the look: keep them as JPEG; byte misreads stay lossless.
                        saveLab(bitmap, listOfNotNull(d.sourceAssetId), "glitch.render", Recipe(glitch = d), png = d.generations == 0)
                    }
                    Room.Patch -> PatchRoom(patch, drawState, sensors, linked = loop.connected, onOpenAsGraph = {
                        patch.stop()
                        val doc = drawState.toDoc()
                        nodesState.replace(dk.cbavf.studio.nodes.model.patchToGraph(patch.doc, drawState.layers.map { it.id }, doc.widthPx, doc.heightPx))
                        room = Room.Nodes
                    })
                    Room.Bridge -> BridgeRoom(
                        bridge.state,
                        lineage,
                        onPermissionResult = {
                            bridge.refreshAccess()
                            bridge.scan()
                        },
                        onLaunch = bridge::launch,
                        onImport = bridge::import,
                    )
                    Room.Library -> LibraryRoom(
                        libraryNav,
                        store,
                        lineage,
                        onChanged = { lineage = withContext(Dispatchers.IO) { store.lineage() } },
                        onOpen = { asset ->
                            // Recipes can be large: read off the main thread, then switch rooms.
                            scope.launch {
                                val recipe = withContext(Dispatchers.IO) { store.recipe(asset) } ?: return@launch
                                recipe.canvas?.let {
                                    drawState.open(it, asset.id)
                                    room = Room.Draw
                                }
                                recipe.animation?.let {
                                    animateState.open(it)
                                    room = Room.Animate
                                }
                                recipe.sequence?.let {
                                    sequenceState.open(it)
                                    room = Room.Sequence
                                }
                                recipe.graph?.let {
                                    nodesState.load(it)
                                    room = Room.Nodes
                                }
                                recipe.compose?.let {
                                    composeState.edit.open(it)
                                    room = Room.Compose
                                }
                                recipe.develop?.let {
                                    developState.open(it)
                                    room = Room.Develop
                                }
                                recipe.fft?.let {
                                    fftState.edit.open(it)
                                    room = Room.Fft
                                }
                                recipe.glitch?.let {
                                    glitchState.edit.open(it)
                                    room = Room.Glitch
                                }
                                recipe.capture?.let {
                                    cameraState.restore(it)
                                    room = Room.Camera
                                }
                            }
                        },
                        onSend = bridge::send,
                        onUseIn = ::useIn,
                    )
                }
            }
        }
    }
}
