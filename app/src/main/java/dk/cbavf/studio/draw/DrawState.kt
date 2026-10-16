package dk.cbavf.studio.draw

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dk.cbavf.studio.draw.model.Content
import dk.cbavf.studio.draw.model.EllipseHatch
import dk.cbavf.studio.draw.model.ImageLayer
import dk.cbavf.studio.draw.model.Placed
import dk.cbavf.studio.draw.model.screenCorners
import dk.cbavf.studio.draw.model.Geometry
import dk.cbavf.studio.draw.model.Grating
import dk.cbavf.studio.draw.model.Layer
import dk.cbavf.studio.draw.model.MotionLimits
import dk.cbavf.studio.draw.model.Pen
import dk.cbavf.studio.draw.model.Pt
import dk.cbavf.studio.draw.model.RadialBurst
import dk.cbavf.studio.draw.model.Stroke
import dk.cbavf.studio.draw.model.SubStroke
import dk.cbavf.studio.draw.model.ownPivot
import dk.cbavf.studio.draw.model.transformed
import dk.cbavf.studio.library.CanvasDoc
import dk.cbavf.studio.library.imageAssetIds
import kotlin.math.hypot

enum class Tool { Move, Pen, Lasso }

/**
 * Canvas state for the Draw room. Layers are immutable values; every edit replaces one,
 * which makes undo a list of snapshots.
 */
class DrawState {
    private var nextId = 1

    val layers = mutableStateListOf(
        Layer(nextId++, Grating(spacingPx = 10f, angleDeg = 0f)),
        Layer(nextId++, Grating(spacingPx = 10f, angleDeg = 5f)),
    )

    /** Layers that Move gestures act on. */
    var selection by mutableStateOf(setOf(layers.last().id))
        private set

    /** The selected layer shown in the editor and readouts. */
    var primaryId by mutableIntStateOf(layers.last().id)
        private set

    var selectedSub by mutableIntStateOf(0)

    var tool by mutableStateOf(Tool.Move)
    var pen by mutableStateOf(Pen.Rails)

    /** Pen stroke or lasso being drawn, in screen pixels. */
    val draft = mutableStateListOf<Pt>()

    var canUndo by mutableStateOf(false)
        private set
    var canPaste by mutableStateOf(false)
        private set

    /** Canvas size in pixels, set by the room once laid out. */
    var canvasWidth by mutableIntStateOf(0)
    var canvasHeight by mutableIntStateOf(0)

    /** The Library asset this canvas descends from; the next save becomes its child. */
    var sourceAssetId by mutableStateOf<String?>(null)

    private class Snapshot(val layers: List<Layer>, val sourceAssetId: String?)

    private val history = ArrayDeque<Snapshot>()
    private var lastCheckpointKey: Any? = null
    private var clipboard: List<Layer> = emptyList()

    val primary: Layer? get() = layers.firstOrNull { it.id == primaryId }

    fun select(id: Int) {
        selection = setOf(id)
        primaryId = id
        selectedSub = 0
    }

    private fun selectAll(ids: List<Int>) {
        selection = ids.toSet()
        ids.lastOrNull()?.let { primaryId = it }
        selectedSub = 0
    }

    // --- Recipes ------------------------------------------------------------------------

    fun toDoc(): CanvasDoc = CanvasDoc(canvasWidth, canvasHeight, layers.toList())

    /** Replaces the canvas with a saved recipe (startup restore). Undo history does not cross a load. */
    fun load(doc: CanvasDoc, sourceAssetId: String?) {
        history.clear()
        replace(doc, sourceAssetId)
    }

    /** Opens a recipe as an undoable edit: Undo brings back the canvas that was here before. */
    fun open(doc: CanvasDoc, sourceAssetId: String?) {
        checkpoint()
        replace(doc, sourceAssetId)
        canUndo = true
    }

    /** A fresh canvas with the two default gratings; undoable. */
    fun newCanvas() = open(
        CanvasDoc(canvasWidth, canvasHeight, listOf(Layer(1, Grating(spacingPx = 10f)), Layer(2, Grating(spacingPx = 10f, angleDeg = 5f)))),
        null,
    )

    private fun replace(doc: CanvasDoc, sourceAssetId: String?) {
        // Until the Draw room is laid out this session, the saved size is the canvas size, so
        // other rooms (Animate's Grab from Draw, the camera overlay) work before Draw is opened.
        if (canvasWidth <= 0 && doc.widthPx > 0) {
            canvasWidth = doc.widthPx
            canvasHeight = doc.heightPx
        }
        layers.clear()
        layers.addAll(doc.layers)
        nextId = (doc.layers.maxOfOrNull { it.id } ?: 0) + 1
        canUndo = history.isNotEmpty()
        lastCheckpointKey = null
        draft.clear()
        this.sourceAssetId = sourceAssetId
        layers.lastOrNull()?.let { select(it.id) } ?: selectAll(emptyList())
    }

    // --- Undo -----------------------------------------------------------------------------

    /**
     * Saves the current layers for undo. Consecutive calls with the same non-null [key]
     * (e.g. one slider being dragged) collapse into one step.
     */
    fun checkpoint(key: Any? = null) {
        if (key != null && key == lastCheckpointKey) return
        lastCheckpointKey = key
        history.addLast(Snapshot(layers.toList(), sourceAssetId))
        if (history.size > MAX_UNDO) history.removeFirst()
        canUndo = true
    }

    fun undo() {
        val previous = history.removeLastOrNull() ?: return
        layers.clear()
        layers.addAll(previous.layers)
        sourceAssetId = previous.sourceAssetId
        nextId = maxOf(nextId, (previous.layers.maxOfOrNull { it.id } ?: 0) + 1)
        lastCheckpointKey = null
        canUndo = history.isNotEmpty()
        val ids = layers.map { it.id }.toSet()
        selection = selection intersect ids
        if (primaryId !in ids) primaryId = selection.lastOrNull() ?: layers.lastOrNull()?.id ?: 0
    }

    // --- Layers ---------------------------------------------------------------------------

    fun add(content: Content) {
        checkpoint()
        val layer = Layer(nextId++, content)
        layers += layer
        select(layer.id)
    }

    fun deleteSelection() {
        if (selection.isEmpty()) return
        checkpoint()
        val firstIndex = layers.indexOfFirst { it.id in selection }
        layers.removeAll { it.id in selection }
        val next = layers.getOrNull((firstIndex - 1).coerceAtLeast(0))
        if (next != null) select(next.id) else selectAll(emptyList())
    }

    fun copy() {
        clipboard = layers.filter { it.id in selection }
        canPaste = clipboard.isNotEmpty()
    }

    fun paste() {
        if (clipboard.isEmpty()) return
        checkpoint()
        val pasted = clipboard.map { it.copy(id = nextId++, content = nudge(it.content, PASTE_OFFSET)) }
        layers += pasted
        selectAll(pasted.map { it.id })
    }

    fun duplicate() {
        copy()
        paste()
    }

    /** Moves the primary layer up (+1, drawn later, on top) or down (−1) the stack. */
    fun moveLayer(delta: Int) {
        val i = layers.indexOfFirst { it.id == primaryId }
        val to = i + delta
        if (i < 0 || to !in layers.indices) return
        checkpoint()
        layers.add(to, layers.removeAt(i))
    }

    /** Places Library images on the canvas, fitted and centred, each a layer of its own. */
    fun addImages(images: List<ImageLayer>) {
        if (images.isEmpty()) return
        checkpoint()
        val added = images.map { Layer(nextId++, it) }
        layers += added
        selectAll(added.map { it.id })
        tool = Tool.Move
    }

    /** Library images this canvas shows: they become parents of what it saves. */
    fun imageAssetIds(): List<String> = toDoc().imageAssetIds()

    fun updatePrimary(key: Any? = null, transform: (Layer) -> Layer) {
        val i = layers.indexOfFirst { it.id == primaryId }
        if (i < 0) return
        checkpoint(key)
        layers[i] = transform(layers[i])
    }

    fun updateContent(key: Any? = null, transform: (Content) -> Content) =
        updatePrimary(key) { it.copy(content = transform(it.content)) }

    /** Edits the selected sub-stroke of the primary stroke. */
    fun updateSub(key: Any? = null, transform: (SubStroke) -> SubStroke) = updateContent(key) { c ->
        if (c !is Stroke || selectedSub !in c.stack.indices) c
        else c.copy(stack = c.stack.toMutableList().also { it[selectedSub] = transform(it[selectedSub]) })
    }

    fun addSub() = updateContent { c ->
        if (c !is Stroke) return@updateContent c
        val base = c.stack.getOrNull(selectedSub) ?: SubStroke()
        selectedSub = c.stack.size
        c.copy(stack = c.stack + base.copy(offsetPx = base.offsetPx + base.widthPx * 2))
    }

    fun removeSub() = updateContent { c ->
        if (c !is Stroke || c.stack.size <= 1) return@updateContent c
        val stack = c.stack.filterIndexed { i, _ -> i != selectedSub }
        selectedSub = selectedSub.coerceAtMost(stack.lastIndex)
        c.copy(stack = stack)
    }

    // --- Gestures -------------------------------------------------------------------------

    fun beginTransform() = checkpoint()

    /**
     * Drag moves, twist rotates, pinch scales. Strokes in a multi-selection turn and scale
     * around their common centre; gratings and bursts react individually.
     */
    fun onTransform(pan: Pt, zoom: Float, rotationDeg: Float) {
        val strokes = layers.filter { it.id in selection && it.content is Placed }
        val pivot = Geometry.centroid(strokes.map { it.content.ownPivot })
        for (i in layers.indices) {
            val layer = layers[i]
            if (layer.id !in selection) continue
            layers[i] = layer.copy(content = layer.content.transformed(pan, zoom, rotationDeg, pivot))
        }
    }

    // --- Stretch handles -------------------------------------------------------------------

    private var stretchId: Int? = null
    private var stretchFrom = Pt(0f, 0f)
    private var stretchBase: dk.cbavf.studio.draw.model.Transform? = null

    /** The selected stroke or image whose corner handle is under [p], if any. */
    fun handleAt(p: Pt): Int? = layers.lastOrNull { layer ->
        val c = layer.content
        layer.id in selection && c is Placed && bounds(c).corners().any { hypot(it.x - p.x, it.y - p.y) <= HANDLE_SLOP }
    }?.id

    fun beginStretch(id: Int, p: Pt) {
        val c = layers.firstOrNull { it.id == id }?.content as? Placed ?: return
        checkpoint()
        stretchId = id
        stretchFrom = p
        stretchBase = c.transform
        select(id)
    }

    /**
     * Uneven stretch from a corner: the finger's distance from the stroke's centre, measured
     * along the stroke's own (rotated) axes, scales x and y separately.
     */
    fun stretchTo(p: Pt) {
        val id = stretchId ?: return
        val base = stretchBase ?: return
        val i = layers.indexOfFirst { it.id == id }
        val c = layers.getOrNull(i)?.content as? Placed ?: return
        val a = -base.rotationDeg * kotlin.math.PI.toFloat() / 180
        fun local(q: Pt): Pt {
            val dx = q.x - base.tx
            val dy = q.y - base.ty
            return Pt(dx * kotlin.math.cos(a) - dy * kotlin.math.sin(a), dx * kotlin.math.sin(a) + dy * kotlin.math.cos(a))
        }
        val from = local(stretchFrom)
        val to = local(p)
        val sx = if (kotlin.math.abs(from.x) > MIN_HANDLE_ARM) base.sx * to.x / from.x else base.sx
        val sy = if (kotlin.math.abs(from.y) > MIN_HANDLE_ARM) base.sy * to.y / from.y else base.sy
        layers[i] = layers[i].copy(
            content = c.withTransform(
                base.copy(
                    sx = sx.coerceIn(MotionLimits.MIN_SCALE, MotionLimits.MAX_SCALE),
                    sy = sy.coerceIn(MotionLimits.MIN_SCALE, MotionLimits.MAX_SCALE),
                )
            )
        )
    }

    fun endStretch() {
        stretchId = null
        stretchBase = null
    }

    fun draftStart(p: Pt) {
        draft.clear()
        draft += p
    }

    fun draftMove(p: Pt) {
        val last = draft.lastOrNull()
        if (last == null || hypot(p.x - last.x, p.y - last.y) >= MIN_POINT_DISTANCE) draft += p
    }

    fun penUp() {
        if (draft.size >= 2) add(Stroke.fromScreen(draft.toList(), pen.stack))
        draft.clear()
    }

    /** Selects every stroke (or image) with a point inside the lasso, then switches to Move. */
    fun lassoUp() {
        val polygon = draft.toList()
        draft.clear()
        val hit = layers.filter { layer ->
            val c = layer.content
            layer.visible && when (c) {
                is Stroke -> c.screenPoints().any { Geometry.insidePolygon(it, polygon) }
                is ImageLayer -> (c.screenCorners() + Pt(c.transform.tx, c.transform.ty)).any { Geometry.insidePolygon(it, polygon) }
                else -> false
            }
        }
        if (hit.isEmpty()) return
        selectAll(hit.map { it.id })
        tool = Tool.Move
    }

    /** Selects the topmost visible stroke drawn near [p], or image under it; false if there is none. */
    fun tapSelect(p: Pt): Boolean {
        val hit = layers.lastOrNull { layer ->
            val c = layer.content
            if (!layer.visible) return@lastOrNull false
            when (c) {
                is Stroke -> {
                    val scale = maxOf(kotlin.math.abs(c.transform.sx), kotlin.math.abs(c.transform.sy))
                    val reach = TAP_SLOP + (c.stack.maxOfOrNull { kotlin.math.abs(it.offsetPx) + it.widthPx / 2 } ?: 0f) * scale
                    c.screenPoints().any { hypot(it.x - p.x, it.y - p.y) <= reach }
                }
                is ImageLayer -> c.contains(p)
                else -> false
            }
        } ?: return false
        select(hit.id)
        return true
    }

    private fun nudge(c: Content, d: Float): Content = when (c) {
        is Grating -> c
        is RadialBurst -> c.copy(centerXPx = c.centerXPx + d, centerYPx = c.centerYPx + d)
        is EllipseHatch -> c.copy(centerXPx = c.centerXPx + d, centerYPx = c.centerYPx + d)
        is Placed -> c.withTransform(c.transform.copy(tx = c.transform.tx + d, ty = c.transform.ty + d))
    }

    companion object {
        /** Screen bounds of a stroke or image, as the selection outline and handles use them. */
        fun bounds(c: Placed): androidx.compose.ui.geometry.Rect = when (c) {
            is Stroke -> strokeBounds(c)
            is ImageLayer -> c.screenCorners().let { pts ->
                androidx.compose.ui.geometry.Rect(pts.minOf { it.x }, pts.minOf { it.y }, pts.maxOf { it.x }, pts.maxOf { it.y })
            }
        }

        /** Screen bounds of a stroke with its line widths. */
        fun strokeBounds(c: Stroke): androidx.compose.ui.geometry.Rect {
            val pts = c.screenPoints()
            val scale = maxOf(kotlin.math.abs(c.transform.sx), kotlin.math.abs(c.transform.sy))
            val pad = (c.stack.maxOfOrNull { kotlin.math.abs(it.offsetPx) + it.widthPx / 2 } ?: 0f) * scale + 8f
            if (pts.isEmpty()) return androidx.compose.ui.geometry.Rect.Zero
            return androidx.compose.ui.geometry.Rect(pts.minOf { it.x } - pad, pts.minOf { it.y } - pad, pts.maxOf { it.x } + pad, pts.maxOf { it.y } + pad)
        }

        private fun androidx.compose.ui.geometry.Rect.corners() =
            listOf(Pt(left, top), Pt(right, top), Pt(left, bottom), Pt(right, bottom))

        private const val HANDLE_SLOP = 40f
        private const val MIN_HANDLE_ARM = 6f
        const val MIN_SPACING = MotionLimits.MIN_SPACING
        const val MAX_SPACING = MotionLimits.MAX_SPACING
        private const val MAX_UNDO = 100
        private const val PASTE_OFFSET = 32f
        private const val MIN_POINT_DISTANCE = 1.5f
        private const val TAP_SLOP = 24f
    }
}
