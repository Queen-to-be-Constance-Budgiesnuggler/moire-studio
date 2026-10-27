package dk.cbavf.studio.loop

import dk.cbavf.studio.debug.Debug
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import dk.cbavf.studio.camera.CameraController
import dk.cbavf.studio.camera.CameraState
import dk.cbavf.studio.camera.Developed
import dk.cbavf.studio.camera.develop
import dk.cbavf.studio.camera.saveJpeg
import dk.cbavf.studio.foreground.RunGate
import dk.cbavf.studio.library.Lineage
import dk.cbavf.studio.library.ProjectStore
import dk.cbavf.studio.library.renderBitmap
import dk.cbavf.studio.link.Link
import dk.cbavf.studio.link.LinkConnection
import dk.cbavf.studio.link.LinkMessage
import dk.cbavf.studio.link.LoopMode
import dk.cbavf.studio.link.LoopRole
import dk.cbavf.studio.patch.model.SourceKind
import dk.cbavf.studio.sequence.model.AssetSource
import dk.cbavf.studio.sequence.model.CanvasSource
import dk.cbavf.studio.sequence.model.SeqSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import android.widget.Toast
import dk.cbavf.studio.library.AssetRecord
import dk.cbavf.studio.link.TransferKind
import dk.cbavf.studio.sync.AssetBundle
import dk.cbavf.studio.sync.IncomingFiles
import dk.cbavf.studio.sync.ProjectZip
import dk.cbavf.studio.sync.Transfers
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import kotlin.random.Random

/** Where the projector's next frame comes from. */
enum class LoopSource {
    /** Show each capture as the next frame: generation after generation (video feedback). */
    Feedback,

    /** Step through the Sequence timeline. */
    Sequence,
}

/**
 * The two-phone loop (spec §3 Loop). The projector phone shows a frame full screen, the camera
 * phone captures it, and the capture comes back over the link to be filed as the next
 * generation, parent = the frame that was on screen.
 * When the studio leaves the screen the loop stops and the link is closed (the other phone sees it drop); when it comes back
 * the link is made again the same way, waiting or connecting.
 */
class LoopEngine(
    private val context: Context,
    private val store: ProjectStore,
    private val scope: CoroutineScope,
    private val cameraState: CameraState,
    private val camera: CameraController,
    /** The frames the projector can start from or step through (the Sequence timeline). */
    private val sequenceSources: () -> List<SeqSource>,
    private val lineage: () -> Lineage,
    private val onLineageChanged: suspend () -> Unit,
    /** The role and host kept in `user.profile`, and how to keep a change there. */
    initialRole: String = "",
    initialHost: String = "",
    private val saveSettings: (role: String, host: String) -> Unit = { _, _ -> },
    /** The picture patched into the Loop unit's Projector input, filed in the Library; null when nothing is patched. */
    private val routedFrame: suspend () -> String? = { null },
) : RunGate.Hook {
    var role by mutableStateOf(runCatching { LoopRole.valueOf(initialRole) }.getOrDefault(LoopRole.Projector))
    var host by mutableStateOf(initialHost)
    var mode by mutableStateOf(LoopMode.Synced)
    var source by mutableStateOf(LoopSource.Feedback)
    var settleMs by mutableLongStateOf(600L)
    var intervalMs by mutableLongStateOf(2000L)
    var generations by mutableIntStateOf(10)
    var strobe by mutableStateOf(false)

    /** Library image the projector starts from (Library → Use in Loop); otherwise the Sequence. */
    var startAssetId by mutableStateOf<String?>(null)

    var status by mutableStateOf("Not connected")
        private set
    var connected by mutableStateOf(false)
        private set
    var running by mutableStateOf(false)
        private set
    var peerDevice by mutableStateOf<String?>(null)
        private set
    var done by mutableIntStateOf(0)
        private set

    /** The other phone's sensors, for remote patch routes. */
    var remoteSensors by mutableStateOf<Map<SourceKind, Float>>(emptyMap())
        private set

    /** Shares this phone's sensors with the other one (no-op when not connected). */
    suspend fun sendSensors(values: Map<SourceKind, Float>) {
        val conn = connection ?: return
        runCatching { conn.send(LinkMessage.Sensors(values)) }
    }

    /** Node Lab: the latest picture from the other phone (a sent frame or a Loop capture). */
    var remoteFrame by mutableStateOf<Bitmap?>(null)
        private set

    /** How many pictures have come from the other phone (From Other Phone's Count). */
    var remoteFrames by mutableIntStateOf(0)
        private set
    private var frameSeq = 0

    /** Sends a picture to the other phone's From Other Phone node; false when not linked or the send failed. */
    suspend fun sendFrame(bitmap: Bitmap, png: Boolean): Boolean {
        val conn = connection ?: return false
        val bytes = withContext(Dispatchers.Default) {
            java.io.ByteArrayOutputStream().use { out ->
                bitmap.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 92, out)
                out.toByteArray()
            }
        }
        return runCatching { conn.send(LinkMessage.Frame(++frameSeq, bitmap.width, bitmap.height), bytes) }.isSuccess
    }

    /** Decodes a received picture at no more than [REMOTE_MAX_PX] on its long side. */
    private suspend fun receivePicture(bytes: ByteArray) {
        val bmp = withContext(Dispatchers.Default) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= REMOTE_MAX_PX) sample *= 2
            runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) }.getOrNull()
        } ?: return
        remoteFrame = bmp
        remoteFrames++
    }

    /** Projector: what is on screen. */
    var shown by mutableStateOf<ImageBitmap?>(null)
        private set
    private var shownAssetId: String? = null

    private var connection: LinkConnection? = null
    private var server: ServerSocket? = null
    private var linkJob: Job? = null
    private var runJob: Job? = null
    private var strobeJob: Job? = null
    private var pending: Pair<Int, CompletableDeferred<String?>>? = null
    private var lastShowId: String? = null

    /** This phone's IPv4 addresses, Tailscale (100.x) first, to type on the other phone. */
    fun ownAddresses(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .map { it.hostAddress!! }
            .sortedBy { if (it.startsWith("100.")) 0 else 1 }
    }.getOrDefault(emptyList())

    /** Waits for the other phone ([host] empty) or connects to it. */
    fun connect() {
        disconnect()
        saveSettings(role.name, host)
        linkJob = scope.launch {
            try {
                val conn = if (host.isBlank()) {
                    status = "Waiting for the other phone on ${ownAddresses().firstOrNull() ?: "?"}:${Link.PORT}…"
                    val s = withContext(Dispatchers.IO) { Link.listen() }
                    server = s
                    Link.accept(s).also { s.close() }
                } else {
                    status = "Connecting to $host…"
                    Link.connect(host.trim())
                }
                connection = conn
                connected = true
                withContext(Dispatchers.IO) { incoming.prune() }
                conn.send(LinkMessage.Hello(Build.MODEL, role))
                status = "Connected to ${conn.peer}"
                while (isActive) {
                    val (message, payload) = conn.receive()
                    handle(message, payload)
                }
            } catch (e: java.io.EOFException) {
                status = if (done > 0) "The other phone disconnected after $done captures." else "The other phone disconnected."
            } catch (e: Exception) {
                Debug.i("Studio", "loop link ended", e)
                if (connected || status.startsWith("Connecting")) status = "Disconnected: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                connected = false
                stopLocal()
                if (sendJob?.isActive == true) {
                    sendJob?.cancel()
                    transferStatus = "The link dropped. Connect and send again to go on."
                }
            }
        }
    }

    /** True when the link was up, or waiting, as the studio left the screen: it is made again when the studio is back. */
    private var relink = false

    /** The studio left the screen: the loop, the torch and the link stop, so no socket or camera work runs in the background. */
    override fun onStop() {
        pauseTransfers()
        relink = connected || linkJob?.isActive == true
        if (!relink) return
        Debug.i("Studio", "loop link closed: the studio is off screen")
        disconnect()
        status = "The link was closed while the studio was off screen."
    }

    /** The studio is back: a link that was closed on stop is made again. */
    override fun onStart() {
        if (!relink) return
        relink = false
        connect()
    }

    fun disconnect() {
        stopLocal()
        sendJob?.cancel()
        sendJob = null
        connection?.close()
        server?.close()
        connection = null
        server = null
        linkJob?.cancel()
        connected = false
        status = "Not connected"
    }

    private suspend fun handle(message: LinkMessage, payload: ByteArray?) {
        when (message) {
            is LinkMessage.Hello -> {
                peerDevice = message.device
                status = if (message.role == role) {
                    "Both phones are set to ${role.name}: change one of them."
                } else {
                    "Connected to ${message.device} (${message.role.name.lowercase()})"
                }
            }
            is LinkMessage.Start -> if (role == LoopRole.Camera) startCamera(message)
            is LinkMessage.Show -> if (role == LoopRole.Camera) {
                lastShowId = message.assetId
                if (running && mode == LoopMode.Synced) captureAndSend(message.seq, message.assetId)
            }
            is LinkMessage.Captured -> if (role == LoopRole.Projector && payload != null) {
                val record = withContext(Dispatchers.IO) {
                    runCatching {
                        saveJpeg(
                            context, store, Developed(payload, message.widthPx, message.heightPx), message.info,
                            "loop.capture", peerDevice ?: "camera phone", listOfNotNull(message.info.projectedAssetId),
                        )
                    }.onFailure { Debug.w("Studio", "loop ingest", it) }.getOrNull()
                }
                onLineageChanged()
                receivePicture(payload)
                done++
                pending?.takeIf { it.first == message.seq }?.second?.complete(record?.id)
                if (mode == LoopMode.FreeRun && source == LoopSource.Feedback && record != null) feedbackNext = record.id
            }
            is LinkMessage.Sensors -> remoteSensors = message.values
            is LinkMessage.Frame -> payload?.let { receivePicture(it) }
            LinkMessage.Stop -> stopLocal()
            is LinkMessage.Offer, is LinkMessage.Accept, is LinkMessage.Chunk, is LinkMessage.End, is LinkMessage.Result ->
                transfers.handle(message, payload)
        }
    }

    // --- Sending assets and projects ---------------------------------------------------------

    private val incoming = IncomingFiles(File(context.filesDir, "incoming"))
    private val transfers = Transfers(
        incoming,
        send = { m, p -> (connection ?: error("Not connected")).send(m, p) },
        onReceived = ::saveReceived,
    )
    private var sendJob: Job? = null

    /** What is being sent, with how far it got; null when nothing is. */
    var sending by mutableStateOf<String?>(null)
        private set

    /** How the last send or receive went. */
    var transferStatus by mutableStateOf("")
        private set

    /** Sends a Library asset (with its recipe) to the other phone's Library. */
    fun sendAsset(record: AssetRecord) = startSend("picture ${record.id.takeLast(6)}") {
        val bundle = File(store.tempDir, "outgoing-asset.zip")
        withContext(Dispatchers.IO) { AssetBundle.pack(store, record, bundle) }
        bundle to (TransferKind.Asset to record.id)
    }

    /** Sends this whole project; the other phone keeps it as a new project. */
    fun sendProject() = startSend("project ${store.dir.name}") {
        val zip = File(store.tempDir, "outgoing-project.zip")
        withContext(Dispatchers.IO) { zip.outputStream().use { ProjectZip.pack(store.dir, it) } }
        zip to (TransferKind.Project to store.dir.name)
    }

    /** Stops a send (the studio went to the background); the next send of the same thing goes on from where it stopped. */
    fun pauseTransfers() {
        if (sendJob?.isActive == true) {
            sendJob?.cancel()
            transferStatus = "Sending paused. Send again to go on."
        }
    }

    private fun startSend(what: String, pack: suspend () -> Pair<File, Pair<TransferKind, String>>) {
        if (connection == null) return run { transferStatus = "Connect to the other phone first." }
        if (sendJob?.isActive == true) return run { transferStatus = "Wait for the send in progress to finish." }
        sendJob = scope.launch {
            var file: File? = null
            try {
                sending = "Packing the $what…"
                val (f, kindName) = pack()
                file = f
                val outcome = transfers.send(f, kindName.first, kindName.second) { done, total ->
                    sending = "Sending the $what: ${percent(done, total)} %"
                }
                toast(outcome.text, Toast.LENGTH_SHORT)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Debug.w("Studio", "link send", e)
                transferStatus = "Send failed: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                sending = null
                file?.let { withContext(NonCancellable + Dispatchers.IO) { it.delete() } }
            }
        }
    }

    /** A file from the other phone arrived whole: file it, and say where it went. */
    private suspend fun saveReceived(kind: TransferKind, name: String, file: File): String {
        // What this phone says, and what the sender is told.
        val (here, there) = when (kind) {
            TransferKind.Asset -> {
                val record = withContext(Dispatchers.IO) { AssetBundle.unpack(file, store) }
                onLineageChanged()
                if (record == null) "The other phone sent a picture the Library already has." to "The other phone's Library already has it."
                else "Got a picture from the other phone. It is in the Library." to "Saved in the other phone's Library."
            }
            TransferKind.Project -> {
                val n = withContext(Dispatchers.IO) { file.inputStream().use { ProjectZip.unpack(it, File(context.filesDir, "projects"), name) } }
                "Got project \"$n\" from the other phone. Open it in Studio, File." to "Saved on the other phone as project \"$n\"."
            }
        }
        toast(here, Toast.LENGTH_LONG)
        return there
    }

    /**
     * Shows [text] as the transfer status and a toast. The link's message loop runs in [scope]
     * (the studio's, on Main today), but the toast is posted to Main explicitly so it never
     * depends on which dispatcher called it.
     */
    private suspend fun toast(text: String, length: Int) = withContext(Dispatchers.Main) {
        transferStatus = text
        Toast.makeText(context, text, length).show()
    }

    // --- Projector ---------------------------------------------------------------------------

    @Volatile
    private var feedbackNext: String? = null

    /** Starts the loop from the projector phone. */
    fun start() {
        val conn = connection ?: return
        if (role != LoopRole.Projector || running) return
        running = true
        done = 0
        runJob = scope.launch {
            val first = LoopFrames.first(
                routedFrame(),
                startAssetId?.takeIf { lineage()[it] != null },
                sequenceSources().firstOrNull(),
                lineage().newestFirst().firstOrNull { it.isStill }?.id,
            )
            if (first == null) {
                status = "Nothing to show: add an image to the Sequence or the Library first."
                running = false
                return@launch
            }
            conn.send(LinkMessage.Start(mode, intervalMs, strobe))
            var current: SeqSource = first
            var step = 0
            try {
                for (seq in 0 until generations) {
                    if (!running) break
                    show(current)
                    status = "Generation ${seq + 1}/$generations"
                    current = if (mode == LoopMode.Synced) {
                        delay(settleMs)
                        val waiter = CompletableDeferred<String?>()
                        pending = seq to waiter
                        conn.send(LinkMessage.Show(seq, shownAssetId))
                        val captured = withTimeoutOrNull(CAPTURE_TIMEOUT_MS) { waiter.await() }
                        if (captured == null) {
                            status = "No capture came back for generation ${seq + 1}."
                            break
                        }
                        next(captured, ++step)
                    } else {
                        conn.send(LinkMessage.Show(seq, shownAssetId))
                        delay(intervalMs)
                        next(feedbackNext, ++step)
                    }
                }
                if (running) status = "Done: $done captures filed in the Library."
            } finally {
                runCatching { conn.send(LinkMessage.Stop) }
                running = false
            }
        }
    }

    /** The frame after a capture: the capture itself (feedback), the rack's picture, or the next Sequence item. */
    private suspend fun next(capturedId: String?, step: Int): SeqSource {
        val routed = if (LoopFrames.showsCapture(source, capturedId)) null else routedFrame()
        return LoopFrames.next(source, capturedId, routed, sequenceSources(), step, shownAssetId)
    }

    private suspend fun show(s: SeqSource) {
        shownAssetId = (s as? AssetSource)?.assetId
        shown = withContext(Dispatchers.Default) { decodeForScreen(s) }
    }

    private fun decodeForScreen(s: SeqSource): ImageBitmap? = when (s) {
        is CanvasSource -> renderBitmap(s.doc).asImageBitmap()
        is AssetSource -> lineage()[s.assetId]?.let { record ->
            val file = store.fileOf(record)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            val screen = context.resources.displayMetrics.let { maxOf(it.widthPixels, it.heightPixels) }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= screen) sample *= 2
            BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
        }
    }

    fun stop() {
        scope.launch { runCatching { connection?.send(LinkMessage.Stop) } }
        stopLocal()
    }

    private fun stopLocal() {
        running = false
        runJob?.cancel()
        strobeJob?.cancel()
        pending?.second?.complete(null)
        pending = null
        runCatching { camera.torch(false) }
    }

    // --- Camera ------------------------------------------------------------------------------

    private fun startCamera(start: LinkMessage.Start) {
        mode = start.mode
        running = true
        done = 0
        status = "Loop running (${start.mode})"
        if (start.strobe) {
            // Low-rate, jittery torch flicker: short flashes at irregular gaps.
            strobeJob = scope.launch {
                while (isActive && running) {
                    camera.torch(true)
                    delay(Random.nextLong(60, 250))
                    camera.torch(false)
                    delay(Random.nextLong(200, 1200))
                }
            }
        }
        if (start.mode == LoopMode.FreeRun) {
            runJob = scope.launch {
                var seq = 0
                while (isActive && running) {
                    captureAndSend(seq++, lastShowId)
                    delay(start.intervalMs)
                }
            }
        }
    }

    private suspend fun captureAndSend(seq: Int, projectedAssetId: String?) {
        val conn = connection ?: return
        val spec = cameraState.spec ?: return run { status = "Camera is not open." }
        runCatching {
            val shots = camera.capture(cameraState.burst, raw = false)
            val info = cameraState.info(projectedAssetId)
            val developed = withContext(Dispatchers.Default) { develop(spec, shots, info.stack, cameraState.half) }
            conn.send(LinkMessage.Captured(seq, info, developed.widthPx, developed.heightPx), developed.jpeg)
            done++
            status = "Sent capture ${seq + 1}"
        }.onFailure {
            Debug.w("Studio", "loop capture", it)
            status = "Capture failed: ${it.message}"
        }
    }

    private fun percent(done: Long, total: Long) = if (total <= 0) 100 else (done * 100 / total).toInt()

    companion object {
        private const val CAPTURE_TIMEOUT_MS = 30_000L
        private const val REMOTE_MAX_PX = 2048
    }
}
