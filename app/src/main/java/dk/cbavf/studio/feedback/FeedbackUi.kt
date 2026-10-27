package dk.cbavf.studio.feedback

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dk.cbavf.studio.studio.UserProfile
import dk.cbavf.studio.ui.ScrollRow

/** Asked once, the first time the studio opens: nothing is sent unless the person says yes. */
@Composable
fun FeedbackAskDialog() {
    if (!Feedback.shouldAsk) return
    AlertDialog(
        onDismissRequest = { Feedback.declineForNow() },
        title = { Text("Help improve the studio?") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("This studio can send the developer a small report about how it is used, so bugs can be found and fixed. It is off until you choose.", style = MaterialTheme.typography.bodyMedium)
                Text("Basic: ${FeedbackLevel.Basic.blurb}", style = MaterialTheme.typography.bodySmall)
                Text("Full: ${FeedbackLevel.Full.blurb}", style = MaterialTheme.typography.bodySmall)
                Text(
                    "Never sent: your pictures, projects, file names, text you typed (except a note you choose to send), name, or location. The report uses a random id that names no one. " +
                        "Studio → Config → Feedback shows exactly what would be sent, lets you change this at any time, and deletes everything held about this phone.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = { Feedback.setLevel(FeedbackLevel.Basic) }) { Text("Basic") }
                TextButton(onClick = { Feedback.setLevel(FeedbackLevel.Full) }) { Text("Full") }
            }
        },
        dismissButton = { TextButton(onClick = { Feedback.declineForNow() }) { Text("No thanks") } },
    )
}

/** Studio → Config → Feedback. */
@Composable
fun FeedbackSection(p: UserProfile, section: @Composable (String) -> Unit) {
    section("Feedback to the developer")
    if (!Feedback.availableInBuild) {
        Text("This edition never sends anything.", style = MaterialTheme.typography.labelMedium)
        return
    }
    val level = FeedbackLevel.of(p.feedbackLevel)
    Text(
        "Off by default. Switch it on and the studio sends small usage and error reports in the background, so bugs can be found without you writing them up.",
        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Send optional diagnostic data", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(
            checked = level != FeedbackLevel.Off,
            onCheckedChange = { on -> Feedback.setLevel(if (on) FeedbackLevel.Basic else FeedbackLevel.Off) },
        )
    }
    if (level != FeedbackLevel.Off) ScrollRow {
        listOf(FeedbackLevel.Basic, FeedbackLevel.Full).forEach { l ->
            FilterChip(selected = level == l, onClick = { Feedback.setLevel(l) }, label = { Text(l.title) })
        }
    }
    Text(level.blurb, style = MaterialTheme.typography.bodySmall)
    if (level != FeedbackLevel.Off) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Send only on Wi-Fi (not on mobile data)", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Switch(checked = p.feedbackWifiOnly, onCheckedChange = { Feedback.setWifiOnly(it) })
        }
    }
    if (Feedback.serverUrl.isBlank()) {
        Text("No feedback server is set in this build, so nothing can be sent.", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
    }
    if (Feedback.status.isNotBlank()) Text(Feedback.status, style = MaterialTheme.typography.labelMedium)
    Text(
        "Id: " + (p.installId.ifBlank { "none yet" }) + "  ·  waiting to send: ${Feedback.pending}",
        style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace,
    )

    var previewOpen by remember { mutableStateOf(false) }
    var noteOpen by remember { mutableStateOf(false) }
    var deleteOpen by remember { mutableStateOf(false) }
    ScrollRow {
        TextButton(onClick = { previewOpen = true }) { Text("See what would be sent") }
        TextButton(onClick = { Feedback.checkpoint(); Feedback.flush(force = true) }, enabled = level != FeedbackLevel.Off) { Text("Send now") }
        TextButton(onClick = { noteOpen = true }) { Text("Write a note…") }
    }
    ScrollRow {
        TextButton(onClick = { Feedback.resetId() }, enabled = p.installId.isNotBlank()) { Text("New random id") }
        TextButton(onClick = { deleteOpen = true }) { Text("Delete my data…") }
    }

    if (previewOpen) AlertDialog(
        onDismissRequest = { previewOpen = false },
        title = { Text("What would be sent") },
        text = {
            Surface(Modifier.fillMaxWidth().heightIn(max = 380.dp), tonalElevation = 1.dp) {
                Text(Feedback.preview(), Modifier.verticalScroll(rememberScrollState()).padding(8.dp), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            }
        },
        confirmButton = { TextButton(onClick = { previewOpen = false }) { Text("Close") } },
    )
    if (noteOpen) {
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { noteOpen = false },
            title = { Text("Write a note") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Sent as typed, with the app version and phone model, whatever the level above is. Say what you were doing and what went wrong.", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), minLines = 4)
                }
            },
            confirmButton = { TextButton(onClick = { if (Feedback.sendNote(text)) noteOpen = false }, enabled = text.isNotBlank()) { Text("Send") } },
            dismissButton = { TextButton(onClick = { noteOpen = false }) { Text("Cancel") } },
        )
    }
    if (deleteOpen) AlertDialog(
        onDismissRequest = { deleteOpen = false },
        title = { Text("Delete my data?") },
        text = { Text("Asks the server to delete every report held under this phone's id, empties anything waiting to be sent, and forgets the id. Feedback stays as you set it.") },
        confirmButton = { TextButton(onClick = { Feedback.deleteMyData(); deleteOpen = false }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { deleteOpen = false }) { Text("Cancel") } },
    )
}
