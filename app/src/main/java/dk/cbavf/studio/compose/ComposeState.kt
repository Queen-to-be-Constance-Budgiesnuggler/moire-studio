package dk.cbavf.studio.compose

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dk.cbavf.studio.compose.model.AssetLayerSource
import dk.cbavf.studio.compose.model.CanvasLayerSource
import dk.cbavf.studio.compose.model.CloneMode
import dk.cbavf.studio.compose.model.CloneStroke
import dk.cbavf.studio.compose.model.ComposeDoc
import dk.cbavf.studio.compose.model.ComposeLayer
import dk.cbavf.studio.compose.model.LayerMask
import dk.cbavf.studio.compose.model.MaskDab
import dk.cbavf.studio.develop.model.DevelopDoc
import dk.cbavf.studio.draw.model.Content
import dk.cbavf.studio.draw.model.Layer
import dk.cbavf.studio.draw.model.Pt
import dk.cbavf.studio.draw.model.label
import dk.cbavf.studio.draw.model.transformed
import dk.cbavf.studio.library.AssetRecord
import dk.cbavf.studio.library.CanvasDoc
import dk.cbavf.studio.ui.Undoable

/** What a one-finger drag on the Compose canvas does. */
enum class ComposeTool(val label: String) {
    View("View"),
    Move("Move"),
    Select("Select"),
    Lasso("Lasso"),
    Paint("Brush"),
    Erase("Eraser"),
    Clone("Clone"),
    Heal("Heal"),
    Mask("Mask"),
    Pick("Eyedropper"),
}

/** The Compose room: the layered document (undoable), the selection, and how it is viewed. */
class ComposeState {
    val edit = Undoable(ComposeDoc())
    val doc: ComposeDoc get() = edit.doc

    var selectedId by mutableStateOf<Int?>(null)
    /** More layers picked by holding them in the layer strip; with [selectedId], what group, delete and move act on. */
    var picked by mutableStateOf(setOf<Int>())
    var tool by mutableStateOf(ComposeTool.Move)

    /** View: document pixels → screen, as zoom on top of "fit to view", plus a pan in screen pixels. */
    var zoom by mutableFloatStateOf(1f)
    var panX by mutableFloatStateOf(0f)
    var panY by mutableFloatStateOf(0f)

    var brushRadius by mutableFloatStateOf(60f)
    var brushSoftness by mutableFloatStateOf(0.5f)
    var brushReveal by mutableStateOf(false)

    /** Paint colour (ARGB), brush width and softness for the Brush and Eraser. */
    var color by mutableStateOf(0xFF000000L)
    var paintWidth by mutableFloatStateOf(24f)
    var paintSoftness by mutableFloatStateOf(0f)
    /** Brush, eraser and mask draw a straight line from where the finger went down. */
    var straight by mutableStateOf(false)

    /** Clone/heal source point in document coordinates; null until the user sets it. */
    var cloneSource by mutableStateOf<Pt?>(null)
    /** The fixed offset (source minus destination) for the current clone/heal stroke. */
    var cloneOffset by mutableStateOf<Pt?>(null)

    /** The current selection: a closed polygon in document pixels (a rectangle has four corners). */
    var selection by mutableStateOf<List<Pt>?>(null)

    val selected: ComposeLayer? get() = doc.layers.firstOrNull { it.id == selectedId }

    /** Every picked layer that still exists (the selected one included). */
    val pickedIds: Set<Int> get() = (picked + listOfNotNull(selectedId)).filter { id -> doc.layers.any { it.id == id } }.toSet()

    /** Tap in the layer strip: just this layer. Clears clone state and guards the tool. */
    fun selectOnly(id: Int) {
        selectedId = id
        picked = emptySet()
        cloneSource = null
        cloneOffset = null
        // If the new layer is not a paint layer, Clone/Heal cannot work: fall back to Move.
        if (tool == ComposeTool.Clone || tool == ComposeTool.Heal) {
            val layer = doc.layers.firstOrNull { it.id == id }
            if (layer == null || layer.source !is dk.cbavf.studio.compose.model.PaintLayerSource) {
                tool = ComposeTool.Move
            }
        }
    }

    /** Hold in the layer strip: add this layer to the picked ones, or take it out again. */
    fun togglePicked(id: Int) {
        val now = pickedIds
        if (id in now) {
            val rest = now - id
            selectedId = rest.lastOrNull()
            picked = rest - setOfNotNull(selectedId)
        } else {
            picked = now
            selectedId = id
        }
    }

    /** Picked layers without any whose group is picked too (the group already carries them). */
    private fun pickedRoots(): Set<Int> {
        val ids = pickedIds
        return ids.filter { id ->
            var g = doc.layers.firstOrNull { it.id == id }?.group
            var depth = 0
            while (g != null && depth++ < 32) {
                if (g in ids) return@filter false
                g = doc.layers.firstOrNull { it.id == g }?.group
            }
            true
        }.toSet()
    }

    /** Ctrl+G: the picked layers go into a new group, where the topmost of them was. */
    fun groupPicked() {
        val ids = pickedRoots()
        if (ids.isEmpty()) return
        val d = doc
        val parent = d.layers.firstOrNull { it.id == selectedId }?.group ?: d.layers.first { it.id in ids }.group
        val gid = d.nextId
        val g = ComposeLayer(
            gid, "Group", dk.cbavf.studio.compose.model.GroupLayerSource(d.widthPx, d.heightPx),
            dk.cbavf.studio.draw.model.Transform(tx = d.widthPx / 2f, ty = d.heightPx / 2f), group = parent,
        )
        val top = d.layers.indexOfLast { it.id in ids }
        val layers = d.layers.map { if (it.id in ids) it.copy(group = gid) else it }.toMutableList().apply { add(top + 1, g) }
        edit.edit { d.copy(layers = layers).normalized() }
        selectOnly(gid)
    }

    /** Takes a group apart: its layers move up to where the group was. */
    fun ungroup(id: Int) {
        val g = doc.layers.firstOrNull { it.id == id && it.isGroup } ?: return
        edit.edit { d -> d.copy(layers = d.layers.filterNot { it.id == id }.map { if (it.group == id) it.copy(group = g.group) else it }).normalized() }
        selectOnly(doc.children(g.group).lastOrNull()?.id ?: return)
    }

    /**
     * Moves the selected layer out of its group: from the upper half of the group it goes just
     * above the group, from the lower half just below, so it stays on the same side of the rest.
     */
    fun leaveGroup() {
        val l = selected ?: return
        val g = doc.layers.firstOrNull { it.id == l.group } ?: return
        val siblings = doc.children(g.id)
        val above = siblings.indexOfFirst { it.id == l.id } * 2 >= siblings.size
        edit.edit { d ->
            val rest = d.layers.filterNot { it.id == l.id }.toMutableList()
            val gi = rest.indexOfFirst { it.id == g.id }
            rest.add(if (above) gi + 1 else gi, l.copy(group = g.group))
            d.copy(layers = rest).normalized()
        }
    }

    /** Moves layer [id] [delta] places among the layers beside it (in its group, or at the top level). */
    fun moveBy(id: Int, delta: Int) {
        val d = doc
        val l = d.layers.firstOrNull { it.id == id } ?: return
        val siblings = d.children(l.group).toMutableList()
        val i = siblings.indexOfFirst { it.id == id }
        val to = (i + delta).coerceIn(0, siblings.lastIndex)
        if (to == i) return
        siblings.add(to, siblings.removeAt(i))
        // Siblings keep the slots they had in the whole list, now in the new order.
        val slots = d.layers.indices.filter { d.layers[it].group == l.group }
        val layers = d.layers.toMutableList()
        slots.forEachIndexed { k, slot -> layers[slot] = siblings[k] }
        edit.edit { d.copy(layers = layers).normalized() }
    }

    fun fitView() {
        zoom = 1f
        panX = 0f
        panY = 0f
    }

    // --- Layers -------------------------------------------------------------------------

    private fun add(layers: List<ComposeLayer>) {
        if (layers.isEmpty()) return
        edit.edit { it.copy(layers = it.layers + layers) }
        selectedId = layers.last().id
    }

    /** Library images as layers, fitted into the document. An empty document takes the first image's size. */
    fun addImages(assets: List<AssetRecord>) {
        if (assets.isEmpty()) return
        if (doc.layers.isEmpty()) {
            val first = assets.first()
            if (first.widthPx > 0) edit.edit { it.copy(widthPx = first.widthPx, heightPx = first.heightPx) }
        }
        var id = doc.nextId
        add(assets.map { a ->
            ComposeDoc.fitted(id++, "Image ${a.id.takeLast(6)}", AssetLayerSource(a.id, a.widthPx, a.heightPx), a.widthPx, a.heightPx, doc.widthPx, doc.heightPx)
        })
    }

    /** A snapshot of the Draw canvas, transparent between its lines, fitted to the document. */
    fun addCanvas(canvas: CanvasDoc, name: String = "Draw canvas") {
        if (canvas.widthPx <= 0) return
        add(listOf(ComposeDoc.fitted(doc.nextId, name, CanvasLayerSource(canvas), canvas.widthPx, canvas.heightPx, doc.widthPx, doc.heightPx)))
    }

    /** A live generator (grating, burst, ellipses) covering the whole document. */
    fun addGenerator(content: Content) {
        val canvas = CanvasDoc(doc.widthPx, doc.heightPx, listOf(Layer(1, content)))
        add(listOf(ComposeDoc.fitted(doc.nextId, content.label, CanvasLayerSource(canvas), doc.widthPx, doc.heightPx, doc.widthPx, doc.heightPx)))
    }

    fun update(key: Any? = null, f: (ComposeLayer) -> ComposeLayer) {
        val id = selectedId ?: return
        edit.edit(key) { d -> d.copy(layers = d.layers.map { if (it.id == id) f(it) else it }) }
    }

    fun move(delta: Int) {
        moveBy(selectedId ?: return, delta)
    }

    /** Duplicates the picked layers (a group with everything in it), each copy right above its original. */
    fun duplicate() {
        val roots = pickedRoots()
        if (roots.isEmpty()) return
        var next = doc.nextId
        val layers = doc.layers.toMutableList()
        val firstCopies = mutableListOf<Int>()
        for (id in roots) {
            val l = layers.first { it.id == id }
            val subtree = if (l.isGroup) doc.members(id) + l else listOf(l)
            val map = subtree.associate { it.id to next++ }
            val copies = subtree.map { c ->
                c.copy(id = map.getValue(c.id), group = if (c.id == id) c.group else map[c.group] ?: c.group, name = if (c.id == id) c.name + " copy" else c.name)
            }
            layers.addAll(layers.indexOfFirst { it.id == id } + 1, copies)
            firstCopies += map.getValue(id)
        }
        edit.edit { it.copy(layers = layers).normalized() }
        selectedId = firstCopies.last()
        picked = firstCopies.toSet() - firstCopies.last()
    }

    /** Deletes the picked layers (a group with everything in it). */
    fun delete() {
        val roots = pickedRoots()
        if (roots.isEmpty()) return
        val gone = roots.flatMap { id -> listOf(id) + (doc.layers.firstOrNull { it.id == id }?.takeIf { it.isGroup }?.let { g -> doc.members(g.id).map { it.id } } ?: emptyList()) }.toSet()
        val i = doc.layers.indexOfFirst { it.id in gone }
        edit.edit { d -> d.copy(layers = d.layers.filterNot { it.id in gone }) }
        selectedId = doc.layers.getOrNull((i - 1).coerceAtLeast(0))?.id
        picked = emptySet()
    }

    // --- New layers -----------------------------------------------------------------------

    private fun fullDocLayer(name: String, source: dk.cbavf.studio.compose.model.LayerSource) =
        ComposeDoc.fitted(doc.nextId, name, source, doc.widthPx, doc.heightPx, doc.widthPx, doc.heightPx)

    fun addSolid(argb: Long) = add(listOf(fullDocLayer("Fill", dk.cbavf.studio.compose.model.SolidLayerSource(argb, doc.widthPx, doc.heightPx))))

    fun addGradient(from: Long, to: Long, radial: Boolean) = add(listOf(fullDocLayer(
        if (radial) "Radial gradient" else "Gradient",
        dk.cbavf.studio.compose.model.GradientLayerSource(from, to, 90f, radial, doc.widthPx, doc.heightPx),
    )))

    fun addPaintLayer() = add(listOf(fullDocLayer("Paint", dk.cbavf.studio.compose.model.PaintLayerSource(doc.widthPx, doc.heightPx))))

    /** Text, measured by the caller (it needs Android's font metrics), in the current colour. */
    fun addText(src: dk.cbavf.studio.compose.model.TextLayerSource) {
        add(listOf(ComposeLayer(doc.nextId, src.text.lineSequence().first().take(16), src.copy(argb = color), dk.cbavf.studio.draw.model.Transform(tx = doc.widthPx / 2f, ty = doc.heightPx / 2f))))
    }

    fun rename(name: String) = update { it.copy(name = name.ifBlank { it.name }) }

    fun flip(horizontal: Boolean) = update { l ->
        l.copy(transform = if (horizontal) l.transform.copy(sx = -l.transform.sx) else l.transform.copy(sy = -l.transform.sy))
    }

    // --- Paint -----------------------------------------------------------------------------

    /**
     * The paint layer brushes go to: the selected one if it is a paint layer, otherwise a new
     * one over the whole document.
     */
    private fun paintLayer(): ComposeLayer {
        selected?.takeIf { it.source is dk.cbavf.studio.compose.model.PaintLayerSource }?.let { return it }
        addPaintLayer()
        return selected!!
    }

    /** Starts a brush (or eraser) stroke at a document point. */
    fun beginStroke(p: Pt, erase: Boolean, key: Any) {
        val layer = paintLayer()
        val local = layer.toLocal(p)
        val scale = ((kotlin.math.abs(layer.transform.sx) + kotlin.math.abs(layer.transform.sy)) / 2).coerceAtLeast(1e-3f)
        val stroke = dk.cbavf.studio.compose.model.PaintStroke(listOf(local), color, paintWidth / scale, paintSoftness, erase)
        update(key) { l ->
            val src = l.source as dk.cbavf.studio.compose.model.PaintLayerSource
            l.copy(source = src.copy(strokes = src.strokes + stroke))
        }
    }

    fun extendStroke(p: Pt, key: Any) = update(key) { l ->
        val src = l.source as? dk.cbavf.studio.compose.model.PaintLayerSource ?: return@update l
        val last = src.strokes.lastOrNull() ?: return@update l
        val local = l.toLocal(p)
        if (straight) {
            return@update l.copy(source = src.copy(strokes = src.strokes.dropLast(1) + last.copy(points = listOf(last.points.first(), local))))
        }
        val prev = last.points.last()
        // Points a few pixels apart keep strokes smooth without thousands of points.
        if (kotlin.math.hypot(prev.x - local.x, prev.y - local.y) < 2f) return@update l
        l.copy(source = src.copy(strokes = src.strokes.dropLast(1) + last.copy(points = last.points + local)))
    }

    // --- Selection -------------------------------------------------------------------------

    fun selectRect(a: Pt, b: Pt) {
        selection = listOf(Pt(a.x, a.y), Pt(b.x, a.y), Pt(b.x, b.y), Pt(a.x, b.y))
    }

    fun selectAll() = selectRect(Pt(0f, 0f), Pt(doc.widthPx.toFloat(), doc.heightPx.toFloat()))

    /** Crops the document to the selection's bounding box. */
    fun cropToSelection() {
        val sel = selection ?: return
        edit.edit { it.cropped(sel.minOf { p -> p.x }, sel.minOf { p -> p.y }, sel.maxOf { p -> p.x }, sel.maxOf { p -> p.y }) }
        selection = null
    }

    /** Masks the selected layer to the selection ([keep]) or cuts the selection out of it. */
    fun maskFromSelection(keep: Boolean, feather: Float) {
        val sel = selection ?: return
        update { l ->
            val m = l.mask ?: dk.cbavf.studio.compose.model.LayerMask()
            val shape = dk.cbavf.studio.compose.model.MaskShape(sel, reveal = keep, feather = feather)
            // Keeping only the selection: start from hidden, reveal the shape.
            l.copy(mask = if (keep) m.copy(invert = true, shapes = m.shapes + shape, enabled = true) else m.copy(shapes = m.shapes + shape, enabled = true))
        }
    }

    /**
     * A live layer for each rack input of Compose that has a cable but no layer yet, sized like
     * what comes in and fitted to the document ([sizeOf] null: nothing has arrived yet, try later).
     */
    fun ensureRouteLayers(patched: List<String>, sizeOf: (String) -> Pair<Int, Int>?) {
        val missing = patched.filter { input -> doc.layers.none { (it.source as? dk.cbavf.studio.compose.model.RouteLayerSource)?.input == input } }
        val new = missing.mapNotNull { input ->
            val (w, h) = sizeOf(input) ?: return@mapNotNull null
            input to (w to h)
        }
        if (new.isEmpty()) return
        if (doc.layers.isEmpty()) edit.edit { it.copy(widthPx = new.first().second.first, heightPx = new.first().second.second) }
        var id = doc.nextId
        add(new.map { (input, size) ->
            ComposeDoc.fitted(id++, "Live · ${input.substringAfter("compose.in").let { "Layer $it" }}",
                dk.cbavf.studio.compose.model.RouteLayerSource(input, size.first, size.second), size.first, size.second, doc.widthPx, doc.heightPx)
        })
    }

    /** A copied part of a layer: the layer itself, its mask clipped to the selection. */
    var clipboard by mutableStateOf<ComposeLayer?>(null)
        private set

    /** Copies the selected layer's part inside the selection (exact: nothing is resampled). */
    fun copySelection(feather: Float) {
        val sel = selection ?: return
        val l = selected ?: return
        val m = l.mask ?: dk.cbavf.studio.compose.model.LayerMask()
        clipboard = l.copy(
            name = l.name.removeSuffix(" (part)") + " (part)",
            mask = m.copy(enabled = true, clip = dk.cbavf.studio.compose.model.MaskShape(sel, reveal = true, feather = feather)),
        )
    }

    /** Copy, then cut the selection out of the layer (by its mask, so Undo or the mask can bring it back). */
    fun cutSelection(feather: Float) {
        copySelection(feather)
        maskFromSelection(keep = false, feather)
    }

    /** Pastes the copied part as a new layer above the selected one, ready to move. */
    fun paste() {
        val c = clipboard ?: return
        val copy = c.copy(id = doc.nextId)
        edit.edit { d ->
            val at = d.layers.indexOfFirst { it.id == selectedId }.let { if (it < 0) d.layers.size else it + 1 }
            d.copy(layers = d.layers.toMutableList().apply { add(at, copy) })
        }
        selectedId = copy.id
        tool = ComposeTool.Move
    }

    /** Fills (or erases) the selection on the paint layer with the current colour. */
    fun fillSelection(erase: Boolean) {
        val sel = selection ?: return
        val layer = paintLayer()
        val pts = sel.map(layer::toLocal)
        update { l ->
            val src = l.source as dk.cbavf.studio.compose.model.PaintLayerSource
            l.copy(source = src.copy(strokes = src.strokes + dk.cbavf.studio.compose.model.PaintStroke(pts, color, 1f, 0f, erase, fill = true)))
        }
    }

    // --- Filters and blending options ------------------------------------------------------

    /**
     * Changes the selected layer's Develop look (one slider drag with the same [key] is one undo
     * step). A look that is back to neutral is dropped, so the layer is drawn untouched again.
     */
    fun adjust(key: Any?, f: (DevelopDoc) -> DevelopDoc) = update(key?.let { "adjust.$it" }) { l ->
        l.copy(adjust = f(l.adjust ?: DevelopDoc()).takeIf { it != DevelopDoc() })
    }

    fun addFilter(f: dk.cbavf.studio.compose.model.LayerFilter) = update { it.copy(filters = it.filters + f) }

    fun setFilter(i: Int, key: Any, f: dk.cbavf.studio.compose.model.LayerFilter) =
        update(key) { l -> l.copy(filters = l.filters.mapIndexed { j, old -> if (j == i) f else old }) }

    fun removeFilter(i: Int) = update { l -> l.copy(filters = l.filters.filterIndexed { j, _ -> j != i }) }

    /** Topmost visible layer under a document point. */
    fun pick(p: Pt): Boolean {
        val hit = doc.layers.lastOrNull { !it.isGroup && doc.shown(it) && it.contains(p) } ?: return false
        selectOnly(hit.id)
        return true
    }

    /** Move / twist / pinch the selected layer by a gesture already converted to document units. */
    fun transformSelected(pan: Pt, zoom: Float, rotationDeg: Float, key: Any) {
        // Several layers (or a group): all of them move, turn and scale about their common centre.
        val targets = pickedRoots().flatMap { id ->
            val l = doc.layers.firstOrNull { it.id == id } ?: return@flatMap emptyList()
            if (l.isGroup) doc.members(id).filterNot { it.isGroup } else listOf(l)
        }
        if (targets.size > 1 || selected?.isGroup == true) {
            if (targets.isEmpty()) return
            val pivot = Pt(targets.map { it.transform.tx }.average().toFloat(), targets.map { it.transform.ty }.average().toFloat())
            val ids = targets.map { it.id }.toSet()
            edit.edit(key) { d ->
                d.copy(layers = d.layers.map { l ->
                    if (l.id !in ids) l else {
                        val placed = dk.cbavf.studio.draw.model.Stroke(emptyList(), l.transform, emptyList())
                        l.copy(transform = (placed.transformed(pan, zoom, rotationDeg, pivot) as dk.cbavf.studio.draw.model.Stroke).transform)
                    }
                })
            }
            return
        }
        transformOne(pan, zoom, rotationDeg, key)
    }

    /** The layer whose handles Move shows: the selected one, unless it is a group or several are picked. */
    val handleLayer: ComposeLayer? get() = selected?.takeIf { !it.isGroup && pickedIds.size <= 1 }

    /** The handle of [handleLayer] under [p] (document pixels), if any. */
    fun handleAt(p: Pt, slop: Float, rotateArm: Float): TransformHandles.Handle? =
        handleLayer?.let { TransformHandles.hit(TransformHandles.handles(it, rotateArm), p, slop) }

    /** Drags [handle] from [from] to [to]; [base] is the layer's transform when the finger went down. */
    fun dragHandle(handle: TransformHandles.Handle, base: dk.cbavf.studio.draw.model.Transform, from: Pt, to: Pt, key: Any) =
        update(key) { it.copy(transform = TransformHandles.drag(handle, base, from, to)) }

    private fun transformOne(pan: Pt, zoom: Float, rotationDeg: Float, key: Any) = update(key) { l ->
        // Reuse Draw's maths: a placed object turning and scaling about its own centre.
        val placed = dk.cbavf.studio.draw.model.Stroke(emptyList(), l.transform, emptyList())
        val moved = placed.transformed(pan, zoom, rotationDeg, Pt(l.transform.tx, l.transform.ty)) as dk.cbavf.studio.draw.model.Stroke
        l.copy(transform = moved.transform)
    }

    fun paintMask(p: Pt, key: Any) = update(key) { l ->
        val m = l.mask ?: LayerMask()
        val last = m.dabs.lastOrNull()
        // Dabs a third of a radius apart make a smooth stroke.
        if (last != null && kotlin.math.hypot(last.x - p.x, last.y - p.y) < brushRadius / 3) return@update l
        l.copy(mask = m.copy(dabs = m.dabs + MaskDab(p.x, p.y, brushRadius, brushReveal, brushSoftness), enabled = true))
    }

    // --- Clone / Heal -----------------------------------------------------------------------

    /**
     * Sets the clone source at a document point. The first tap with Clone or Heal sets this;
     * subsequent drags paint with a fixed offset from this source.
     */
    fun pickCloneSource(p: Pt) {
        cloneSource = p
        cloneOffset = null
    }

    /** Begins a clone or heal stroke at a document point. The offset is locked on the first stroke. */
    fun beginCloneStroke(p: Pt, key: Any) {
        val src = cloneSource ?: return
        val layer = paintLayer()
        val local = layer.toLocal(p)
        val localSrc = layer.toLocal(src)
        val off = cloneOffset ?: Pt(localSrc.x - local.x, localSrc.y - local.y)
        cloneOffset = off
        val mode = if (tool == ComposeTool.Heal) CloneMode.Heal else CloneMode.Clone
        val scale = ((kotlin.math.abs(layer.transform.sx) + kotlin.math.abs(layer.transform.sy)) / 2).coerceAtLeast(1e-3f)
        val stroke = CloneStroke(off.x, off.y, listOf(local), paintWidth / scale, mode)
        update(key) { l ->
            val s = l.source as dk.cbavf.studio.compose.model.PaintLayerSource
            l.copy(source = s.copy(cloneStrokes = s.cloneStrokes + stroke))
        }
    }

    fun extendCloneStroke(p: Pt, key: Any) = update(key) { l ->
        val s = l.source as? dk.cbavf.studio.compose.model.PaintLayerSource ?: return@update l
        val last = s.cloneStrokes.lastOrNull() ?: return@update l
        val local = l.toLocal(p)
        val prev = last.points.last()
        if (kotlin.math.hypot(prev.x - local.x, prev.y - local.y) < 2f) return@update l
        l.copy(source = s.copy(cloneStrokes = s.cloneStrokes.dropLast(1) + last.copy(points = last.points + local)))
    }
}
