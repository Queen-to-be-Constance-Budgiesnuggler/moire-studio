@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package dk.cbavf.studio.library

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dk.cbavf.studio.bridge.model.ExternalApp
import dk.cbavf.studio.ui.AssetImage
import dk.cbavf.studio.ui.LibraryPicker
import dk.cbavf.studio.ui.Room
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToLong

@Composable
fun LibraryRoom(
    nav: LibraryNav,
    store: ProjectStore,
    lineage: Lineage,
    onChanged: suspend () -> Unit,
    onOpen: (AssetRecord) -> Unit,
    onSend: (AssetRecord, ExternalApp) -> Unit,
    /** Sends images into a room: placed in Draw, frames in Animate, items in Sequence, … */
    onUseIn: (List<AssetRecord>, Room) -> Unit,
    /** Sends an asset to the other phone over the Loop link; null when no phone is linked. */
    onSendToPhone: ((AssetRecord) -> Unit)? = null,
) {
    // Filter: a kind ("draw", "camera", …) or "#tag"; null shows everything.
    val selected = nav.selectedId?.let { lineage[it] }
    val scope = rememberCoroutineScope()
    val mark: (Set<String>, (AssetRecord) -> AssetRecord) -> Unit = { ids, f ->
        scope.launch {
            withContext(Dispatchers.IO) { store.updateMany(ids, f) }
            onChanged()
        }
    }
    val compare = nav.compare?.let { (a, b) -> lineage[a]?.let { x -> lineage[b]?.let { y -> x to y } } }
    val loupe = nav.loupeId?.let { lineage[it] }
    if (compare != null) {
        Compare(store, compare.first, compare.second, onClose = { nav.compare = null })
    } else if (selected == null && loupe != null) {
        Loupe(
            store, remember(lineage, nav.filter, nav.marks, nav.sort) { visibleAssets(lineage, nav) }, loupe,
            onShow = { nav.loupeId = it.id },
            onMark = mark,
            onInfo = { nav.selectedId = loupe.id },
            onClose = { nav.loupeId = null },
        )
    } else if (selected == null) {
        AssetGrid(store, lineage, nav, onChanged, onUseIn, mark) { nav.loupeId = it.id }
    } else {
        AssetDetail(
            store, lineage, selected,
            onSelect = { nav.selectedId = it.id },
            onBack = { nav.selectedId = null },
            onChanged, onOpen, onSend, onUseIn, onSendToPhone,
        )
    }
}

/** Rooms a still image can go to from Library, with what happens there. */
private val useInRooms = listOf(
    Room.Compose to "Compose",    // a layer in the main composite
    Room.Nodes to "Node Lab", // a Library Image node
    Room.Draw to "Draw",          // placed as an image layer
    Room.Animate to "Animate",    // added as a frame
    Room.Sequence to "Sequence",  // added as an item
    Room.Develop to "Develop",
    Room.Fft to "FFT",
    Room.Glitch to "Glitch",
    Room.Camera to "Camera",      // shown as the preview overlay
    Room.Loop to "Loop",          // the projector's first frame
    Room.Patch to "Patch",        // placed in Draw, ready for routes
)

/** Rooms that take several images at once (the others work on one). */
private val batchRooms = setOf(Room.Nodes, Room.Compose, Room.Draw, Room.Animate, Room.Sequence, Room.Patch)

/** Where Library is (open image, filter); kept by the app so leaving the room doesn't lose it. */
class LibraryNav {
    var selectedId by mutableStateOf<String?>(null)
    var filter by mutableStateOf<String?>(null)

    /** Multi-select in the grid (long-press to start), in the order picked. */
    var picked by mutableStateOf<List<String>>(emptyList())

    // Light table.
    /** The image open in the loupe (full screen, swipe through the grid's order). */
    var loupeId by mutableStateOf<String?>(null)
    /** Two images side by side. */
    var compare by mutableStateOf<Pair<String, String>?>(null)
    var columns by mutableStateOf(3)
    var sort by mutableStateOf(LibrarySort.Newest)
    var marks by mutableStateOf(Marks())
}

/** What the grid (and the loupe) shows: the kind or tag filter, the marks filter, in the sort order. */
private fun visibleAssets(lineage: Lineage, nav: LibraryNav): List<AssetRecord> {
    val all = lineage.newestFirst()
    val filter = nav.filter
    val byKind = when {
        filter == null -> all
        filter.startsWith("#") -> all.filter { filter.drop(1) in it.tags }
        else -> all.filter { it.kind == filter }
    }
    return byKind.filter(nav.marks::admits).sortedFor(nav.sort)
}

/** Kind of an asset, from its operation: "draw", "camera", "external", … */
private val AssetRecord.kind: String get() = operation.substringBefore(':').substringBefore('.')

@Composable
private fun AssetGrid(
    store: ProjectStore,
    lineage: Lineage,
    nav: LibraryNav,
    onChanged: suspend () -> Unit,
    onUseIn: (List<AssetRecord>, Room) -> Unit,
    onMark: (Set<String>, (AssetRecord) -> AssetRecord) -> Unit,
    onSelect: (AssetRecord) -> Unit,
) {
    val filter = nav.filter
    val onFilter: (String?) -> Unit = { nav.filter = it }
    val scope = rememberCoroutineScope()
    var tagging by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    val picked = nav.picked.mapNotNull { lineage[it] }
    val all = lineage.newestFirst()
    if (all.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
            Text("Nothing saved yet. Draw something and press Save.", style = MaterialTheme.typography.bodyMedium)
        }
        return
    }
    // Filtered and sorted once per change, not on every recomposition of the grid.
    val assets = remember(lineage, nav.filter, nav.marks, nav.sort) { visibleAssets(lineage, nav) }
    val kinds = remember(lineage) { all.groupingBy { it.kind }.eachCount().toSortedMap() }
    if (deleting) {
        val context = androidx.compose.ui.platform.LocalContext.current
        val plan = remember(picked.map { it.id }) { DeletePlan.of(all, picked.map { it.id }.toSet(), withDescendants = false) }
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text("Delete ${plan.order.size} image${if (plan.order.size == 1) "" else "s"}?") },
            text = {
                Text(
                    "Removes them from the Library (and any copy the studio exported). This can't be undone." +
                        if (plan.blocked.isNotEmpty()) " ${plan.blocked.size} stay because something else is made from them." else "",
                )
            },
            confirmButton = {
                TextButton(enabled = plan.order.isNotEmpty(), onClick = {
                    deleting = false
                    nav.picked = emptyList()
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            store.deleteAll(plan)
                            plan.order.forEach { a -> a.exportedUri?.let { runCatching { context.contentResolver.delete(Uri.parse(it), null, null) } } }
                        }
                        onChanged()
                    }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text("Cancel") } },
        )
    }
    if (tagging) {
        TagDialog(lineage.allTags(), onDismiss = { tagging = false }) { tag ->
            tagging = false
            scope.launch {
                withContext(Dispatchers.IO) { picked.forEach { store.setTags(it.id, it.tags + tag) } }
                onChanged()
            }
        }
    }
    // In landscape the bars go to the side panel and the grid keeps the window's height.
    dk.cbavf.studio.ui.layout.PageSplit(barsOnTop = true, bars = {
        if (picked.isNotEmpty()) {
            // Selection bar: send the picked images on, in the order they were picked.
            Surface(tonalElevation = 3.dp) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("${picked.size} picked · use in", style = MaterialTheme.typography.labelMedium)
                    val stills = picked.filter { it.isStill }
                    useInRooms.filter { it.first in batchRooms }.forEach { (room, label) ->
                        TextButton(onClick = {
                            onUseIn(stills, room)
                            nav.picked = emptyList()
                        }, enabled = stills.isNotEmpty()) { Text(label) }
                    }
                    TextButton(onClick = { tagging = true }) { Text("Tag") }
                    TextButton(onClick = { deleting = true }) { Text("Delete") }
                    if (picked.size == 2) TextButton(onClick = { nav.compare = picked[0].id to picked[1].id }) { Text("Compare") }
                    TextButton(onClick = { nav.picked = emptyList() }) { Text("Cancel") }
                }
            }
            // Marks for every picked image at once.
            Surface(tonalElevation = 3.dp) {
                MarkControls(picked.first().rating, picked.first().flag, picked.first().label) { f -> onMark(picked.map { it.id }.toSet(), f) }
            }
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            FilterChip(selected = filter == null, onClick = { onFilter(null) }, label = { Text("All ${all.size}") })
            kinds.forEach { (kind, n) ->
                FilterChip(selected = filter == kind, onClick = { onFilter(kind) }, label = { Text("${kind.replaceFirstChar { it.uppercase() }} $n") })
            }
            lineage.allTags().forEach { tag ->
                FilterChip(selected = filter == "#$tag", onClick = { onFilter("#$tag") }, label = { Text("#$tag") })
            }
        }
        // Light table: sort, filter by marks, grid size (or pinch the grid).
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LibrarySort.entries.forEach { s -> FilterChip(selected = nav.sort == s, onClick = { nav.sort = s }, label = { Text(s.label) }) }
            Text("·")
            (1..5).forEach { n ->
                FilterChip(selected = nav.marks.minRating == n, onClick = { nav.marks = nav.marks.copy(minRating = if (nav.marks.minRating == n) 0 else n) }, label = { Text("★$n+") })
            }
            FilterChip(selected = nav.marks.flag == 1, onClick = { nav.marks = nav.marks.copy(flag = if (nav.marks.flag == 1) null else 1) }, label = { Text("⚑ Picks") })
            FilterChip(selected = nav.marks.flag == -1, onClick = { nav.marks = nav.marks.copy(flag = if (nav.marks.flag == -1) null else -1) }, label = { Text("✕ Rejects") })
            ColorLabel.entries.forEach { c ->
                Box(
                    Modifier.size(24.dp).background(androidx.compose.ui.graphics.Color(c.argb), androidx.compose.foundation.shape.CircleShape)
                        .border(if (nav.marks.label == c) 3.dp else 0.dp, MaterialTheme.colorScheme.onSurface, androidx.compose.foundation.shape.CircleShape)
                        .clickable { nav.marks = nav.marks.copy(label = if (nav.marks.label == c) null else c) }
                )
            }
            Text("·")
            TextButton(onClick = { nav.columns = (nav.columns + 1).coerceAtMost(8) }) { Text("−") }
            Text("${nav.columns} across", style = MaterialTheme.typography.labelMedium)
            TextButton(onClick = { nav.columns = (nav.columns - 1).coerceAtLeast(1) }) { Text("+") }
        }
    }) {
      Column(Modifier.fillMaxSize()) {
        if (assets.isEmpty()) {
            Text("Nothing matches these filters.", Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(nav.columns),
            modifier = Modifier
                .fillMaxSize()
                .padding(4.dp)
                // Pinch: fewer, bigger images (out) or more, smaller ones (in); one finger still scrolls.
                .pointerInput(Unit) {
                    awaitEachGesture {
                        var acc = 1f
                        do {
                            val e = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                            if (e.changes.count { it.pressed } >= 2) {
                                acc *= e.calculateZoom()
                                if (acc > 1.3f) { nav.columns = (nav.columns - 1).coerceAtLeast(1); acc = 1f }
                                if (acc < 0.77f) { nav.columns = (nav.columns + 1).coerceAtMost(8); acc = 1f }
                                e.changes.forEach { it.consume() }
                            }
                        } while (e.changes.any { it.pressed })
                    }
                },
        ) {
            items(assets, key = { it.id }) { a ->
                val n = nav.picked.indexOf(a.id)
                Box(
                    Modifier
                        .padding(4.dp)
                        .border(if (n >= 0) 3.dp else 0.dp, MaterialTheme.colorScheme.primary)
                        .combinedClickable(
                            // While picking, a tap adds or removes; otherwise it opens.
                            onClick = {
                                if (nav.picked.isEmpty()) onSelect(a)
                                else nav.picked = if (n >= 0) nav.picked - a.id else nav.picked + a.id
                            },
                            onLongClick = { if (n < 0) nav.picked = nav.picked + a.id },
                        )
                ) {
                    AssetImage(store.fileOf(a), maxPx = if (nav.columns <= 2) THUMB_PX * 2 else THUMB_PX, modifier = Modifier.fillMaxWidth().aspectRatio(a.aspect))
                    MarkBadges(a, Modifier.align(Alignment.TopStart).padding(4.dp))
                    if (!a.isStill && !a.isVideo) {
                        // Files Android can't draw (DNG): name the format instead of an empty tile.
                        Text(a.file.substringAfterLast('.').uppercase(), Modifier.align(Alignment.Center), style = MaterialTheme.typography.titleMedium)
                    }
                    if (n >= 0) {
                        Surface(Modifier.align(Alignment.TopEnd).padding(4.dp), color = MaterialTheme.colorScheme.primary, shape = MaterialTheme.shapes.extraSmall) {
                            Text("${n + 1}", Modifier.padding(horizontal = 6.dp), style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    Surface(
                        Modifier.align(Alignment.BottomStart).padding(4.dp),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                        shape = MaterialTheme.shapes.extraSmall,
                    ) {
                        Text(
                            "g${lineage.generation(a.id)}" + if (a.isVideo) " ▶" else "",
                            Modifier.padding(horizontal = 4.dp),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        }
      }
    }
}

@Composable
private fun AssetDetail(
    store: ProjectStore,
    lineage: Lineage,
    asset: AssetRecord,
    onSelect: (AssetRecord) -> Unit,
    onBack: () -> Unit,
    onChanged: suspend () -> Unit,
    onOpen: (AssetRecord) -> Unit,
    onSend: (AssetRecord, ExternalApp) -> Unit,
    onUseIn: (List<AssetRecord>, Room) -> Unit,
    onSendToPhone: ((AssetRecord) -> Unit)?,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val parents = lineage.parents(asset.id)
    val ancestors = lineage.ancestors(asset.id)
    val children = lineage.children(asset.id)

    // A/B flicker against the first parent, or any image picked with "Compare with…".
    var partnerId by remember(asset.id) { mutableStateOf(parents.firstOrNull()?.id) }
    val partner = partnerId?.let { lineage[it] }
    var flicker by remember(asset.id) { mutableStateOf(false) }
    var showPartner by remember(asset.id) { mutableStateOf(false) }
    var intervalMs by remember { mutableFloatStateOf(250f) }
    var pickingPartner by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var tagging by remember { mutableStateOf(false) }

    LaunchedEffect(flicker, partnerId) {
        showPartner = false
        while (flicker && partner != null) {
            // Off screen the flicker stops until the studio is back.
            dk.cbavf.studio.foreground.Foreground.gate.sleep(intervalMs.roundToLong())
            showPartner = !showPartner
        }
    }

    if (pickingPartner) {
        LibraryPicker(store, lineage, single = true) { ids ->
            pickingPartner = false
            ids.firstOrNull()?.let {
                partnerId = it
                flicker = true
            }
        }
        return
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this image?") },
            text = {
                Text(
                    "Removes it from the Library" + (if (asset.exportedUri != null) " and its copy in ${exportFolder(asset)}" else "") +
                        ". Its parents are kept. This can't be undone."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) {
                            store.delete(asset).also { deleted ->
                                // The shared copy is ours (we inserted it), so we may remove it.
                                if (deleted) asset.exportedUri?.let { runCatching { context.contentResolver.delete(Uri.parse(it), null, null) } }
                            }
                        }
                        if (ok) {
                            onBack()
                            onChanged()
                        } else {
                            Toast.makeText(context, "It has children; delete those first.", Toast.LENGTH_LONG).show()
                        }
                    }
                }) { Text("Delete") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
                    if (children.isNotEmpty()) TextButton(onClick = {
                        confirmDelete = false
                        scope.launch {
                            val plan = DeletePlan.of(lineage.newestFirst(), setOf(asset.id), withDescendants = true)
                            withContext(Dispatchers.IO) {
                                store.deleteAll(plan)
                                plan.order.forEach { a -> a.exportedUri?.let { runCatching { context.contentResolver.delete(Uri.parse(it), null, null) } } }
                            }
                            onBack()
                            onChanged()
                        }
                    }) { Text("Delete with ${children.size} child${if (children.size == 1) "" else "ren"}…") }
                }
            },
        )
    }

    if (tagging) {
        TagDialog(lineage.allTags() - asset.tags.toSet(), onDismiss = { tagging = false }) { tag ->
            tagging = false
            scope.launch {
                withContext(Dispatchers.IO) { store.setTags(asset.id, asset.tags + tag) }
                onChanged()
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← Library") }
            Text(
                "generation ${ancestors.size}" + if (showPartner && partner != null) " · showing g${lineage.generation(partner.id)} ${partner.id.takeLast(6)}" else "",
                style = MaterialTheme.typography.labelLarge,
            )
        }
        // The picture and its details: details under it in portrait, beside it in landscape.
        dk.cbavf.studio.ui.layout.RoomSplit(Modifier.weight(1f).fillMaxWidth(), canvas = {
            Box(Modifier.fillMaxSize().padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
                // Pixel-exact display: smoothing would wash out the moiré. Both pictures of an A/B
                // flicker stay loaded and only the shown one is drawn, so a flip never reads a file.
                val partnerShown = showPartner && partner != null
                AssetImage(
                    store.fileOf(asset), maxPx = Int.MAX_VALUE, filter = FilterQuality.None,
                    modifier = Modifier.fillMaxSize().graphicsLayer { alpha = if (partnerShown) 0f else 1f },
                )
                if (partner != null && flicker) {
                    AssetImage(
                        store.fileOf(partner), maxPx = Int.MAX_VALUE, filter = FilterQuality.None,
                        modifier = Modifier.fillMaxSize().graphicsLayer { alpha = if (partnerShown) 1f else 0f },
                    )
                }
            }
        }, controls = {
        Surface(tonalElevation = 2.dp) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                val style = MaterialTheme.typography.labelMedium
                Text("${asset.operation} · ${DateFormat.getDateTimeInstance().format(Date(asset.createdAt))}", style = style)
                Text(
                    "${asset.device} · ${asset.widthPx}×${asset.heightPx} px" + (asset.summary?.takeIf { it.isNotEmpty() }?.let { " · $it" } ?: ""),
                    style = style,
                )
                if (asset.exportedUri != null) Text("Copy in ${exportFolder(asset)}", style = style)

                ChipRow("Parents", parents, lineage, onSelect)
                // The first-parent chain further back, when there is one.
                if (ancestors.size > 1) ChipRow("Ancestry", ancestors.drop(1).reversed(), lineage, onSelect)
                ChipRow("Children", children, lineage, onSelect)

                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Tags", Modifier.width(64.dp), style = style)
                    asset.tags.forEach { tag ->
                        InputChip(selected = false, onClick = {
                            scope.launch {
                                withContext(Dispatchers.IO) { store.setTags(asset.id, asset.tags - tag) }
                                onChanged()
                            }
                        }, label = { Text("#$tag ×") })
                    }
                    TextButton(onClick = { tagging = true }) { Text("+ tag") }
                }

                if (asset.hasRecipe) {
                    // Reopen how it was made (its recipe), as opposed to using the picture.
                    TextButton(onClick = { onOpen(asset) }) { Text("Reopen recipe in " + asset.room.replaceFirstChar { it.uppercase() }) }
                }
                if (asset.isStill) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Use in", Modifier.width(64.dp), style = style)
                        useInRooms.forEach { (room, label) -> TextButton(onClick = { onUseIn(listOf(asset), room) }) { Text(label) } }
                    }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(
                        selected = flicker,
                        onClick = { flicker = !flicker },
                        enabled = partner != null,
                        label = { Text(if (partner != null && partner.id == parents.firstOrNull()?.id) "A/B vs parent" else "A/B flicker") },
                    )
                    TextButton(onClick = { pickingPartner = true }) { Text("Compare with…") }
                    asset.exportedUri?.let { uri ->
                        TextButton(onClick = {
                            val view = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(uri), mimeOf(asset)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            runCatching { context.startActivity(Intent.createChooser(view, "View in")) }
                                .onFailure { Toast.makeText(context, "No app can open this file.", Toast.LENGTH_SHORT).show() }
                        }) { Text(if (asset.isVideo) "Play in…" else "View in…") }
                    }
                    TextButton(onClick = { confirmDelete = true }) { Text("Delete") }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Send to", Modifier.width(64.dp), style = style)
                    if (asset.isStill) TextButton(onClick = { onSend(asset, ExternalApp.Gallery) }) { Text("Photo editor…") }
                    if (asset.isStill) TextButton(onClick = { onSend(asset, ExternalApp.Notes) }) { Text("Share…") }
                    TextButton(onClick = { onSend(asset, ExternalApp.Other) }) { Text("Other app…") }
                    // Over the Loop link, with its recipe, into the other phone's Library.
                    onSendToPhone?.let { send -> TextButton(onClick = { send(asset) }) { Text("Other phone") } }
                }
                if (flicker) {
                    dk.cbavf.studio.ui.LabeledSlider("Interval", intervalMs, 30f..1000f, " ms", format = { "${it.roundToLong()}" }) { intervalMs = it }
                }
            }
        }
        })
    }
}

@Composable
private fun TagDialog(suggestions: List<String>, onDismiss: () -> Unit, onTag: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    val clean = text.trim().removePrefix("#").replace(Regex("\\s+"), "-").lowercase()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add a tag") },
        text = {
            Column {
                OutlinedTextField(text, { text = it }, singleLine = true, label = { Text("Tag") })
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    suggestions.forEach { s -> AssistChip(onClick = { onTag(s) }, label = { Text("#$s") }) }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onTag(clean) }, enabled = clean.isNotEmpty()) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ChipRow(label: String, assets: List<AssetRecord>, lineage: Lineage, onSelect: (AssetRecord) -> Unit) {
    if (assets.isEmpty()) return
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.width(64.dp), style = MaterialTheme.typography.labelMedium)
        assets.forEach { a ->
            AssistChip(onClick = { onSelect(a) }, label = { Text("g${lineage.generation(a.id)} ${a.id.takeLast(6)}") })
        }
    }
}

private fun mimeOf(a: AssetRecord): String = when (a.file.substringAfterLast('.').lowercase()) {
    "mp4" -> "video/mp4"
    "gif" -> "image/gif"
    "jpg", "jpeg" -> "image/jpeg"
    "dng" -> "image/x-adobe-dng"
    else -> "image/png"
}

private fun exportFolder(a: AssetRecord) = if (a.isVideo) "Movies/Studio" else "Pictures/Studio"

private const val THUMB_PX = 360
