package dk.cbavf.studio.draw

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dk.cbavf.studio.blend.isSubtract
import dk.cbavf.studio.draw.model.Content
import dk.cbavf.studio.draw.model.EllipseHatch
import dk.cbavf.studio.draw.model.ImageLayer
import dk.cbavf.studio.draw.model.Placed
import dk.cbavf.studio.draw.model.screenCorners
import dk.cbavf.studio.draw.model.Geometry
import dk.cbavf.studio.draw.model.Grating
import dk.cbavf.studio.draw.groups.GNode
import dk.cbavf.studio.draw.groups.GroupLogic
import dk.cbavf.studio.draw.groups.LNode
import dk.cbavf.studio.draw.model.Layer
import dk.cbavf.studio.draw.model.LayerGroup
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
import dk.cbavf.studio.ui.HistoryView
import kotlin.math.hypot

enum class Tool { Move, Pen, Lasso }

/**
 * Canvas state for the Draw room. Layers are immutable values; every edit replaces one,
 * which makes undo a list of snapshots.
 */
class DrawState : HistoryView {
    /** Draws the predicted moiré fringes over the canvas (the beat of the selected grating with the one below it). */
    var showFringes by androidx.compose.runtime.mutableStateOf(false)

    private var nextId = 1

    val layers = mutableStateListOf(
        Layer(nextId++, Grating(spacingPx = 10f, angleDeg = 0f)),
        Layer(nextId++, Grating(spacingPx = 10f, angleDeg = 5f)),
    )

    /** Layer groups: nested, collapsible, hideable. Layers point at one through [Layer.group]. */
    val groups = mutableStateListOf<LayerGroup>()

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
    var canRedo by mutableStateOf(false)
        private set
    var canPaste by mutableStateOf(false)
        private set

    /** Canvas size in pixels, set by the room once laid out. */
    var canvasWidth by mutableIntStateOf(0)
    var canvasHeight by mutableIntStateOf(0)

    /** The Library asset this canvas descends from; the next save becomes its child. */
    var sourceAssetId by mutableStateOf<String?>(null)

    /** The size Save renders at. Kept with the canvas, not in the undo history: it is a save setting, not an edit. */
    var renderSize by mutableStateOf(dk.cbavf.studio.draw.model.RenderSize.Screen)

    /** What Save will make of the canvas as it is now, at [size]. */
    fun renderPlan(size: dk.cbavf.studio.draw.model.RenderSize = renderSize): dk.cbavf.studio.draw.model.RenderPlan =
        dk.cbavf.studio.draw.model.RenderSizes.plan(
            size, canvasWidth, canvasHeight,
            dk.cbavf.studio.draw.model.RenderSizes.bitmapsFor(visibleLayers().any { it.visible && it.blend.isSubtract }),
        )

    /** [label] names the action that follows this state (when in [history]) or that undo took back (when in [future]). */
    private class Snapshot(val layers: List<Layer>, val groups: List<LayerGroup>, val sourceAssetId: String?, val label: String = "Edit")

    private val history = ArrayDeque<Snapshot>()
    private val future = ArrayDeque<Snapshot>()
    private var lastCheckpointKey: Any? = null
    private var clipboard: List<Layer> = emptyList()
    private var clipboardGroups: List<LayerGroup> = emptyList()

    val primary: Layer? get() = layers.firstOrNull { it.id == primaryId }

    /** Picks one object, and the rest of its group with it. */
    fun select(id: Int) {
        val g = layers.firstOrNull { it.id == id }?.group
        selection = if (g == null) setOf(id) else layers.filter { it.group == g }.map { it.id }.toSet()
        primaryId = id
        selectedSub = 0
    }

    /** Hold: adds an object (with its group) to the selection, or takes it out. */
    fun toggleSelect(id: Int) {
        val g = layers.firstOrNull { it.id == id }?.group
        val ids = if (g == null) setOf(id) else layers.filter { it.group == g }.map { it.id }.toSet()
        selection = if (id in selection) selection - ids else selection + ids
        primaryId = if (id in selection) id else selection.lastOrNull() ?: layers.lastOrNull()?.id ?: 0
        selectedSub = 0
    }

    private fun gnodes() = groups.map { GNode(it.id, it.parent, it.visible) }
    private fun lnodes() = layers.map { LNode(it.id, it.group, it.visible) }

    private fun nextGroupId(): Int =
        maxOf(groups.maxOfOrNull { it.id } ?: 0, layers.mapNotNull { it.group }.maxOrNull() ?: 0) + 1

    /** Groups that still hold layers (directly or in nested groups) stay; the rest are removed. */
    private fun pruneEmptyGroups() {
        val gn = gnodes()
        val ln = lnodes()
        groups.removeAll { g -> GroupLogic.layersIn(g.id, ln, gn).isEmpty() }
    }

    /** Old projects only have group numbers on layers: give each one a group entry. */
    private fun ensureGroupEntries() {
        val known = groups.map { it.id }.toSet()
        layers.mapNotNull { it.group }.distinct().filter { it !in known }.forEach { groups += LayerGroup(it, "Group $it") }
    }

    /** The tab chips pick just this layer, even inside a group (a tap on the canvas still picks the whole group). */
    fun selectSingle(id: Int) {
        selection = setOf(id)
        primaryId = id
        selectedSub = 0
    }

    /** Hold on a tab chip: adds just this layer to the selection, or takes it out. */
    fun toggleSingle(id: Int) {
        selection = if (id in selection) selection - id else selection + id
        primaryId = if (id in selection) id else selection.lastOrNull() ?: layers.lastOrNull()?.id ?: 0
        selectedSub = 0
    }

    /**
     * The selected objects become one new group. Whole groups inside the selection are nested in it
     * (subgroups); loose objects, and objects from groups only partly selected, go straight into it.
     * If everything came from one group, the new group is a subgroup of that group.
     */
    fun groupSelection() {
        if (selection.size < 2) return
        checkpoint(label = "Group")
        val gn = gnodes()
        val ln = lnodes()
        val full = groups.filter { g -> GroupLogic.layersIn(g.id, ln, gn).let { l -> l.isNotEmpty() && l.all { it.id in selection } } }.map { it.id }.toSet()
        val top = full.filter { id -> groups.first { it.id == id }.parent !in full }.toSet()
        val inTop = top + top.flatMap { GroupLogic.descendants(it, gn) }
        val loose = layers.filter { it.id in selection && it.group !in inTop }
        val attach = (top.map { id -> groups.first { it.id == id }.parent } + loose.map { it.group }).toSet()
        val newId = nextGroupId()
        for (i in groups.indices) if (groups[i].id in top) groups[i] = groups[i].copy(parent = newId)
        for (i in layers.indices) if (layers[i].id in selection && layers[i].group !in inTop) layers[i] = layers[i].copy(group = newId)
        groups += LayerGroup(newId, "Group $newId", parent = attach.singleOrNull())
        pruneEmptyGroups()
    }

    /** The selected objects leave their group, one level up (out of every group if it was a top-level one). */
    fun ungroupSelection() {
        if (layers.none { it.id in selection && it.group != null }) return
        checkpoint(label = "Ungroup")
        for (i in layers.indices) {
            val l = layers[i]
            if (l.id !in selection || l.group == null) continue
            layers[i] = l.copy(group = groups.firstOrNull { it.id == l.group }?.parent)
        }
        pruneEmptyGroups()
    }

    /** Picks every object in a group, subgroups included. */
    fun selectGroup(id: Int) {
        val ids = GroupLogic.layersIn(id, lnodes(), gnodes()).map { it.id }
        if (ids.isNotEmpty()) selectAll(ids)
    }

    fun toggleGroupVisible(id: Int) {
        val i = groups.indexOfFirst { it.id == id }
        if (i < 0) return
        checkpoint(label = "Show or hide group")
        groups[i] = groups[i].copy(visible = !groups[i].visible)
    }

    /** Opens or folds a group's row of tabs. Not an undo step: it is how the tabs look, not the picture. */
    fun toggleGroupCollapsed(id: Int) {
        val i = groups.indexOfFirst { it.id == id }
        if (i >= 0) groups[i] = groups[i].copy(collapsed = !groups[i].collapsed)
    }

    fun renameGroup(id: Int, name: String) {
        val i = groups.indexOfFirst { it.id == id }
        if (i < 0 || groups[i].name == name) return
        checkpoint("rename-group-$id")
        groups[i] = groups[i].copy(name = name)
    }

    /** Shows or hides every grouped layer at once: all groups, and the layers inside them. */
    fun setGroupedVisible(visible: Boolean) {
        if (groups.isEmpty()) return
        checkpoint(label = "Show or hide all groups")
        for (i in groups.indices) groups[i] = groups[i].copy(visible = visible)
        for (i in layers.indices) if (layers[i].group != null) layers[i] = layers[i].copy(visible = visible)
    }

    /** One tab in the layer rows: a layer, or a group with how many layers it holds. */
    class TabItem(val layer: Layer?, val group: LayerGroup?, val count: Int, val order: Int)

    /** What the layer tabs show directly under group [parent] (null = the top row), in stack order. */
    fun tabItems(parent: Int?): List<TabItem> {
        val known = groups.map { it.id }.toSet()
        val gn = gnodes()
        val ln = lnodes()
        val out = ArrayList<TabItem>()
        layers.forEachIndexed { i, l ->
            val here = if (parent == null) (l.group == null || l.group !in known) else l.group == parent
            if (here) out += TabItem(l, null, 0, i)
        }
        for (g in groups) {
            val here = if (parent == null) (g.parent == null || g.parent !in known) else g.parent == parent
            if (!here) continue
            val inside = GroupLogic.layersIn(g.id, ln, gn)
            val order = inside.minOfOrNull { n -> layers.indexOfFirst { it.id == n.id } } ?: Int.MAX_VALUE
            out += TabItem(null, g, inside.size, order)
        }
        return out.sortedBy { it.order }
    }

    /** Ids of every layer in a group, subgroups included. */
    fun groupLayerIds(id: Int): Set<Int> = GroupLogic.layersIn(id, lnodes(), gnodes()).map { it.id }.toSet()

    /** Whether a layer is drawn: its own flag and that of every group it sits in. */
    fun isShown(layer: Layer): Boolean = GroupLogic.effectiveVisible(LNode(layer.id, layer.group, layer.visible), gnodes())

    /** The layers to draw: hidden ones, and those in hidden groups, left out. */
    fun visibleLayers(): List<Layer> {
        if (groups.isEmpty()) return layers.toList()
        val gn = gnodes()
        return layers.filter { GroupLogic.effectiveVisible(LNode(it.id, it.group, it.visible), gn) }
    }

    /** Moves object [id] [delta] places in the stack (hold and drag in the object row). */
    fun moveLayerBy(id: Int, delta: Int) {
        val i = layers.indexOfFirst { it.id == id }
        val to = (i + delta).coerceIn(0, layers.lastIndex)
        if (i < 0 || to == i) return
        checkpoint(label = "Reorder layer")
        layers.add(to, layers.removeAt(i))
    }

    private fun selectAll(ids: List<Int>) {
        selection = ids.toSet()
        ids.lastOrNull()?.let { primaryId = it }
        selectedSub = 0
    }

    // --- Recipes ------------------------------------------------------------------------

    fun toDoc(): CanvasDoc = CanvasDoc(canvasWidth, canvasHeight, layers.toList(), groups.toList(), renderSize)

    /** Replaces the canvas with a saved recipe (startup restore). Undo history does not cross a load. */
    fun load(doc: CanvasDoc, sourceAssetId: String?) {
        history.clear()
        future.clear()
        canRedo = false
        historyVersion++
        replace(doc, sourceAssetId)
    }

    /** Opens a recipe as an undoable edit: Undo brings back the canvas that was here before. */
    fun open(doc: CanvasDoc, sourceAssetId: String?) {
        checkpoint(label = "Open canvas")
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
        groups.clear()
        groups.addAll(doc.groups)
        ensureGroupEntries()
        nextId = (doc.layers.maxOfOrNull { it.id } ?: 0) + 1
        canUndo = history.isNotEmpty()
        lastCheckpointKey = null
        draft.clear()
        this.sourceAssetId = sourceAssetId
        renderSize = doc.render
        layers.lastOrNull()?.let { select(it.id) } ?: selectAll(emptyList())
    }

    // --- Undo -----------------------------------------------------------------------------

    /**
     * Saves the current layers for undo. Consecutive calls with the same non-null [key]
     * (e.g. one slider being dragged) collapse into one step.
     */
    fun checkpoint(key: Any? = null, label: String = "Edit") {
        if (key != null && key == lastCheckpointKey) return
        lastCheckpointKey = key
        history.addLast(Snapshot(layers.toList(), groups.toList(), sourceAssetId, label))
        if (history.size > MAX_UNDO) history.removeFirst()
        canUndo = true
        future.clear()
        canRedo = false
        historyVersion++
    }

    // --- The History panel --------------------------------------------------------------------

    private var historyVersion by mutableIntStateOf(0)

    /** "Start", then one label per action done, then the undone ones that can still be redone. */
    override val timeline: List<String>
        get() {
            historyVersion; canUndo; canRedo
            return listOf("Start") + history.map { it.label } + future.reversed().map { it.label }
        }

    override val position: Int get() { historyVersion; canUndo; return history.size }

    /** Goes back or forward to the state after entry [i] of the timeline. */
    override fun jumpTo(i: Int) {
        val target = i.coerceIn(0, history.size + future.size)
        while (history.size > target) undo()
        while (history.size < target) redo()
    }

    fun undo() {
        val previous = history.removeLastOrNull() ?: return
        future.addLast(Snapshot(layers.toList(), groups.toList(), sourceAssetId, previous.label))
        restore(previous)
        canUndo = history.isNotEmpty()
        canRedo = true
        historyVersion++
    }

    fun redo() {
        val next = future.removeLastOrNull() ?: return
        history.addLast(Snapshot(layers.toList(), groups.toList(), sourceAssetId, next.label))
        restore(next)
        canUndo = true
        canRedo = future.isNotEmpty()
        historyVersion++
    }

    private fun restore(previous: Snapshot) {
        layers.clear()
        layers.addAll(previous.layers)
        groups.clear()
        groups.addAll(previous.groups)
        sourceAssetId = previous.sourceAssetId
        nextId = maxOf(nextId, (previous.layers.maxOfOrNull { it.id } ?: 0) + 1)
        lastCheckpointKey = null
        val ids = layers.map { it.id }.toSet()
        selection = selection intersect ids
        if (primaryId !in ids) primaryId = selection.lastOrNull() ?: layers.lastOrNull()?.id ?: 0
    }

    // --- Layers ---------------------------------------------------------------------------

    fun add(content: Content) {
        checkpoint(label = "Add layer")
        val layer = Layer(nextId++, content)
        layers += layer
        select(layer.id)
    }

    fun deleteSelection() {
        if (selection.isEmpty()) return
        checkpoint(label = "Delete")
        val firstIndex = layers.indexOfFirst { it.id in selection }
        layers.removeAll { it.id in selection }
        pruneEmptyGroups()
        val next = layers.getOrNull((firstIndex - 1).coerceAtLeast(0))
        if (next != null) select(next.id) else selectAll(emptyList())
    }

    fun copy() {
        clipboard = layers.filter { it.id in selection }
        clipboardGroups = groups.toList()
        canPaste = clipboard.isNotEmpty()
    }

    /**
     * Copies of [source] with new ids, moved by [offset]. Groups wholly inside [source] are copied as
     * new groups (nesting kept); layers of partly copied groups stay in their original group.
     */
    private fun makeCopies(source: List<Layer>, sourceGroups: List<LayerGroup>, offset: Float): List<Layer> {
        val ids = source.map { it.id }.toSet()
        val r = GroupLogic.duplicate(
            source.map { LNode(it.id, it.group, it.visible) },
            sourceGroups.map { GNode(it.id, it.parent, it.visible) },
            ids, nextId, nextGroupId(),
        )
        val fromNew = r.layerIdMap.entries.associate { it.value to it.key }
        val groupFromNew = r.groupIdMap.entries.associate { it.value to it.key }
        r.newGroups.forEach { g ->
            val o = sourceGroups.first { it.id == groupFromNew.getValue(g.id) }
            groups += o.copy(id = g.id, parent = g.parent)
        }
        val copies = r.newLayers.map { n ->
            val o = source.first { it.id == fromNew.getValue(n.id) }
            o.copy(id = n.id, content = if (offset == 0f) o.content else nudge(o.content, offset), group = n.group)
        }
        nextId = (r.layerIdMap.values.maxOrNull() ?: (nextId - 1)) + 1
        return copies
    }

    /** Paste: copies a little to the side, so they can be told from the originals. */
    fun paste() {
        if (clipboard.isEmpty()) return
        checkpoint(label = "Paste")
        val pasted = makeCopies(clipboard, clipboardGroups, PASTE_OFFSET)
        layers += pasted
        ensureGroupEntries()
        selectAll(pasted.map { it.id })
    }

    /** Duplicate: copies exactly where the originals are, just above the topmost of them in the stack. */
    fun duplicate() {
        val source = layers.filter { it.id in selection }
        if (source.isEmpty()) return
        checkpoint(label = "Duplicate")
        val copies = makeCopies(source, groups.toList(), 0f)
        val at = layers.indexOfLast { it.id in selection } + 1
        layers.addAll(at, copies)
        selectAll(copies.map { it.id })
    }

    /** Moves the primary layer up (+1, drawn later, on top) or down (−1) the stack. */
    fun moveLayer(delta: Int) {
        val i = layers.indexOfFirst { it.id == primaryId }
        val to = i + delta
        if (i < 0 || to !in layers.indices) return
        checkpoint(label = "Reorder layer")
        layers.add(to, layers.removeAt(i))
    }

    /** Places Library images on the canvas, fitted and centred, each a layer of its own. */
    fun addImages(images: List<ImageLayer>) {
        if (images.isEmpty()) return
        checkpoint(label = "Add images")
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
        checkpoint(key, "Edit layer")
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

    fun beginTransform() = checkpoint(label = "Move or transform")

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

    /** The pen draws straight lines from where the finger went down. */
    var straight by mutableStateOf(false)

    fun draftMove(p: Pt) {
        if (straight && tool == Tool.Pen && draft.isNotEmpty()) {
            // Points along the line, as a hand-drawn stroke has, so stroke stacks (rails, dashes) behave the same.
            val a = draft.first()
            val len = hypot(p.x - a.x, p.y - a.y)
            val n = (len / MIN_POINT_DISTANCE).toInt().coerceIn(1, 4000)
            draft.clear()
            for (i in 0..n) draft += Pt(a.x + (p.x - a.x) * i / n, a.y + (p.y - a.y) * i / n)
            return
        }
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
            isShown(layer) && when (c) {
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
            if (!isShown(layer)) return@lastOrNull false
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
