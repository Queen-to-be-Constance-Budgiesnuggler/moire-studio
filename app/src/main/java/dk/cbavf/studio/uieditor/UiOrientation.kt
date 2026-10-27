package dk.cbavf.studio.uieditor

import kotlin.math.max

/**
 * The UI Editor keeps one front per orientation and one set of cables for both. Portrait is the layout's own panels and
 * controls; landscape is [UiLayout.landscape]. Until landscape is edited it is the portrait front laid out again in wider
 * rows, so turning the phone shows a sensible layout from the start.
 */
object UiOrientation {
    /** Panels per row in a landscape front that was never edited (the portrait seed uses 4). */
    const val LANDSCAPE_COLUMNS = 6

    /** The layout the editor shows in this orientation: that orientation's front, with the shared cables. */
    fun view(doc: UiLayout, landscape: Boolean): UiLayout {
        if (!landscape) return doc.copy(landscape = null)
        val front = doc.landscape ?: return reflow(doc.copy(landscape = null), LANDSCAPE_COLUMNS)
        return UiLayout(front.panels, front.controls, doc.cables)
    }

    /** Applies an edit [f] made in one orientation's view; the other front is kept, the cables are shared. */
    fun edit(doc: UiLayout, landscape: Boolean, f: (UiLayout) -> UiLayout): UiLayout {
        val next = f(view(doc, landscape))
        return if (!landscape) next.copy(landscape = doc.landscape)
        else doc.copy(cables = next.cables, landscape = UiFront(next.panels, next.controls))
    }

    /** Brings both fronts up to date with what the modules offer now (see [UiModel.merge]). */
    fun merge(saved: UiLayout, modules: List<ModuleSeed>): UiLayout {
        val portrait = UiModel.merge(saved.copy(landscape = null), UiModel.seed(modules))
        val land = saved.landscape ?: return portrait
        val merged = UiModel.merge(UiLayout(land.panels, land.controls, portrait.cables), UiModel.seed(modules, LANDSCAPE_COLUMNS))
        return portrait.copy(landscape = UiFront(merged.panels, merged.controls))
    }

    /** Forgets the landscape front, so landscape follows portrait again. */
    fun resetLandscape(doc: UiLayout): UiLayout = doc.copy(landscape = null)

    /**
     * The panels laid out again in rows of [columns], in reading order (top to bottom, then left to right). Each panel keeps its
     * size, and its controls move with it.
     */
    fun reflow(l: UiLayout, columns: Int): UiLayout {
        val order = l.panels.sortedWith(compareBy({ it.y }, { it.x }))
        val moves = HashMap<String, Pair<Float, Float>>()
        var y = 0f
        var rowHeight = 0f
        order.forEachIndexed { i, p ->
            val col = i % columns.coerceAtLeast(1)
            if (col == 0 && i > 0) { y += rowHeight + PANEL_GAP; rowHeight = 0f }
            val x = col * (PANEL_W + PANEL_GAP)
            moves[p.id] = (x - p.x) to (y - p.y)
            rowHeight = max(rowHeight, p.h)
        }
        return l.copy(
            panels = l.panels.map { p -> moves[p.id]?.let { (dx, dy) -> p.copy(x = p.x + dx, y = p.y + dy) } ?: p },
            controls = l.controls.map { c -> moves[c.module]?.let { (dx, dy) -> c.copy(x = c.x + dx, y = c.y + dy) } ?: c },
        )
    }
}
