package dk.cbavf.studio.bridge

import android.Manifest
import android.app.Activity
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dk.cbavf.studio.bridge.model.Candidate
import dk.cbavf.studio.bridge.model.ExternalApp
import dk.cbavf.studio.bridge.model.PendingSend
import dk.cbavf.studio.library.Lineage
import dk.cbavf.studio.ui.ScrollRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

@Composable
fun BridgeRoom(
    state: BridgeState,
    lineage: Lineage,
    onPermissionResult: () -> Unit,
    onLaunch: (ExternalApp) -> Unit,
    onImport: (Candidate, parentId: String?) -> Unit,
) {
    val context = LocalContext.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { onPermissionResult() }
    val recording by ScreenRecordService.recording.collectAsState()
    val capture = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val data = r.data
        if (r.resultCode == Activity.RESULT_OK && data != null) ScreenRecordService.start(context, r.resultCode, data)
    }
    val installed = ExternalApp.entries.filter { it != ExternalApp.Other && installedPackage(context, it) != null }

    Column(Modifier.fillMaxSize()) {
        Surface(tonalElevation = 2.dp) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                val style = MaterialTheme.typography.labelMedium
                if (!state.hasMediaAccess) {
                    Text("The inbox needs access to photos to see what other apps save.", style = style)
                    TextButton(onClick = { permission.launch(Manifest.permission.READ_MEDIA_IMAGES) }) { Text("Allow photo access") }
                } else {
                    Text(
                        "Watching new photos since ${time(state.doc.watchSinceSec)}. Choose \"Allow all\" access: " +
                            "with only selected photos, new images from other apps stay invisible.",
                        style = style,
                    )
                }
                ScrollRow {
                    installed.forEach { app -> TextButton(onClick = { onLaunch(app) }) { Text("Open ${app.label}") } }
                    if (installed.isEmpty()) Text("Gallery, Notes and FV-5 are not installed on this phone.", style = style)
                }
                Text(
                    "Send an image from Library (Send to…), edit it there, save; it shows up below matched to what you sent.",
                    style = style,
                )
                ScrollRow {
                    when (val rec = recording) {
                        is RecordingStatus.Recording -> {
                            Text("● Recording since ${time(rec.sinceMs / 1000)}", style = style, color = MaterialTheme.colorScheme.error)
                            TextButton(onClick = { ScreenRecordService.stop(context) }) { Text("Stop") }
                        }
                        else -> {
                            TextButton(onClick = {
                                val mpm = context.getSystemService(MediaProjectionManager::class.java)
                                capture.launch(mpm.createScreenCaptureIntent())
                            }) { Text("Record screen") }
                            if (rec is RecordingStatus.Saved) Text(rec.message, style = style)
                        }
                    }
                }
            }
        }
        Sends(state, lineage)
        HorizontalDivider()
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Inbox · ${state.inbox.size}", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            val sure = state.inbox.filter { it.sure && it.match?.assetId != null }
            TextButton(onClick = { sure.forEach { onImport(it, it.match?.assetId) } }, enabled = sure.isNotEmpty()) {
                Text("Import ${sure.size} matched")
            }
            TextButton(onClick = state::clearInbox, enabled = state.inbox.isNotEmpty()) { Text("Clear") }
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(state.inbox.reversed(), key = { it.mediaId }) { c -> InboxRow(c, lineage, state, onImport) }
        }
    }
}

@Composable
private fun Sends(state: BridgeState, lineage: Lineage) {
    val sends = state.doc.sends
    if (sends.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Waiting for", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            TextButton(onClick = state::clearSends) { Text("Forget") }
        }
        sends.sortedByDescending { it.sentAtSec }.forEach { s ->
            Text("${s.app.label} · ${what(s, lineage)} · ${time(s.sentAtSec)}", style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun InboxRow(c: Candidate, lineage: Lineage, state: BridgeState, onImport: (Candidate, String?) -> Unit) {
    val context = LocalContext.current
    val thumb by produceState<ImageBitmap?>(null, c.uri) {
        value = withContext(Dispatchers.IO) {
            runCatching { context.contentResolver.loadThumbnail(Uri.parse(c.uri), Size(240, 240), null).asImageBitmap() }.getOrNull()
        }
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(72.dp)) {
            thumb?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        }
        Column(Modifier.weight(1f)) {
            val style = MaterialTheme.typography.labelMedium
            Text(c.displayName, style = style)
            Text("${c.relativePath.trimEnd('/')} · ${c.app?.label ?: c.ownerPackage ?: "unknown app"} · ${time(c.addedAtSec)}", style = style)
            val match = c.match
            Text(
                when {
                    match == null -> "No send in the last 30 min: imports as a new original."
                    c.sure -> "From your ${match.app.label} send of ${what(match, lineage)}."
                    else -> "Maybe from your ${match.app.label} send of ${what(match, lineage)} — please confirm."
                },
                style = style,
                color = if (match != null && !c.sure) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurface,
            )
            ScrollRow {
                val parent = match?.assetId?.takeIf { lineage[it] != null }
                if (parent != null) TextButton(onClick = { onImport(c, parent) }) { Text("Import as child") }
                TextButton(onClick = { onImport(c, null) }) { Text(if (parent != null) "As new" else "Import") }
                TextButton(onClick = { state.handled(c) }) { Text("Dismiss") }
            }
        }
    }
}

private fun what(s: PendingSend, lineage: Lineage): String {
    val id = s.assetId ?: return "app opened"
    return "g${lineage.generation(id)} ${id.takeLast(6)}"
}

private fun time(sec: Long): String =
    if (sec <= 0) "now" else DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(sec * 1000))
