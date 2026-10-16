package dk.cbavf.studio.compose

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dk.cbavf.studio.compose.model.AssetLayerSource
import dk.cbavf.studio.compose.model.CanvasLayerSource
import dk.cbavf.studio.compose.model.ComposeDoc
import dk.cbavf.studio.compose.model.ComposeLayer
import dk.cbavf.studio.compose.model.LayerMask
import dk.cbavf.studio.compose.model.MaskDab
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
    Mask("Mask"),
    Pick("Eyedropper"),
}

/** The Compose room: the layered document (undoable), the selection, and how it is viewed. */
class ComposeState {
    val edit = Undoable(ComposeDoc())
    val doc: ComposeDoc get() = edit.doc

    var selectedId by mutableStateOf<Int?>(null)
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

    /** The current selection: a closed polygon in document pixels (a rectangle has four corners). */
    var selection by mutableStateOf<List<Pt>?>(null)

    val selected: ComposeLayer? get() = doc.layers.firstOrNull { it.id == selectedId }

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
        val i = doc.layers.indexOfFirst { it.id == selectedId }
        val to = i + delta
        if (i < 0 || to !in doc.layers.indices) return
        edit.edit { d -> d.copy(layers = d.layers.toMutableList().apply { add(to, removeAt(i)) }) }
    }

    fun duplicate() {
        val l = selected ?: return
        val copy = l.copy(id = doc.nextId, name = l.name + " copy")
        edit.edit { d -> d.copy(layers = d.layers.toMutableList().apply { add(d.layers.indexOf(l) + 1, copy) }) }
        selectedId = copy.id
    }

    fun delete() {
        val l = selected ?: return
        val i = doc.layers.indexOf(l)
        edit.edit { d -> d.copy(layers = d.layers - l) }
        selectedId = doc.layers.getOrNull((i - 1).coerceAtLeast(0))?.id
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

    /** Text, measured by the caller (it needs Android's font metrics). */
    fun addText(text: String, sizePx: Float, bold: Boolean, w: Int, h: Int) {
        val src = dk.cbavf.studio.compose.model.TextLayerSource(text, sizePx, color, bold, w, h)
        add(listOf(ComposeLayer(doc.nextId, text.lineSequence().first().take(16), src, dk.cbavf.studio.draw.model.Transform(tx = doc.widthPx / 2f, ty = doc.heightPx / 2f))))
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

    fun addFilter(f: dk.cbavf.studio.compose.model.LayerFilter) = update { it.copy(filters = it.filters + f) }

    fun setFilter(i: Int, key: Any, f: dk.cbavf.studio.compose.model.LayerFilter) =
        update(key) { l -> l.copy(filters = l.filters.mapIndexed { j, old -> if (j == i) f else old }) }

    fun removeFilter(i: Int) = update { l -> l.copy(filters = l.filters.filterIndexed { j, _ -> j != i }) }

    /** Topmost visible layer under a document point. */
    fun pick(p: Pt): Boolean {
        val hit = doc.layers.lastOrNull { it.visible && it.contains(p) } ?: return false
        selectedId = hit.id
        return true
    }

    /** Move / twist / pinch the selected layer by a gesture already converted to document units. */
    fun transformSelected(pan: Pt, zoom: Float, rotationDeg: Float, key: Any) = update(key) { l ->
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
}
