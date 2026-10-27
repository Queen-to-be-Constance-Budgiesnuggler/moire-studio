package dk.cbavf.studio.bridge

import dk.cbavf.studio.debug.Debug
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import dk.cbavf.studio.Incoming
import dk.cbavf.studio.bridge.model.Candidate
import dk.cbavf.studio.bridge.model.ExternalApp
import dk.cbavf.studio.bridge.model.Matcher
import dk.cbavf.studio.library.AssetRecord
import dk.cbavf.studio.library.ProjectStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import dk.cbavf.studio.foreground.Foreground
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Bridge actions: sending assets out, watching shared storage for what comes back, and
 * importing it into the Library with its parent. [onLineageChanged] reloads the Library.
 */
class BridgeController(
    val context: Context,
    private val store: ProjectStore,
    val state: BridgeState,
    private val scope: CoroutineScope,
    private val onLineageChanged: suspend () -> Unit,
) {
    private fun now() = System.currentTimeMillis() / 1000

    suspend fun lineageChanged() = onLineageChanged()

    fun refreshAccess() {
        state.hasMediaAccess =
            context.checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
    }

    fun scan() {
        if (!state.hasMediaAccess) return
        val doc = state.doc
        scope.launch {
            val found = withContext(Dispatchers.IO) {
                runCatching { scanNewImages(context, doc.watchSinceSec, doc.sends, doc.handled) }.getOrDefault(emptyList())
            }
            state.setInbox(found)
        }
    }

    fun send(asset: AssetRecord, app: ExternalApp) {
        if (sendAsset(context, store, asset, app)) {
            state.recordSend(app, asset.id, now())
        } else {
            toast(if (app.packages.isEmpty()) "No app on this phone could take this file." else "${app.label} is not installed or can't open this file.")
        }
    }

    fun launch(app: ExternalApp) {
        if (launchApp(context, app)) state.recordSend(app, null, now()) else toast(if (app.packages.isEmpty()) "No app on this phone could be opened." else "${app.label} is not installed.")
    }

    fun import(c: Candidate, parentId: String?) {
        state.handled(c)
        val operation = (c.match?.app ?: c.app)?.operation ?: ("external:" + c.folder.name.lowercase())
        scope.launch {
            val record = withContext(Dispatchers.IO) {
                runCatching { importImage(context, store, Uri.parse(c.uri), operation, parentId, Build.MODEL) }.getOrNull()
            }
            onLineageChanged()
            toast(if (record == null) "Could not import ${c.displayName}" else "Imported ${c.displayName}" + if (parentId != null) " as a child" else "")
        }
    }

    /**
     * Images shared into the studio. If the sharing app is one we sent to recently, the image
     * becomes a child of what was sent (only when the match is unambiguous).
     */
    fun receive(incoming: Incoming) {
        val app = ExternalApp.ofPackage(incoming.fromPackage)
        val (match, sure) = Matcher.match(now(), app, state.doc.sends)
        val parent = match?.assetId.takeIf { sure }
        val operation = app?.operation ?: "external:share"
        scope.launch {
            val records = withContext(Dispatchers.IO) {
                incoming.uris.mapNotNull { uri ->
                    runCatching { importImage(context, store, uri, operation, parent, Build.MODEL) }
                        .onFailure { Debug.w("Studio", "share import failed: $uri", it) }
                        .getOrNull()
                }
            }
            onLineageChanged()
            toast("Received ${records.size} of ${incoming.uris.size} from ${app?.label ?: incoming.fromPackage ?: "another app"}" +
                if (parent != null) " · child of your send" else "")
        }
    }

    private fun toast(message: String) = Toast.makeText(context, message, Toast.LENGTH_LONG).show()
}

/** Loads and autosaves Bridge state, rescans on media changes and on resume, and takes in shares. */
@OptIn(FlowPreview::class)
@Composable
fun BridgeEffects(bridge: BridgeController, store: ProjectStore, incoming: SnapshotStateList<Incoming>, onReceived: () -> Unit) {
    val state = bridge.state
    LaunchedEffect(store) {
        withContext(Dispatchers.IO) { store.loadBridge() }?.let(state::load)
        bridge.refreshAccess()
        snapshotFlow { state.doc }
            .drop(1)
            .debounce(500)
            .collect { withContext(Dispatchers.IO) { store.saveBridge(it) } }
    }

    // Watch while the app is open: initial scan, then every change to shared images (debounced,
    // since one save fires several notifications) and every change to the pending sends.
    LaunchedEffect(state.hasMediaAccess) {
        if (!state.hasMediaAccess) return@LaunchedEffect
        state.startWatchingIfNeeded(System.currentTimeMillis() / 1000)
        // Watched only while on screen: off screen the observer is removed, and the scan on return (ON_RESUME) catches up.
        Foreground.gate.started.collectLatest { onScreen ->
            if (!onScreen) return@collectLatest
            merge(flowOf(Unit), mediaChanges(bridge.context), folderChanges(), snapshotFlow { state.doc.sends }.drop(1))
                .debounce(800)
                .collect { bridge.scan() }
        }
    }

    // A finished screen recording lands in the Library, whichever room is open.
    LaunchedEffect(Unit) {
        ScreenRecordService.recording.collect { if (it is RecordingStatus.Saved && it.assetId != null) bridge.lineageChanged() }
    }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                bridge.refreshAccess()
                bridge.scan()
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(incoming.size) {
        if (incoming.isEmpty()) return@LaunchedEffect
        // Take the batch out first, so a share arriving meanwhile is never imported twice.
        val batch = incoming.toList()
        incoming.clear()
        batch.forEach(bridge::receive)
        onReceived()
    }
}
