package dk.cbavf.studio.ui

import androidx.compose.ui.zIndex
import dk.cbavf.studio.debug.Debug
import dk.cbavf.studio.foreground.Foreground
import dk.cbavf.studio.ui.gestures.Gesture
import dk.cbavf.studio.ui.gestures.studioGestures
import dk.cbavf.studio.feedback.tapProbe
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import kotlinx.coroutines.flow.drop
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asAndroidBitmap
import kotlinx.coroutines.flow.collectLatest
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
import dk.cbavf.studio.draw.groups.flattened
import dk.cbavf.studio.camera.overlaySnapshot
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
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val AUTOSAVE_DELAY_MS = 800L
private const val TAG = "Studio"

/** The rooms from docs/ARCHITECTURE.md, in build order. */
enum class Room(val title: String, val purpose: String) {
    Studio("Studio", "File and projects, preferences, theme, profile"),
    Library("Library", "Lineage tree of every asset and how it was made"),
    Compose("Compose", "The main layered editor: composite and control it all"),
    Nodes("Node Lab", "Node-based image pipelines: graphs, groups, live time nodes and presets"),
    Draw("Draw", "Stroke objects, stroke stacks, live moirÃ© while dragging"),
    Animate("Animate", "MoirÃ©-shift stop motion and barrier-grid animation"),
    Sequence("Sequence", "Timeline and projector mode"),
    Bridge("Bridge", "Photo editor, share and camera-app round-trips, and watch folders"),
    Camera("Camera", "Manual capture and software optical stack"),
    Loop("Loop", "Two-phone projector / camera loop"),
    Patch("Patch", "Sensors and LFOs routed to any parameter"),
    Develop("Develop", "Per-hue mixer, curves, LUTs, style"),
    Fft("FFT", "Spectrum view, spectrum painting, magnitude/phase swap"),
    Glitch("Glitch", "Byte-level reinterpretation and re-encode generations"),
    SlitScan("Slit-scan", "Every slit of a picture taken from a different frame, with a time offset per slit"),
    Calc("Calculator", "Sums with history, variables, units and hex, bin and oct; a result to a control or a jack"),
    Math("Math", "The studio's curves plotted with their parameters, and used as a tone curve on a picture"),
    Vector3d("3D", "Low-poly shapes with lines on their faces, an orbit view and flat shading; saved as a picture or SVG"),
    Physics("Physics", "Shapes from Draw fall, bounce and collide; tilt the phone to tip them; hits as triggers, frames to Animate"),
    Forge("Module Forge", "Build a module's face, wrap it as a plugin, switch it on (data only, no code)"),
    Plugins("Plugins", "The switched-on plugins: their controls as real controls the UI Editor's cables can read and drive"),
    UiEditor("UI Editor", "The whole UI on one canvas: lay it out on the front, patch jacks between controls on the back"),
}

@OptIn(FlowPreview::class)
@Composable
fun StudioApp(incoming: SnapshotStateList<Incoming>) {
    val outerContext = LocalContext.current
    val studioPrefs = remember { dk.cbavf.studio.studio.StudioPrefs(outerContext) }
    val baseDensity = androidx.compose.ui.platform.LocalDensity.current
    val baseHaptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    MaterialTheme(colorScheme = studioPrefs.theme.scheme()) {
      androidx.compose.runtime.CompositionLocalProvider(
        // Interface size scales everything; haptics can be switched off in Studio → Preferences.
        androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(baseDensity.density * studioPrefs.uiScale, baseDensity.fontScale),
        androidx.compose.ui.platform.LocalHapticFeedback provides (if (studioPrefs.haptics) baseHaptics else NoHaptics),
        // The hand-drawn areas (Node Lab canvas, rack panels) follow the theme too.
        LocalCanvasColors provides CanvasTheme.of(studioPrefs.theme),
        dk.cbavf.studio.modulation.LocalModulation provides remember(studioPrefs) {
            dk.cbavf.studio.modulation.ModulationHub(studioPrefs.modulations) { studioPrefs.saveModulations(it) }
        },
        dk.cbavf.studio.ui.layout.LocalPanelSizes provides remember(studioPrefs) {
            dk.cbavf.studio.ui.layout.PanelSizes({ studioPrefs.panelWidthDp }, { v -> studioPrefs.setPanelWidthDp(v) })
        },
      ) {
        val context = LocalContext.current
        // The last room survives restarts, not just rotation (unless Preferences name a start room).
        var room by rememberSaveable {
            val start = studioPrefs.startRoom.takeIf { it != "last" } ?: studioPrefs.config.workspace.lastRoom
            mutableStateOf(runCatching { Room.valueOf(start) }.getOrDefault(Room.Draw))
        }
        // Keep the screen on while the studio is open, if asked to.
        val activity = context as? android.app.Activity
        DisposableEffect(studioPrefs.keepScreenOn) {
            if (studioPrefs.keepScreenOn) activity?.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else activity?.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            onDispose { }
        }
        LaunchedEffect(room) { studioPrefs.config.updateWorkspace { it.copy(lastRoom = room.name) } }
        LaunchedEffect(room) { Debug.i("studio", "room: ${room.title}") }
        // Feedback (off unless the person switched it on in Studio → Config): attach once, follow rooms, report when the studio is left.
        LaunchedEffect(Unit) { dk.cbavf.studio.feedback.Feedback.attach(context, studioPrefs.config) }
        LaunchedEffect(room, studioPrefs.config.profile.feedbackLevel) { dk.cbavf.studio.feedback.Feedback.enterRoom(room.title) }
        DisposableEffect(Unit) {
            val remove = Foreground.gate.add(object : dk.cbavf.studio.foreground.RunGate.Hook {
                override fun onStop() = dk.cbavf.studio.feedback.Feedback.onStop()
                override fun onStart() = dk.cbavf.studio.feedback.Feedback.onStart()
            })
            onDispose { remove() }
        }
        // A checkpoint every ten minutes on screen; leaving the screen makes one, and the clock starts again on return.
        LaunchedEffect(studioPrefs.config.profile.feedbackLevel) {
            while (studioPrefs.config.profile.feedbackLevel != "off") {
                Foreground.gate.sleep(10 * 60_000L)
                dk.cbavf.studio.feedback.Feedback.checkpoint()
            }
        }
        dk.cbavf.studio.feedback.FeedbackAskDialog()
        // Full feedback needs the last debug lines, so the log keeps at least the info level while it is on.
        LaunchedEffect(studioPrefs.config.profile.debugLevel, studioPrefs.config.profile.feedbackLevel) {
            val p = studioPrefs.config.profile
            dk.cbavf.studio.debug.Debug.level = maxOf(p.debugLevel, if (p.feedbackLevel == "full" && dk.cbavf.studio.feedback.Feedback.availableInBuild) 2 else 0)
        }
        val drawState = remember { DrawState() }
        // The studio's live patch bay (Rack room): cables between module outputs and inputs.
        val router = remember { dk.cbavf.studio.routing.Router() }
        val gateBank = remember { dk.cbavf.studio.routing.GateBank() }
        val oscBus = remember { dk.cbavf.studio.osc.OscBus() }
        LaunchedEffect(oscBus, router) { oscBus.run(router) }
        val oscOut = remember { dk.cbavf.studio.osc.OscOutBus() }
        LaunchedEffect(oscOut, router) { oscOut.run(router, { studioPrefs.config.profile.oscOutHost }, { studioPrefs.config.profile.oscOutPort }) }
        val midiBus = remember { dk.cbavf.studio.midi.MidiBus(context.applicationContext) }
        LaunchedEffect(midiBus, router) { midiBus.runIn(router) }
        LaunchedEffect(midiBus, router) { midiBus.runOut(router) }
        var channelSpace by remember { mutableStateOf(dk.cbavf.studio.channels.ColorSpace.RGBA) }
        // The channel splitter: while a picture is patched in, publish its planes on the four channel jacks.
        LaunchedEffect(router.inputVersion("channels.in"), channelSpace) {
            val src = router.inputBitmap("channels.in", 1024) ?: return@LaunchedEffect
            val planes = withContext(Dispatchers.Default) {
                val w = src.width
                val h = src.height
                val px = IntArray(w * h).also { src.getPixels(it, 0, w, 0, 0, w, h) }
                (0 until 4).map { c ->
                    android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888).also {
                        it.setPixels(dk.cbavf.studio.channels.ChannelPlanes.split(channelSpace, c, px), 0, w, 0, 0, w, h)
                    }
                }
            }
            planes.forEachIndexed { i, b -> router.publish("channels.c${i + 1}", dk.cbavf.studio.routing.RouteValue.Image(b)) }
        }
        LaunchedEffect(gateBank, router) { gateBank.run(router) }
        // The studio's back side: shown, and which module's back is looked at (null: all cables).
        var studioBack by remember { mutableStateOf(false) }
        var backRoom by remember { mutableStateOf<String?>(null) }
        var modulePresets by remember { mutableStateOf<Map<String, List<dk.cbavf.studio.routing.ModulePreset>>>(emptyMap()) }
        var rackPresets by remember { mutableStateOf<List<dk.cbavf.studio.routing.model.RackPreset>>(emptyList()) }

        val animateState = remember { AnimateState() }
        val uiEditor = remember { dk.cbavf.studio.uieditor.UiEditorState() }
        val pluginState = remember { dk.cbavf.studio.plugins.PluginState() }
        val slitScanState = remember { dk.cbavf.studio.slitscan.SlitScanState() }
        val calcState = remember { dk.cbavf.studio.calc.CalcState() }
        val mathState = remember { dk.cbavf.studio.math.MathState() }
        val vector3dState = remember { dk.cbavf.studio.vector3d.Vector3dState() }
        var pluginRevision by remember { mutableStateOf(0) }
        // The switched-on plugins' behaviours run every frame while the studio is in front (their outputs feed the cables).
        var runningPlugins by remember { mutableStateOf<List<dk.cbavf.studio.plugins.PluginManifest>>(emptyList()) }
        val enabledPluginIds = studioPrefs.config.profile.enabledPlugins.toSet()
        LaunchedEffect(pluginRevision, enabledPluginIds) {
            runningPlugins = withContext(Dispatchers.IO) {
                (dk.cbavf.studio.plugins.PluginStore.BUILT_IN + dk.cbavf.studio.plugins.PluginStore(context).installed())
                    .filter { it.id in enabledPluginIds && it.behaviour != null }
            }
        }
        LaunchedEffect(pluginState) { pluginState.runBehaviours({ runningPlugins }) }
        val sequenceState = remember { SequenceState() }
        val exportStatus = remember { ExportStatus() }
        var projecting by remember { mutableStateOf(false) }
        val store = remember { ProjectStore.open(File(context.filesDir, "projects/${studioPrefs.project}")).also { dk.cbavf.studio.draw.LayerImages.store = it } }

        /** A rack input's current picture filed in the Library, so a result made from it has a parent. */
        suspend fun snapshotInput(input: String): String? = withContext(Dispatchers.IO) {
            val bmp = router.inputBitmap(input, 4096) ?: return@withContext null
            saveBitmapAsset(context, store, bmp, emptyList(), "route.snapshot", Recipe(), png = true).id
        }
        var lineage by remember { mutableStateOf(Lineage(emptyList())) }
        val routeSnaps = remember { HashMap<String, Pair<Int, String>>() }

        /** [snapshotInput], once per picture that arrives: while the input does not change it keeps its Library file. */
        suspend fun snapshotInputOnce(input: String): String? {
            val version = router.inputVersion(input).takeIf { it >= 0 } ?: return null
            routeSnaps[input]?.takeIf { it.first == version }?.let { return it.second }
            val id = runCatching { snapshotInput(input) }.getOrNull() ?: return null
            routeSnaps[input] = version to id
            lineage = withContext(Dispatchers.IO) { store.lineage() }
            return id
        }
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
                initialRole = studioPrefs.config.profile.loopRole,
                initialHost = studioPrefs.config.profile.loopHost,
                saveSettings = { r, h -> studioPrefs.config.updateProfile { it.copy(loopRole = r, loopHost = h) } },
                // The Loop unit's Projector input, filed in the Library so each capture has a parent.
                routedFrame = { snapshotInputOnce(dk.cbavf.studio.routing.model.LOOP_IN) },
            )
        }
        DisposableEffect(loop) {
            // Off screen a send stops (sending again goes on), and the link closes; it is made again on return.
            val remove = Foreground.gate.add(loop)
            onDispose { remove(); loop.disconnect() }
        }
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
            val doc0 = developState.doc
            scope.launch {
                val message = runCatching {
                    // A live input is filed in the Library first: it becomes the result's parent.
                    val doc = if (router.doc.sourceOf("develop.in") != null) {
                        val snap = snapshotInput("develop.in") ?: error("Nothing is coming into Develop's input")
                        lineage = withContext(Dispatchers.IO) { store.lineage() }
                        doc0.copy(sourceAssetId = snap)
                    } else doc0
                    val r = withContext(Dispatchers.Default) { saveDevelop(context, store, lineage, doc) }
                    lineage = withContext(Dispatchers.IO) { store.lineage() }
                    "Saved ${r.widthPx}Ã—${r.heightPx} Â· generation ${lineage.generation(r.id)}"
                }.getOrElse { "Save failed: ${it.message}" }
                developBusy = false
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
        }

        val fftState = remember { FftState() }
        val glitchState = remember { GlitchState() }
        var labBusy by remember { mutableStateOf(false) }

        /** Files a lab result (FFT, Glitch) as a child of the images it was made from. */
        fun saveLab(bitmap: android.graphics.Bitmap, parents: List<String>, operation: String, recipe: Recipe, png: Boolean, routedInput: String? = null) {
            if (labBusy) return
            labBusy = true
            scope.launch {
                val message = runCatching {
                    val live = routedInput?.takeIf { router.doc.sourceOf(it) != null }?.let { snapshotInput(it) }
                    // A live cable's picture is filed as a parent; the recipe must name it too, or reopening would not find its source.
                    val kept = if (live == null) recipe else recipe.copy(
                        fft = recipe.fft?.copy(sourceAssetId = live),
                        glitch = recipe.glitch?.copy(sourceAssetId = live),
                    )
                    val r = withContext(Dispatchers.IO) { saveBitmapAsset(context, store, bitmap, listOfNotNull(live) + parents, operation, kept, png) }
                    lineage = withContext(Dispatchers.IO) { store.lineage() }
                    "Saved ${r.widthPx}Ã—${r.heightPx} Â· generation ${lineage.generation(r.id)}"
                }.getOrElse { "Save failed: ${it.message}" }
                labBusy = false
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
        }

        val composeState = remember { dk.cbavf.studio.compose.ComposeState() }
        val layerPixels = remember {
            dk.cbavf.studio.compose.LayerPixels(
                store, { lineage },
                routed = { input, maxPx -> router.inputBitmap(input, maxPx) },
                routeVersion = { input -> router.inputVersion(input) },
            )
        }
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
                    "Saved ${r.widthPx}Ã—${r.heightPx} Â· generation ${lineage.generation(r.id)}"
                }.getOrElse { "Save failed: ${it.message}" }
                composeBusy = false
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
        }

        var rerunning by remember { mutableStateOf(false) }

        /** The lab notebook's Rerun: the entry made again from its recipe, filed as a new entry beside the first run. */
        fun rerun(record: dk.cbavf.studio.library.AssetRecord) {
            if (rerunning) return
            rerunning = true
            scope.launch {
                val message = runCatching {
                    val r = withContext(Dispatchers.Default) { dk.cbavf.studio.studio.rerunExperiment(context, store, lineage, record, layerPixels) }
                    lineage = withContext(Dispatchers.IO) { store.lineage() }
                    "Ran again: ${r.widthPx}×${r.heightPx}, a new entry"
                }.getOrElse { "Rerun failed: ${it.message}" }
                rerunning = false
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
        }

        val sensors = remember { SensorHub(context) }
        DisposableEffect(sensors) {
            // Off screen every sensor and the microphone are let go, whoever asked for them.
            val remove = Foreground.gate.add(sensors)
            onDispose { remove(); sensors.release() }
        }
        val sensorBus = remember { dk.cbavf.studio.sensors.SensorBus(sensors, { context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED }, saveTake = { t -> store.saveSensorTake(t) }) }
        LaunchedEffect(sensorBus, router) { sensorBus.run(router) }
        // The Physics room's world steps while Run is on, in any room; off screen it waits and lets the accelerometer go.
        val physicsState = remember { dk.cbavf.studio.physics.PhysicsState() }
        val physicsView = androidx.compose.ui.platform.LocalView.current
        LaunchedEffect(physicsState, router) { physicsState.run(sensors, router) { physicsView.display?.rotation ?: 0 } }
        // Sensor-triggered envelopes read the bus's jacks; the jacks keep their last value when the bus is off, so off reads as none.
        val sensorLevels = remember(sensorBus, router) {
            dk.cbavf.studio.modulation.SensorLevels(
                read = { name ->
                    if (!sensorBus.on) null
                    else (router.value(dk.cbavf.studio.modulation.SensorTriggers.jack(name)) as? dk.cbavf.studio.routing.RouteValue.Level)?.v
                },
                switchOn = { sensorBus.on = true },
            )
        }
        LaunchedEffect(store) { withContext(Dispatchers.IO) { store.loadSensorTake() }?.let { sensorBus.take = it } }
        val patch = remember {
            PatchEngine(sensors, drawState, animateState, loop, scope,
                publish = { jack, v -> router.publishLevel(jack, v) },
                listened = { dk.cbavf.studio.patch.model.PatchSignals.listened(router.doc) })
        }
        // The Patch bay unit's LFO and touch jacks; they wait while the studio is off screen.
        val patchSignals = remember { dk.cbavf.studio.patch.PatchSignalBus(patch) }
        LaunchedEffect(patchSignals, router) { patchSignals.run(router) }

        val nodesState = remember { dk.cbavf.studio.nodes.NodesState() }
        val nodeBackend = remember {
            dk.cbavf.studio.nodes.AndroidImageBackend(
                store, { lineage }, { drawState.toDoc() },
                remote = { loop.remoteFrame to loop.remoteFrames },
                module = { jack, maxPx -> router.inputBitmap(jack, maxPx) },
                moduleVer = { jack -> router.inputVersion(jack) },
                moduleNumber = { jack -> router.level(jack) },
                compose = { composeState.doc },
                composeVer = { dk.cbavf.studio.routing.model.COMPOSE_INPUTS.sumOf { router.inputVersion(it) } },
                roomFrames = { source ->
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
        val nodesOutWanted = "nodes.out" in router.doc.patchedOutputs
        LaunchedEffect(nodesState.top, nodesState.previewTarget, room == Room.Nodes, nodesLive.live, nodesOutWanted, router.inputVersion("nodes.in1"), router.inputVersion("nodes.in2"), router.inputVersion("nodes.sig1"), router.inputVersion("nodes.sig2")) {
            if ((room != Room.Nodes && !nodesOutWanted) || nodesLive.live) return@LaunchedEffect
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

        // --- Rack: load and keep the patching; feed every patched output, live. ---------------
        LaunchedEffect(store) {
            withContext(Dispatchers.IO) { store.loadRouting() }?.let(router::load)
            snapshotFlow { router.doc }
                .debounce(AUTOSAVE_DELAY_MS)
                .collect { withContext(Dispatchers.IO) { store.saveRouting(it) } }
        }
        LaunchedEffect(store) {
            modulePresets = withContext(Dispatchers.IO) { store.loadModulePresets() }
            snapshotFlow { modulePresets }.drop(1).collect { withContext(Dispatchers.IO) { store.saveModulePresets(it) } }
        }
        LaunchedEffect(store) {
            rackPresets = withContext(Dispatchers.IO) { store.loadRackPresets() }
            snapshotFlow { rackPresets }.drop(1).collect { withContext(Dispatchers.IO) { store.saveRackPresets(it) } }
        }
        /** Is anything listening to [out] (a cable)? */
        fun wanted(out: String) = out in router.doc.patchedOutputs
        val routePx = 1440
        LaunchedEffect(wanted("draw.canvas")) {
            if (!wanted("draw.canvas")) return@LaunchedEffect
            // A new picture on the live image layer is a new canvas too.
            snapshotFlow { drawState.toDoc().flattened() to router.inputVersion(dk.cbavf.studio.routing.model.DRAW_IMAGE_IN) }
                .debounce(30)
                .collect { (doc, _) -> if (doc.widthPx > 0) router.publish("draw.canvas", dk.cbavf.studio.routing.RouteValue.Canvas(doc)) }
        }
        // Draw's image-layer input: its pixels for the live layer, and the layer itself once a cable is plugged in.
        LaunchedEffect(router.inputVersion(dk.cbavf.studio.routing.model.DRAW_IMAGE_IN)) {
            val image = withContext(Dispatchers.Default) {
                router.inputBitmap(dk.cbavf.studio.routing.model.DRAW_IMAGE_IN, 2048)?.asImageBitmap()?.also { it.prepareToDraw() }
            }
            dk.cbavf.studio.draw.LayerImages.setRouted(dk.cbavf.studio.routing.model.DRAW_IMAGE_IN, image)
        }
        LaunchedEffect(router.doc.sourceOf(dk.cbavf.studio.routing.model.DRAW_IMAGE_IN)) {
            val input = dk.cbavf.studio.routing.model.DRAW_IMAGE_IN
            if (router.doc.sourceOf(input) == null) return@LaunchedEffect
            // Wait for the first picture (its size) and for a canvas with a size.
            val (w, h) = snapshotFlow { router.inputValue(input)?.size?.takeIf { drawState.canvasWidth > 0 } }.filterNotNull().first()
            if (dk.cbavf.studio.routing.model.RouteLayers.needsLayer(drawState.toDoc().layers, input, router.doc.sourceOf(input) != null)) {
                drawState.addImages(listOf(dk.cbavf.studio.routing.model.RouteLayers.layer(input, w, h, drawState.canvasWidth, drawState.canvasHeight)))
            }
        }
        LaunchedEffect(wanted("animate.frame")) {
            if (!wanted("animate.frame")) return@LaunchedEffect
            snapshotFlow { animateState.currentFrame?.doc }.collect { d -> d?.let { router.publish("animate.frame", dk.cbavf.studio.routing.RouteValue.Canvas(it)) } }
        }
        LaunchedEffect(wanted("compose.image")) {
            if (!wanted("compose.image")) return@LaunchedEffect
            snapshotFlow { composeState.doc to dk.cbavf.studio.routing.model.COMPOSE_INPUTS.sumOf { router.inputVersion(it) } }
                .debounce(80)
                .collectLatest { (doc, _) ->
                    if (doc.layers.isEmpty()) return@collectLatest
                    val k = minOf(1f, routePx.toFloat() / maxOf(doc.widthPx, doc.heightPx))
                    dk.cbavf.studio.ui.renderSafely({}) { dk.cbavf.studio.compose.renderComposite(doc, k, layerPixels) { ensureActive() } }
                        ?.let { router.publish("compose.image", dk.cbavf.studio.routing.RouteValue.Image(it)) }
                }
        }
        // The labs: their result from their own source, or from what is patched into them.
        var developSrc by remember { mutableStateOf<Pair<String, android.graphics.Bitmap>?>(null) }
        fun labSource(input: String, assetId: String?, maxPx: Int): android.graphics.Bitmap? =
            router.inputBitmap(input, maxPx) ?: assetId?.let { id ->
                developSrc?.takeIf { it.first == "$id@$maxPx" }?.second
                    ?: lineage[id]?.let { dk.cbavf.studio.develop.decodeAsset(store, it, maxPx) }?.also { developSrc = "$id@$maxPx" to it }
            }
        LaunchedEffect(wanted("develop.image")) {
            if (!wanted("develop.image")) return@LaunchedEffect
            snapshotFlow { developState.doc to router.inputVersion("develop.in") }
                .debounce(100)
                .collectLatest { (doc, _) ->
                    dk.cbavf.studio.ui.renderSafely({}) {
                        val src = labSource("develop.in", doc.sourceAssetId, routePx) ?: return@renderSafely null
                        val style = doc.styleAssetId?.let { lineage[it] }?.let { dk.cbavf.studio.develop.decodeAsset(store, it, routePx) }
                        dk.cbavf.studio.develop.renderDevelop(src, doc, dk.cbavf.studio.develop.model.Lut.build(doc), dk.cbavf.studio.develop.loadCube(store, doc), style) { ensureActive() }
                    }?.let { router.publish("develop.image", dk.cbavf.studio.routing.RouteValue.Image(it)) }
                }
        }
        // The colour image is read once per pick, not on every render.
        val glitchPalette = remember { dk.cbavf.studio.glitch.model.LastLoaded<String, IntArray> { id -> dk.cbavf.studio.glitch.decodePalette(store, lineage, id) } }
        LaunchedEffect(wanted("glitch.image")) {
            if (!wanted("glitch.image")) return@LaunchedEffect
            snapshotFlow { glitchState.edit.doc to router.inputVersion("glitch.in") }
                .debounce(100)
                .collectLatest { (doc, _) ->
                    dk.cbavf.studio.ui.renderSafely({}) {
                        val src = labSource("glitch.in", doc.sourceAssetId, 1024) ?: return@renderSafely null
                        val palette = glitchPalette[doc.colorFromAssetId]
                        dk.cbavf.studio.glitch.renderGlitch(src, doc, palette) { ensureActive() }
                    }?.let { router.publish("glitch.image", dk.cbavf.studio.routing.RouteValue.Image(it)) }
                }
        }
        LaunchedEffect(wanted("fft.image")) {
            if (!wanted("fft.image")) return@LaunchedEffect
            snapshotFlow { fftState.edit.doc to router.inputVersion("fft.in") }
                .debounce(120)
                .collectLatest { (doc, _) ->
                    dk.cbavf.studio.ui.renderSafely({}) {
                        val src = labSource("fft.in", doc.sourceAssetId, doc.size * 2) ?: return@renderSafely null
                        val sq = dk.cbavf.studio.fft.squareCrop(src, doc.size)
                        val spectra = dk.cbavf.studio.fft.forward(sq).also { if (sq !== src) sq.recycle() }
                        val phase = doc.phaseFromAssetId?.let { lineage[it] }?.let { dk.cbavf.studio.develop.decodeAsset(store, it, doc.size * 2) }?.let { b ->
                            val s2 = dk.cbavf.studio.fft.squareCrop(b, doc.size)
                            dk.cbavf.studio.fft.forward(s2).also { s2.recycle(); b.recycle() }
                        }
                        dk.cbavf.studio.fft.inverse(spectra, dk.cbavf.studio.fft.model.SpectrumMask.build(doc), phase) { ensureActive() }
                    }?.let { router.publish("fft.image", dk.cbavf.studio.routing.RouteValue.Image(it)) }
                }
        }
        LaunchedEffect(wanted("camera.last"), lineage) {
            if (!wanted("camera.last")) return@LaunchedEffect
            val last = lineage.newestFirst().firstOrNull { it.operation.startsWith("camera.") && it.isStill } ?: return@LaunchedEffect
            withContext(Dispatchers.IO) { dk.cbavf.studio.develop.decodeAsset(store, last, routePx) }?.let { router.publish("camera.last", dk.cbavf.studio.routing.RouteValue.Image(it)) }
        }
        LaunchedEffect(wanted("loop.remote")) {
            if (!wanted("loop.remote")) return@LaunchedEffect
            snapshotFlow { loop.remoteFrame }.collect { b -> b?.let { router.publish("loop.remote", dk.cbavf.studio.routing.RouteValue.Image(it)) } }
        }
        LaunchedEffect(nodesOutWanted) {
            if (!nodesOutWanted) return@LaunchedEffect
            snapshotFlow { if (nodesLive.live) nodesLive.preview else nodePreview }
                .collect { img -> img?.let { router.publish("nodes.out", dk.cbavf.studio.routing.RouteValue.Image(it.asAndroidBitmap())) } }
        }
        // Compose: a live layer for every patched input that has none yet.
        LaunchedEffect(router.doc, dk.cbavf.studio.routing.model.COMPOSE_INPUTS.sumOf { router.inputVersion(it) }) {
            val patched = dk.cbavf.studio.routing.model.COMPOSE_INPUTS.filter { router.doc.sourceOf(it) != null }
            composeState.ensureRouteLayers(patched) { input -> router.inputValue(input)?.size }
        }
        // What the labs and the camera see from the rack, at their working sizes.
        val developRouted by produceState<android.graphics.Bitmap?>(null, router.inputVersion("develop.in")) {
            value = withContext(Dispatchers.Default) { router.inputBitmap("develop.in", dk.cbavf.studio.develop.DevelopState.PREVIEW_PX) }
        }
        val fftRouted by produceState<android.graphics.Bitmap?>(null, router.inputVersion("fft.in"), fftState.edit.doc.size) {
            value = withContext(Dispatchers.Default) { router.inputBitmap("fft.in", fftState.edit.doc.size * 2) }
        }
        // A number patched into Glitch's Seed jack (the Sensors unit, say) replaces its corruption seed.
        val glitchSeed: Int? = if (router.inputVersion("glitch.seed") >= 0) router.level("glitch.seed").toInt() else null
        val glitchRouted by produceState<android.graphics.Bitmap?>(null, router.inputVersion("glitch.in")) {
            value = withContext(Dispatchers.Default) { router.inputBitmap("glitch.in", dk.cbavf.studio.glitch.GLITCH_PX) }
        }
        val abRoutedA by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, router.inputVersion("ab.a")) {
            value = withContext(Dispatchers.Default) { router.inputBitmap("ab.a", 1440)?.asImageBitmap() }
        }
        val abRoutedB by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, router.inputVersion("ab.b")) {
            value = withContext(Dispatchers.Default) { router.inputBitmap("ab.b", 1440)?.asImageBitmap() }
        }
        val abState = remember { dk.cbavf.studio.abcompare.AbState() }
        val abContext = remember {
            dk.cbavf.studio.abcompare.AbContext(
                abState,
                routedA = { abRoutedA.takeIf { router.doc.sourceOf("ab.a") != null } },
                routedB = { abRoutedB.takeIf { router.doc.sourceOf("ab.b") != null } },
                twoFingers = { studioPrefs.config.profile.abTwoFingerHold },
            )
        }
        val cameraRouted by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, router.inputVersion("camera.overlay")) {
            value = withContext(Dispatchers.Default) { router.inputBitmap("camera.overlay", 1440)?.asImageBitmap() }
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
                        "Sent ${bmp.width}Ã—${bmp.height} to ${loop.peerDevice ?: "the other phone"}"
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
                    "Saved ${record.widthPx}Ã—${record.heightPx}" + if (type == "tocompose") " Â· added to Compose" else ""
                }.getOrElse { "Run failed: ${it.message}" }
                nodeRunning = false
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
        }

        DisposableEffect(patch) { onDispose { patch.stop() } }
        LaunchedEffect(store) {
            withContext(Dispatchers.IO) { store.loadUiLayout() }?.let { uiEditor.edit.load(it) }
            snapshotFlow { uiEditor.edit.doc }
                .debounce(AUTOSAVE_DELAY_MS)
                .collect { withContext(Dispatchers.IO) { store.saveUiLayout(it) } }
        }
        LaunchedEffect(store) {
            withContext(Dispatchers.IO) { store.loadPatch() }?.let(patch::load)
            snapshotFlow { patch.doc }
                .debounce(AUTOSAVE_DELAY_MS)
                .collect { withContext(Dispatchers.IO) { store.savePatch(it) } }
        }

        /** Where the cable into [input] comes from, as "Compose · Composite", or null if none is plugged in. */
        fun liveFrom(input: String): String? = router.doc.sourceOf(input)?.let { src ->
            val module = dk.cbavf.studio.routing.model.Rack.modules.firstOrNull { it.id == dk.cbavf.studio.routing.model.Rack.moduleOf(src) }?.title ?: src
            module + (dk.cbavf.studio.routing.model.Rack.jack(src)?.label?.let { " · $it" } ?: "")
        }

        fun overlaySources() = OverlaySources(
            drawLayers = { drawState.visibleLayers() },
            barrierStripPx = { animateState.stripPx },
            barrierFrames = { animateState.frames.size },
            routed = { cameraRouted.takeIf { router.doc.sourceOf("camera.overlay") != null } },
        )

        fun capture() {
            val spec = cameraState.spec ?: return
            if (cameraState.busy) return
            cameraState.busy = true
            cameraState.message = null
            val info = cameraState.info()
            val half = cameraState.half
            // Snapshot now: the overlay burnt in is the one on screen when the shutter was pressed.
            val overlay = cameraState.overlaySnapshot(overlaySources())
            scope.launch {
                val message = runCatching {
                    val shots = camera.capture(cameraState.burst, cameraState.raw)
                    val record = withContext(Dispatchers.Default) { saveCapture(context, store, spec, shots, info, half, overlay) }
                    lineage = withContext(Dispatchers.IO) { store.lineage() }
                    "Saved ${record.widthPx}Ã—${record.heightPx}" + if (shots.any { it.dng != null }) " + DNG" else ""
                }.getOrElse { "Capture failed: ${it.message}" }
                Debug.i(TAG, message)
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
            val live = drawState.toDoc()
            if (live.widthPx <= 0 || live.heightPx <= 0) return
            val source = drawState.sourceAssetId
            // Screen-exact, or the size picked in the Draw room's size menu (lowered to what fits in memory).
            val plan = drawState.renderPlan()
            scope.launch {
                // A live image layer's picture is filed in the Library first: the saved canvas names that file, not the cable.
                val snaps = dk.cbavf.studio.routing.model.RouteLayers.inputsIn(live).mapNotNull { input -> snapshotInputOnce(input)?.let { input to it } }.toMap()
                val doc = dk.cbavf.studio.routing.model.RouteLayers.pinned(live, snaps)
                // What it branches from, plus every Library image placed on it.
                val parents = (listOfNotNull(source) + doc.imageAssetIds()).distinct()
                val record = withContext(Dispatchers.Default) {
                    // A high-res image is tens of megabytes: running out of memory is a message, not a crash.
                    val png = try { renderPng(doc, plan.scale) } catch (e: OutOfMemoryError) { null } ?: return@withContext null
                    val r = store.addAsset(png, parents, "draw.render", Recipe(canvas = doc), Build.MODEL, plan.widthPx, plan.heightPx)
                    val uri = exportToPictures(context, "studio-${r.id}", png)
                    if (uri == null) r else r.copy(exportedUri = uri.toString()).also(store::update)
                }
                if (record == null) {
                    Toast.makeText(context, dk.cbavf.studio.draw.model.RenderSizes.tooBigMessage(plan.size), Toast.LENGTH_SHORT).show()
                    return@launch
                }
                // Further edits now branch from what was just saved.
                drawState.sourceAssetId = record.id
                lineage = withContext(Dispatchers.IO) { store.lineage() }
                Toast.makeText(context, "Saved Â· generation ${lineage.generation(record.id)}" + dk.cbavf.studio.draw.model.RenderSizes.savedNote(plan), Toast.LENGTH_SHORT).show()
            }
        }

        /** Saves the interleaved image and its barrier overlay; the barrier is the image's child. */
        fun saveBarrier() {
            val anim = animateState.toDoc()
            if (anim.frames.size < 2) return
            // Sized for the paper and dpi picked in the Barrier panel (Screen keeps the canvas size).
            val plan = animateState.printPlan() ?: return
            scope.launch {
                var tooBig = false
                val saved = withContext(Dispatchers.Default) {
                    // Render to export in one guard: a print-size sheet can run out of memory at any step.
                    try {
                        val image = renderInterleaved(anim, plan) ?: return@withContext null
                        val w = image.width
                        val h = image.height
                        // One big bitmap at a time: a print-size sheet is tens of megabytes.
                        val imagePng = try { encodePng(image) } finally { image.recycle() }
                        val barrier = renderBarrier(w, h, plan.stripPx, anim.frames.size)
                        val barrierPng = try { encodePng(barrier) } finally { barrier.recycle() }
                        // The recipe keeps the animation but not the paper or dpi; accepted for now.
                        val i = store.addAsset(imagePng, anim.frames.flatMap { it.doc.imageAssetIds() }.distinct(), "animate.interleave", Recipe(animation = anim), Build.MODEL, w, h)
                        val b = store.addAsset(barrierPng, listOf(i.id), "animate.barrier", null, Build.MODEL, w, h, recipeOf = i)
                        exportToPictures(context, "studio-${i.id}", imagePng)?.let { store.update(i.copy(exportedUri = it.toString())) }
                        exportToPictures(context, "studio-${b.id}-barrier", barrierPng)?.let { store.update(b.copy(exportedUri = it.toString())) }
                        i
                    } catch (e: OutOfMemoryError) {
                        tooBig = true
                        null
                    }
                }
                if (saved == null) {
                    if (tooBig) Toast.makeText(context, dk.cbavf.studio.animate.model.PrintSize.tooBigMessage(plan.paper), Toast.LENGTH_SHORT).show()
                    return@launch
                }
                lineage = withContext(Dispatchers.IO) { store.lineage() }
                val size = if (plan.paper == dk.cbavf.studio.animate.model.Paper.Screen) "" else " Â· ${plan.paper.label} at ${plan.dpi} dpi"
                Toast.makeText(context, "Saved image + barrier Â· ${anim.frames.size} frames$size", Toast.LENGTH_SHORT).show()
            }
        }

        /**
         * Encodes the sequence in the background, then files it in the Library (parents = the
         * Library images it uses) and in the shared media store.
         */
        fun exportSequence(
            kind: String,
            doc: dk.cbavf.studio.sequence.model.SequenceDoc = sequenceState.toDoc(),
            op: String = "sequence.$kind",
            recipe: Recipe = Recipe(sequence = doc),
        ) {
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
                            tmp, kind, decoder.parentIds(doc), op, Build.MODEL,
                            report.widthPx, report.heightPx, recipe,
                        )
                        val mime = if (kind == "gif") "image/gif" else "video/mp4"
                        exportToMedia(context, "studio-${record.id}.$kind", mime) { out ->
                            store.fileOf(record).inputStream().use { it.copyTo(out) }
                        }?.let { store.update(record.copy(exportedUri = it.toString())) }
                        (listOf("Saved ${kind.uppercase()} Â· ${report.frames} frames Â· ${report.widthPx}Ã—${report.heightPx}") + report.notes)
                            .joinToString("\n")
                    }.getOrElse { "Export failed: ${it.message}" }.also { tmp.delete() }
                }
                exportStatus.progress = null
                lineage = withContext(Dispatchers.IO) { store.lineage() }
                Debug.i(TAG, message)
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            }
        }

        /** Animate's frames as a one-off timeline (one tick per frame at the Speed setting, holds kept), saved as a GIF. */
        fun saveAnimateGif() {
            val anim = animateState.toDoc()
            if (anim.frames.isEmpty()) return
            exportSequence(
                "gif",
                dk.cbavf.studio.sequence.model.SequenceDoc(
                    items = anim.frames.map { f -> dk.cbavf.studio.sequence.model.SeqItem(dk.cbavf.studio.sequence.model.CanvasSource(f.doc), ticks = f.hold.coerceAtLeast(1)) },
                    rateHz = anim.fps.coerceAtLeast(1),
                    shape = dk.cbavf.studio.sequence.model.FrameShape.First,
                ),
                op = "animate.gif",
                recipe = Recipe(animation = anim),
            )
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
                Room.Develop -> { router.disconnect("develop.in"); developState.open(first) }
                Room.Fft -> router.disconnect("fft.in").also { fftState.edit.open(dk.cbavf.studio.fft.model.FftDoc(sourceAssetId = first.id, size = fftState.edit.doc.size)) }
                Room.Glitch -> { router.disconnect("glitch.in"); glitchState.edit.open(glitchState.edit.doc.copy(sourceAssetId = first.id)) }
                Room.Camera -> {
                    cameraState.overlayAssetId = first.id
                    cameraState.overlay = dk.cbavf.studio.camera.Overlay.Image
                }
                Room.Loop -> { router.disconnect(dk.cbavf.studio.routing.model.LOOP_IN); loop.startAssetId = first.id }
                else -> return
            }
            room = target
            if (assets.size > 1 && target !in setOf(Room.Nodes, Room.Compose, Room.Draw, Room.Patch, Room.Animate, Room.Sequence)) {
                Toast.makeText(context, "${target.title} works on one image: took the first.", Toast.LENGTH_SHORT).show()
            }
        }

        // Node Lab Projector node: its input on the whole screen.
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
            return@CompositionLocalProvider
        }

        // Sequence projector with a picture patched into its rack input: that picture, live, instead of the timeline.
        if (projecting && router.doc.sourceOf(dk.cbavf.studio.routing.model.SEQUENCE_IN) != null) {
            val screen = remember { screenSize(context).second }
            val live by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, router.inputVersion(dk.cbavf.studio.routing.model.SEQUENCE_IN)) {
                value = withContext(Dispatchers.Default) { router.inputBitmap(dk.cbavf.studio.routing.model.SEQUENCE_IN, screen)?.asImageBitmap() } ?: value
            }
            val doc = sequenceState.toDoc()
            dk.cbavf.studio.nodes.NodeProjector(live, doc.rateHz, doc.projectorBrightness, waiting = "Waiting for a picture from the rack…") { projecting = false }
            return@CompositionLocalProvider
        }

        if (projecting) {
            Projector(sequenceState.toDoc(), remember(lineage) { FrameDecoder(store, lineage, screenSize(context)) }) { result ->
                projecting = false
                if (result != null) {
                    val message = "Display ${result.displayHz.toInt()} Hz Â· ${result.vsyncs} vsyncs Â· ${result.dropped} dropped"
                    Debug.i(TAG, "Projector: $message")
                    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                }
            }
            return@CompositionLocalProvider
        }

        fun buildModuleSpecs(): List<dk.cbavf.studio.routing.ModuleBackSpec> {
            fun <T> port(serializer: kotlinx.serialization.KSerializer<T>, get: () -> T, set: (T) -> Unit) = dk.cbavf.studio.routing.ModulePort(
                snapshot = { ProjectStore.json.encodeToString(serializer, get()) },
                restore = { s -> runCatching { ProjectStore.json.decodeFromString(serializer, s) }.getOrNull()?.let(set) },
            )
            fun knob(label: String, value: Float, range: ClosedFloatingPointRange<Float>, whole: Boolean = false, format: ((Float) -> String)? = null, set: (Float) -> Unit) =
                dk.cbavf.studio.routing.KnobSpec(label, value, range, whole, format, set)
            fun button(label: String, on: Boolean? = null, click: () -> Unit) = dk.cbavf.studio.routing.ButtonSpec(label, on, click)
            val primary = drawState.primary?.content
            val pluginSpecs = run {
                val on = studioPrefs.config.profile.enabledPlugins.toSet()
                (dk.cbavf.studio.plugins.PluginStore.BUILT_IN + dk.cbavf.studio.plugins.PluginStore(context).installed())
                    .filter { it.id in on && it.widgets.isNotEmpty() }.map(pluginState::spec)
            }
            val specs = pluginSpecs + listOf(
                dk.cbavf.studio.routing.ModuleBackSpec(Room.Studio.name, "Studio", "gates",
                    knobs = listOf(
                        knob("Interface size", studioPrefs.uiScale * 100, 80f..140f, true, { "${it.toInt()} %" }) { studioPrefs.updateUiScale(it / 100) },
                        knob("Clock rate", gateBank.clockHz, 0.1f..8f, false, { String.format(java.util.Locale.US, "%.1f Hz", it) }) { gateBank.clockHz = it },
                    ),
                    buttons = listOf(
                        button("Theme: ${studioPrefs.theme.label} ▸") { studioPrefs.updateTheme(dk.cbavf.studio.ui.StudioTheme.entries[(studioPrefs.theme.ordinal + 1) % dk.cbavf.studio.ui.StudioTheme.entries.size]) },
                        button("Gate A", gateBank.a) { gateBank.a = !gateBank.a },
                        button("Gate B", gateBank.b) { gateBank.b = !gateBank.b },
                        button("Haptics", studioPrefs.haptics) { studioPrefs.updateHaptics(!studioPrefs.haptics) },
                        button("Keep screen on", studioPrefs.keepScreenOn) { studioPrefs.updateKeepScreenOn(!studioPrefs.keepScreenOn) },
                    )),
                dk.cbavf.studio.routing.ModuleBackSpec("OSC", "OSC in", "osc",
                    buttons = listOf(button(if (oscBus.on) "Listening on ${dk.cbavf.studio.osc.OscBus.PORT}" + (if (oscBus.last.isNotEmpty()) " · ${oscBus.last}" else "") else "OSC off", oscBus.on) { oscBus.on = !oscBus.on })),
                dk.cbavf.studio.routing.ModuleBackSpec("OSC out", "OSC out", "oscout",
                    buttons = listOf(button(
                        if (oscOut.on) "Sending to ${studioPrefs.config.profile.oscOutHost.ifBlank { "no host" }}:${studioPrefs.config.profile.oscOutPort}" + (if (oscOut.last.isNotEmpty()) " · ${oscOut.last}" else "") else "OSC out off",
                        oscOut.on,
                    ) { oscOut.on = !oscOut.on })),
                run {
                    // MIDI in: a switch, the devices it can read from (tap one to use it), and what feeds A to D.
                    val m = midiBus
                    val fits = dk.cbavf.studio.midi.MidiDevices.forIn(m.devices)
                    dk.cbavf.studio.routing.ModuleBackSpec("MIDI in", "MIDI in", "midi",
                        knobs = dk.cbavf.studio.midi.MIDI_JACKS.indices.map { i ->
                            knob("${"ABCD"[i]} from", m.inSources[i].toFloat(), 0f..128f, true, { dk.cbavf.studio.midi.MidiInMap.sourceLabel(it.toInt()) }) { v ->
                                m.inSources = m.inSources.toMutableList().also { it[i] = v.toInt() }
                            }
                        } + knob("Channel", m.inChannel.toFloat(), 0f..16f, true, { dk.cbavf.studio.midi.MidiInMap.channelLabel(it.toInt()) }) { m.inChannel = it.toInt() },
                        buttons = listOf(
                            button(if (m.inOn) m.inOpen?.let { "Reading ${it.name}" + (if (m.inLast.isNotEmpty()) " · ${m.inLast}" else "") } ?: "Opening…" else "MIDI in off" + (if (m.inLast.isNotEmpty()) " · ${m.inLast}" else ""), m.inOn) {
                                m.inLast = ""
                                m.inOn = !m.inOn
                            },
                            button("Find devices") { m.refresh() },
                        ) + fits.map { d -> button(d.name, m.inOpen?.id == d.id || (m.inOpen == null && m.inPicked == d.id)) { m.inPicked = d.id } })
                },
                run {
                    // MIDI out: a switch, the devices it can send to, and the controller each jack goes out as.
                    val m = midiBus
                    val fits = dk.cbavf.studio.midi.MidiDevices.forOut(m.devices)
                    dk.cbavf.studio.routing.ModuleBackSpec("MIDI out", "MIDI out", "midiout",
                        knobs = dk.cbavf.studio.midi.MIDI_JACKS.indices.map { i ->
                            knob("${"ABCD"[i]} as", m.outCcs[i].toFloat(), 0f..127f, true, { "CC ${it.toInt()}" }) { v ->
                                m.outCcs = m.outCcs.toMutableList().also { it[i] = v.toInt() }
                            }
                        } + knob("Channel", m.outChannel.toFloat(), 1f..16f, true, { "Ch ${it.toInt()}" }) { m.outChannel = it.toInt() },
                        buttons = listOf(
                            button(if (m.outOn) m.outOpen?.let { "Sending to ${it.name}" + (if (m.outLast.isNotEmpty()) " · ${m.outLast}" else "") } ?: "Opening…" else "MIDI out off" + (if (m.outLast.isNotEmpty()) " · ${m.outLast}" else ""), m.outOn) {
                                m.outLast = ""
                                m.outOn = !m.outOn
                            },
                            button("Find devices") { m.refresh() },
                        ) + fits.map { d -> button(d.name, m.outOpen?.id == d.id || (m.outOpen == null && m.outPicked == d.id)) { m.outPicked = d.id } })
                },
                dk.cbavf.studio.routing.ModuleBackSpec("Sensors", "Sensors", "sensors",
                    buttons = listOf(
                        button("Sensors on", sensorBus.on) { sensorBus.on = !sensorBus.on },
                        button("Record", sensorBus.recording) { if (sensorBus.on) sensorBus.recording = !sensorBus.recording },
                        button(sensorBus.take?.let { "Replay: %.1f s".format(java.util.Locale.US, it.durationMs / 1000f) } ?: "Replay: no take", sensorBus.replaying) {
                            if (sensorBus.take != null) sensorBus.replaying = !sensorBus.replaying
                        },
                    )),
                dk.cbavf.studio.routing.ModuleBackSpec("Channels", "Channels", "channels",
                    buttons = dk.cbavf.studio.channels.ColorSpace.entries.map { sp -> button(sp.name, channelSpace == sp) { channelSpace = sp } }),
                dk.cbavf.studio.routing.ModuleBackSpec(Room.Library.name, "Library", null,
                    knobs = listOf(knob("Columns", libraryNav.columns.toFloat(), 1f..8f, true) { libraryNav.columns = it.toInt() }),
                    buttons = listOf(button("Sort: ${libraryNav.sort.label} ▸") { libraryNav.sort = dk.cbavf.studio.library.LibrarySort.entries[(libraryNav.sort.ordinal + 1) % dk.cbavf.studio.library.LibrarySort.entries.size] })),
                dk.cbavf.studio.routing.ModuleBackSpec(Room.Compose.name, "Compose", "compose",
                    knobs = listOf(
                        knob("Width", composeState.doc.widthPx.toFloat(), 64f..8192f, true) { v -> composeState.edit.edit("bw") { it.copy(widthPx = v.toInt()) } },
                        knob("Height", composeState.doc.heightPx.toFloat(), 64f..8192f, true) { v -> composeState.edit.edit("bh") { it.copy(heightPx = v.toInt()) } },
                        knob("View zoom", composeState.zoom, 0.1f..8f) { composeState.zoom = it },
                    ),
                    buttons = listOf(button("Save", null, ::saveComposite), button("Fit view") { composeState.fitView() }, button("Undo") { composeState.edit.undo() }, button("Redo") { composeState.edit.redo() }),
                    port = port(dk.cbavf.studio.compose.model.ComposeDoc.serializer(), { composeState.doc }) { composeState.edit.open(it) }),
                dk.cbavf.studio.routing.ModuleBackSpec(Room.Nodes.name, "Node Lab", "nodes",
                    knobs = listOf(knob("Zoom", nodesState.zoom, 0.2f..3f) { nodesState.zoom = it }),
                    buttons = listOf(
                        button("Live", nodesLive.live) { if (nodesLive.live) nodesLive.stop() else { patch.stop(); nodesLive.start(scope) } },
                        button("Backdrop", nodesState.backdrop) { nodesState.backdrop = !nodesState.backdrop },
                        button("Undo") { nodesState.undo() }, button("Redo") { nodesState.redo() },
                    ),
                    port = port(dk.cbavf.studio.nodes.model.Graph.serializer(), { nodesState.top }) { nodesState.load(it) }),
                dk.cbavf.studio.routing.ModuleBackSpec(Room.Draw.name, "Draw", "draw",
                    knobs = when (primary) {
                        is dk.cbavf.studio.draw.model.Grating -> listOf(
                            knob("Spacing", primary.spacingPx, 2f..80f) { v -> drawState.updatePrimary("bsp") { l -> l.copy(content = (l.content as? dk.cbavf.studio.draw.model.Grating)?.copy(spacingPx = v) ?: l.content) } },
                            knob("Angle", primary.angleDeg, -90f..90f) { v -> drawState.updatePrimary("ban") { l -> l.copy(content = (l.content as? dk.cbavf.studio.draw.model.Grating)?.copy(angleDeg = v) ?: l.content) } },
                            knob("Offset", primary.offsetPx, -50f..50f) { v -> drawState.updatePrimary("bof") { l -> l.copy(content = (l.content as? dk.cbavf.studio.draw.model.Grating)?.copy(offsetPx = v) ?: l.content) } },
                            knob("Line width", primary.duty, 0.05f..0.95f) { v -> drawState.updatePrimary("bdu") { l -> l.copy(content = (l.content as? dk.cbavf.studio.draw.model.Grating)?.copy(duty = v) ?: l.content) } },
                        )
                        is dk.cbavf.studio.draw.model.RadialBurst -> listOf(
                            knob("Rays", primary.rays.toFloat(), 8f..720f, true) { v -> drawState.updatePrimary("bra") { l -> l.copy(content = (l.content as? dk.cbavf.studio.draw.model.RadialBurst)?.copy(rays = v.toInt()) ?: l.content) } },
                            knob("Rotation", primary.rotationDeg, -180f..180f) { v -> drawState.updatePrimary("bro") { l -> l.copy(content = (l.content as? dk.cbavf.studio.draw.model.RadialBurst)?.copy(rotationDeg = v) ?: l.content) } },
                            knob("Line width", primary.duty, 0.05f..0.95f) { v -> drawState.updatePrimary("bdu") { l -> l.copy(content = (l.content as? dk.cbavf.studio.draw.model.RadialBurst)?.copy(duty = v) ?: l.content) } },
                        )
                        else -> emptyList()
                    },
                    buttons = listOf(button("Save", null, ::save), button("New canvas") { drawState.newCanvas() }, button("Straight line", drawState.straight) { drawState.straight = !drawState.straight },
                        button("Undo") { drawState.undo() }, button("Redo") { drawState.redo() }),
                    port = port(dk.cbavf.studio.library.CanvasDoc.serializer(), { drawState.toDoc() }) { drawState.open(it, null) }),
                dk.cbavf.studio.routing.ModuleBackSpec(Room.Animate.name, "Animate", "animate",
                    knobs = listOf(
                        knob("Speed", animateState.fps.toFloat(), 1f..60f, true, { "${it.toInt()} fps" }) { animateState.fps = it.toInt() },
                        knob("Strip", animateState.stripPx.toFloat(), 1f..12f, true, { "${it.toInt()} px" }) { animateState.stripPx = it.toInt() },
                    ),
                    buttons = listOf(button("Play", animateState.playing) { animateState.playing = !animateState.playing }, button("Save GIF", null, ::saveAnimateGif),
                        button("Clear all") { animateState.clearAll() }, button("Undo") { animateState.undo() }, button("Redo") { animateState.redo() }),
                    port = port(dk.cbavf.studio.animate.model.AnimationDoc.serializer(), { animateState.toDoc() }) { animateState.open(it) }),
                dk.cbavf.studio.routing.ModuleBackSpec(Room.Sequence.name, "Sequence", "sequence",
                    knobs = listOf(
                        knob("Black frames", sequenceState.blackTicks.toFloat(), 0f..4f, true) { sequenceState.blackTicks = it.toInt() },
                        knob("Brightness", (sequenceState.projectorBrightness ?: 0f) * 100, 0f..100f, true, { if (it < 1f) "phone" else "${it.toInt()} %" }) { v -> sequenceState.projectorBrightness = if (v < 1f) null else v / 100 },
                    ),
                    buttons = listOf(button("Projector") { if (sequenceState.items.isNotEmpty() || router.doc.sourceOf(dk.cbavf.studio.routing.model.SEQUENCE_IN) != null) { studioBack = false; projecting = true } },
                        button("Export GIF") { exportSequence("gif") }, button("Export MP4") { exportSequence("mp4") }, button("Undo") { sequenceState.undo() }, button("Redo") { sequenceState.redo() }),
                    port = port(dk.cbavf.studio.sequence.model.SequenceDoc.serializer(), { sequenceState.toDoc() }) { sequenceState.open(it) }),
                dk.cbavf.studio.routing.ModuleBackSpec(Room.Bridge.name, "Bridge", null,
                    buttons = listOf(button("Scan inbox") { bridge.scan() }, button("Open gallery") { bridge.launch(ExternalApp.Gallery) })),
                dk.cbavf.studio.routing.ModuleBackSpec(Room.Camera.name, "Camera", "camera",
                    knobs = listOfNotNull(
                        cameraState.spec?.zoomRange?.takeIf { it.endInclusive > it.start }?.let { r -> knob("Zoom", cameraState.zoom.coerceIn(r.start, r.endInclusive), r, false, { "%.2fÃ—".format(it) }) { cameraState.zoom = it } },
                        cameraState.spec?.isoRange?.let { r -> knob("ISO", cameraState.iso.toFloat(), r.first.toFloat()..r.last.toFloat(), true) { cameraState.iso = it.toInt(); cameraState.manualExposure = true } },
                        knob("Burst", cameraState.burst.toFloat(), 1f..8f, true) { cameraState.burst = it.toInt() },
                    ),
                    buttons = listOf(button("Capture") { studioBack = false; room = Room.Camera; capture() }, button("Torch", cameraState.torch) { cameraState.torch = !cameraState.torch },
                        button("Manual exposure", cameraState.manualExposure) { cameraState.manualExposure = !cameraState.manualExposure }),
                    port = port(dk.cbavf.studio.camera.model.CaptureInfo.serializer(), { cameraState.info() }) { cameraState.restore(it) }),
                dk.cbavf.studio.routing.ModuleBackSpec(Room.Loop.name, "Loop", "loop",
                    knobs = listOf(
                        knob("Settle", loop.settleMs.toFloat(), 100f..3000f, true, { "${it.toInt()} ms" }) { loop.settleMs = it.toLong() },
                        knob("Interval", loop.intervalMs.toFloat(), 200f..10000f, true, { "${it.toInt()} ms" }) { loop.intervalMs = it.toLong() },
                        knob("Generations", loop.generations.toFloat(), 1f..100f, true) { loop.generations = it.toInt() },
                    ),
                    buttons = listOf(button(if (loop.connected) "Connected" else "Connect", loop.connected) { if (!loop.connected) loop.connect() },
                        button("Running", loop.running) { if (loop.running) loop.stop() else loop.start() }, button("Torch strobe", loop.strobe) { loop.strobe = !loop.strobe })),
                dk.cbavf.studio.routing.ModuleBackSpec(Room.Patch.name, "Patch", "patch",
                    knobs = listOf(
                        knob("LFO 1", patch.doc.lfo1.rateHz, 0f..5f, false, { "%.2f Hz".format(it) }) { v -> patch.doc = patch.doc.copy(lfo1 = patch.doc.lfo1.copy(rateHz = v)) },
                        knob("LFO 2", patch.doc.lfo2.rateHz, 0f..5f, false, { "%.2f Hz".format(it) }) { v -> patch.doc = patch.doc.copy(lfo2 = patch.doc.lfo2.copy(rateHz = v)) },
                    ),
                    buttons = listOf(button("Live", patch.live) { if (patch.live) patch.stop() else { nodesLive.stop(); patch.start() } }),
                    port = port(dk.cbavf.studio.patch.model.PatchDoc.serializer(), { patch.doc }) { patch.load(it) }),
                dk.cbavf.studio.routing.ModuleBackSpec(Room.Develop.name, "Develop", "develop",
                    knobs = listOf(
                        knob("Exposure", developState.doc.exposureEv, -3f..3f, false, { "%.2f EV".format(it) }) { v -> developState.edit("bexp") { it.copy(exposureEv = v) } },
                        knob("Temperature", developState.doc.temperature, -1f..1f) { v -> developState.edit("btemp") { it.copy(temperature = v) } },
                        knob("Tint", developState.doc.tint, -1f..1f) { v -> developState.edit("btint") { it.copy(tint = v) } },
                        knob("Opacity", developState.doc.opacity * 100, 0f..100f, true, { "${it.toInt()} %" }) { v -> developState.edit("bop") { it.copy(opacity = v / 100) } },
                        knob("Style", developState.doc.artAmount * 100, 0f..100f, true, { "${it.toInt()} %" }) { v -> developState.edit("bart") { it.copy(artAmount = v / 100) } },
                    ),
                    buttons = listOf(button("Save", null, ::saveDeveloped), button("Reset") { developState.reset() }, button("Undo") { developState.undo() }, button("Redo") { developState.redo() }),
                    port = port(dk.cbavf.studio.develop.model.DevelopDoc.serializer(), { developState.doc }) { developState.open(it) }),
                dk.cbavf.studio.routing.ModuleBackSpec(Room.Fft.name, "FFT", "fft",
                    knobs = listOf(
                        knob("Size", listOf(256, 512, 1024).indexOf(fftState.edit.doc.size).coerceAtLeast(0).toFloat(), 0f..2f, true, { "${listOf(256, 512, 1024)[it.toInt().coerceIn(0, 2)]}" }) { v -> fftState.edit.edit { it.copy(size = listOf(256, 512, 1024)[v.toInt().coerceIn(0, 2)]) } },
                        knob("Radius", fftState.edit.doc.radius, 0.005f..0.5f) { v -> fftState.edit.edit("brad") { it.copy(radius = v) } },
                        knob("Band", fftState.edit.doc.bandWidth, 0.005f..0.3f) { v -> fftState.edit.edit("bband") { it.copy(bandWidth = v) } },
                        knob("Boost", fftState.edit.doc.boost, 1f..10f) { v -> fftState.edit.edit("bboost") { it.copy(boost = v) } },
                    ),
                    buttons = listOf(button("Undo") { fftState.edit.undo() }, button("Redo") { fftState.edit.redo() }),
                    port = port(dk.cbavf.studio.fft.model.FftDoc.serializer(), { fftState.edit.doc }) { fftState.edit.open(it) }),
                dk.cbavf.studio.routing.ModuleBackSpec(Room.Glitch.name, "Glitch", "glitch",
                    knobs = listOf(
                        knob("Width Î”", glitchState.edit.doc.widthDelta.toFloat(), -128f..128f, true) { v -> glitchState.edit.edit("bw") { it.copy(widthDelta = v.toInt()) } },
                        knob("Stride Î”", glitchState.edit.doc.strideDelta.toFloat(), -512f..512f, true) { v -> glitchState.edit.edit("bs") { it.copy(strideDelta = v.toInt()) } },
                        knob("Chroma X", glitchState.edit.doc.chromaX.toFloat(), -64f..64f, true) { v -> glitchState.edit.edit("bc") { it.copy(chromaX = v.toInt()) } },
                        knob("JPEG gens", glitchState.edit.doc.generations.toFloat(), 0f..50f, true) { v -> glitchState.edit.edit("bg") { it.copy(generations = v.toInt()) } },
                        knob("Quality", glitchState.edit.doc.quality.toFloat(), 1f..95f, true) { v -> glitchState.edit.edit("bq") { it.copy(quality = v.toInt()) } },
                        knob("Correction", glitchState.edit.doc.correction * 100, 0f..100f, true, { "${it.toInt()} %" }) { v -> glitchState.edit.edit("bfix") { it.copy(correction = v / 100) } },
                        knob("Sort angle", glitchState.edit.doc.sortAngle, 0f..360f, true, { "${it.toInt()}°" }) { v -> glitchState.edit.edit("bsa") { it.withSortAngle(v) } },
                    ),
                    buttons = listOf(button("Reset") { glitchState.edit.edit { dk.cbavf.studio.glitch.model.GlitchDoc(sourceAssetId = it.sourceAssetId) } }, button("Undo") { glitchState.edit.undo() }, button("Redo") { glitchState.edit.redo() }),
                    port = port(dk.cbavf.studio.glitch.model.GlitchDoc.serializer(), { glitchState.edit.doc }) { glitchState.edit.open(it) }),
                dk.cbavf.studio.routing.ModuleBackSpec(Room.Calc.name, "Calculator", "calc",
                    buttons = listOf(button("Clear history") { calcState.book = calcState.book.clearHistory() })),
                dk.cbavf.studio.routing.ModuleBackSpec(Room.Math.name, "Math", null,
                    knobs = listOf(knob("Mix", mathState.mix * 100, 0f..100f, true, { "${it.toInt()} %" }) { mathState.mix = it / 100 })),
                dk.cbavf.studio.routing.ModuleBackSpec(Room.Vector3d.name, "3D", null,
                    knobs = listOf(
                        knob("View turn", vector3dState.camera.yawDeg.toFloat(), 0f..360f, true, { "${it.toInt()}°" }) { v -> vector3dState.camera = vector3dState.camera.copy(yawDeg = v.toDouble()) },
                        knob("View height", vector3dState.camera.pitchDeg.toFloat(), -89f..89f, true, { "${it.toInt()}°" }) { v -> vector3dState.camera = vector3dState.camera.copy(pitchDeg = v.toDouble()) },
                        knob("Distance", vector3dState.camera.distance.toFloat(), 1.2f..30f) { v -> vector3dState.camera = vector3dState.camera.copy(distance = v.toDouble()) },
                        knob("Phase", (vector3dState.current?.grating?.phase ?: 0.0).toFloat(), 0f..1f) { v -> vector3dState.editGrating { it.copy(phase = v.toDouble()) } },
                    )),
                dk.cbavf.studio.routing.ModuleBackSpec(Room.Physics.name, "Physics", "physics",
                    knobs = listOf(
                        knob("Gravity", physicsState.gravityScale, 0f..3f, false, { "%.2f×".format(java.util.Locale.US, it) }) { physicsState.gravityScale = it },
                        knob("Bounce", physicsState.settings.bounce, 0f..1f) { v -> physicsState.updateSettings(physicsState.settings.copy(bounce = v)) },
                        knob("Friction", physicsState.settings.friction, 0f..1f) { v -> physicsState.updateSettings(physicsState.settings.copy(friction = v)) },
                    ),
                    buttons = listOf(
                        button("Run", physicsState.running) { if (physicsState.source != null) physicsState.running = !physicsState.running },
                        button("Reset") { physicsState.reset() },
                        button("Record", physicsState.recording) { if (physicsState.source != null) physicsState.recording = !physicsState.recording },
                    )),
            )
            return specs
        }

        // --- Studio-wide gestures: undo, redo, rooms, history, the cheat sheet --------------------
        var cheatSheet by remember { mutableStateOf(false) }
        var historyOpen by remember { mutableStateOf(false) }
        fun historyOf(r: Room): dk.cbavf.studio.ui.HistoryView? = when (r) {
            Room.Draw -> drawState
            Room.Compose -> composeState.edit
            Room.Fft -> fftState.edit
            Room.Glitch -> glitchState.edit
            Room.Nodes -> nodesState.edit
            Room.UiEditor -> uiEditor.edit
            else -> null
        }
        fun onStudioGesture(g: Gesture) {
            dk.cbavf.studio.debug.Debug.i("gesture", "$g in ${room.name}")
            fun press(label: String) = buildModuleSpecs().firstOrNull { it.roomId == room.name }?.buttons?.firstOrNull { it.label == label }?.onClick?.invoke()
            when (g) {
                Gesture.Tap2 -> press("Undo")
                Gesture.Tap3 -> press("Redo")
                Gesture.Tap4 -> cheatSheet = !cheatSheet
                Gesture.Swipe3Left -> room = Room.entries[(room.ordinal + 1) % Room.entries.size]
                Gesture.Swipe3Right -> room = Room.entries[(room.ordinal + Room.entries.size - 1) % Room.entries.size]
                Gesture.EdgeSwipeRight ->
                    if (historyOf(room) != null) historyOpen = true
                    else Toast.makeText(context, "${room.title} has no history list yet", Toast.LENGTH_SHORT).show()
                else -> Unit
            }
        }

        // --- The command palette: a notification that names the room and takes a typed room name ---
        val paletteOn = studioPrefs.config.profile.paletteNotification
        var notifyTick by remember { mutableStateOf(0) }
        val notifyPermission = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { notifyTick++ }
        LaunchedEffect(paletteOn) {
            if (paletteOn && android.os.Build.VERSION.SDK_INT >= 33 &&
                context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) notifyPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        LaunchedEffect(paletteOn, room, studioPrefs.project, notifyTick) {
            if (paletteOn) dk.cbavf.studio.palette.Palette.show(context, room.title, studioPrefs.project) else dk.cbavf.studio.palette.Palette.hide(context)
        }
        val asked = dk.cbavf.studio.palette.PaletteBus.requested
        LaunchedEffect(asked) {
            if (asked != null) {
                Room.entries.firstOrNull { it.title.equals(asked, ignoreCase = true) }?.let { room = it }
                dk.cbavf.studio.palette.PaletteBus.requested = null
            }
        }
        // --- The studio's back (Node Lab → Studio routing) ------------------------------------
        // The UI Editor's cables: while any exist, each frame carries a source control's level to its target control.
        LaunchedEffect(uiEditor) {
            val memo = HashMap<String, Float>()
            while (true) {
                // Off screen the cables carry nothing and the loop does not wake.
                Foreground.gate.awaitStarted()
                val cables = uiEditor.edit.doc.cables
                if (cables.isEmpty()) {
                    memo.clear()
                    Foreground.gate.sleep(250)
                } else {
                    androidx.compose.runtime.withFrameMillis { }
                    dk.cbavf.studio.uieditor.UiCables.step(cables, dk.cbavf.studio.uieditor.liveControls(buildModuleSpecs()), memo)
                }
            }
        }
        val flip by androidx.compose.animation.core.animateFloatAsState(if (studioBack) 180f else 0f, androidx.compose.animation.core.tween(550), label = "studio flip")
        val flipDensity = androidx.compose.ui.platform.LocalDensity.current.density
        Box(Modifier.fillMaxSize().studioGestures(::onStudioGesture).tapProbe { room.title }) {
        // After a crash, the phone says so: tell the person that nothing is lost.
        val lastExit = remember { dk.cbavf.studio.studio.CrashRecovery.lastExit(context) }
        var dismissedAt by remember { mutableStateOf(studioPrefs.config.workspace.recoveryDismissedAt) }
        val recoveryText = lastExit?.let { (reason, at) -> dk.cbavf.studio.studio.CrashRecovery.banner(reason, at, dismissedAt) }
        if (recoveryText != null) {
            androidx.compose.material3.Surface(
                modifier = Modifier.align(Alignment.TopCenter).padding(12.dp).zIndex(10f),
                color = MaterialTheme.colorScheme.errorContainer,
                shape = MaterialTheme.shapes.medium,
                tonalElevation = 6.dp,
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(recoveryText, color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodySmall)
                    androidx.compose.material3.TextButton(onClick = {
                        val at = lastExit?.second ?: 0L
                        dismissedAt = at
                        studioPrefs.config.updateWorkspace { it.copy(recoveryDismissedAt = at) }
                    }) { Text("Dismiss") }
                }
            }
        }
        androidx.compose.runtime.CompositionLocalProvider(dk.cbavf.studio.abcompare.LocalAb provides abContext) {
        Box(Modifier.fillMaxSize().graphicsLayer { rotationY = flip; cameraDistance = 14f * flipDensity }) {
        if (flip > 90f) Box(Modifier.fillMaxSize().graphicsLayer { rotationY = 180f }) {
            val specs = buildModuleSpecs()
            dk.cbavf.studio.routing.StudioBack(
                router, specs, backRoom,
                onSelect = { backRoom = it },
                presets = modulePresets,
                onSavePreset = { id, name ->
                    val spec = specs.first { it.roomId == id }
                    val p = dk.cbavf.studio.routing.ModulePreset(name, spec.port!!.snapshot(), System.currentTimeMillis())
                    modulePresets = modulePresets + (id to (modulePresets[id].orEmpty().filterNot { it.name == name } + p))
                },
                onDeletePreset = { id, p -> modulePresets = modulePresets + (id to (modulePresets[id].orEmpty() - p)) },
                rackPresets = rackPresets,
                onSaveRackPreset = { name -> rackPresets = dk.cbavf.studio.routing.model.RackPresets.save(rackPresets, name, router.doc, System.currentTimeMillis()) },
                onDeleteRackPreset = { p -> rackPresets = dk.cbavf.studio.routing.model.RackPresets.delete(rackPresets, p.name) },
                onLoadRackPreset = { p ->
                    val dropped = router.loadPreset(p)
                    val left = if (dropped.isEmpty()) "" else " (${dropped.size} cable${if (dropped.size == 1) "" else "s"} no longer fit)"
                    Toast.makeText(context, "Loaded ${p.name}$left. Undo brings back the old cables.", Toast.LENGTH_SHORT).show()
                },
                onFlip = {
                    // Back to the front of the module being looked at (or Node Lab).
                    backRoom?.let { id -> runCatching { Room.valueOf(id) }.getOrNull() }?.let { room = it }
                    studioBack = false
                },
            )
        } else
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
                                ?: (ScreenRecordService.recording.collectAsState().value as? RecordingStatus.Recording)?.let { " ● rec" }
                            Room.Patch -> if (patch.live) " ●" else null
                            Room.Physics -> if (physicsState.running) " ●" else null
                            Room.Loop -> if (loop.running) " ●" else if (loop.connected) " ⇄" else null
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
                androidx.compose.runtime.CompositionLocalProvider(
                    dk.cbavf.studio.modulation.LocalControlScope provides room.name,
                    dk.cbavf.studio.modulation.LocalSensorLevels provides sensorLevels,
                ) {
                when (room) {
                    Room.Draw -> DrawRoom(drawState, store, lineage, onSave = ::save)
                    Room.Animate -> AnimateRoom(
                        animateState,
                        store,
                        lineage,
                        grabFromDraw = {
                            drawState.toDoc().flattened().takeIf { it.widthPx > 0 && it.heightPx > 0 }
                                ?: null.also { Toast.makeText(context, "Open the Draw room once first, so the canvas has a size.", Toast.LENGTH_LONG).show() }
                        },
                        onOpenInDraw = { doc ->
                            animateState.playing = false
                            drawState.open(doc, null)
                            room = Room.Draw
                        },
                        onSaveBarrier = ::saveBarrier,
                        onSaveGif = ::saveAnimateGif,
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
                        liveFrom = liveFrom(dk.cbavf.studio.routing.model.SEQUENCE_IN),
                    )
                    Room.Camera -> CameraRoom(
                        cameraState,
                        camera,
                        overlaySources(),
                        onCapture = ::capture,
                        onOpenFv5 = { bridge.launch(ExternalApp.Fv5) },
                        onOpenLast = lineage.newestFirst().firstOrNull { it.operation.startsWith("camera.") }?.let { a -> { libraryNav.selectedId = a.id; room = Room.Library } },
                    )
                    Room.Loop -> LoopRoom(loop, cameraState, camera, feedFrom = liveFrom(dk.cbavf.studio.routing.model.LOOP_IN))
                    Room.UiEditor -> {
                        // Switched-on plugins are modules too: their controls get a panel here.
                        val enabledPlugins = studioPrefs.config.profile.enabledPlugins.toSet()
                        val extra = remember(pluginRevision, enabledPlugins) {
                            (dk.cbavf.studio.plugins.PluginStore.BUILT_IN + dk.cbavf.studio.plugins.PluginStore(context).installed())
                                .filter { it.id in enabledPlugins && it.widgets.isNotEmpty() }
                                .map(dk.cbavf.studio.plugins.PluginFormat::seed)
                        }
                        dk.cbavf.studio.uieditor.UiEditorRoom(buildModuleSpecs(), uiEditor, emptyList())
                    }
                    Room.Forge -> dk.cbavf.studio.ui.layout.BoundedWidth {
dk.cbavf.studio.forge.ForgeRoom(studioPrefs) { pluginRevision++ }
                    }
                    Room.Plugins -> dk.cbavf.studio.ui.layout.BoundedWidth {
                        val on = studioPrefs.config.profile.enabledPlugins.toSet()
                        val list = remember(pluginRevision, on) {
                            (dk.cbavf.studio.plugins.PluginStore.BUILT_IN + dk.cbavf.studio.plugins.PluginStore(context).installed()).filter { it.id in on }
                        }
                        dk.cbavf.studio.plugins.PluginRoom(list, pluginState, router)
                    }
                    Room.SlitScan -> dk.cbavf.studio.slitscan.SlitScanRoom(slitScanState, animateState, store, lineage, incoming = router.inputBitmap("slitscan.in", 480), incomingVersion = router.inputVersion("slitscan.in"), sequence = sequenceState) { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
                    Room.Calc -> dk.cbavf.studio.ui.layout.BoundedWidth {
                        dk.cbavf.studio.calc.CalcRoom(
                            calcState,
                            router,
                            // Every knob on the modules' backs, the same ones the UI Editor's cables drive.
                            targets = {
                                buildModuleSpecs().filter { it.roomId != Room.Calc.name }.flatMap { s ->
                                    s.knobs.map { k -> dk.cbavf.studio.calc.CalcTarget("${s.title} · ${k.label}", k.range, k.whole, k.onChange) }
                                }
                            },
                        ) { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
                    }
                    Room.Math -> dk.cbavf.studio.math.MathRoom(
                        mathState, store, lineage, busy = labBusy,
                        onParamsChanged = { studioPrefs.config.saveMathParams() },
                    ) { bitmap, sourceId -> saveLab(bitmap, listOf(sourceId), "math.curve", Recipe(), png = true) }
                    Room.Vector3d -> dk.cbavf.studio.vector3d.Vector3dRoom(
                        vector3dState, busy = labBusy,
                        onSaveImage = { bitmap -> saveLab(bitmap, emptyList(), "vector3d.render", Recipe(), png = true) },
                    ) { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
                    Room.Physics -> dk.cbavf.studio.physics.PhysicsRoom(
                        physicsState,
                        animateState,
                        grabFromDraw = {
                            drawState.toDoc().flattened().takeIf { it.widthPx > 0 && it.heightPx > 0 }
                                ?: null.also { Toast.makeText(context, "Open the Draw room once first, so the canvas has a size.", Toast.LENGTH_LONG).show() }
                        },
                        onOpenAnimate = { room = Room.Animate },
                    ) { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
                    Room.Compose ->dk.cbavf.studio.compose.ComposeRoom(
                        composeState,
                        store,
                        lineage,
                        layerPixels,
                        busy = composeBusy,
                        drawCanvas = { drawState.toDoc().flattened().takeIf { it.widthPx > 0 } },
                        screen = screenSize(context),
                        onSave = ::saveComposite,
                        onMessage = { Toast.makeText(context, it, Toast.LENGTH_LONG).show() },
                        routeTick = dk.cbavf.studio.routing.model.COMPOSE_INPUTS.sumOf { router.inputVersion(it) },
                    )
                    Room.Nodes -> dk.cbavf.studio.nodes.NodesRoom(
                        nodesState,
                        store,
                        lineage,
                        preview = if (nodesLive.live) nodesLive.preview else nodePreview,
                        previewStatus = when {
                            nodesLive.live -> "live %.1f s Â· %.0f fps".format(nodesLive.seconds, nodesLive.fps) +
                                (if (nodesLive.armed.isNotEmpty()) " Â· rec ${nodesLive.recorded}" else "") +
                                (if (nodesLive.sent > 0) " Â· sent ${nodesLive.sent}" else "") +
                                (if (loop.remoteFrames > 0) " Â· received ${loop.remoteFrames}" else "")
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
                        onStudioRouting = {
                            studioBack = true
                            backRoom = null
                        },
                        onSavePreset = { name ->
                            nodesState.presets = nodesState.presets.filterNot { it.name == name && !it.group } + dk.cbavf.studio.nodes.model.GraphPreset(name, nodesState.top)
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
                        routed = developRouted.takeIf { router.doc.sourceOf("develop.in") != null },
                        liveFrom = liveFrom("develop.in"),
                        onUnplug = { router.disconnect("develop.in") },
                    )
                    Room.Fft -> FftRoom(fftState, store, lineage, busy = labBusy, routed = fftRouted.takeIf { router.doc.sourceOf("fft.in") != null }, liveFrom = liveFrom("fft.in"), onUnplug = { router.disconnect("fft.in") }) { bitmap ->
                        val d = fftState.edit.doc
                        val live = router.doc.sourceOf("fft.in") != null
                        saveLab(bitmap, listOfNotNull(d.sourceAssetId.takeIf { !live }, d.phaseFromAssetId), "fft.inverse", Recipe(fft = d), png = true, routedInput = "fft.in")
                    }
                    Room.Glitch -> GlitchRoom(glitchState, store, lineage, busy = labBusy, routed = glitchRouted.takeIf { router.doc.sourceOf("glitch.in") != null }, liveFrom = liveFrom("glitch.in"), seedFrom = glitchSeed, onUnplug = { router.disconnect("glitch.in") }) { bitmap ->
                        val d = glitchState.edit.doc.let { if (glitchSeed != null) it.copy(seed = glitchSeed) else it }
                        val live = router.doc.sourceOf("glitch.in") != null
                        // JPEG generations are the look: keep them as JPEG; byte misreads stay lossless.
                        saveLab(bitmap, listOfNotNull(d.sourceAssetId.takeIf { !live }, d.colorFromAssetId.takeIf { d.colorAmount > 0f }), "glitch.render", Recipe(glitch = d), png = d.generations == 0, routedInput = "glitch.in")
                    }
                    Room.Patch -> PatchRoom(patch, drawState, sensors, linked = loop.connected, onOpenAsGraph = {
                        patch.stop()
                        val doc = drawState.toDoc()
                        nodesState.replace(dk.cbavf.studio.nodes.model.patchToGraph(patch.doc, drawState.layers.map { it.id }, doc.widthPx, doc.heightPx))
                        room = Room.Nodes
                    })
                    Room.Bridge -> dk.cbavf.studio.ui.layout.BoundedWidth {
BridgeRoom(
                        bridge.state,
                        lineage,
                        onPermissionResult = {
                            bridge.refreshAccess()
                            bridge.scan()
                        },
                        onLaunch = bridge::launch,
                        onImport = bridge::import,
                        favourites = studioPrefs.config.profile.favouriteApps,
                        onFavourites = { l -> studioPrefs.config.updateProfile { it.copy(favouriteApps = l) } },
                    )
                    }
                    // In landscape the Studio's tabs are a rail beside the page, so the page gets the rail's width on top.
                    Room.Studio -> dk.cbavf.studio.ui.layout.BoundedWidth(landscapeExtraDp = dk.cbavf.studio.ui.layout.RoomLayoutMath.RAIL) {
dk.cbavf.studio.studio.StudioRoom(
                        studioPrefs,
                        rooms = Room.entries.filter { it != Room.Studio }.map { it.name to it.title },
                        libraryCount = lineage.newestFirst().size,
                        addresses = loop.ownAddresses(),
                        notebook = lineage.newestFirst(),
                        onOpenAsset = { id -> libraryNav.selectedId = id; room = Room.Library },
                        onNote = { id, note ->
                            scope.launch {
                                withContext(Dispatchers.IO) { store.setNote(id, note) }
                                lineage = withContext(Dispatchers.IO) { store.lineage() }
                            }
                        },
                        onRerun = ::rerun,
                        rerunning = rerunning,
                        onSaveAll = {
                            // Everything to disk now, without waiting for the autosaves.
                            store.saveCanvas(Autosave(drawState.toDoc(), drawState.sourceAssetId))
                            store.saveAnimation(animateState.toDoc())
                            store.saveSequence(sequenceState.toDoc())
                            store.saveCompose(composeState.doc)
                            store.saveDevelop(developState.doc)
                            store.saveNodes(nodesState.top)
                            store.saveRouting(router.doc)
                            store.savePatch(patch.doc)
                        },
                    )
                    }
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
                                    router.disconnect("develop.in")
                                    developState.open(it)
                                    room = Room.Develop
                                }
                                recipe.fft?.let {
                                    router.disconnect("fft.in")
                                    fftState.edit.open(it)
                                    // A saved result opens as the result, not as the spectrum of its source.
                                    fftState.view = dk.cbavf.studio.fft.model.FftView.Result
                                    room = Room.Fft
                                }
                                recipe.glitch?.let {
                                    router.disconnect("glitch.in")
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
                        onSendToPhone = if (loop.connected) { a ->
                            loop.sendAsset(a)
                            Toast.makeText(context, "Sending to the other phone…", Toast.LENGTH_SHORT).show()
                        } else null,
                    )
                }
                }
            }
        }
        }
        }
        if (cheatSheet) dk.cbavf.studio.ui.gestures.GestureCheatSheet { cheatSheet = false }
        if (historyOpen) historyOf(room)?.let { dk.cbavf.studio.ui.HistoryPanel(room.title, it) { historyOpen = false } }
        // The debug terminal (standard out only) over whichever room is open, if switched on in Studio → Config.
        if (studioPrefs.config.profile.terminalVisible) dk.cbavf.studio.debug.DebugTerminal(
            onClose = { studioPrefs.config.updateProfile { it.copy(terminalVisible = false) } },
            modifier = Modifier.align(androidx.compose.ui.Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(0.38f),
        )
        }
    }
      }
}

/** Haptics switched off (Studio → Preferences). */
private object NoHaptics : androidx.compose.ui.hapticfeedback.HapticFeedback {
    override fun performHapticFeedback(hapticFeedbackType: androidx.compose.ui.hapticfeedback.HapticFeedbackType) = Unit
}
