package dk.cbavf.studio.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dk.cbavf.studio.library.AssetRecord
import dk.cbavf.studio.library.Lineage
import dk.cbavf.studio.library.ProjectStore

/** Grid of Library stills; tap in order to pick ([single]: the first tap picks). */
@Composable
fun LibraryPicker(store: ProjectStore, lineage: Lineage, single: Boolean = false, onDone: (List<String>) -> Unit) {
    var picked by remember { mutableStateOf(listOf<String>()) }
    val assets = lineage.newestFirst().filter(AssetRecord::isStill)
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { onDone(emptyList()) }) { Text("Cancel") }
            Text(if (single) "Tap an image" else "Tap images in the order to add them", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
            if (!single) TextButton(onClick = { onDone(picked) }, enabled = picked.isNotEmpty()) { Text("Add ${picked.size}") }
        }
        LazyVerticalGrid(columns = GridCells.Adaptive(96.dp), modifier = Modifier.fillMaxSize().padding(4.dp)) {
            items(assets, key = { it.id }) { a ->
                val n = picked.indexOf(a.id)
                Box(
                    Modifier
                        .padding(4.dp)
                        .border(if (n >= 0) 3.dp else 0.dp, MaterialTheme.colorScheme.primary)
                        .clickable { if (single) onDone(listOf(a.id)) else picked = if (n >= 0) picked - a.id else picked + a.id }
                ) {
                    AssetImage(store.fileOf(a), maxPx = 300, modifier = Modifier.fillMaxWidth().aspectRatio(a.aspect))
                    if (n >= 0) {
                        Surface(Modifier.align(Alignment.TopEnd).padding(4.dp), color = MaterialTheme.colorScheme.primary, shape = MaterialTheme.shapes.extraSmall) {
                            Text("${n + 1}", Modifier.padding(horizontal = 6.dp), style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
    }
}
