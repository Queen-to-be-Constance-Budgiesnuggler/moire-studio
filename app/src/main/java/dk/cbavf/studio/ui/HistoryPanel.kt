package dk.cbavf.studio.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp

/**
 * What was done in a room, newest first. The current step is marked; steps that were undone stay in the
 * list, greyed, until something new is done. Tap any step to go back (or forward) to exactly that state.
 */
@Composable
fun HistoryPanel(title: String, history: HistoryView, onClose: () -> Unit) {
    val entries = history.timeline
    val here = history.position
    val list = rememberLazyListState()
    // Newest at the top, so the list opens on the current step.
    LaunchedEffect(here) { list.scrollToItem((entries.lastIndex - here).coerceAtLeast(0)) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("History · $title") },
        text = {
            LazyColumn(Modifier.heightIn(max = 380.dp), state = list) {
                itemsIndexed(entries.asReversed()) { reversed, label ->
                    val i = entries.lastIndex - reversed
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { history.jumpTo(i) }
                            .alpha(if (i > here) 0.45f else 1f)
                            .padding(vertical = 8.dp),
                    ) {
                        Text(if (i == here) "●" else "○", Modifier.padding(end = 10.dp), color = MaterialTheme.colorScheme.primary)
                        Text("$i  $label", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } },
    )
}
