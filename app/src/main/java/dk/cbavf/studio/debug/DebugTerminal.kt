package dk.cbavf.studio.debug

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * A terminal for [Debug]: standard out only. It scrolls to the newest line, and has no input field and
 * no keyboard. Clear empties it, Close hides it (Studio → Config shows it again).
 */
@Composable
fun DebugTerminal(onClose: () -> Unit, modifier: Modifier = Modifier) {
    val version = Debug.version
    val lines = remember(version) { Debug.log.lines() }
    val list = rememberLazyListState()
    LaunchedEffect(lines.size) { if (lines.isNotEmpty()) list.scrollToItem(lines.lastIndex) }
    Column(modifier.background(Color(0xE8000000))) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "stdout · level ${Debug.level} · ${lines.size} lines",
                Modifier.weight(1f),
                color = Color(0xFF8BE28B), fontFamily = FontFamily.Monospace, fontSize = 11.sp,
            )
            TextButton(onClick = Debug::clear) { Text("Clear", color = Color(0xFFCCCCCC)) }
            TextButton(onClick = onClose) { Text("Close", color = Color(0xFFCCCCCC)) }
        }
        if (lines.isEmpty()) {
            Text(
                if (Debug.level == 0) "Debug output is off. Studio → Config → Developer sets the level." else "No output yet.",
                Modifier.padding(8.dp), color = Color(0xFF888888), fontFamily = FontFamily.Monospace, fontSize = 11.sp,
            )
        }
        LazyColumn(state = list, modifier = Modifier.padding(horizontal = 8.dp)) {
            items(lines) { line ->
                // "HH:MM:SS.mmm L/tag: message": the letter after the time is the level.
                val color = when (line.getOrNull(13)) {
                    'E' -> Color(0xFFFF7B72)
                    'T' -> Color(0xFF8B949E)
                    else -> Color(0xFFE6EDF3)
                }
                Text(line, color = color, fontFamily = FontFamily.Monospace, fontSize = 10.sp, lineHeight = 13.sp)
            }
        }
    }
}
