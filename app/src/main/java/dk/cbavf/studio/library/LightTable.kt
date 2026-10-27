package dk.cbavf.studio.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import dk.cbavf.studio.ui.AssetImage
import dk.cbavf.studio.ui.ScrollRow
import kotlin.math.abs

/** Light table sort orders. */
enum class LibrarySort(val label: String) { Newest("Newest"), Oldest("Oldest"), Rating("Rating"), Kind("Kind") }

/** Light table filters: minimum stars, a flag (1 picks, −1 rejects) and a colour label. */
data class Marks(val minRating: Int = 0, val flag: Int? = null, val label: ColorLabel? = null) {
    fun admits(a: AssetRecord): Boolean =
        a.rating >= minRating && (flag == null || a.flag == flag) && (label == null || a.label == label)

    val active: Boolean get() = minRating > 0 || flag != null || label != null
}

fun List<AssetRecord>.sortedFor(sort: LibrarySort): List<AssetRecord> = when (sort) {
    LibrarySort.Newest -> sortedByDescending { it.createdAt }
    LibrarySort.Oldest -> sortedBy { it.createdAt }
    LibrarySort.Rating -> sortedWith(compareByDescending<AssetRecord> { it.rating }.thenByDescending { it.createdAt })
    LibrarySort.Kind -> sortedWith(compareBy<AssetRecord> { it.operation.substringBefore('.') }.thenByDescending { it.createdAt })
}

/** Stars, flag and colour label on a tile corner. */
@Composable
fun MarkBadges(a: AssetRecord, modifier: Modifier = Modifier) {
    if (a.rating == 0 && a.flag == 0 && a.label == null) return
    Surface(modifier, color = Color(0xCC111111), shape = MaterialTheme.shapes.extraSmall) {
        Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            if (a.rating > 0) Text("★".repeat(a.rating), color = Color(0xFFFFC94D), style = MaterialTheme.typography.labelSmall)
            if (a.flag == 1) Text("⚑", color = Color(0xFF6BD18A), style = MaterialTheme.typography.labelSmall)
            if (a.flag == -1) Text("✕", color = Color(0xFFFF6B6B), style = MaterialTheme.typography.labelSmall)
            a.label?.let { Box(Modifier.size(9.dp).background(Color(it.argb), CircleShape)) }
        }
    }
}

/** Rating, flag and colour label controls; [onMark] changes the records they apply to. */
@Composable
fun MarkControls(rating: Int, flag: Int, label: ColorLabel?, onMark: ((AssetRecord) -> AssetRecord) -> Unit) {
    ScrollRow {
        (1..5).forEach { n ->
            Text(
                if (n <= rating) "★" else "☆",
                Modifier.clickable { onMark { it.copy(rating = if (it.rating == n) 0 else n) } }.padding(horizontal = 4.dp, vertical = 6.dp),
                color = Color(0xFFFFC94D),
                style = MaterialTheme.typography.titleLarge,
            )
        }
        FilterChip(selected = flag == 1, onClick = { onMark { it.copy(flag = if (it.flag == 1) 0 else 1) } }, label = { Text("⚑ Pick") })
        FilterChip(selected = flag == -1, onClick = { onMark { it.copy(flag = if (it.flag == -1) 0 else -1) } }, label = { Text("✕ Reject") })
        ColorLabel.entries.forEach { c ->
            Box(
                Modifier
                    .padding(horizontal = 3.dp)
                    .size(26.dp)
                    .background(Color(c.argb), CircleShape)
                    .border(if (label == c) 3.dp else 0.dp, Color.White, CircleShape)
                    .clickable { onMark { it.copy(label = if (it.label == c) null else c) } }
            )
        }
    }
}

/**
 * Loupe: one image full screen; swipe sideways for the next or previous one in [list], pinch to
 * zoom (double-tap resets), mark it below. Info opens its detail page.
 */
@Composable
fun Loupe(
    store: ProjectStore,
    list: List<AssetRecord>,
    current: AssetRecord,
    onShow: (AssetRecord) -> Unit,
    onMark: (Set<String>, (AssetRecord) -> AssetRecord) -> Unit,
    onInfo: () -> Unit,
    onClose: () -> Unit,
) {
    BackHandler(onBack = onClose)
    val i = list.indexOfFirst { it.id == current.id }
    var zoom by remember(current.id) { mutableFloatStateOf(1f) }
    var panX by remember(current.id) { mutableFloatStateOf(0f) }
    var panY by remember(current.id) { mutableFloatStateOf(0f) }
    Column(Modifier.fillMaxSize().background(Color(0xFF0E0E10))) {
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .pointerInput(current.id, list.size) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        var swipe = 0f
                        var pinched = false
                        var lastTap = down.uptimeMillis
                        do {
                            val e = awaitPointerEvent()
                            val fingers = e.changes.count { it.pressed }
                            val z = e.calculateZoom()
                            val p = e.calculatePan()
                            if (fingers >= 2 || zoom > 1.01f) {
                                if (fingers >= 2) pinched = true
                                zoom = (zoom * z).coerceIn(1f, 12f)
                                panX += p.x
                                panY += p.y
                                if (zoom <= 1.01f) { panX = 0f; panY = 0f }
                            } else {
                                swipe += p.x
                            }
                            e.changes.forEach { it.consume() }
                        } while (e.changes.any { it.pressed })
                        // A sideways swipe at normal size moves through the list.
                        if (!pinched && zoom <= 1.01f && abs(swipe) > size.width / 6f) {
                            val next = if (swipe < 0) i + 1 else i - 1
                            list.getOrNull(next)?.let(onShow)
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            AssetImage(
                store.fileOf(current), maxPx = 2560,
                modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = zoom; scaleY = zoom; translationX = panX; translationY = panY },
                filter = if (zoom > 2f) FilterQuality.None else FilterQuality.Low,
            )
            Surface(Modifier.align(Alignment.TopStart).padding(8.dp), color = Color(0xAA000000), shape = MaterialTheme.shapes.small) {
                Text(
                    "${i + 1} / ${list.size} · ${current.widthPx}×${current.heightPx} · ${current.operation}" + if (zoom > 1.01f) " · ${"%.1f".format(zoom)}×" else "",
                    Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
        Surface(tonalElevation = 2.dp) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
                MarkControls(current.rating, current.flag, current.label) { f -> onMark(setOf(current.id), f) }
                ScrollRow {
                    TextButton(onClick = { list.getOrNull(i - 1)?.let(onShow) }, enabled = i > 0) { Text("◀ Previous") }
                    TextButton(onClick = { list.getOrNull(i + 1)?.let(onShow) }, enabled = i < list.lastIndex) { Text("Next ▶") }
                    TextButton(onClick = { zoom = 1f; panX = 0f; panY = 0f }, enabled = zoom > 1.01f) { Text("Fit") }
                    TextButton(onClick = onInfo) { Text("Info · Use in…") }
                    TextButton(onClick = onClose) { Text("Grid") }
                }
            }
        }
    }
}

/** Two images side by side (stacked on a portrait screen), zoomed and panned together. */
@Composable
fun Compare(store: ProjectStore, a: AssetRecord, b: AssetRecord, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    var zoom by remember { mutableFloatStateOf(1f) }
    var panX by remember { mutableFloatStateOf(0f) }
    var panY by remember { mutableFloatStateOf(0f) }
    var swapped by remember { mutableStateOf(false) }
    val (first, second) = if (swapped) b to a else a to b
    Column(Modifier.fillMaxSize().background(Color(0xFF0E0E10))) {
        Column(
            Modifier.weight(1f).fillMaxWidth().pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown()
                    do {
                        val e = awaitPointerEvent()
                        zoom = (zoom * e.calculateZoom()).coerceIn(1f, 12f)
                        val p = e.calculatePan()
                        panX += p.x
                        panY += p.y
                        if (zoom <= 1.01f) { panX = 0f; panY = 0f }
                        e.changes.forEach { it.consume() }
                    } while (e.changes.any { it.pressed })
                }
            },
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            listOf(first, second).forEach { r ->
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    AssetImage(
                        store.fileOf(r), maxPx = 2048,
                        modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = zoom; scaleY = zoom; translationX = panX; translationY = panY },
                        filter = if (zoom > 2f) FilterQuality.None else FilterQuality.Low,
                    )
                    Surface(Modifier.align(Alignment.TopStart).padding(6.dp), color = Color(0xAA000000), shape = MaterialTheme.shapes.small) {
                        Text("${r.id.takeLast(6)} · ${r.operation}", Modifier.padding(horizontal = 6.dp), color = Color.White, style = MaterialTheme.typography.labelSmall)
                    }
                    MarkBadges(r, Modifier.align(Alignment.TopEnd).padding(6.dp))
                }
            }
        }
        Surface(tonalElevation = 2.dp) {
            ScrollRow {
                TextButton(onClick = { swapped = !swapped }) { Text("Swap") }
                TextButton(onClick = { zoom = 1f; panX = 0f; panY = 0f }) { Text("Fit both") }
                TextButton(onClick = onClose) { Text("Done") }
            }
        }
    }
}
